package id.gauvin.goose

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
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
    // Sends that must wait for (re)connect — a queue, not one slot, so a second reply while
    // still connecting can't clobber the first. The user bubble is added when queued (in send()).
    private data class PendingSend(val text: String, val images: List<ImageBlock>)
    private val pendingSends = ArrayDeque<PendingSend>()

    val messages = mutableStateListOf<ChatMessage>()
    val status = mutableStateOf("not connected")
    val online = mutableStateOf(false)   // true between Ready and disconnect — for a UI status pill
    val config = mutableStateOf<List<ConfigOption>>(emptyList())
    val sessions = mutableStateOf<List<SessionInfo>>(emptyList())
    val busy = mutableStateOf(false)
    val usage = mutableStateOf<AcpEvent.Usage?>(null)   // context window used/size + cost
    val commands = mutableStateOf<List<String>>(emptyList())
    val permissions = mutableStateListOf<AcpEvent.Permission>()   // pending approvals, oldest first
    // Handed in by OS entry points (share sheet, shortcut, tile), consumed by the UI.
    val pendingShareText = mutableStateOf<String?>(null)
    val pendingShareImages = mutableStateListOf<ImageBlock>()
    val pendingNewChat = mutableStateOf(false)
    // A notification tap that should open a specific session (finished-turn alert). Consumed in MainActivity.
    val pendingOpenSession = mutableStateOf<String?>(null)
    // Draft attachments live here (process-scoped) so a rotation/recreation doesn't drop picked
    // images — and base64 payloads stay out of the saved-state Bundle (TransactionTooLarge).
    val draftAttachments = mutableStateListOf<ImageBlock>()
    val dynamicColor = mutableStateOf(store.dynamicColor)
    val showAllProviders = mutableStateOf(store.showAllProviders)
    val speakReplies = mutableStateOf(store.speakReplies)
    val knownModels = mutableStateOf(emptySet<String>())   // models for the CURRENT provider only
    val extensions = mutableStateOf<List<ExtInfo>>(emptyList())
    val extensionsBusy = mutableStateOf(false)

    private val io = java.util.concurrent.Executors.newSingleThreadExecutor()
    private fun extApi() = ExtensionsApi("https://${store.host}:${store.port}", store.secretKey)

    /** Fetch the extension list (GET /config/extensions). */
    fun loadExtensions() {
        extensionsBusy.value = true
        io.execute {
            val list = runCatching { extApi().list() }.getOrDefault(emptyList())
            main.post { extensions.value = list; extensionsBusy.value = false }
        }
    }

    /** Enable/disable an extension globally (affects new chats). */
    fun toggleExtension(e: ExtInfo, enabled: Boolean) {
        extensionsBusy.value = true
        io.execute {
            runCatching { extApi().set(e, enabled) }
            val list = runCatching { extApi().list() }.getOrDefault(emptyList())
            main.post { extensions.value = list; extensionsBusy.value = false }
        }
    }

    // Providers actually set up on this goose (config.yaml `providers:` with configured:true).
    // Unconfigured catalog entries are hidden unless showAllProviders is on.
    val configuredProviders = setOf("openai", "openrouter")

    fun setDynamicColor(v: Boolean) { store.dynamicColor = v; dynamicColor.value = v }
    fun setShowAllProviders(v: Boolean) { store.showAllProviders = v; showAllProviders.value = v }
    fun setSpeakReplies(v: Boolean) { store.speakReplies = v; speakReplies.value = v }

    private val main = Handler(Looper.getMainLooper())
    private var client: AcpClient? = null
    private var clientGen = 0   // bumped per open(); drops events from superseded clients
    private var streamingRole: String? = null
    private var live = false
    private var connecting = false
    private var lastSessionId: String? = null
    private val optionIds = listOf("provider", "model", "mode", "thinking_effort")

    // Voice: the voice sheet has a short lifecycle, so CM owns speaking the reply (it survives the
    // sheet closing) and suppresses the finished-turn notification for voice turns. The turn can
    // run on a faster voice model, restored afterwards.
    @Volatile var voiceActive = false
    @Volatile private var voiceReplyPending = false
    @Volatile private var voiceModelActive = false
    private var lastVoiceAt = 0L
    private val voiceSpeaker by lazy { Speaker(appContext) }

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
        if (live) {
            client?.sendPrompt(text, images)
        } else {
            // Not connected yet (initial connect / silent reconnect window): queue and connect,
            // rather than calling sendPrompt against a session-less client (which just errors and
            // loses the message). Flushed in the Ready branch.
            pendingSends.add(PendingSend(text, images))
            if (!connecting) open(resume = lastSessionId ?: store.lastSessionId, suppressReplay = true)
        }
    }

    /** Send once connected — used by notification replies, which may arrive disconnected. */
    fun sendWhenReady(text: String) = send(text)

    /** Send from the voice assistant: CM speaks the reply (so it survives the sheet closing),
     *  the finished-turn notification is suppressed, and the turn optionally runs on the fast
     *  voice model (restored afterwards). */
    fun sendVoice(text: String) {
        voiceReplyPending = true
        lastVoiceAt = SystemClock.elapsedRealtime()
        voiceSpeaker   // touch → start TTS init now so it's ready by turn-end
        val vm = store.voiceModel
        if (vm.isNotBlank() && live) {
            voiceModelActive = true   // suppress persisting this transient model to store
            store.voiceProvider.takeIf { it.isNotBlank() }?.let { client?.setConfigOption("provider", it) }
            client?.setConfigOption("model", vm)
        }
        send(text)
    }

    /** True during and briefly after a voice turn — used to drop the server's finished-turn push. */
    fun recentVoice(): Boolean =
        voiceReplyPending || (SystemClock.elapsedRealtime() - lastVoiceAt) < 20_000

    /** Restore the app's saved provider/model after a voice turn that swapped in the voice model. */
    private fun restoreModel() {
        if (!voiceModelActive) return
        voiceModelActive = false
        val saved = store.savedOptions(optionIds)
        saved["provider"]?.let { client?.setConfigOption("provider", it) }
        saved["model"]?.let { client?.setConfigOption("model", it) }
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

    /** Whether the app is currently in the foreground (for push dedup). */
    val isForeground: Boolean get() = appForeground

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

    /** Compact the conversation history to reclaim context (goose /compact command). */
    fun compact() { busy.value = true; client?.sendPrompt("/compact") }

    /** Answer the given approval request; null optionId denies (cancelled). */
    fun answerPermission(p: AcpEvent.Permission, optionId: String?) {
        client?.respondPermission(p.toolCallId, optionId)
        permissions.remove(p)
    }

    private fun open(resume: String?, suppressReplay: Boolean) {
        client?.close()
        live = false; connecting = true; online.value = false
        val url = "wss://${store.host}:${store.port}/acp"
        status.value = when {
            resume == null -> "connecting to $url"
            suppressReplay -> "reconnecting…"
            else -> "loading session…"
        }
        val saved = store.savedOptions(optionIds)
        // Tag this client's events with a generation; a just-closed client still fires
        // onClosed/onFailure asynchronously and its stale "disconnected" must not flip us offline
        // after the new client is already live.
        val gen = ++clientGen
        client = AcpClient(url, store.secretKey) { ev -> main.post { if (gen == clientGen) onEvent(ev) } }.also {
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
                if (ev.text == "disconnected") { live = false; connecting = false; online.value = false }
            }
            is AcpEvent.Error -> {
                messages.add(ChatMessage("error", ev.text)); streamingRole = null; busy.value = false
                if (voiceReplyPending) { voiceReplyPending = false; restoreModel() }
            }
            is AcpEvent.ToolCall -> { messages.add(ChatMessage("tool", ev.title)); streamingRole = null }
            is AcpEvent.Usage -> usage.value = ev
            is AcpEvent.Chart -> { messages.add(ChatMessage("chart", ev.spec)); streamingRole = null }
            is AcpEvent.TurnDone -> {
                streamingRole = null; busy.value = false
                if (voiceReplyPending) {
                    // Voice turn: speak the reply here (survives the voice sheet closing) and do
                    // NOT notify — the point of voice is to just talk back. Then restore the model.
                    voiceReplyPending = false
                    voiceSpeaker.speak(lastAssistantText())
                    restoreModel()
                } else if (!appForeground && !store.pushEnabled) {
                    // If push is on, the goose Stop hook nudges the phone — don't double-notify.
                    notifier.postReply(lastAssistantText())
                }
                if (!store.persistentConnection) stopService()
            }
            is AcpEvent.AgentChunk -> appendStream("assistant", ev.text)
            is AcpEvent.ThoughtChunk -> appendStream("thought", ev.text)
            is AcpEvent.UserChunk -> { messages.add(ChatMessage("user", ev.text)); streamingRole = null }
            is AcpEvent.Config -> if (ev.options.isNotEmpty()) {
                config.value = ev.options
                // Persist the true current values so re-apply on reconnect can't drift
                // (e.g. leave a LocalAI model selected after switching to openrouter). Skip while a
                // transient voice model is applied, so it doesn't overwrite the app's saved model.
                if (!voiceModelActive)
                    ev.options.forEach { if (it.currentValue.isNotBlank()) store.saveOption(it.id, it.currentValue) }
                // Remember real model slugs PER PROVIDER (goose only lists featured + "current"),
                // then expose only the current provider's models so LocalAI/OpenRouter don't mix.
                val provider = ev.options.firstOrNull { it.id == "provider" }?.currentValue ?: ""
                ev.options.firstOrNull { it.id == "model" }?.currentValue?.let { m ->
                    if (m.isNotBlank() && m != "current") store.addKnownModel(provider, m)
                }
                knownModels.value = if (provider.isNotBlank()) store.knownModels(provider) else emptySet()
            }
            is AcpEvent.Ready -> {
                live = true; connecting = false; online.value = true
                lastSessionId = ev.sessionId; store.lastSessionId = ev.sessionId
                // Flush every queued send (bubbles were already added when queued).
                while (pendingSends.isNotEmpty()) {
                    val p = pendingSends.removeFirst()
                    client?.sendPrompt(p.text, p.images)
                }
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
