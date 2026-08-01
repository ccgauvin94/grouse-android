package id.gauvin.grouse

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.runtime.mutableStateListOf
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
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
    // Tool-role only: goose's toolCallId (correlates tool_call_update notifications),
    // lifecycle status (in_progress/completed/failed), and the tool's OUTPUT text when the
    // completion update carried content. Live sessions only — replays don't reconstruct these.
    val toolCallId: String = "",
    val status: String = "",
    val output: String = "",
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
    // messageId of the chunk currently streaming into the open bubble (replay only).
    private var streamMsgId: String? = null

    val messages = mutableStateListOf<ChatMessage>()
    val status = mutableStateOf("not connected")
    val online = mutableStateOf(false)   // true between Ready and disconnect — for a UI status pill
    val config = mutableStateOf<List<ConfigOption>>(emptyList())
    val sessions = mutableStateOf<List<SessionInfo>>(emptyList())

    /** Goose projects, refreshed alongside the session list. A project is a named source with an
     *  id, not a directory -- so filing a chat no longer decides where its tools run, and the
     *  same project is one entry from every client instead of one per cwd spelling. */
    val projects = mutableStateOf<List<ProjectInfo>>(emptyList())

    /** Sessions grouped by project, most-recent project first, unfiled last. Unfiled is ONE
     *  bucket: the old cwd grouping made a separate group per directory, which is how a single
     *  "state" project ended up holding every chat. */
    fun sessionsByProject(): List<Pair<String, List<SessionInfo>>> {
        val byName = projects.value.associate { it.id to it.name }
        return sessions.value
            .groupBy { it.projectId ?: "" }
            .entries
            .sortedWith(compareBy({ it.key.isEmpty() }, { -(it.value.maxOfOrNull { s -> s.updatedAt } ?: "").hashCode() }))
            .map { (id, list) ->
                val label = if (id.isEmpty()) "Unfiled" else (byName[id] ?: id)
                label to list.sortedByDescending { it.updatedAt }
            }
    }

    fun refreshProjects() { client?.listProjects() }
    fun createProject(name: String) { client?.createProject(name.trim()) }
    fun fileSession(sessionId: String, projectId: String?) {
        client?.assignSessionProject(sessionId, projectId)
        sessions.value = sessions.value.map {
            if (it.sessionId == sessionId) it.copy(projectId = projectId) else it
        }
    }
    /** Observable mirror of store.recentWorkspaceProjects() — SharedPreferences aren't Compose
     *  state, so a delete that only touched the store left the drawer stale until app restart.
     *  store is declared above (line ~38), so reading it at init here is safe. */
    val recentProjects = mutableStateOf(store.recentWorkspaceProjects())
    val currentSession = mutableStateOf<String?>(null)   // id of the session on screen (for the Assistant binding)
    val busy = mutableStateOf(false)
    val usage = mutableStateOf<AcpEvent.Usage?>(null)   // context window used/size + cost
    // True while compaction (manual /compact or server-triggered auto-compact) is running. The
    // protocol only ever sends discrete text status lines, never a numeric percentage, so this
    // drives an INDETERMINATE indicator, not a real progress fraction.
    val compacting = mutableStateOf(false)
    val commands = mutableStateOf<List<String>>(emptyList())
    val permissions = mutableStateListOf<AcpEvent.Permission>()   // pending approvals, oldest first
    val elicitations = mutableStateListOf<AcpEvent.Elicitation>() // pending input forms, oldest first
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
    // Tools ACTIVE in the current session, grouped extension -> tool names (the `ext__tool` prefix
    // goose uses, stripped). Reflects available_tools filtering, so it is the "checked" set.
    val sessionTools = mutableStateOf<Map<String, List<String>>>(emptyMap())
    // Full tool catalogue per extension, i.e. what you'd get with no allowlist. Not obtainable
    // directly -- goose has no per-extension tools endpoint -- so it is discovered on demand by
    // discoverTools() and cached here for the process lifetime. Absent = not discovered yet.
    val toolCatalog = mutableStateOf<Map<String, List<String>>>(emptyMap())
    // Extension whose full catalogue is being discovered; its tools/list reply is the catalogue,
    // not the live set, so the Tools handler must not treat it as sessionTools.
    private var discovering: String? = null
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

    /** Group `ext__tool` names into ext -> [tool]. ONLY mcp-type extensions namespace their tools
     *  this way: developer's are bare (`shell`, `edit`, `tree`), summon's is `delegate`, skills' is
     *  `load_skill`. So an extension with no matching prefix is not "zero tools", it is "cannot be
     *  attributed" -- see toolsAttributable(). Grouping bare names under a bucket and showing that
     *  as a count is what made every builtin read 0. */
    private fun group(names: List<String>): Map<String, List<String>> =
        names.filter { it.contains("__") }
            .groupBy({ it.substringBefore("__") }, { it.substringAfter("__") })

    /** Whether per-tool control can be offered for this extension at all. Only mcp-backed ones
     *  namespace their tools, and only namespaced tools can be mapped back to an owner. */
    fun toolsAttributable(e: ExtInfo): Boolean = e.type == "mcp"

    /** Ask goose for this session's active tools; lands as AcpEvent.Tools. */
    fun refreshTools() { discovering = null; client?.listTools() }

    /** Everything the in-chat tool sheet displays, refreshed together on open. */
    fun refreshSessionSheet() { refreshTools(); client?.listSessionExtensions() }

    /** Discover an extension's FULL tool set. goose only reports ALLOWED tools, so the only way to
     *  see what an allowlist is hiding is to briefly run the extension unfiltered: re-add it
     *  session-scoped with an empty available_tools, list, then put the real setting back. Entirely
     *  session-local -- config.yaml is untouched -- and self-healing, since the restore re-applies
     *  whatever the session should have. */
    fun discoverTools(ext: ExtInfo) {
        // Catalogue is cached for the process lifetime, but sessionTools is NOT reliably fresh:
        // MCP extensions attach asynchronously after Ready, and the two listTools polls (0s/2.5s)
        // can both miss a slow one. Expanding a cached row used to early-return without any
        // refresh, so the sheet rendered the PREVIOUS session's tool state after a global-default
        // change -- "the session tools list isn't accurate". Refresh cheaply instead.
        if (toolCatalog.value.containsKey(ext.name)) { refreshTools(); return }
        val c = client ?: return
        val unfiltered = JsonObject(ext.raw.toMutableMap().apply {
            put("available_tools", JsonArray(emptyList()))
        })
        discovering = ext.name
        c.removeSessionExtension(ext.name)
        c.addSessionExtension(unfiltered)   // its reply triggers listTools -- see AcpClient
    }

    /** Restrict `ext` to `allowed` for THIS session only (no config.yaml write). Empty = all. */
    fun setSessionTools(ext: ExtInfo, allowed: Set<String>) {
        val c = client ?: return
        val full = toolCatalog.value[ext.name].orEmpty()
        // An allowlist equal to the whole catalogue is the same as no allowlist, and storing []
        // keeps it that way if the extension later gains tools.
        val list = if (allowed.size >= full.size && full.isNotEmpty()) emptyList() else allowed.toList()
        val scoped = JsonObject(ext.raw.toMutableMap().apply {
            put("available_tools", JsonArray(list.map { JsonPrimitive(it) }))
        })
        discovering = null
        c.removeSessionExtension(ext.name)
        c.addSessionExtension(scoped)       // its reply triggers listTools
    }

    /** Save `allowed` as the GLOBAL default for `ext` (config.yaml; applies to new chats). */
    fun setDefaultTools(ext: ExtInfo, allowed: Set<String>) {
        val c = client ?: return
        val full = toolCatalog.value[ext.name].orEmpty()
        val list = if (allowed.size >= full.size && full.isNotEmpty()) emptyList() else allowed.toList()
        val updated = JsonObject(ext.raw.toMutableMap().apply {
            put("available_tools", JsonArray(list.map { JsonPrimitive(it) }))
        })
        extensionsBusy.value = true
        c.addExtensionConfig(updated, ext.enabled)
    }

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
    // True between a ReplayStart wiping `messages` and the following Ready, which re-adds the
    // bubbles of any still-queued prompts (they aren't in the server history the replay rebuilt).
    private var replayWiped = false
    // Replay scroll-pinning: the chat list is keyed, so each replayed bubble inserted at index 0
    // can drift the key-anchored viewport off the exact bottom, after which the atBottom-gated
    // autoscroll stops and history lands scrolled mid-list. While a replay is rebuilding, the UI
    // pins unconditionally; the tick fires one final snap when the rebuild completes.
    val replayActive = mutableStateOf(false)
    val replayDoneTick = mutableStateOf(0)
    // Replays rebuild into this buffer instead of mutating `messages`; Ready swaps it in only
    // when the content actually differs. The common background-reconnect replay is identical,
    // so the visible list is never touched and the scroll position survives by construction —
    // every capture/restore scheme raced the UI's own collectors and lost.
    private val replayBuffer = mutableListOf<ChatMessage>()
    private fun t(): MutableList<ChatMessage> =
        if (replayActive.value) replayBuffer else messages
    // The cwd resolved for the in-flight open() -- persisted to store.lastSessionCwd once Ready
    // fires (Ready itself carries no cwd; this is the single source of truth for what we asked for).
    private var pendingOpenCwd: String = DEFAULT_CWD
    private val optionIds = listOf("provider", "model", "mode", "thinking_effort")

    // Voice: the voice sheet has a short lifecycle, so CM owns speaking the reply (it survives the
    // sheet closing) and suppresses the finished-turn notification for voice turns. The turn can
    // run on a faster voice model, restored afterwards.
    @Volatile var voiceActive = false
    @Volatile private var voiceReplyPending = false
    @Volatile private var voiceModelActive = false
    private var lastVoiceAt = 0L
    private val voiceSpeaker by lazy { Speaker(appContext) }
    private var serverPlayer: android.media.MediaPlayer? = null

    /** Speak `text`, through LocalAI if the user turned that on, else Android TextToSpeech.
     *  Falls back to the on-device voice when the server call fails, so a LocalAI outage degrades
     *  the voice rather than silencing replies. */
    fun say(text: String, whenDone: (() -> Unit)? = null) {
        if (!store.serverTts) { voiceSpeaker.speak(text, whenDone); return }
        serverPlayer?.let { runCatching { it.stop() }; runCatching { it.release() } }
        serverPlayer = ServerSpeech.speak(
            store.localAiUrl, store.ttsModel, text, appContext.cacheDir,
            onError = { msg ->
                main.post {
                    status.value = "TTS: $msg"
                    voiceSpeaker.speak(text, whenDone)   // fall back to the device voice
                }
            },
            onDone = { main.post { whenDone?.invoke() } },
        )
    }

    /** Stop any in-flight speech, whichever engine produced it. */
    fun stopSpeaking() {
        voiceSpeaker.stop()
        serverPlayer?.let { runCatching { it.stop() }; runCatching { it.release() } }
        serverPlayer = null
    }

    val configured: Boolean get() = store.hasKey()

    /** Connect using the already-saved host/port/key (post-unlock auto-connect). */
    fun connectSaved() { if (store.hasKey()) open(resume = null) }

    // connectHome() re-enters composition every time the lock screen (or any recreation) swaps
    // AppRoot back in; only the FIRST call per process should land on the Assistant. Later calls
    // resume whatever session was open instead.
    private var homeOpened = false

    /** Fresh start: land directly on the privileged Assistant thread (its home). Uses the cached
     *  id to resume it with no churn; on first run (no cache) connects fresh and opens it once the
     *  list arrives. After the first call this only re-establishes the connection. */
    fun connectHome() {
        if (!store.hasKey()) return
        if (homeOpened) { ensureConnected(); return }
        homeOpened = true
        // Master switch off: connect to the last regular session instead of the Assistant.
        if (!store.assistantEnabled) { ensureConnected(); return }
        val a = store.assistantSessionId
        if (a != null) openSession(a, knownKind = SessionKind.ASSISTANT)
        else { pendingOpenAssistant = true; open(resume = null) }
    }

    /** Save new credentials and connect fresh (from the Connect screen). */
    fun connect(host: String, port: String, key: String) {
        store.host = host; store.port = port; store.secretKey = key
        lastSessionId = null; config.value = emptyList()
        open(resume = null)
    }

    /** Reconnect silently after Android drops the socket in the background. The resume always
     *  replays: the server history is rebuilt into the transcript on every session/load (see
     *  AcpEvent.ReplayStart), which both repopulates after a background process kill and picks up
     *  turns another client (Desktop, deliver.sh) added to this session while we were away. */
    fun ensureConnected() {
        if (!store.hasKey() || live || connecting) return
        open(resume = lastSessionId)
    }

    // A turn was streaming when the socket died. goosed streams a turn ONLY to the connection
    // that prompted it (protocol fact, not a bug here), so the remainder is invisible to the
    // replacement socket and the chat looks frozen mid-tool-calls. Best available recovery:
    // after reconnecting, replay the session a few times so the finished turn shows up.
    private var droppedMidTurn = false
    private var resyncTicks = 0
    private fun turnResyncTick() {
        if (resyncTicks <= 0) return
        resyncTicks--
        // The user started something new (or left) — their action wins; stop quietly.
        if (busy.value || turnInFlight || !appForeground) return
        lastSessionId?.let { open(resume = it) }
        if (resyncTicks > 0) main.postDelayed(::turnResyncTick, 8_000)
    }

    /** Refresh sessions AND the projects that label them. Kept as one call so the two can never
     *  drift -- a session list newer than the project list renders groups labelled by raw id. */
    fun listSessions() { client?.listSessions(); client?.listProjects() }

    /** Archive a session: history stays on disk, it just leaves the list. The soft option --
     *  deleteSession is the permanent one (goose ≥1.44; the old "no delete" note is obsolete). */
    fun archiveSession(sessionId: String) {
        client?.archiveSession(sessionId)
        sessions.value = sessions.value.filterNot { it.sessionId == sessionId }   // optimistic
        if (sessionId == store.assistantSessionId) store.assistantSessionId = null
    }

    /** Publish this device's UnifiedPush endpoint into goose's config.yaml (server-side),
     *  where deliver.sh prefers it over the static .env value -- endpoint rotation then
     *  self-heals instead of silently killing pushes. Best-effort. */
    fun publishPushEndpoint(url: String) {
        if (url.isNotBlank()) client?.upsertConfig("GROUSE_PUSH_ENDPOINT", url)
    }

    /** Answer a pending elicitation form and drop it from the queue. */
    fun answerElicitation(e: AcpEvent.Elicitation, values: Map<String, JsonPrimitive>?, cancelled: Boolean = false) {
        client?.respondElicitation(e.requestKey, values, cancelled)
        elicitations.remove(e)
    }

    /** Delete a session outright (history gone server-side). Archive remains the soft option. */
    fun deleteSession(sessionId: String) {
        client?.deleteSession(sessionId)
        sessions.value = sessions.value.filterNot { it.sessionId == sessionId }   // optimistic
        if (sessionId == store.assistantSessionId) store.assistantSessionId = null
    }

    /** Move a chat into a project (or back to /state): the sanctioned working_dir rewrite.
     *  Also the in-app repair for sessions stranded by a renamed project directory. */
    fun moveSession(sessionId: String, cwd: String) {
        client?.updateWorkingDir(sessionId, cwd)
        sessions.value = sessions.value.map {          // optimistic
            if (it.sessionId == sessionId) it.copy(cwd = cwd) else it
        }
        store.rememberSessionCwds(listOf(sessionId to cwd))
    }

    /** Set a session's title (goose _goose/unstable/session/rename; the reply re-lists). */
    fun renameSession(sessionId: String, title: String) {
        val t = title.trim()
        if (t.isEmpty()) return
        client?.renameSession(sessionId, t)
        sessions.value = sessions.value.map {          // optimistic
            if (it.sessionId == sessionId) it.copy(title = t) else it
        }
    }

    fun openSession(sessionId: String, knownKind: SessionKind? = null) {
        // Cancel any deferred "open the assistant thread" -- the user has since picked a specific
        // session and that choice wins. Without this, a pendingOpenAssistant set while offline (its
        // listSessions() is a no-op with no client) survives until the NEXT Sessions event, which
        // arrives from the listSessions() in this very open()'s Ready handler -- and then reopens
        // the assistant on top of the session just chosen. The chat visibly switches and the next
        // message lands in the assistant thread.
        pendingOpenAssistant = false
        messages.clear(); lastSessionId = sessionId; currentSession.value = sessionId
        // A caller resuming a session it already has cached (e.g. connectHome's assistant shortcut)
        // can pass knownKind to skip the sessions.value lookup, which may not be populated yet on a
        // cold start; open() falls back to that lookup (then CHAT) when knownKind is null.
        val kind = knownKind ?: sessions.value.firstOrNull { it.sessionId == sessionId }
            ?.let { ConnectionManager.sessionKind(it) }
        open(resume = sessionId, kind = kind)
    }

    fun newSession(cwd: String = DEFAULT_CWD, kind: SessionKind = SessionKind.CHAT) {
        pendingOpenAssistant = false      // same as openSession: an explicit choice cancels it
        messages.clear(); lastSessionId = null; currentSession.value = null; config.value = emptyList()
        open(resume = null, cwd = cwd, kind = kind)
    }

    /** A project session: scoped to a directory under the server's /projects bind mount
     *  (the user's ~/dev) instead of the default /state. This IS the "designation" -- goose has no
     *  tags/labels, so cwd is the native, protocol-level signal sessionKind() reads back later. */
    fun newCodeSession(project: String) {
        // Forgive "/workspace/foo" and "workspace/foo" -- typing the full path used to build
        // /workspace/workspace/foo, whose session/new rejection looked like a silent no-op.
        val clean = project.trim().trim('/').removePrefix("projects/")
            .removePrefix("Projects/").removePrefix("workspace/").trim('/')
        require(clean.isNotEmpty() && !clean.contains("..")) { "invalid project name" }
        store.addRecentWorkspaceProject(clean)
        recentProjects.value = store.recentWorkspaceProjects()
        // PROJECT_ROOT, not "/projects/", so a project opened here and the same project opened
        // from Goose Desktop produce the SAME cwd string. Desktop groups its project list by
        // exact cwd, so two spellings of one directory render as two projects with identical
        // names. All three spellings are the same host dir; only the string matters.
        newSession(cwd = "$PROJECT_ROOT$clean", kind = SessionKind.CODE)
    }

    /** Run one shell command server-side via a DIRECT tool call and report (error, output).
     *
     *  No model is involved: a throwaway AcpClient opens a session at /state purely to get a
     *  sessionId, then invokes developer__shell through goose's _goose/unstable/tools/call --
     *  deterministic, exact output, near-instant. Because the session never receives a prompt
     *  it has ZERO messages, and session/list filters message-less sessions, so it never
     *  appears anywhere -- no archiving dance needed. (The earlier version prompted the fast
     *  model to run commands; it was slow, paraphrased output, and its session flashed into
     *  the list until archived.) */
    private fun runUtilityTool(command: String, timeoutMs: Long = 30_000, onDone: (String?, String) -> Unit) {
        val url = "wss://${store.host}:${store.port}/acp"
        var boot: AcpClient? = null
        var finished = false
        lateinit var watchdog: Runnable
        fun finish(err: String?, out: String) {
            if (finished) return
            finished = true
            main.removeCallbacks(watchdog)
            val b = boot
            main.postDelayed({ b?.close() }, 500)
            boot = null
            onDone(err, out)
        }
        watchdog = Runnable { finish("Timed out talking to the server.", "") }
        main.postDelayed(watchdog, timeoutMs)
        boot = AcpClient(url, store.secretKey) { ev ->
            main.post {
                if (finished) return@post
                when (ev) {
                    is AcpEvent.Ready -> {
                        // Builtin developer tools are UNPREFIXED ("shell", not developer__shell —
                        // matches permission.yaml's bare names). Small delay: extensions attach
                        // async after session/new.
                        val sid = ev.sessionId
                        main.postDelayed({ if (!finished) boot?.callTool(sid, "shell",
                            kotlinx.serialization.json.buildJsonObject {
                                put("command", kotlinx.serialization.json.JsonPrimitive(command))
                            }) }, 800)
                    }
                    is AcpEvent.DirectToolResult ->
                        if (ev.isError) finish(ev.text.ifBlank { "tool call failed" }, ev.text)
                        else finish(null, ev.text)
                    // No UI here — never let a form request hang the utility call.
                    is AcpEvent.Elicitation -> boot?.respondElicitation(ev.requestKey, null, cancelled = true)
                    is AcpEvent.Error -> finish(ev.text, "")
                    else -> {}
                }
            }
        }.also {
            it.desiredCwd = DEFAULT_CWD
            it.connect()
        }
    }

    private fun cleanProjectName(raw: String): String? {
        val name = raw.trim().trim('/').removePrefix("projects/")
            .removePrefix("workspace/").trim('/')
        if (name.isEmpty() || name.contains("..") || name.contains('/') ||
            name.any { it.isWhitespace() } || name.contains('\'') || name.contains('"')) return null
        return name
    }

    /** Create /projects/<name> on the server (null = success, else error). Deterministic
     *  direct mkdir -- no model. */
    fun createProject(rawName: String, onResult: (String?) -> Unit) {
        val name = cleanProjectName(rawName)
            ?: run { onResult("Single folder name — no slashes, spaces, or quotes."); return }
        runUtilityTool("mkdir -p '/projects/$name'") { err, _ -> onResult(err) }
    }

    /** Read a project's .goosehints and local memory (goose's memory extension stores its
     *  local scope at <cwd>/.goose/memory). Direct shell call -- exact file contents. */
    fun fetchProjectInfo(project: String, onResult: (String?, String) -> Unit) {
        val name = cleanProjectName(project) ?: run { onResult("bad project name", ""); return }
        runUtilityTool(
            "echo '=== .goosehints ==='; cat '/workspace/$name/.goosehints' 2>/dev/null || echo '(none)'; " +
                "echo; echo '=== .goose/memory ==='; " +
                "for f in '/workspace/$name/.goose/memory'/*; do [ -f \"\$f\" ] || continue; " +
                "echo \"-- \$(basename \"\$f\")\"; cat \"\$f\"; done 2>/dev/null; " +
                "[ -d '/workspace/$name/.goose/memory' ] || echo '(none)'"
        ) { err, text -> onResult(err, text) }
    }

    /** Delete a project: archive its chats, drop it from recents, and remove the server
     *  directory ONLY if empty (rmdir, never rm -rf -- a non-empty project keeps its files
     *  and merely disappears from the list). Reports a human-readable outcome note. */
    fun deleteProject(project: String, onResult: (String) -> Unit) {
        val name = cleanProjectName(project) ?: run { onResult("bad project name"); return }
        sessions.value.filter { ConnectionManager.projectOf(it.cwd) == name }
            .forEach { archiveSession(it.sessionId) }
        store.removeRecentWorkspaceProject(name)
        recentProjects.value = store.recentWorkspaceProjects()
        runUtilityTool("rmdir '/workspace/$name'") { err, out ->
            onResult(when {
                err == null -> "Project removed."
                (err + out).contains("No such file", ignoreCase = true) ->
                    "Project removed from the list (the directory was already gone)."
                else -> "Chats archived. The directory has files in it, so it was left in place."
            })
        }
    }

    /** The persistent "goose-assistant" thread (briefings/proactive/voice land here), if it exists. */
    /** The assistant thread's id.
     *
     *  The CACHED id wins over the title lookup, not the other way round. Two reasons, both
     *  measured: session/list omits sessions with no content, so a freshly reset thread is
     *  invisible to a title search; and resets had left FOUR sessions titled "goose-assistant" at
     *  once (two of them empty), so a title search is ambiguous as well as blind. The cached id is
     *  the one this app actually created and renamed, so it is the authoritative answer; the title
     *  lookup is the fallback for a fresh install that has no cache yet. */
    fun assistantSessionId(): String? =
        store.assistantSessionId
            ?: sessions.value.firstOrNull { it.title == ASSISTANT_TITLE }?.sessionId

    /** True when the on-screen conversation IS the privileged assistant thread. */
    val onAssistant: Boolean get() = currentSession.value != null && currentSession.value == assistantSessionId()

    @Volatile private var pendingOpenAssistant = false

    /** Open the privileged assistant thread; if the session list isn't loaded yet, refresh it and
     *  open as soon as it arrives (see the Sessions event handler). */
    fun openAssistant() {
        val id = assistantSessionId()
        if (id != null) { openSession(id, knownKind = SessionKind.ASSISTANT); return }
        // No id yet: defer until a session list arrives -- but only if one can actually arrive.
        // With no client, listSessions() does nothing and the flag would sit armed indefinitely.
        if (client != null) { pendingOpenAssistant = true; listSessions() }
        else beginAssistantThread(null)
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
    /** Generic mirror of config/read replies, keyed by config key — the phone-writable
     *  server settings channel (schedule times, etc.). */
    val serverConfig = androidx.compose.runtime.mutableStateMapOf<String, String>()

    /** The Assistant thread's own enabled-extension names (session-scoped; the daily
     *  rotation copies extension_data forward, so edits here persist across days). */
    val assistantExtNames = mutableStateOf<List<String>>(emptyList())
    fun loadAssistantExtensions() {
        loadExtensions()
        assistantSessionId()?.let { client?.listSessionExtensionsFor(it) }
    }
    fun toggleAssistantExtension(ext: ExtInfo, on: Boolean) {
        val sid = assistantSessionId() ?: return
        if (on) client?.addSessionExtensionFor(sid, ext.raw)
        else client?.removeSessionExtensionFor(sid, ext.name)
        assistantExtNames.value =
            if (on) (assistantExtNames.value + ext.name).distinct()
            else assistantExtNames.value - ext.name
    }

    /** The Assistant thread's ACTIVE tools, grouped ext -> tool names (targeted tools/list). */
    val assistantTools = mutableStateOf<Map<String, List<String>>>(emptyMap())
    private var discoveringAssistant: String? = null
    fun refreshAssistantTools() { assistantSessionId()?.let { client?.listToolsFor(it) } }

    /** Mirror of discoverTools for the ASSISTANT session: briefly re-adds the extension
     *  unfiltered so the full catalogue becomes observable, then the UI's Save re-applies. */
    fun discoverAssistantTools(ext: ExtInfo) {
        val sid = assistantSessionId() ?: return
        if (toolCatalog.value.containsKey(ext.name)) { refreshAssistantTools(); return }
        val c = client ?: return
        val unfiltered = JsonObject(ext.raw.toMutableMap().apply {
            put("available_tools", JsonArray(emptyList()))
        })
        discoveringAssistant = ext.name
        c.removeSessionExtensionFor(sid, ext.name)
        c.addSessionExtensionFor(sid, unfiltered)
        main.postDelayed({ client?.listToolsFor(sid) }, 1_200)
    }

    /** Restrict `ext` to `allowed` in the ASSISTANT thread (rotation carries it forward). */
    fun setAssistantTools(ext: ExtInfo, allowed: Set<String>) {
        val sid = assistantSessionId() ?: return
        val c = client ?: return
        val full = toolCatalog.value[ext.name].orEmpty()
        val list = if (allowed.size >= full.size && full.isNotEmpty()) emptyList() else allowed.toList()
        val scoped = JsonObject(ext.raw.toMutableMap().apply {
            put("available_tools", JsonArray(list.map { JsonPrimitive(it) }))
        })
        discoveringAssistant = null
        c.removeSessionExtensionFor(sid, ext.name)
        c.addSessionExtensionFor(sid, scoped)
        main.postDelayed({ refreshAssistantTools() }, 1_200)
    }

    /** Observable master switch (mirrors SecureStore.assistantEnabled for the drawer). */
    val assistantEnabled = mutableStateOf(store.assistantEnabled)
    fun setAssistantEnabled(on: Boolean) {
        store.assistantEnabled = on
        assistantEnabled.value = on
        writeServerConfig("ASSISTANT_ENABLED", if (on) "true" else "false")
    }

    /** Trigger a server-side test run of a delivery pipeline ("morning" | "briefing"):
     *  a direct tool call touches a /state drop file that a host systemd path unit watches;
     *  the unit runs deliver.sh with TEST_RUN=1 (all gates and stamps bypassed, briefings
     *  forced to produce a visible push). */
    fun testAssistantJob(kind: String, onResult: (String?) -> Unit) {
        runUtilityTool("touch /state/.assistant-test-$kind") { err, _ -> onResult(err) }
    }

    /** Read + write server-side goose config (the deliver.sh schedule keys live there). */
    fun readServerConfig(vararg keys: String) { keys.forEach { client?.readConfig(it) } }
    fun writeServerConfig(key: String, value: String) {
        client?.upsertConfig(key, value)
        serverConfig[key] = value   // optimistic
    }

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
            client?.sendPrompt(text, images, expect = currentSession.value)
        } else if (live) {
            // A turn is already running. Queue rather than firing a second sendPrompt into the
            // same session -- concurrent prompts interleave in the transcript and the second
            // reply is attributed to the wrong question. Flushed on TurnDone.
            enqueue(PendingSend(text, images))
        } else {
            // Not connected yet (initial connect / silent reconnect window): queue and connect,
            // rather than calling sendPrompt against a session-less client (which just errors and
            // loses the message). Flushed in the Ready branch. The resume's replay wipes the local
            // transcript (including this just-added bubble); Ready re-adds the queued bubbles on
            // top of the rebuilt history -- see the ReplayStart/Ready handlers.
            enqueue(PendingSend(text, images))
            if (!connecting) open(resume = lastSessionId ?: store.lastSessionId)
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
            // Even with the socket still alive, this session may have moved on without us: goosed
            // only streams a turn to the connection that prompted it, so anything another client
            // (Desktop, deliver.sh) added while we were backgrounded is invisible until a
            // session/load replay. Reopen the current session to resync -- idle-only, so a turn
            // this app is actually streaming is never yanked. ensureConnected() above covers the
            // dropped-socket case (its resume now always replays too).
            if (live && !busy.value && !turnInFlight) {
                (lastSessionId ?: store.lastSessionId)?.let { open(resume = it) }
            }
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
     *  dropped, and the fresh client comes back idle. The reconnect's replay rebuilds the
     *  transcript to whatever the server persisted of the cancelled turn. */
    fun cancel() {
        client?.cancel()
        streamingRole = null
        busy.value = false
        turnInFlight = false   // wire is free again; without this the queue never drains
        clearQueue()           // Stop means stop: don't let queued prompts fire after a cancel
        if (store.hasKey()) open(resume = lastSessionId ?: store.lastSessionId)
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

    private fun open(resume: String?, cwd: String? = null, kind: SessionKind? = null) {
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
        replayWiped = false
        replayActive.value = false
        val url = "wss://${store.host}:${store.port}/acp"
        status.value = if (resume == null) "connecting to $url" else "loading session…"
        val saved = store.savedOptions(optionIds)
        // Resolve the cwd for this open(): an explicit param wins (new session creation always
        // knows its own target); otherwise, for a resume, prefer the cached SessionInfo's cwd
        // (sessions.value, if already loaded) and fall back to the last-persisted cwd for a cold
        // start before any session/list round-trip has happened. session/load's cwd param SILENTLY
        // REWRITES the session's working_dir if wrong, so this must be right, not just "close enough".
        // There used to be an "assistant hard rule" here pinning that thread to /state BY
        // CONSTRUCTION. It stopped being true on 2026-07-30, when conversational sessions moved
        // under /home/colin/Projects/ so Goose Desktop would group them as projects -- and
        // because session/load REWRITES working_dir, this line did not merely guess wrong, it
        // actively dragged the Assistant back to /state within seconds of every correction,
        // including edits made directly in the sessions DB. Ask the server instead; after
        // DEFAULT_CWD was fixed this was the ONE remaining hardcoded /state, and it silently
        // undid that fix.
        //
        // Resolution order for a resume: live cache -> the per-session cwd map -> ASK THE SERVER
        // (null: the client queries _goose/unstable/session/info before session/load). NEVER a
        // guess: session/load rewrites working_dir when handed the wrong cwd, and a global
        // last-used guess re-homed the assistant thread into a project once.
        val resolvedCwd: String? = cwd ?: if (resume == null) DEFAULT_CWD else
            sessions.value.firstOrNull { it.sessionId == resume }?.cwd?.takeIf { it.isNotBlank() }
                ?: store.sessionCwd(resume)
        pendingOpenCwd = resolvedCwd ?: ""
        // Tag this client's events with a generation; a just-closed client still fires
        // onClosed/onFailure asynchronously and its stale "disconnected" must not flip us offline
        // after the new client is already live.
        val gen = ++clientGen
        client = AcpClient(url, store.secretKey) { ev -> main.post { if (gen == clientGen) onEvent(ev) } }.also {
            it.desiredOptions = if (resume == null) saved else emptyMap()
            it.resumeSessionId = resume
            it.resumeCwd = resolvedCwd ?: DEFAULT_CWD
            it.resumeCwdKnown = resolvedCwd != null
            it.desiredCwd = resolvedCwd ?: DEFAULT_CWD
            it.connect()
        }
    }

    private fun onEvent(ev: AcpEvent) {
        when (ev) {
            // Direct tool replies only occur on utility clients, which have their own handler.
            is AcpEvent.DirectToolResult -> {}
            is AcpEvent.Elicitation -> elicitations.add(ev)
            is AcpEvent.Status -> {
                status.value = ev.text
                if (ev.text == "disconnected") {
                    if (turnInFlight) droppedMidTurn = true   // see turnResyncTick
                    live = false; connecting = false; online.value = false
                }
            }
            is AcpEvent.Error -> {
                messages.add(ChatMessage("error", ev.text)); streamingRole = null; busy.value = false; turnInFlight = false
                compacting.value = false   // safety net: a dropped/garbled status must never stick
                if (voiceReplyPending) { voiceReplyPending = false; restoreModel() }
            }
            is AcpEvent.ToolCall -> {
                t().add(ChatMessage("tool", ev.title, detail = ev.detail,
                    toolCallId = ev.toolCallId, status = "in_progress"))
                streamingRole = null
            }
            is AcpEvent.ToolCallUpdate -> {
                if (ev.toolCallId.isNotBlank()) {
                    val i = t().indexOfLast { it.role == "tool" && it.toolCallId == ev.toolCallId }
                    if (i >= 0) t()[i] = t()[i].copy(
                        status = ev.status.ifBlank { t()[i].status },
                        output = if (ev.output.isNotBlank()) ev.output else t()[i].output,
                    )
                }
            }
            is AcpEvent.SessionInfoChanged -> {
                // Live title/updatedAt sync (auto-naming after the first turn, renames from any
                // client) — previously only visible after a full session re-list.
                sessions.value = sessions.value.map {
                    if (it.sessionId == ev.sessionId) it.copy(
                        title = ev.title ?: it.title,
                        updatedAt = ev.updatedAt ?: it.updatedAt,
                    ) else it
                }
            }
            is AcpEvent.ModeChanged -> {
                config.value = config.value.map {
                    if (it.id == "mode") it.copy(currentValue = ev.modeId) else it
                }
            }
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
            is AcpEvent.Chart -> { t().add(ChatMessage("chart", ev.spec)); streamingRole = null }
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
                    say(lastAssistantText())
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
                    client?.sendPrompt(queued.text, queued.images, expect = currentSession.value)
                } else if (!store.persistentConnection) stopService()
            }
            is AcpEvent.ReplayStart -> {
                // A session/load replay is about to stream: the server transcript is ground truth
                // (it may hold turns Desktop or deliver.sh added while this app wasn't looking),
                // so rebuild from scratch — into the side buffer; `messages` stays visible and
                // untouched until Ready decides whether anything actually changed.
                replayBuffer.clear()
                streamingRole = null
                replayWiped = true
                replayActive.value = true
            }
            is AcpEvent.AgentChunk -> {
                // Replay boundary: a NEW messageId means a new source message — break the
                // bubble instead of gluing (consecutive assistant messages, e.g. a briefing
                // relay followed by an appended digest, used to merge into one).
                if (ev.messageId != null && ev.messageId != streamMsgId) streamingRole = null
                if (ev.messageId != null) streamMsgId = ev.messageId
                appendStream("assistant", ev.text)
            }
            is AcpEvent.ThoughtChunk -> appendStream("thought", ev.text)
            is AcpEvent.UserChunk -> { t().add(ChatMessage("user", ev.text)); streamingRole = null }
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
                // Ready itself carries no cwd -- record what this open resolved (see open()).
                // Blank = the server was asked via session/info; the next session/list merge
                // records the authoritative value instead.
                if (pendingOpenCwd.isNotBlank())
                    store.rememberSessionCwds(listOf(ev.sessionId to pendingOpenCwd))
                if (droppedMidTurn) {
                    // The turn we lost is still finishing server-side; poll it back into view.
                    droppedMidTurn = false
                    resyncTicks = 3
                    main.postDelayed(::turnResyncTick, 8_000)
                }
                currentSession.value = ev.sessionId
                // Populate the in-chat "N tools" indicator and the per-extension tool lists.
                // MCP-backed extensions come up asynchronously AFTER the session is ready: a
                // tools/list fired here returns only the builtins (measured -- nextcloud, beeper,
                // kagi and memory were all absent from a list taken immediately). Ask again shortly
                // for the full picture rather than caching a half-built one.
                client?.listTools()
                val genAtReady = clientGen
                main.postDelayed({ if (genAtReady == clientGen) client?.listTools() }, 2500)
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
                if (replayActive.value) {
                    replayActive.value = false
                    // Swap the rebuilt transcript in only when it differs from what's shown.
                    // The common background-reconnect replay is byte-identical, and leaving
                    // `messages` untouched preserves the reading position by construction.
                    // Compare content fields, not ChatMessage itself: `id` is per-instance.
                    val same = replayBuffer.size == messages.size && replayBuffer.indices.all { i ->
                        val a = replayBuffer[i]; val b = messages[i]
                        a.role == b.role && a.text == b.text && a.detail == b.detail &&
                            a.status == b.status && a.output == b.output &&
                            a.images.size == b.images.size
                    }
                    if (!same) {
                        messages.clear(); messages.addAll(replayBuffer)
                        replayDoneTick.value++   // ChatScreen: new content -> snap to bottom
                    }
                    replayBuffer.clear()
                }
                // Prompts still waiting in the queue aren't in the server history (they haven't
                // been sent). Re-add their bubbles on top, in queue order, so the user's unsent
                // messages don't vanish from the screen. After the swap: a queued bubble was in
                // the shown list but never in the buffer, so `same` is false and the swap
                // dropped it.
                if (replayWiped) {
                    replayWiped = false
                    pendingSends.forEach { messages.add(ChatMessage("user", it.text, it.images)) }
                }
                // Send ONE queued prompt (bubbles were already added when queued); TurnDone drains
                // the rest. This used to `while`-loop the whole deque, firing every queued prompt
                // into the session at once -- which interleaves them in the transcript and
                // misattributes each reply, the exact failure the queue exists to prevent. It also
                // left turnInFlight false, so the next send() would fire a concurrent prompt too.
                dequeue()?.let { p ->
                    store.pendingPushSessionId = ev.sessionId
                    turnInFlight = true
                    busy.value = true
                    client?.sendPrompt(p.text, p.images, expect = currentSession.value)
                }
            }
            is AcpEvent.Projects -> projects.value = ev.list
            is AcpEvent.Sessions -> {
                sessions.value = ev.list
                store.rememberSessionCwds(ev.list.map { it.sessionId to it.cwd })
                // Validate the cached assistant id against the list. The cache wins over a title
                // lookup on open (an empty, freshly-reset thread is invisible to session/list, so
                // absence alone proves nothing) -- but if the server SHOWS the cached session under
                // a different title, it was renamed aside (archived / fork cleanup) and the cache
                // is definitively stale. This is exactly how the 2026-07-26 fork presented: the
                // phone kept opening its cached acp-type thread while deliver.sh and Desktop used
                // a same-named user-type one it couldn't see. Repoint to the newest session
                // actually bearing the title, and if the stale thread is on screen AS the
                // assistant, hop to the right one.
                val cached = store.assistantSessionId
                val cachedEntry = cached?.let { c -> ev.list.firstOrNull { it.sessionId == c } }
                if (cachedEntry != null && cachedEntry.title != ASSISTANT_TITLE) {
                    val fresh = ev.list.filter { it.title == ASSISTANT_TITLE }
                        .maxByOrNull { it.updatedAt }?.sessionId
                    store.assistantSessionId = fresh   // null just clears -> reseed below / recreate
                    if (fresh != null && currentSession.value == cached)
                        openSession(fresh, knownKind = SessionKind.ASSISTANT)
                }
                // Only seed the cache when empty. It used to be written on every session list,
                // which let an OLD session sharing the title clobber the id of the thread this app
                // had just created. Newest title match wins, in case stale duplicates linger.
                if (store.assistantSessionId == null)
                    sessions.value.filter { it.title == ASSISTANT_TITLE }
                        .maxByOrNull { it.updatedAt }?.let { store.assistantSessionId = it.sessionId }
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
                serverConfig[ev.key] = ev.value   // generic mirror for settings UIs
            }
            is AcpEvent.Commands -> commands.value = ev.names
            is AcpEvent.Extensions -> { extensions.value = ev.list; extensionsBusy.value = false }
            is AcpEvent.SessionExtensions -> {
                if (ev.sessionId == store.assistantSessionId) assistantExtNames.value = ev.names
                if (ev.sessionId == currentSession.value || ev.sessionId != store.assistantSessionId)
                    sessionExtensionNames.value = ev.names
            }
            is AcpEvent.Tools -> {
                val g = group(ev.names)
                // Targeted reply for the Assistant thread: its own state bucket + its own
                // discovery flow; never touches the on-screen session's sessionTools.
                if (ev.sessionId != null && ev.sessionId == store.assistantSessionId) {
                    val dt = discoveringAssistant
                    if (dt != null && g.containsKey(dt)) {
                        toolCatalog.value = toolCatalog.value + (dt to g[dt].orEmpty())
                        // Leave the unfiltered set active until the user Saves (mirrors the
                        // sheet's explore-then-save semantics).
                    }
                    assistantTools.value = g
                    return
                }
                val target = discovering
                if (target != null) {
                    // Catalogue read: record the full set, then restore the session's real setting.
                    toolCatalog.value = toolCatalog.value + (target to g[target].orEmpty())
                    discovering = null
                    extensions.value.firstOrNull { it.name == target }?.let { e ->
                        val allowed = (e.raw["available_tools"] as? JsonArray)
                            ?.mapNotNull { it.jsonPrimitive.contentOrNull }?.toSet().orEmpty()
                        setSessionTools(e, if (allowed.isEmpty()) g[target].orEmpty().toSet() else allowed)
                    }
                } else sessionTools.value = g
            }
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
        val list = t()
        val last = list.lastOrNull()
        if (streamingRole == role && last != null && last.role == role) {
            list[list.lastIndex] = last.copy(text = last.text + chunk)
        } else {
            streamingRole = role
            list.add(ChatMessage(role, chunk))
        }
    }

    companion object {
        /** Server-side name of the persistent assistant thread (see docker/llm/goose-recipes).
         *  Renamed from "goose-assistant" 2026-07-28 -- coordinated with sessions.db and
         *  deliver.sh's SESSION_NAME, since resolution on all sides is an exact title match. */
        const val ASSISTANT_TITLE = "Assistant"
        /** goose has no session tags/labels -- cwd is the native signal. A project session is one
         *  scoped to a project directory; everything else on the default /state is Chat, unless
         *  its title marks it as the privileged Assistant thread. */
        fun sessionKind(s: SessionInfo): SessionKind = when {
            s.title == ASSISTANT_TITLE -> SessionKind.ASSISTANT
            projectOf(s.cwd) != null -> SessionKind.CODE
            else -> SessionKind.CHAT
        }

        /** All server paths that mean "project <name>". /workspace is the canonical spelling
         *  (Grouse-created sessions); the other two are the SAME host directory (~/dev) reached
         *  through the Desktop cwd-shims -- goose stores cwd verbatim as each client sent it
         *  (no canonicalize on session/new), so the spellings coexist and must be unified here. */
        /** Where WE create projects. Matches what Goose Desktop's directory picker produces on
         *  Linux, because Desktop's project list is a grouping of the raw cwd string and two
         *  spellings of one directory show up as two identically-named projects. Desktop on a
         *  Mac still yields /Users/colin/Projects/<name>; no single path is native to both, so
         *  that one duplicate is accepted (see goose.container). */
        const val PROJECT_ROOT = "/home/colin/Projects/"

        // Order matters only for readability; each is a distinct spelling of the same host dir
        // (~/services/goose-projects, mounted at /projects and under both homedir shims).
        // PROJECT_ROOT is canonical for new sessions; /projects/ is what Grouse itself used
        // until 2026-07-30 and /workspace/ before 2026-07-28, when projects lived alongside
        // source code. The dev-shim paths are what an older Desktop picker produced. All kept
        // so existing sessions keep grouping instead of dropping into the free-chat list.
        private val PROJECT_PREFIXES = listOf(
            PROJECT_ROOT, "/Users/colin/Projects/", "/projects/", "/workspace/",
            "/Users/colin/dev/", "/home/colin/dev/")

        /** The project name a session cwd belongs to, or null for non-project paths. */
        fun projectOf(cwd: String): String? = PROJECT_PREFIXES.firstNotNullOfOrNull { p ->
            if (cwd.startsWith(p)) cwd.removePrefix(p).trim('/').substringBefore('/')
                .takeIf { it.isNotEmpty() } else null
        }
        @Volatile private var instance: ConnectionManager? = null
        fun get(context: Context): ConnectionManager =
            instance ?: synchronized(this) {
                instance ?: ConnectionManager(context.applicationContext).also { instance = it }
            }
    }
}
