package id.gauvin.grouse

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf

/** The three drawer session categories. See ConnectionManager.sessionKind(). */
enum class SessionKind { ASSISTANT, CHAT, CODE }

private val chatMessageSeq = java.util.concurrent.atomic.AtomicLong(0)
/** Stable per-message id so the chat LazyColumn keys on identity, not position. copy() preserves it,
 *  so the streaming message keeps the same id as its text grows → its composition is reused, not rebuilt. */
data class ChatMessage(
    val role: String,
    val text: String,
    val images: List<ImageBlock> = emptyList(),
    // Extra detail for a "tool" message: the tool's rawInput (command/args) — Desktop shows this,
    // Grouse was discarding it and only keeping the title. Unused by other roles.
    val detail: String = "",
    val id: Long = chatMessageSeq.getAndIncrement(),
)

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

    /** How many prompts are waiting behind the running turn. Drives the "N queued" chip -- without
     *  it a queued message is indistinguishable from a dropped one, since its bubble looks exactly
     *  like a sent one. Mutate the deque ONLY through enqueue/dequeue/clearQueue so this can't drift. */
    val queuedCount = mutableStateOf(0)
    private fun enqueue(p: PendingSend) { pendingSends.add(p); queuedCount.value = pendingSends.size }
    private fun dequeue(): PendingSend? =
        (if (pendingSends.isEmpty()) null else pendingSends.removeFirst()).also { queuedCount.value = pendingSends.size }
    private fun clearQueue() { pendingSends.clear(); queuedCount.value = 0 }
    // True between sendPrompt and TurnDone. `busy` is UI state and is also set while merely
    // queued, so it cannot answer "is the wire busy" -- this can.
    private var turnInFlight = false

    val messages = mutableStateListOf<ChatMessage>()
    val status = mutableStateOf("not connected")
    val online = mutableStateOf(false)   // true between Ready and disconnect — for a UI status pill
    val config = mutableStateOf<List<ConfigOption>>(emptyList())
    val sessions = mutableStateOf<List<SessionInfo>>(emptyList())
    val currentSession = mutableStateOf<String?>(null)   // id of the session on screen (for the Assistant binding)
    val busy = mutableStateOf(false)
    val usage = mutableStateOf<AcpEvent.Usage?>(null)   // context window used/size + cost
    // True while compaction (manual /compact or server-triggered auto-compact) is running. The
    // protocol only ever sends discrete text status lines, never a numeric percentage, so this
    // drives an INDETERMINATE indicator, not a real progress fraction.
    val compacting = mutableStateOf(false)
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
    // Live model list for the CURRENT provider, from the server, in memory only -- never
    // persisted. See the AcpEvent.Config/SupportedModels handlers for why.
    val knownModels = mutableStateOf(emptySet<String>())
    // Guards listSupportedModels() to fire once per provider per connection, not on every Config
    // event (which fires on every option change, not just provider switches).
    private var liveModelsFetchedFor: String? = null
    val extensions = mutableStateOf<List<ExtInfo>>(emptyList())
    val extensionsBusy = mutableStateOf(false)
    // Names of the CURRENT session's enabled extensions — drives the in-chat "N tools" indicator
    // and its management sheet. Refreshed on every session open (Ready); optimistically updated by
    // toggleSessionExtension since add/remove replies are empty (no server re-list to react to).
    val sessionExtensionNames = mutableStateOf<List<String>>(emptyList())
    // Per-message generation stats (tok/s, cost) for the most recently finished assistant reply.
    // Cleared when a new turn starts so stale numbers don't linger under the next streaming bubble.
    val lastMessageUsage = mutableStateOf<AcpEvent.MessageUsage?>(null)

    /** Fetch the extension list over ACP (agent-global). Reply lands as AcpEvent.Extensions.
     *  goose ≥1.42 dropped goosed's REST /config/extensions; this uses the ACP method instead. */
    fun loadExtensions() {
        val c = client ?: run { extensions.value = emptyList(); extensionsBusy.value = false; return }
        extensionsBusy.value = true
        c.listExtensions()
    }

    // Per-session-type extension profiles (Assistant/Chat/Code) were REMOVED 2026-07-25. They were
    // a third layer on top of the two goose actually defines, and the three fought each other: goose
    // seeds a session from config.yaml, the profile then diffed it back to a stored set, and the
    // in-chat sheet edited the result -- so "what tools does this chat have" had three owners and no
    // single answer. Goose's own model is the two below, and it is enough:
    //
    //   config/extensions/set-enabled  -> writes config.yaml, the default every NEW session starts from
    //   session/extensions/{add,remove} -> this session only, never persisted
    //
    // Settings owns the first, the in-chat sheet owns the second. If a whole class of session needs a
    // different tool set, that is what a recipe's `extensions:` block is for -- goose already scopes
    // per-run there, with `available_tools` to trim inside an extension.

    /** Enable/disable an extension globally (affects new chats); the reply refreshes the list. */
    fun toggleExtension(e: ExtInfo, enabled: Boolean) {
        val c = client ?: return
        extensionsBusy.value = true
        c.setExtensionEnabled(e.configKey, enabled)
    }

    /** Enable/disable one extension for just THIS session (session-scoped API — never touches
     *  config.yaml or any other open session). Optimistic: add/remove replies are empty, so
     *  sessionExtensionNames is updated immediately rather than waiting on a re-list. */
    fun toggleSessionExtension(name: String, enabled: Boolean) {
        if (enabled) {
            extensions.value.firstOrNull { it.name == name }?.let { client?.addSessionExtension(it.raw) }
            sessionExtensionNames.value = sessionExtensionNames.value + name
        } else {
            client?.removeSessionExtension(name)
            sessionExtensionNames.value = sessionExtensionNames.value - name
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
    // The cwd resolved for the in-flight open() -- persisted to store.lastSessionCwd once Ready
    // fires (Ready itself carries no cwd; this is the single source of truth for what we asked for).
    private var pendingOpenCwd: String = "/state"
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

    /** Startup: land directly on the privileged Assistant thread (its home). Uses the cached id to
     *  resume it with no churn; on first run (no cache) connects fresh and opens it once the list
     *  arrives. */
    fun connectHome() {
        if (!store.hasKey()) return
        val a = store.assistantSessionId
        if (a != null) openSession(a, knownKind = SessionKind.ASSISTANT)
        else { pendingOpenAssistant = true; open(resume = null, suppressReplay = false) }
    }

    /** Save new credentials and connect fresh (from the Connect screen). */
    fun connect(host: String, port: String, key: String) {
        store.host = host; store.port = port; store.secretKey = key
        lastSessionId = null; config.value = emptyList()
        open(resume = null, suppressReplay = false)
    }

    /** Reconnect silently after Android drops the socket in the background. */
    fun ensureConnected() {
        if (!store.hasKey() || live || connecting) return
        // suppressReplay ONLY when we still hold the conversation in memory. It exists to stop a
        // brief socket blip duplicating messages we already show -- but if the PROCESS was killed
        // while backgrounded (aggressive OEM battery management does this even to a foreground
        // service), `messages` comes back empty and suppressing the replay leaves a blank chat.
        // The turn goose actually finished is then invisible, which reads as "it died" and gets
        // re-prompted. Replaying repopulates it instead.
        open(resume = lastSessionId, suppressReplay = messages.isNotEmpty())
    }

    fun listSessions() = client?.listSessions()

    fun openSession(sessionId: String, knownKind: SessionKind? = null) {
        messages.clear(); lastSessionId = sessionId; currentSession.value = sessionId
        // A caller resuming a session it already has cached (e.g. connectHome's assistant shortcut)
        // can pass knownKind to skip the sessions.value lookup, which may not be populated yet on a
        // cold start; open() falls back to that lookup (then CHAT) when knownKind is null.
        val kind = knownKind ?: sessions.value.firstOrNull { it.sessionId == sessionId }
            ?.let { ConnectionManager.sessionKind(it) }
        open(resume = sessionId, suppressReplay = false, kind = kind)
    }

    fun newSession(cwd: String = "/state", kind: SessionKind = SessionKind.CHAT) {
        messages.clear(); lastSessionId = null; currentSession.value = null; config.value = emptyList()
        open(resume = null, suppressReplay = false, cwd = cwd, kind = kind)
    }

    /** A Code session: scoped to a project directory under the server's /workspace bind mount
     *  (the user's ~/dev) instead of the default /state. This IS the "designation" -- goose has no
     *  tags/labels, so cwd is the native, protocol-level signal sessionKind() reads back later. */
    fun newCodeSession(project: String) {
        val clean = project.trim().trim('/')
        require(clean.isNotEmpty() && !clean.contains("..")) { "invalid project name" }
        store.addRecentWorkspaceProject(clean)
        newSession(cwd = "/workspace/$clean", kind = SessionKind.CODE)
    }

    /** The persistent "goose-assistant" thread (briefings/proactive/voice land here), if it exists. */
    fun assistantSessionId(): String? = sessions.value.firstOrNull { it.title == ASSISTANT_TITLE }?.sessionId

    /** True when the on-screen conversation IS the privileged assistant thread. */
    val onAssistant: Boolean get() = currentSession.value != null && currentSession.value == assistantSessionId()

    @Volatile private var pendingOpenAssistant = false

    /** Open the privileged assistant thread; if the session list isn't loaded yet, refresh it and
     *  open as soon as it arrives (see the Sessions event handler). */
    fun openAssistant() {
        val id = assistantSessionId()
        if (id != null) openSession(id, knownKind = SessionKind.ASSISTANT)
        else { pendingOpenAssistant = true; listSessions() }
    }

    // --- Assistant-thread reset / (re)create ---
    // The reset is bound to the SPECIFIC connection generation it opens (resetGen), so an
    // unrelated Ready — e.g. the user tapping another chat before the fresh session connects —
    // can never be mistaken for the reset's session and wrongly renamed to ASSISTANT_TITLE
    // (which would grant that chat the privileged auto-approve policy). Both renames run on that
    // one live socket in order, so nothing is lost to a close race. If a different open()
    // supersedes the pending reset, it's abandoned cleanly (see open()).
    private var resetGen = -1                 // clientGen of the reset-initiated connect
    private var resetOldId: String? = null    // thread to archive once the fresh one is live (null = create-only)

    /** Reset the privileged assistant thread: archive the current one (history kept — renamed to
     *  "goose-assistant-archived-<yyyymmdd>", so deliver.sh's name-grep and the app's title match
     *  stop resolving it) and stand up a fresh empty one. Both renames happen on the fresh
     *  session's live socket in the Ready handler. Used when the thread jams (e.g. context
     *  overflow). No server-side deletion. Safe offline — it simply no-ops until reconnected. */
    fun resetAssistant() {
        // Prefer the authoritative cached id over the (possibly stale) title lookup.
        val old = store.assistantSessionId ?: assistantSessionId()
        store.assistantSessionId = null
        store.lastSessionId = null            // don't let a fallback reconnect resume the old thread
        beginAssistantThread(old)
    }

    /** Open a fresh session that will become the ASSISTANT_TITLE thread once live. `archiveOld`,
     *  if non-null, is renamed aside first (reset); null just creates a new assistant thread
     *  (recovery when none exists). Binds to the connection generation this open() creates. */
    private fun beginAssistantThread(archiveOld: String?) {
        resetOldId = archiveOld
        resetGen = clientGen + 1              // the gen newSession()'s open() is about to create
        newSession(kind = SessionKind.ASSISTANT)
    }

    /** yyyymmdd from the wall clock, only for a human-readable archived-thread suffix. */
    private fun archiveStamp(): String =
        java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US).format(java.util.Date())

    // --- Server-side goose config (global config.yaml, edited over ACP) ---
    /** Current server values, populated by loadServerConfig(); empty until read. */
    val serverContextLimit = mutableStateOf("")
    val serverFastModel = mutableStateOf("")

    /** Read the app-editable global goose settings so Settings can show current values. */
    fun loadServerConfig() {
        client?.readConfig("GOOSE_CONTEXT_LIMIT")
        client?.readConfig("GOOSE_FAST_MODEL")
    }

    /** Upsert a global goose setting (takes effect for NEW sessions/tasks), then re-read to confirm. */
    fun setServerConfig(key: String, value: String) {
        client?.upsertConfig(key, value)
        client?.readConfig(key)
    }

    fun setOption(configId: String, value: String) {
        store.saveOption(configId, value)
        client?.setConfigOption(configId, value)
    }

    fun send(text: String, images: List<ImageBlock> = emptyList()) {
        // Keep the images ON the message so the bubble renders the actual thumbnail(s), not a
        // "[📎 N]" placeholder. (Live-session only — a session reloaded from the server replays
        // text; images aren't reconstructed from the replayed content blocks.)
        messages.add(ChatMessage("user", text, images)); streamingRole = null; busy.value = true
        lastMessageUsage.value = null   // stale stats from the previous turn shouldn't linger
        startService()   // keep the socket alive if the user backgrounds mid-turn
        if (live && !turnInFlight) {
            turnInFlight = true
            lastSessionId?.let { store.pendingPushSessionId = it }
            client?.sendPrompt(text, images)
        } else if (live) {
            // A turn is already running. Queue rather than firing a second sendPrompt into the
            // same session -- concurrent prompts interleave in the transcript and the second
            // reply is attributed to the wrong question. Flushed on TurnDone.
            enqueue(PendingSend(text, images))
        } else {
            // Not connected yet (initial connect / silent reconnect window): queue and connect,
            // rather than calling sendPrompt against a session-less client (which just errors and
            // loses the message). Flushed in the Ready branch.
            enqueue(PendingSend(text, images))
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
        // Never re-send the legacy "current" sentinel -- goose forwards it verbatim and LocalAI
        // 404s. Devices that picked the old "Provider default" entry have it persisted, so
        // without this they would keep re-poisoning the config on every reconnect.
        saved["model"]?.takeIf { it != "current" }?.let { client?.setConfigOption("model", it) }
    }

    fun setForeground(fg: Boolean) {
        appForeground = fg
        if (fg) {
            notifier.cancelAlert()
            ensureConnected()
            // Re-ask the server for its model list every time we come back. It was previously
            // fetched ONCE per provider per connection (guarded by liveModelsFetchedFor, which
            // only resets in open()), so a long-lived socket never noticed the set changing --
            // models renamed or added server-side stayed invisible until a full reconnect, and
            // ensureConnected() above can't force one because it early-returns on `live`.
            // One /v1/models round trip through goose; cheap enough to just redo on resume.
            if (live) {
                config.value.firstOrNull { it.id == "provider" }?.currentValue
                    ?.takeIf { it.isNotBlank() }
                    ?.let { client?.listSupportedModels(it) }
            }
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

    /** Stop the running turn — reliably, even if goose is wedged mid-turn and won't honor the
     *  polite ACP cancel. We send the cancel, free the UI immediately, then reconnect (resume):
     *  reopening bumps the client generation so any late events from the stuck connection are
     *  dropped, and the fresh client comes back idle. The streamed partial stays in the list. */
    fun cancel() {
        client?.cancel()
        streamingRole = null
        busy.value = false
        turnInFlight = false   // wire is free again; without this the queue never drains
        clearQueue()           // Stop means stop: don't let queued prompts fire after a cancel
        if (store.hasKey()) open(resume = lastSessionId ?: store.lastSessionId, suppressReplay = true)
    }

    /** Compact the conversation history to reclaim context (goose /compact command). */
    fun compact() {
        busy.value = true
        compacting.value = true   // client-initiated: don't wait for the server's own status echo
        client?.sendPrompt("/compact")
    }

    /** Answer the given approval request; null optionId denies (cancelled). */
    fun answerPermission(p: AcpEvent.Permission, optionId: String?) {
        client?.respondPermission(p.toolCallId, optionId)
        permissions.remove(p)
    }

    private fun open(resume: String?, suppressReplay: Boolean, cwd: String? = null, kind: SessionKind? = null) {
        // If a reset/create-assistant is pending but THIS open() isn't the one it scheduled
        // (clientGen+1 != resetGen), a different navigation superseded it — abandon it so a later
        // unrelated Ready can't complete a stale rename.
        if (resetOldId != null || resetGen >= 0) {
            if (clientGen + 1 != resetGen) { resetOldId = null; resetGen = -1 }
        }
        client?.close()
        live = false; connecting = true; online.value = false
        // A new client can't receive the old client's TurnDone, so clear turn state here.
        // Otherwise a hung/dropped turn leaves busy=true and every new chat + reconnect
        // inherits a stuck "goose is thinking…" with nothing sent. Same logic for compacting:
        // it's only cleared by TurnDone/Error/a matched CompactionStatus on the SAME client — if
        // you switch sessions or background mid-compact, the old client gets superseded (dropped
        // by the clientGen guard) before any of those arrive, and compacting (global, not
        // per-session) stays stuck true forever after, permanently hiding the usage line under it
        // (they're if/else-if) on every session including ones that were never compacting at all.
        busy.value = false; streamingRole = null; compacting.value = false
            // The superseded client will never report TurnDone here, so free the wire.
            turnInFlight = false
        liveModelsFetchedFor = null   // re-fetch supported models fresh on every new connection
        val url = "wss://${store.host}:${store.port}/acp"
        status.value = when {
            resume == null -> "connecting to $url"
            suppressReplay -> "reconnecting…"
            else -> "loading session…"
        }
        val saved = store.savedOptions(optionIds)
        // Resolve the cwd for this open(): an explicit param wins (new session creation always
        // knows its own target); otherwise, for a resume, prefer the cached SessionInfo's cwd
        // (sessions.value, if already loaded) and fall back to the last-persisted cwd for a cold
        // start before any session/list round-trip has happened. session/load's cwd param SILENTLY
        // REWRITES the session's working_dir if wrong, so this must be right, not just "close enough".
        val resolvedCwd = cwd ?: if (resume == null) "/state" else
            sessions.value.firstOrNull { it.sessionId == resume }?.cwd?.takeIf { it.isNotBlank() }
                ?: store.lastSessionCwd
        pendingOpenCwd = resolvedCwd
        // Tag this client's events with a generation; a just-closed client still fires
        // onClosed/onFailure asynchronously and its stale "disconnected" must not flip us offline
        // after the new client is already live.
        val gen = ++clientGen
        client = AcpClient(url, store.secretKey) { ev -> main.post { if (gen == clientGen) onEvent(ev) } }.also {
            it.desiredOptions = if (resume == null) saved else emptyMap()
            it.resumeSessionId = resume
            it.resumeCwd = resolvedCwd
            it.desiredCwd = resolvedCwd
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
                messages.add(ChatMessage("error", ev.text)); streamingRole = null; busy.value = false; turnInFlight = false
                compacting.value = false   // safety net: a dropped/garbled status must never stick
                if (voiceReplyPending) { voiceReplyPending = false; restoreModel() }
            }
            is AcpEvent.ToolCall -> { messages.add(ChatMessage("tool", ev.title, detail = ev.detail)); streamingRole = null }
            is AcpEvent.Usage -> usage.value = ev
            is AcpEvent.CompactionStatus -> {
                // Substring match on goose's own status copy — see agents/agent.rs (aaif-goose/goose,
                // commit 1e03bbb5): Notice "Exceeded auto-compact threshold... Performing
                // auto-compaction...", Progress "goose is compacting the conversation...", Notice
                // "Compaction complete". Both start and end lines are type Notice, so "notice vs
                // progress" can't gate the indicator — the text itself is the only stable signal. If
                // a future goose upgrade rewords these, this just stops firing (fails safe: no
                // spinner, never a wrong one) — re-check against that file after a server bump.
                val m = ev.message.lowercase()
                if (m.contains("compact")) compacting.value = true
                if (m.contains("complete") || m.contains("error")) compacting.value = false
            }
            is AcpEvent.Chart -> { messages.add(ChatMessage("chart", ev.spec)); streamingRole = null }
            is AcpEvent.TurnDone -> {
                streamingRole = null; compacting.value = false
                turnInFlight = false
                // Drain one queued prompt, if any: send it and STAY busy, so the UI never flickers
                // idle between a queue and its turn.
                val queued = dequeue()
                busy.value = queued != null
                // We got the authoritative completion straight from our own socket -- stop waiting
                // on the Stop-hook push for this turn so a later turn from another client in the
                // same (possibly shared) session doesn't spuriously match the stale flag.
                store.pendingPushSessionId = null
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
                if (queued != null) {
                    // Send the queued prompt now that the wire is free. Service stays up (we are
                    // still busy), so backgrounding between the two turns is safe.
                    turnInFlight = true
                    lastSessionId?.let { store.pendingPushSessionId = it }
                    client?.sendPrompt(queued.text, queued.images)
                } else if (!store.persistentConnection) stopService()
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
                // Models come from the SERVER, live, and are never persisted. The old design kept
                // a per-provider set in SharedPreferences that only ever GREW: every slug goose
                // ever reported stayed forever, so a model retired server-side (Qwen3_1.7B), an
                // alias that was renamed (the whole 2026-07-25 rename), and slugs that leaked in
                // from another provider during a switch (z-ai/glm-5.2, gpt-4o under openai) all
                // accumulated with no way to clear them short of wiping app data. Two separate
                // one-time migrations existed in SecureStore purely to repair that cache, and a
                // shape heuristic here tried to stop it being poisoned in the first place.
                //
                // None of that is needed: fetch_supported_models is authoritative and cheap. Ask
                // the server, show the answer, keep nothing.
                val provider = ev.options.firstOrNull { it.id == "provider" }?.currentValue ?: ""
                if (provider != liveModelsFetchedFor) {
                    // Provider changed (or first Config): drop the previous provider's list
                    // immediately so its slugs cannot be shown under the new one, even briefly.
                    knownModels.value = emptySet()
                    liveModelsFetchedFor = provider
                    if (provider.isNotBlank()) client?.listSupportedModels(provider)
                }
            }
            is AcpEvent.SupportedModels -> {
                // Straight replace, in memory only. Ignore a reply for a provider we have since
                // switched away from, so a slow response can't repopulate the wrong list.
                val currentProvider = config.value.firstOrNull { it.id == "provider" }?.currentValue
                if (ev.providerId == currentProvider) knownModels.value = ev.models.toSet()
            }
            is AcpEvent.Ready -> {
                live = true; connecting = false; online.value = true
                lastSessionId = ev.sessionId; store.lastSessionId = ev.sessionId
                store.lastSessionCwd = pendingOpenCwd   // Ready itself carries no cwd -- see open()
                currentSession.value = ev.sessionId
                // Populate the in-chat "N tools" indicator for THIS session.
                client?.listSessionExtensions()
                // Finishing an assistant reset/create: only when THIS Ready is the reset's own
                // fresh session (its connection generation matches resetGen). Archive the old
                // thread first (if any), then name this one so both the app (title match) and
                // deliver.sh (name-grep) resolve it as the assistant thread. Both renames run
                // here, on this one live socket, in order — nothing is lost to a close race.
                if (resetGen == clientGen) {
                    val old = resetOldId
                    resetOldId = null; resetGen = -1
                    if (old != null && old != ev.sessionId)
                        client?.renameSession(old, "$ASSISTANT_TITLE-archived-${archiveStamp()}")
                    client?.renameSession(ev.sessionId, ASSISTANT_TITLE)
                    store.assistantSessionId = ev.sessionId
                }
                client?.listSessions()   // so the Assistant thread can be resolved by title
                // Send ONE queued prompt (bubbles were already added when queued); TurnDone drains
                // the rest. This used to `while`-loop the whole deque, firing every queued prompt
                // into the session at once -- which interleaves them in the transcript and
                // misattributes each reply, the exact failure the queue exists to prevent. It also
                // left turnInFlight false, so the next send() would fire a concurrent prompt too.
                dequeue()?.let { p ->
                    store.pendingPushSessionId = ev.sessionId
                    turnInFlight = true
                    busy.value = true
                    client?.sendPrompt(p.text, p.images)
                }
            }
            is AcpEvent.Sessions -> {
                sessions.value = ev.list
                assistantSessionId()?.let { store.assistantSessionId = it }   // cache for startup landing
                if (pendingOpenAssistant) {
                    pendingOpenAssistant = false
                    val id = assistantSessionId()
                    // If no assistant thread exists (e.g. an interrupted reset, or a fresh box),
                    // recreate one instead of silently no-oping so openAssistant() can never dead-end.
                    if (id != null) openSession(id, knownKind = SessionKind.ASSISTANT) else beginAssistantThread(null)
                }
            }
            is AcpEvent.ServerConfig -> {
                when (ev.key) {
                    "GOOSE_CONTEXT_LIMIT" -> serverContextLimit.value = ev.value
                    "GOOSE_FAST_MODEL" -> serverFastModel.value = ev.value
                }
            }
            is AcpEvent.Commands -> commands.value = ev.names
            is AcpEvent.Extensions -> { extensions.value = ev.list; extensionsBusy.value = false }
            is AcpEvent.SessionExtensions -> sessionExtensionNames.value = ev.names
            is AcpEvent.MessageUsage -> lastMessageUsage.value = ev
            is AcpEvent.Permission -> {
                // The privileged Assistant thread honors the user's chosen action policy; every
                // other conversation (and voice) always prompts. Voice already auto-denies via its
                // own observer, so this only changes behaviour on the Assistant thread.
                when (if (onAssistant) store.assistantActions else "confirm") {
                    "auto" -> {   // trusted: approve without prompting (prefer allow-always)
                        val allow = ev.options.firstOrNull { it.kind.contains("allow_always") }
                            ?: ev.options.firstOrNull { it.kind.contains("allow") }
                        client?.respondPermission(ev.toolCallId, allow?.optionId)
                    }
                    "readonly" -> client?.respondPermission(ev.toolCallId, null)   // deny writes/shell
                    else -> {   // confirm: raise the approval sheet
                        permissions.add(ev)
                        if (!appForeground) notifier.postApprovalNeeded(ev.title)
                    }
                }
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
        /** Server-side name of the persistent assistant thread (see docker/llm/goose-recipes). */
        const val ASSISTANT_TITLE = "goose-assistant"
        /** goose has no session tags/labels -- cwd is the native signal. A Code session is simply
         *  one scoped under /workspace (the server's ~/dev bind mount); everything else on the
         *  default /state is Chat, unless its title marks it as the privileged Assistant thread. */
        fun sessionKind(s: SessionInfo): SessionKind = when {
            s.title == ASSISTANT_TITLE -> SessionKind.ASSISTANT
            s.cwd.startsWith("/workspace") -> SessionKind.CODE
            else -> SessionKind.CHAT
        }
        @Volatile private var instance: ConnectionManager? = null
        fun get(context: Context): ConnectionManager =
            instance ?: synchronized(this) {
                instance ?: ConnectionManager(context.applicationContext).also { instance = it }
            }
    }
}
