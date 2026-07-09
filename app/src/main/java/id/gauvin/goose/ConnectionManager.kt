package id.gauvin.goose

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf

data class ChatMessage(val role: String, val text: String)

/**
 * Process-scoped owner of the ACP connection + chat state. A singleton (not a ViewModel) so
 * it survives navigation/config changes and can later be shared with a background service,
 * share intents, tiles, etc. Compose observes its snapshot state directly.
 */
class ConnectionManager private constructor(context: Context) {
    val store = SecureStore(context)
    private val appContext = context.applicationContext
    private val notifier = Notifier(context)
    private var appForeground = true
    private var serviceRunning = false
    private var pendingSend: String? = null   // a reply that must wait for (re)connect

    val messages = mutableStateListOf<ChatMessage>()
    val status = mutableStateOf("not connected")
    val config = mutableStateOf<List<ConfigOption>>(emptyList())
    val sessions = mutableStateOf<List<SessionInfo>>(emptyList())
    val busy = mutableStateOf(false)
    val commands = mutableStateOf<List<String>>(emptyList())
    val permissions = mutableStateListOf<AcpEvent.Permission>()   // pending approvals, oldest first
    // Handed in by OS entry points (share sheet, shortcut, tile), consumed by the UI.
    val pendingShareText = mutableStateOf<String?>(null)
    val pendingShareImages = mutableStateListOf<ImageBlock>()
    val pendingNewChat = mutableStateOf(false)
    val dynamicColor = mutableStateOf(store.dynamicColor)
    val showAllProviders = mutableStateOf(store.showAllProviders)

    // Providers actually set up on this goose (config.yaml `providers:` with configured:true).
    // Unconfigured catalog entries are hidden unless showAllProviders is on.
    val configuredProviders = setOf("openai", "openrouter")

    fun setDynamicColor(v: Boolean) { store.dynamicColor = v; dynamicColor.value = v }
    fun setShowAllProviders(v: Boolean) { store.showAllProviders = v; showAllProviders.value = v }

    private val main = Handler(Looper.getMainLooper())
    private var client: AcpClient? = null
    private var streamingRole: String? = null
    private var live = false
    private var connecting = false
    private var lastSessionId: String? = null
    private val optionIds = listOf("provider", "model", "mode", "thinking_effort")

    val configured: Boolean get() = store.hasKey()

    /** Connect using the already-saved host/port/key (post-unlock auto-connect). */
    fun connectSaved() { if (store.hasKey()) open(resume = null, suppressReplay = false) }

    /** Save new credentials and connect fresh (from the Connect screen). */
    fun connect(host: String, port: String, key: String) {
        store.host = host; store.port = port; store.secretKey = key
        lastSessionId = null; config.value = emptyList()
        open(resume = null, suppressReplay = false)
    }

    /** Reconnect silently after Android drops the socket in the background. */
    fun ensureConnected() {
        if (!store.hasKey() || live || connecting) return
        open(resume = lastSessionId, suppressReplay = true)
    }

    fun listSessions() = client?.listSessions()

    fun openSession(sessionId: String) {
        messages.clear(); lastSessionId = sessionId
        open(resume = sessionId, suppressReplay = false)
    }

    fun newSession() {
        messages.clear(); lastSessionId = null; config.value = emptyList()
        open(resume = null, suppressReplay = false)
    }

    fun setOption(configId: String, value: String) {
        store.saveOption(configId, value)
        client?.setConfigOption(configId, value)
    }

    fun send(text: String, images: List<ImageBlock> = emptyList()) {
        val label = if (images.isEmpty()) text else "$text  [📎 ${images.size}]".trim()
        messages.add(ChatMessage("user", label)); streamingRole = null; busy.value = true
        startService()   // keep the socket alive if the user backgrounds mid-turn
        client?.sendPrompt(text, images)
    }

    /** Send once connected — used by notification replies, which may arrive disconnected. */
    fun sendWhenReady(text: String) {
        if (live) { send(text); return }
        pendingSend = text
        startService()
        if (!connecting) open(resume = lastSessionId ?: store.lastSessionId, suppressReplay = true)
    }

    fun setForeground(fg: Boolean) {
        appForeground = fg
        if (fg) {
            notifier.cancelAlert()
            ensureConnected()
            if (!store.persistentConnection && !busy.value) stopService()
        } else if (store.persistentConnection) {
            startService()
        }
    }

