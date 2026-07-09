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
        open(resume = null)
    }

    /** Reconnect if the socket died while backgrounded, resuming the same session. */
    fun ensureConnected() {
        if (key.isBlank() || live || connecting) return
        open(resume = lastSessionId)
    }

    private fun open(resume: String?) {
        client?.close()
        live = false; connecting = true
        val url = "ws://$host:$port/acp"
        status.value = if (resume != null) "reconnecting…" else "connecting to $url"
        client = AcpClient(url, key) { ev -> main.post { onEvent(ev) } }
            .also { it.desiredOptions = saved; it.resumeSessionId = resume; it.connect() }
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
            is AcpEvent.Config -> if (ev.options.isNotEmpty()) config.value = ev.options
            is AcpEvent.Ready -> { live = true; connecting = false; lastSessionId = ev.sessionId }
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
