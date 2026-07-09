package id.gauvin.goose

import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel

data class ChatMessage(val role: String, val text: String)

class ChatViewModel : ViewModel() {
    val messages = mutableStateListOf<ChatMessage>()
    val status = mutableStateOf("not connected")
    val config = mutableStateOf<List<ConfigOption>>(emptyList())
    val sessions = mutableStateOf<List<SessionInfo>>(emptyList())

    private val main = Handler(Looper.getMainLooper())
    private var client: AcpClient? = null
    private var streaming = false

    // Remembered so we can silently reconnect after Android drops the socket in the background.
    private var host = ""; private var port = ""; private var key = ""
    private var saved: Map<String, String> = emptyMap()
    private var lastSessionId: String? = null
    private var live = false        // session is open and usable
    private var connecting = false  // a connect attempt is in flight

    fun connect(host: String, port: String, key: String, saved: Map<String, String> = emptyMap()) {
        this.host = host; this.port = port; this.key = key; this.saved = saved
        lastSessionId = null           // explicit (re)connect starts fresh
        config.value = emptyList()
        open(resume = null, suppressReplay = false)
    }

    /** Reconnect if the socket died while backgrounded, resuming the same session silently. */
    fun ensureConnected() {
        if (key.isBlank() || live || connecting) return
        open(resume = lastSessionId, suppressReplay = true)
    }

    fun listSessions() = client?.listSessions()

    /** Open a picked session from the list: clear the view and replay its transcript. */
    fun openSession(sessionId: String) {
        messages.clear()
        lastSessionId = sessionId
        open(resume = sessionId, suppressReplay = false)
    }

    /** Start a brand-new session (applies saved model picks). */
    fun newSession() {
        messages.clear()
        lastSessionId = null
        config.value = emptyList()
        open(resume = null, suppressReplay = false)
    }

    private fun open(resume: String?, suppressReplay: Boolean) {
        client?.close()
        live = false; connecting = true
        val url = "ws://$host:$port/acp"
        status.value = when {
            resume == null -> "connecting to $url"
            suppressReplay -> "reconnecting…"
            else -> "loading session…"
        }
        client = AcpClient(url, key) { ev -> main.post { onEvent(ev) } }.also {
            // Only push saved picks onto a fresh session; a resumed one keeps its own model.
            it.desiredOptions = if (resume == null) saved else emptyMap()
            it.resumeSessionId = resume
            it.suppressReplay = suppressReplay
            it.connect()
        }
    }

    /** User picked a new value for a config knob (provider/model/mode/thinking_effort). */
    fun setOption(configId: String, value: String) = client?.setConfigOption(configId, value)

    fun send(text: String) {
        messages.add(ChatMessage("user", text))
        streaming = false
        client?.sendPrompt(text)
    }

    private fun onEvent(ev: AcpEvent) {
        when (ev) {
            is AcpEvent.Status -> {
                status.value = ev.text
                if (ev.text == "disconnected") { live = false; connecting = false }
            }
            is AcpEvent.Error -> {
                messages.add(ChatMessage("error", ev.text)); streaming = false
                if (ev.text.startsWith("connection failed")) { live = false; connecting = false }
            }
            is AcpEvent.ToolCall -> { messages.add(ChatMessage("tool", ev.title)); streaming = false }
            is AcpEvent.TurnDone -> streaming = false
            is AcpEvent.AgentChunk -> appendAgent(ev.text)
            is AcpEvent.UserChunk -> { messages.add(ChatMessage("user", ev.text)); streaming = false }
            is AcpEvent.Config -> if (ev.options.isNotEmpty()) config.value = ev.options
            is AcpEvent.Ready -> { live = true; connecting = false; lastSessionId = ev.sessionId }
            is AcpEvent.Sessions -> sessions.value = ev.list
        }
    }

    private fun appendAgent(chunk: String) {
        val last = messages.lastOrNull()
        if (streaming && last != null && last.role == "assistant") {
            messages[messages.lastIndex] = last.copy(text = last.text + chunk)
        } else {
            streaming = true
            messages.add(ChatMessage("assistant", chunk))
        }
    }

    override fun onCleared() { client?.close() }
}