    fun setPersistent(on: Boolean) {
        store.persistentConnection = on
        if (on) startService() else if (!busy.value && appForeground) stopService()
    }

    val persistent: Boolean get() = store.persistentConnection

    private fun startService() {
        if (serviceRunning) return
        serviceRunning = true
        appContext.startForegroundService(Intent(appContext, ConnectionService::class.java))
    }

    private fun stopService() {
        if (!serviceRunning) return
        serviceRunning = false
        appContext.stopService(Intent(appContext, ConnectionService::class.java))
    }

    private fun lastAssistantText(): String =
        messages.lastOrNull { it.role == "assistant" }?.text ?: "Turn finished."

    /** Interrupt the running turn. */
    fun cancel() = client?.cancel()

    /** Answer the given approval request; null optionId denies (cancelled). */
    fun answerPermission(p: AcpEvent.Permission, optionId: String?) {
        client?.respondPermission(p.toolCallId, optionId)
        permissions.remove(p)
    }

    private fun open(resume: String?, suppressReplay: Boolean) {
        client?.close()
        live = false; connecting = true
        val url = "ws://${store.host}:${store.port}/acp"
        status.value = when {
            resume == null -> "connecting to $url"
            suppressReplay -> "reconnecting…"
            else -> "loading session…"
        }
        val saved = store.savedOptions(optionIds)
        client = AcpClient(url, store.secretKey) { ev -> main.post { onEvent(ev) } }.also {
            it.desiredOptions = if (resume == null) saved else emptyMap()
            it.resumeSessionId = resume
            it.suppressReplay = suppressReplay
            it.connect()
        }
    }

    private fun onEvent(ev: AcpEvent) {
        when (ev) {
            is AcpEvent.Status -> {
                status.value = ev.text
                if (ev.text == "disconnected") { live = false; connecting = false }
            }
            is AcpEvent.Error -> {
                messages.add(ChatMessage("error", ev.text)); streamingRole = null; busy.value = false
                if (ev.text.startsWith("connection failed")) { live = false; connecting = false }
            }
            is AcpEvent.ToolCall -> { messages.add(ChatMessage("tool", ev.title)); streamingRole = null }
            is AcpEvent.TurnDone -> {
                streamingRole = null; busy.value = false
                if (!appForeground) notifier.postReply(lastAssistantText())
                if (!store.persistentConnection) stopService()
            }
            is AcpEvent.AgentChunk -> appendStream("assistant", ev.text)
            is AcpEvent.ThoughtChunk -> appendStream("thought", ev.text)
            is AcpEvent.UserChunk -> { messages.add(ChatMessage("user", ev.text)); streamingRole = null }
            is AcpEvent.Config -> if (ev.options.isNotEmpty()) {
                config.value = ev.options
                // Persist the true current values so re-apply on reconnect can't drift
                // (e.g. leave a LocalAI model selected after switching to openrouter).
                ev.options.forEach { if (it.currentValue.isNotBlank()) store.saveOption(it.id, it.currentValue) }
            }
            is AcpEvent.Ready -> {
                live = true; connecting = false
                lastSessionId = ev.sessionId; store.lastSessionId = ev.sessionId
                pendingSend?.let { pendingSend = null; send(it) }   // flush a queued reply
            }
            is AcpEvent.Sessions -> sessions.value = ev.list
            is AcpEvent.Commands -> commands.value = ev.names
            is AcpEvent.Permission -> {
                permissions.add(ev)
                if (!appForeground) notifier.postApprovalNeeded(ev.title)
            }
        }
    }

    /** Merge a streamed chunk into the last bubble of the same role, else start a new one. */
    private fun appendStream(role: String, chunk: String) {
        val last = messages.lastOrNull()
        if (streamingRole == role && last != null && last.role == role) {
            messages[messages.lastIndex] = last.copy(text = last.text + chunk)
        } else {
            streamingRole = role
            messages.add(ChatMessage(role, chunk))
        }
    }

    companion object {
        @Volatile private var instance: ConnectionManager? = null
        fun get(context: Context): ConnectionManager =
            instance ?: synchronized(this) {
                instance ?: ConnectionManager(context.applicationContext).also { instance = it }
            }
    }
}
