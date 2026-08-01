package id.gauvin.grouse

import android.content.Context
import android.net.Uri
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.snapshotFlow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import android.widget.Toast
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavController
import com.halilibo.richtext.markdown.Markdown
import com.halilibo.richtext.ui.material3.RichText
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Display names for goose's four approval modes.
 *
 *  The server sends snake_case ids (auto, approve, smart_approve, chat) and, for some builds, no
 *  label at all -- de-snaking gives "smart approve", which reads like a verb. These are the same
 *  four modes goose documents in GooseMode, named for what they DO to you rather than what they
 *  do to the tool call. Unknown ids fall through de-snaked rather than being hidden, so a new
 *  upstream mode still appears and still works.
 */
private fun prettyMode(value: String?): String = when (value) {
    "auto" -> "Auto"
    "approve" -> "Ask always"
    "smart_approve" -> "Ask when risky"
    "chat" -> "Chat only"
    null, "" -> "Mode"
    else -> value.replace('_', ' ').replaceFirstChar { it.uppercase() }
}

/** One-line explanation under each mode in the picker, from GooseMode's own descriptions. */
private fun modeBlurb(value: String): String = when (value) {
    "auto" -> "Runs tools without asking"
    "approve" -> "Asks before every tool call"
    "smart_approve" -> "Asks only for sensitive tool calls"
    "chat" -> "No tools at all"
    else -> ""
}

private val CONFIG_IDS = listOf("provider", "model", "mode", "thinking_effort")

/** Run on the main looper. ServerSpeech's callbacks fire on its own worker threads; Compose
 *  snapshot state tolerates that, but UI state changes are clearer (and safer for anything that
 *  later touches a View) marshalled back. */
private fun mainThread(block: () -> Unit) =
    android.os.Handler(android.os.Looper.getMainLooper()).post(block)

// ---- Connect (onboarding) ---------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectScreen(cm: ConnectionManager, onConnected: () -> Unit) {
    var host by remember { mutableStateOf(cm.store.host) }
    var port by remember { mutableStateOf(cm.store.port) }
    var key by remember { mutableStateOf("") }
    var showKey by remember { mutableStateOf(false) }
    Scaffold(topBar = { TopAppBar(title = { Text("Connect to Grouse") }) }) { pad ->
        Column(
            Modifier.padding(pad).padding(24.dp).fillMaxWidth().verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(16.dp))
            Icon(Icons.Filled.Psychology, contentDescription = null,
                modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(10.dp))
            Text("Welcome to Grouse", style = MaterialTheme.typography.headlineSmall)
            Text("Connect to your self-hosted goose server over the tailnet.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.outline,
                textAlign = TextAlign.Center, modifier = Modifier.padding(top = 4.dp))
            Spacer(Modifier.height(28.dp))
            OutlinedTextField(host, { host = it }, label = { Text("Host") },
                supportingText = { Text("e.g. 192.168.1.5 or a Tailscale name") },
                singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(port, { port = it }, label = { Text("Port") }, singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
            OutlinedTextField(key, { key = it }, label = { Text("Secret key") }, singleLine = true,
                visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    TextButton(onClick = { showKey = !showKey }) { Text(if (showKey) "Hide" else "Show") }
                },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
            Spacer(Modifier.height(20.dp))
            Button(
                onClick = { cm.connect(host.trim(), port.trim(), key.trim()); onConnected() },
                enabled = key.isNotBlank() && host.isNotBlank(), modifier = Modifier.fillMaxWidth()
            ) { Text("Connect") }
        }
    }
}

// ---- Chat -------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(cm: ConnectionManager, onOpenDrawer: () -> Unit) {
    val ctx = LocalContext.current
    var showConfig by remember { mutableStateOf(false) }
    var showSchedule by remember { mutableStateOf(false) }
    var showTools by remember { mutableStateOf(false) }
    // Assistant health from last-briefing recency (briefings run hourly 7 AM–10 PM). Green = fresh,
    // yellow = late, red = stale/never. Overnight the gap grows to ~9h and that's still healthy.
    val lastBriefing = cm.store.lastBriefingAt
    val briefingAgo = if (lastBriefing > 0)
        relativeTime(java.time.Instant.ofEpochMilli(lastBriefing).toString()) else "none yet"
    val briefingAgeMin = if (lastBriefing > 0) (System.currentTimeMillis() - lastBriefing) / 60000 else Long.MAX_VALUE
    val briefingActiveHrs = java.time.ZonedDateTime.now().hour in 7..22
    val briefingHealthy = lastBriefing > 0 &&
        (if (briefingActiveHrs) briefingAgeMin <= 90 else briefingAgeMin <= 12 * 60)
    val briefingLate = lastBriefing > 0 && !briefingHealthy &&
        (if (briefingActiveHrs) briefingAgeMin <= 180 else briefingAgeMin <= 15 * 60)
    var hintDismissed by remember { mutableStateOf(cm.store.assistantHintSeen) }
    // rememberSaveable so a rotation/dark-mode recreate doesn't wipe the typed draft.
    var input by rememberSaveable { mutableStateOf("") }
    // Hoisted to ConnectionManager so picked images survive recreation (see draftAttachments).
    val attachments = cm.draftAttachments
    val listState = rememberLazyListState()

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? -> uri?.let { readImage(ctx, it)?.let(attachments::add) } }

    // Voice: push-to-talk STT streaming into the draft, and TTS to read replies aloud.
    val voiceInput = remember { VoiceInput(ctx) }
    val speaker = remember { Speaker(ctx) }
    // Server-side (Whisper) recording in flight, when the LocalAI STT setting is on. Null while
    // idle, or always if the user is on Android's recognizer.
    var serverRec by remember { mutableStateOf<ServerSpeech.Recording?>(null) }
    val listening = voiceInput.listening || serverRec != null

    DisposableEffect(Unit) { onDispose { voiceInput.stop(); serverRec?.cancel(); speaker.shutdown() } }
    fun startListening() {
        val base = input
        if (cm.store.serverStt) {
            // Whisper has no streaming partials over this API -- the clip goes up when you stop.
            serverRec = ServerSpeech.record(ctx.cacheDir)
            return
        }
        voiceInput.start(
            onPartial = { input = (base.trim() + " " + it).trim() },
            onFinal = { input = (base.trim() + " " + it).trim() },
            onError = {},
        )
    }

    fun stopListening() {
        val rec = serverRec
        if (rec != null) {
            serverRec = null
            val base = input
            rec.stop(cm.store.localAiUrl, cm.store.sttModel,
                onText = { t -> mainThread { input = (base.trim() + " " + t).trim() } },
                onError = { e -> mainThread { cm.status.value = "STT: $e" } })
        } else voiceInput.stop()
    }
    val micPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startListening()
    }

    // Content shared into Goose from another app — append (don't clobber an in-progress draft).
    LaunchedEffect(cm.pendingShareText.value) {
        cm.pendingShareText.value?.let { input = (input.trim() + " " + it).trim(); cm.pendingShareText.value = null }
    }
    LaunchedEffect(cm.pendingShareImages.size) {
        if (cm.pendingShareImages.isNotEmpty()) {
            attachments.addAll(cm.pendingShareImages); cm.pendingShareImages.clear()
        }
    }

    val currentModel = cm.config.value.firstOrNull { it.id == "model" }?.currentValue ?: ""
    var showVisionWarn by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    // "At bottom" = the last item is visible; drives autoscroll + the jump-to-bottom button.
    // With reverseLayout the bottom is index 0; we're at the bottom when it's fully shown.
    val atBottom by remember {
        derivedStateOf {
            listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0
        }
    }

    fun reallySend() {
        cm.send(input.trim(), attachments.toList())
        input = ""; attachments.clear()
    }
    fun doSend() {
        if (input.isBlank() && attachments.isEmpty()) return
        // Sending images to a non-vision model (esp. LocalAI without mmproj) hangs the session.
        // The heuristic is a name-substring guess and gets it wrong (it missed Qwen3.6-35B-A3B,
        // which reads images fine); the user's own past answer for this exact model overrides it.
        if (attachments.isNotEmpty() && !isLikelyVisionModel(currentModel) &&
            !cm.store.visionOk(currentModel)) { showVisionWarn = true; return }
        reallySend()
    }

    // Stay pinned to the bottom (index 0) as messages stream in / arrive — unless the user scrolled up.
    // Driven off snapshotFlow so per-token text growth doesn't recompose the whole ChatScreen (this
    // used to read the last message's length in the composable body). Instant scrollToItem avoids
    // restarting a scroll animation on every token.
    LaunchedEffect(listState) {
        snapshotFlow { cm.messages.size to (cm.messages.lastOrNull()?.text?.length ?: 0) }
            .collect { if (atBottom) listState.scrollToItem(0) }
    }
    // Opening/switching a session: snap to the bottom (index 0). reverseLayout keeps it pinned
    // as history replays in.
    LaunchedEffect(cm.currentSession.value) { listState.scrollToItem(0) }
    // A replay swapped in a genuinely different transcript: go to the bottom. Identical
    // rebuilds never touch `messages`, so the reading position survives untouched and this
    // never fires for them.
    LaunchedEffect(cm.replayDoneTick.value) { listState.scrollToItem(0) }
    // Speak the reply aloud when a turn finishes (busy true→false), if enabled.
    var wasBusy by remember { mutableStateOf(false) }
    LaunchedEffect(cm.busy.value) {
        if (wasBusy && !cm.busy.value && cm.speakReplies.value) {
            // cm.say routes to LocalAI TTS or Android TTS per the setting (and falls back).
            cm.messages.lastOrNull { it.role == "assistant" }?.text?.let { cm.say(it) }
        }
        wasBusy = cm.busy.value
    }
    // Reconnect (resuming the session) when we return to the foreground.
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) cm.ensureConnected() }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }

    cm.elicitations.firstOrNull()?.let { e ->
        ElicitationSheet(e,
            onAccept = { values -> cm.answerElicitation(e, values) },
            onDecline = { cm.answerElicitation(e, null) },
            onCancel = { cm.answerElicitation(e, null, cancelled = true) })
    }
    cm.permissions.firstOrNull()?.let { req ->
        PermissionSheet(req, onChoose = { cm.answerPermission(req, it) })
    }

    if (showTools) {
        LaunchedEffect(Unit) { if (cm.extensions.value.isEmpty()) cm.loadExtensions() }
        ToolManagementSheet(cm, onDismiss = { showTools = false })
    }

    if (showVisionWarn) AlertDialog(
        onDismissRequest = { showVisionWarn = false },
        title = { Text("Model may not support images") },
        text = { Text("“$currentModel” probably can't read images and may get stuck on them — " +
            "even later text messages. If that happens, start a New chat.\n\n" +
            "Sending anyway won't ask again for this model.") },
        confirmButton = { TextButton(onClick = {
            showVisionWarn = false
            cm.store.markVisionOk(currentModel)   // remember: don't ask again for THIS model
            reallySend()
        }) { Text("Send anyway") } },
        dismissButton = { TextButton(onClick = { showVisionWarn = false }) { Text("Cancel") } },
    )

    if (showSchedule) AlertDialog(
        onDismissRequest = { showSchedule = false },
        confirmButton = { TextButton(onClick = { showSchedule = false }) { Text("Close") } },
        title = { Text("Assistant briefings") },
        text = {
            Column {
                Text("Runs hourly, 7 AM–10 PM.")
                Spacer(Modifier.height(6.dp))
                Text("Last briefing: $briefingAgo")
                Text("Status: " + when {
                    briefingHealthy -> "up to date"
                    briefingLate -> "running late"
                    lastBriefing <= 0L -> "no briefings yet"
                    else -> "not updating"
                })
                Spacer(Modifier.height(8.dp))
                Text("Schedule is managed on the server.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
            }
        },
    )

    Scaffold(topBar = {
        TopAppBar(
            title = {
                val online = cm.online.value
                val busy = cm.busy.value
                val dot = when {
                    online -> Color(0xFF3DDC84)                       // connected → green
                    cm.status.value.contains("connect", true) ||
                        cm.status.value.contains("load", true) -> Color(0xFFF5A623)  // connecting → amber
                    else -> MaterialTheme.colorScheme.error          // offline → red
                }
                val usage = cm.usage.value
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clickable(enabled = !online) { cm.connectSaved() }) {
                        Surface(color = dot, shape = RoundedCornerShape(50), modifier = Modifier.size(10.dp)) {}
                        Spacer(Modifier.width(8.dp))
                        // Single line + ellipsis: this Row shares the app bar with up to 3 action
                        // icons, so on a compact width the default (titleLarge, unbounded) title text
                        // used to wrap to a second line and visually collide with/get clipped by the
                        // icons (e.g. "Assistant · working…" broke across two lines mid-word). A
                        // tighter style plus a hard single-line cap keeps it readable and self-eliding
                        // instead of visually breaking.
                        Text(
                            when {
                                cm.onAssistant && busy -> "Assistant · working…"
                                cm.onAssistant -> "Assistant"
                                online && busy -> "Grouse · working…"
                                online -> "Grouse"
                                cm.status.value.contains("connect", true) ||
                                    cm.status.value.contains("load", true) -> "Connecting…"
                                else -> "Offline · tap to reconnect"
                            },
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    // Compacting (manual /compact or a server-triggered auto-compact) takes priority
                    // over the usage line — it's transient and explains why the numbers are about to
                    // change. INDETERMINATE: the protocol only ever sends text status lines, never a
                    // numeric percentage, so there's no real fraction to show.
                    if (cm.compacting.value) {
                        Row(verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(start = 18.dp, top = 2.dp)) {
                            LinearProgressIndicator(modifier = Modifier.width(40.dp).height(3.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Compacting…", style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline)
                        }
                    } else if (usage != null && usage.size > 0) {
                        // Context window used/size, so you can see how full the conversation is
                        // (goose compacts around the limit). Appears once the first turn reports usage.
                        val pct = (usage.used * 100 / usage.size).coerceIn(0, 100)
                        Text("${fmtTokens(usage.used)} / ${fmtTokens(usage.size)} · ${pct}%",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (pct >= 90) MaterialTheme.colorScheme.error
                                    else MaterialTheme.colorScheme.outline,
                            modifier = Modifier.padding(start = 18.dp))
                    }
                }
            },
            navigationIcon = {
                IconButton(onClick = onOpenDrawer) { Icon(Icons.Filled.Menu, contentDescription = "menu") }
            },
            actions = {
                // Assistant/Sessions/Settings are now drawer items — this icon's navigation role
                // moved there. On the Assistant thread itself it stays as the page-local briefing-
                // health indicator (opens the schedule dialog); off it, there's nothing to show here.
                if (cm.onAssistant) IconButton(onClick = { showSchedule = true }) {
                    val dot = when {
                        briefingHealthy -> Color(0xFF3DDC84)                     // fresh → green
                        briefingLate -> Color(0xFFF5A623)                        // late → amber
                        else -> MaterialTheme.colorScheme.error                  // stale/never → red
                    }
                    Box {
                        Icon(Icons.Filled.Psychology, contentDescription = "assistant status",
                            tint = MaterialTheme.colorScheme.primary)
                        Surface(color = dot, shape = RoundedCornerShape(50),
                            modifier = Modifier.size(9.dp).align(Alignment.TopEnd)) {}
                    }
                }
                // Live tool count for THIS session — tap to see/toggle which are actually on.
                AssistChip(
                    onClick = { showTools = true },
                    label = { Text("${cm.sessionExtensionNames.value.size}") },
                    leadingIcon = { Icon(Icons.Filled.Build, contentDescription = "tools",
                        modifier = Modifier.size(16.dp)) },
                    modifier = Modifier.padding(end = 4.dp),
                )
                IconButton(onClick = { showConfig = !showConfig }) {
                    Icon(Icons.Filled.Tune, contentDescription = "model")
                }
            }
        )
    }) { pad ->
        Column(Modifier.padding(pad).padding(horizontal = 12.dp).fillMaxSize()) {
            if (cm.onAssistant && !hintDismissed) AssistantHint {
                hintDismissed = true; cm.store.assistantHintSeen = true
            }
            // Model/mode picker opens from the Tune button in the top bar (no always-on bar).
            if (showConfig) ConfigPanel(cm.config.value, cm.showAllProviders.value,
                cm.configuredProviders, cm.knownModels.value, cm::setOption, cm::compact, cm.compacting.value)
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (cm.messages.isEmpty() && !cm.busy.value) {
                    Column(
                        Modifier.fillMaxSize().padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(Icons.Filled.Psychology, contentDescription = null,
                            modifier = Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.height(12.dp))
                        Text(if (cm.online.value) "Ask Grouse anything" else "Connecting…",
                            style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(4.dp))
                        Text("Calendar, notes, web search, and memory are wired up — tap the mic or type below.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline, textAlign = TextAlign.Center)
                    }
                } else {
                    // reverseLayout: index 0 renders at the BOTTOM. Typing indicator first (very
                    // bottom), then messages newest→oldest upward. Bottom is always index 0, so
                    // "open at bottom", "jump to bottom", and streaming-stays-pinned are trivial.
                    LazyColumn(state = listState, reverseLayout = true, modifier = Modifier.fillMaxSize()) {
                        if (cm.busy.value) item { TypingIndicator() }
                        // Consecutive tool calls collapse into one dropdown (goose often fires a run of
                        // 5-10 shell/read calls back to back — a wall of individual chips otherwise).
                        // Grouped on the forward list so adjacency is chronological, then reversed for
                        // display like the flat list was. NOT remembered on messages.size: the streaming
                        // assistant message updates via .copy() (new object, same id, size unchanged), so
                        // a size-keyed cache would go stale mid-stream. Grouping is O(#messages) — cheap.
                        val grouped = groupChatItems(cm.messages).asReversed()
                        // key on the stable id of the group's first message so a growing tool-call run
                        // (or the streaming assistant bubble) reuses its composition instead of rebuilding;
                        // i==0 is the newest item → mark an assistant Msg streaming so it renders as plain
                        // text until the turn finishes (skips the per-token Markdown re-parse).
                        itemsIndexed(grouped, key = { _, item -> item.firstId }) { i, item ->
                            when (item) {
                                is ChatItem.Tools -> ToolChipGroup(item.items)
                                is ChatItem.Msg -> MessageBubble(
                                    item.m, streaming = i == 0 && cm.busy.value && item.m.role == "assistant",
                                    // Only the newest, finished assistant reply gets the stats line.
                                    usage = if (i == 0 && item.m.role == "assistant" && !cm.busy.value)
                                        cm.lastMessageUsage.value else null)
                            }
                        }
                    }
                    if (!atBottom) SmallFloatingActionButton(
                        onClick = { scope.launch { listState.animateScrollToItem(0) } },
                        modifier = Modifier.align(Alignment.BottomEnd).padding(10.dp)
                    ) { Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "scroll to bottom") }
                }
            }

            // Slash-command autocomplete (goose's available commands).
            val slash = input.startsWith("/") && !input.contains(' ')
            if (slash) {
                val matches = cm.commands.value.filter { it.startsWith(input.drop(1), true) }.take(6)
                if (matches.isNotEmpty()) Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = MaterialTheme.shapes.small, modifier = Modifier.fillMaxWidth()
                ) {
                    Column {
                        matches.forEach { name ->
                            Text("/$name", modifier = Modifier.fillMaxWidth()
                                .clickable { input = "/$name " }.padding(horizontal = 12.dp, vertical = 8.dp),
                                style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }

            if (attachments.isNotEmpty()) Row(Modifier.padding(vertical = 4.dp)) {
                attachments.forEachIndexed { i, _ ->
                    AssistChip(onClick = { attachments.removeAt(i) },
                        label = { Text("image ${i + 1}") },
                        trailingIcon = { Icon(Icons.Filled.Close, contentDescription = "remove",
                            modifier = Modifier.size(16.dp)) },
                        modifier = Modifier.padding(end = 6.dp))
                }
            }

            // A queued message's bubble is identical to a sent one, so without this there is no way
            // to tell "waiting its turn" from "silently dropped".
            if (cm.queuedCount.value > 0) {
                Text("${cm.queuedCount.value} queued — will send when this turn finishes",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp, top = 2.dp))
            }

            // Composer modelled on Claude's: ONE rounded, outlined container holding the text
            // field and the action row together, rather than a pill field with buttons floating
            // underneath it. The container is the affordance -- everything inside belongs to the
            // message you are composing.
            //
            // Send/stop is a single filled circle on the right that CHANGES MEANING with state
            // (arrow to send, square to stop), which is why it reads at a glance. The previous
            // layout showed stop and send as two separate square buttons simultaneously.
            Surface(
                shape = RoundedCornerShape(28.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp),
            ) {
                Column(Modifier.padding(start = 18.dp, end = 10.dp, top = 14.dp, bottom = 8.dp)) {
                    // BasicTextField, not TextField: Material's own container/padding/indicator
                    // would draw a second surface inside this one. Here the Surface IS the field.
                    Box(Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
                        if (input.isEmpty()) {
                            Text(
                                if (cm.busy.value) "Queue message…" else "Message Grouse…",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        BasicTextField(
                            value = input,
                            onValueChange = { input = it },
                            textStyle = MaterialTheme.typography.bodyLarge.copy(
                                color = MaterialTheme.colorScheme.onSurface),
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            maxLines = 8,
                            modifier = Modifier.fillMaxWidth().heightIn(max = 200.dp),
                        )
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        // MODE pill, left -- the same slot Claude uses for its mode selector, and
                        // the same meaning: how much the agent will do without asking. The model
                        // lives in Settings; what you want at a glance while typing is whether
                        // this turn is going to stop for approval.
                        //
                        val modeOpt = cm.config.value.firstOrNull { it.id == "mode" }
                        val modeLabel = prettyMode(modeOpt?.currentValue)
                        var modeMenu by remember { mutableStateOf(false) }
                        Box {
                            Surface(
                                shape = RoundedCornerShape(20.dp),
                                color = MaterialTheme.colorScheme.surface,
                                modifier = Modifier.clickable(enabled = modeOpt != null) {
                                    modeMenu = true
                                },
                            ) {
                                Row(
                                    Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(Icons.Filled.Bolt, contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                        tint = MaterialTheme.colorScheme.onSurface)
                                    Spacer(Modifier.width(6.dp))
                                    Text(modeLabel, style = MaterialTheme.typography.labelLarge,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.widthIn(max = 130.dp))
                                }
                            }
                            DropdownMenu(expanded = modeMenu, onDismissRequest = { modeMenu = false }) {
                                modeOpt?.choices?.forEach { c ->
                                    DropdownMenuItem(
                                        text = {
                                            Column {
                                                Text(prettyMode(c.value))
                                                Text(modeBlurb(c.value),
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            }
                                        },
                                        onClick = {
                                            modeMenu = false
                                            cm.setOption("mode", c.value)
                                        },
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.weight(1f))
                        IconButton(
                            onClick = {
                                picker.launch(PickVisualMediaRequest(
                                    ActivityResultContracts.PickVisualMedia.ImageOnly))
                            },
                            modifier = Modifier.size(40.dp),
                        ) {
                            Icon(Icons.Filled.AttachFile, contentDescription = "attach image",
                                modifier = Modifier.size(21.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        // One circle, three states: stop while a turn runs, send when there is
                        // text, mic when there is not. Sending mid-turn queues, so the arrow is
                        // never wrong -- it just may not go out immediately.
                        val canSend = input.isNotBlank()
                        FilledIconButton(
                            onClick = {
                                when {
                                    cm.busy.value && !canSend -> cm.cancel()
                                    canSend -> doSend()
                                    listening -> stopListening()
                                    androidx.core.content.ContextCompat.checkSelfPermission(
                                        ctx, android.Manifest.permission.RECORD_AUDIO) ==
                                        android.content.pm.PackageManager.PERMISSION_GRANTED ->
                                            startListening()
                                    else -> micPerm.launch(android.Manifest.permission.RECORD_AUDIO)
                                }
                            },
                            modifier = Modifier.size(42.dp),
                            colors = IconButtonDefaults.filledIconButtonColors(
                                containerColor = when {
                                    cm.busy.value && !canSend -> MaterialTheme.colorScheme.surface
                                    canSend -> MaterialTheme.colorScheme.primary
                                    listening -> MaterialTheme.colorScheme.error
                                    else -> MaterialTheme.colorScheme.surface
                                },
                            ),
                        ) {
                            Icon(
                                when {
                                    cm.busy.value && !canSend -> Icons.Filled.Stop
                                    canSend -> Icons.Filled.ArrowUpward
                                    listening -> Icons.Filled.MicOff
                                    else -> Icons.Filled.Mic
                                },
                                contentDescription = when {
                                    cm.busy.value && !canSend -> "stop"
                                    canSend -> if (cm.busy.value) "queue" else "send"
                                    listening -> "stop listening"
                                    else -> "voice input"
                                },
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Tool-approval bottom sheet. Options come straight from goose (allow/reject × once/always). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PermissionSheet(req: AcpEvent.Permission, onChoose: (String?) -> Unit) {
    ModalBottomSheet(onDismissRequest = { onChoose(null) }) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
            Text("Allow tool?", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(6.dp))
            Text(req.title, style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary)
            if (req.detail.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                // Cap + internally scroll: a big tool call's rawInput (e.g. a large `write` body)
                // must never grow this box past a fixed height, or it pushes the allow/deny buttons
                // below the sheet's visible/reachable area on the phone.
                Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()) {
                    Text(req.detail, style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier
                            .heightIn(max = 220.dp)
                            .verticalScroll(rememberScrollState())
                            .padding(10.dp))
                }
            }
            Spacer(Modifier.height(16.dp))
            req.options.forEach { opt ->
                val reject = opt.kind.startsWith("reject")
                if (reject) {
                    OutlinedButton(onClick = { onChoose(opt.optionId) },
                        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) { Text(prettyOption(opt.label)) }
                } else {
                    Button(onClick = { onChoose(opt.optionId) },
                        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) { Text(prettyOption(opt.label)) }
                }
            }
        }
    }
}

/** Form elicitation (MCP/ACP): a tool asked for structured input. Renders the requested
 *  schema as native controls — switch for booleans, choice chips for enums, keyboard-typed
 *  fields otherwise — and answers accept/decline/cancel. Dismissing the sheet cancels. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ElicitationSheet(
    e: AcpEvent.Elicitation,
    onAccept: (Map<String, kotlinx.serialization.json.JsonPrimitive>) -> Unit,
    onDecline: () -> Unit,
    onCancel: () -> Unit,
) {
    val text = remember(e.requestKey) { mutableStateMapOf<String, String>() }
    val bools = remember(e.requestKey) { mutableStateMapOf<String, Boolean>() }
    ModalBottomSheet(onDismissRequest = onCancel) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp)
            .verticalScroll(rememberScrollState())) {
            Text(e.title.ifBlank { "Input requested" }, style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(4.dp))
            Text(e.message, style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            e.fields.forEach { f ->
                Spacer(Modifier.height(14.dp))
                when {
                    f.type == "boolean" -> Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(checked = bools[f.name] ?: false,
                            onCheckedChange = { bools[f.name] = it })
                        Spacer(Modifier.width(10.dp))
                        Text(f.title, style = MaterialTheme.typography.bodyLarge)
                    }
                    f.options.isNotEmpty() -> Column {
                        Text(f.title + if (f.required) " *" else "",
                            style = MaterialTheme.typography.labelLarge)
                        Spacer(Modifier.height(6.dp))
                        Row(Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            f.options.forEach { c ->
                                FilterChip(selected = text[f.name] == c.value,
                                    onClick = { text[f.name] = c.value },
                                    label = { Text(c.label) })
                            }
                        }
                    }
                    else -> OutlinedTextField(
                        text[f.name] ?: "", { text[f.name] = it }, singleLine = true,
                        label = { Text(f.title + if (f.required) " *" else "") },
                        supportingText = if (f.description.isNotBlank())
                            ({ Text(f.description) }) else null,
                        keyboardOptions = if (f.type == "number" || f.type == "integer")
                            KeyboardOptions(keyboardType = KeyboardType.Number)
                        else KeyboardOptions.Default,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            Spacer(Modifier.height(18.dp))
            val complete = e.fields.filter { it.required }.all { f ->
                if (f.type == "boolean") true else !text[f.name].isNullOrBlank()
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDecline) { Text("Decline") }
                Spacer(Modifier.width(8.dp))
                Button(enabled = complete, onClick = {
                    val values = buildMap {
                        e.fields.forEach { f ->
                            when {
                                f.type == "boolean" ->
                                    put(f.name, kotlinx.serialization.json.JsonPrimitive(bools[f.name] ?: false))
                                f.type == "number" || f.type == "integer" ->
                                    text[f.name]?.toDoubleOrNull()?.let { n ->
                                        if (f.type == "integer")
                                            put(f.name, kotlinx.serialization.json.JsonPrimitive(n.toLong()))
                                        else put(f.name, kotlinx.serialization.json.JsonPrimitive(n))
                                    }
                                else -> text[f.name]?.takeIf { it.isNotBlank() }
                                    ?.let { put(f.name, kotlinx.serialization.json.JsonPrimitive(it)) }
                            }
                        }
                    }
                    onAccept(values)
                }) { Text("Submit") }
            }
        }
    }
}

/** What tools are actually active in THIS session, with a switch per extension to change it on the
 *  fly (session-scoped — never touches config.yaml or any other open session). Distinct from
 *  Settings' Extensions screen (that one's the GLOBAL default for new chats) and the Session
 *  extension profiles (a saved preset applied at open time) — this is "right now, this chat". */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ToolManagementSheet(cm: ConnectionManager, onDismiss: () -> Unit) {
    // Re-list the session's tools and extensions every time the sheet opens. Ready's two polls
    // (0s/2.5s) can both miss a slow-attaching MCP extension, after which nothing else refreshed —
    // the sheet then showed the previous session's state until a manual toggle forced a round trip.
    LaunchedEffect(Unit) { cm.refreshSessionSheet() }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp).verticalScroll(rememberScrollState())) {
            Text("Tools for this chat", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(4.dp))
            Text("Session-only — doesn't change your global defaults or other chats. Reflects this " +
                "chat's own tool set from when it was opened, which can lag behind a global change " +
                "made since — flip it here if it's stale.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
            Spacer(Modifier.height(12.dp))
            if (cm.extensions.value.isEmpty()) {
                Text("loading…", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline)
            }
            val active = cm.sessionExtensionNames.value.toSet()
            cm.extensions.value.forEach { e ->
                val isOn = e.name in active
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).padding(end = 12.dp)) {
                        Text(e.name, style = MaterialTheme.typography.bodyLarge)
                        if (e.description.isNotBlank())
                            Text(e.description, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline, maxLines = 2)
                    }
                    // Every switch is live, bundled or not. This used to be
                    // `enabled = !(e.bundled && isOn)`, meant to stop a core extension being stripped
                    // -- but 8 of the 12 enabled extensions are bundled, so most of the sheet was
                    // permanently greyed and read as broken. goose itself imposes no such rule:
                    // session/extensions/remove drops a bundled extension for this session happily,
                    // and the next new chat starts from config.yaml again, so the blast radius is one
                    // conversation.
                    Switch(
                        checked = isOn,
                        onCheckedChange = { on -> cm.toggleSessionExtension(e.name, on) },
                    )
                }
                if (isOn) ToolList(cm, e, cm.sessionTools.value[e.name].orEmpty().toSet()) {
                    cm.setSessionTools(e, it)          // this chat only
                }
                HorizontalDivider()
            }
        }
    }
}

/** Best-effort guess whether a model can accept images, to warn before hanging a text-only model. */
private fun isLikelyVisionModel(model: String): Boolean {
    val s = model.lowercase()
    return listOf(
        "gemini", "claude", "gpt-4o", "gpt-4.1", "gpt-5", "o3", "o4-", "llava", "vision",
        "-vl", "qwen2-vl", "qwen2.5-vl", "qwen3-vl", "pixtral", "minicpm", "internvl",
        "gemma-3", "gemma3", "mmproj", "molmo", "phi-3.5-vision", "phi-4-multimodal", "llama-3.2",
    ).any { s.contains(it) }
}

private fun prettyOption(raw: String) = when (raw) {
    "allow_once" -> "Allow once"
    "allow_always" -> "Always allow"
    "reject_once" -> "Reject"
    "reject_always" -> "Always reject"
    else -> raw.replace('_', ' ').replaceFirstChar { it.uppercase() }
}

// ---- Sessions ---------------------------------------------------------------

/** The drawer's chats area: projects (collapsible groups of sessions, grouped by cwd —
 *  ConnectionManager.projectOf unifies /workspace with the Desktop cwd-shim spellings) on top,
 *  free chats below. The project list is the union of names seen in session cwds and the
 *  typed-recents store, so a just-created project with no sessions yet still shows.
 *  Tap opens a session; long-press offers Rename / Archive. Lives INSIDE ModalDrawerSheet —
 *  everything here is the app's main menu, not a separate screen. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DrawerChats(cm: ConnectionManager, onOpen: () -> Unit, onOpenProject: (String) -> Unit) {
    var showNewProject by remember { mutableStateOf(false) }
    var actionsFor by remember { mutableStateOf<SessionInfo?>(null) }
    var expanded by rememberSaveable { mutableStateOf(listOf<String>()) }

    actionsFor?.let { s -> SessionActionsDialog(cm, s) { actionsFor = null } }
    if (showNewProject) NewProjectDialog(
        cm = cm,
        onCreated = { name -> showNewProject = false; cm.newCodeSession(name); onOpen() },
        onDismiss = { showNewProject = false },
    )

    // Projects come from the SERVER now (sources/list type=project), and a session belongs to one
    // by project_id. This used to derive both from cwd -- names were the last path segment, which
    // is why they rendered capitalised (the DIRECTORY was capitalised), and the list was padded
    // with locally-remembered names from SharedPreferences, which is why deleted projects lingered
    // as ghosts. Neither source could be renamed, shared between clients, or trusted.
    // The Assistant has its own pinned row at the top of the drawer, so it must not ALSO appear
    // in the grouped lists. It used to fall out naturally: grouping keyed on cwd and the
    // assistant lived at /state, which was not a project. Now it carries a project_id like any
    // other session (the 2026-08-01 migration filed it under "inbox"), so it needs excluding
    // explicitly or it renders twice -- once pinned, once as an ordinary chat.
    val all = cm.sessions.value.filter { ConnectionManager.sessionKind(it) != SessionKind.ASSISTANT }
    val byProjectId = all.groupBy { it.projectId }
    val freeChats = byProjectId[null].orEmpty()
    val projects = cm.projects.value

    @Composable
    fun sessionRow(s: SessionInfo, indent: Boolean) {
        Row(Modifier.fillMaxWidth()
            .combinedClickable(onClick = { cm.openSession(s.sessionId); onOpen() },
                onLongClick = { actionsFor = s })
            .padding(start = if (indent) 34.dp else 10.dp, end = 8.dp)
            .padding(vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(s.title.ifBlank { "Untitled chat" }, style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(listOf("${s.messageCount} msgs", relativeTime(s.updatedAt))
                    .filter { it.isNotBlank() }.joinToString("  ·  "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline, maxLines = 1)
            }
        }
    }

    @Composable
    fun addRow(label: String, indent: Boolean, onClick: () -> Unit) {
        Row(Modifier.fillMaxWidth().clickable(onClick = onClick)
            .padding(start = if (indent) 34.dp else 10.dp)
            .padding(vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Add, contentDescription = null,
                modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.outline)
            Spacer(Modifier.width(8.dp))
            Text(label, style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.outline)
        }
    }

    LazyColumn(Modifier.fillMaxWidth().padding(horizontal = 14.dp)) {
        item {
            Text("PROJECTS", style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 10.dp, top = 10.dp, bottom = 2.dp))
        }
        projects.forEach { proj ->
            val p = proj.name
            val inProject = byProjectId[proj.id].orEmpty()
            val open = proj.id in expanded
            item(key = "project:" + proj.id) {
                // Name -> the project page; the chevron alone toggles the inline dropdown.
                Row(Modifier.fillMaxWidth().padding(start = 10.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Row(Modifier.weight(1f).clickable { onOpenProject(p) }.padding(vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Folder, contentDescription = null,
                            modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(10.dp))
                        Text(p, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f),
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (inProject.isNotEmpty()) Text("${inProject.size}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline)
                    }
                    IconButton(onClick = { expanded = if (open) expanded - proj.id else expanded + proj.id }) {
                        Icon(if (open) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                            contentDescription = if (open) "collapse" else "expand",
                            tint = MaterialTheme.colorScheme.outline)
                    }
                }
            }
            if (open) {
                item(key = "project:" + proj.id + ":new") {
                    addRow("New chat", indent = true) { cm.newChatInProject(proj.id); onOpen() }
                }
                items(inProject, key = { "s:" + it.sessionId }) { s -> sessionRow(s, indent = true) }
            }
        }
        item { addRow("New project", indent = false) { showNewProject = true } }
        item {
            Text("CHATS", style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 10.dp, top = 16.dp, bottom = 2.dp))
        }
        item { addRow("New chat", indent = false) { cm.newSession(); onOpen() } }
        items(freeChats, key = { "s:" + it.sessionId }) { s -> sessionRow(s, indent = false) }
    }
}

/** Long-press actions for one session: Rename / Move to project / Archive / Delete.
 *  Archive hides (history stays server-side, restorable via unarchive); Delete is goose's real
 *  session/delete (≥1.44 -- the old "-32601 no delete" note is obsolete) and is permanent.
 *  Move is the sanctioned working_dir rewrite -- also the repair for chats stranded by a
 *  renamed project directory. */
@Composable
private fun SessionActionsDialog(cm: ConnectionManager, s: SessionInfo, onDone: () -> Unit) {
    var mode by remember(s.sessionId) { mutableStateOf("menu") }
    when (mode) {
        "menu" -> AlertDialog(
            onDismissRequest = onDone,
            title = { Text(s.title.ifBlank { "Untitled chat" }, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            text = {
                Column {
                    TextButton(onClick = { mode = "rename" }) { Text("Rename…") }
                    TextButton(onClick = { mode = "move" }) { Text("Move to project…") }
                    TextButton(onClick = { mode = "archive" }) { Text("Archive…") }
                    TextButton(onClick = { mode = "delete" }) {
                        Text("Delete…", color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = onDone) { Text("Cancel") } },
        )
        "rename" -> {
            var newName by remember(s.sessionId) { mutableStateOf(s.title) }
            AlertDialog(
                onDismissRequest = onDone,
                title = { Text("Rename chat") },
                text = { OutlinedTextField(newName, { newName = it }, singleLine = true,
                    modifier = Modifier.fillMaxWidth()) },
                confirmButton = {
                    TextButton(enabled = newName.isNotBlank(), onClick = {
                        cm.renameSession(s.sessionId, newName); onDone()
                    }) { Text("Rename") }
                },
                dismissButton = { TextButton(onClick = onDone) { Text("Cancel") } },
            )
        }
        "move" -> {
            AlertDialog(
                onDismissRequest = onDone,
                title = { Text("Move to project") },
                text = {
                    Column {
                        // Sets project_id. It used to rewrite working_dir, which also moved where
                        // the session's tools ran -- filing a chat and re-homing it were one act.
                        TextButton(enabled = s.projectId != null, onClick = {
                            cm.fileSession(s.sessionId, null); onDone()
                        }) { Text("Chats (no project)") }
                        cm.projects.value.forEach { proj ->
                            TextButton(enabled = proj.id != s.projectId, onClick = {
                                cm.fileSession(s.sessionId, proj.id); onDone()
                            }) { Text(proj.name) }
                        }
                    }
                },
                confirmButton = {},
                dismissButton = { TextButton(onClick = onDone) { Text("Cancel") } },
            )
        }
        "archive" -> AlertDialog(
            onDismissRequest = onDone,
            title = { Text("Archive chat?") },
            text = { Text("“${s.title.ifBlank { "Untitled chat" }}” leaves this list; the history " +
                "stays on the server.") },
            confirmButton = { TextButton(onClick = {
                cm.archiveSession(s.sessionId); onDone()
            }) { Text("Archive") } },
            dismissButton = { TextButton(onClick = onDone) { Text("Cancel") } },
        )
        "delete" -> AlertDialog(
            onDismissRequest = onDone,
            title = { Text("Delete chat?") },
            text = { Text("Permanently deletes “${s.title.ifBlank { "Untitled chat" }}” and its " +
                "history from the server. Archive instead if you might want it back.") },
            confirmButton = { TextButton(onClick = {
                cm.deleteSession(s.sessionId); onDone()
            }) { Text("Delete", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = onDone) { Text("Cancel") } },
        )
    }
}

/** Create a project on the server, then open its first chat. No ACP directory-listing or mkdir
 *  method exists, so ConnectionManager.createProject runs a throwaway fast-model session that
 *  executes the mkdir (see its doc) — the busy state here covers that round trip. */
@Composable
private fun NewProjectDialog(cm: ConnectionManager, onCreated: (String) -> Unit, onDismiss: () -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("New project") },
        text = {
            Column {
                Text("Lowercase letters, digits and hyphens only. Creates a project on the server \u2014 no folder is made, and chats can be moved between projects freely.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(name, { name = it }, singleLine = true, enabled = !busy,
                    placeholder = { Text("e.g. bird-feeder-cam") }, modifier = Modifier.fillMaxWidth())
                if (busy) {
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp))
                        Text("Creating on the server…", style = MaterialTheme.typography.bodySmall)
                    }
                }
                error?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank() && !busy, onClick = {
                busy = true; error = null
                cm.createProject(name) { err ->
                    if (err == null) onCreated(name) else { busy = false; error = err }
                }
            }) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("Cancel") } },
    )
}

/** A project's home: its chats, its .goosehints and local memory (fetched on demand -- there
 *  is no file read over ACP, so a throwaway fast-model session cats them and echoes the output),
 *  and deletion. Delete archives the project's chats and rmdir's the server directory ONLY if
 *  empty -- a project with files keeps them and merely leaves the list. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ProjectScreen(cm: ConnectionManager, nav: NavController, project: String) {
    LaunchedEffect(Unit) { cm.listSessions() }
    var actionsFor by remember { mutableStateOf<SessionInfo?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    var deleteBusy by remember { mutableStateOf(false) }
    var deleteNote by remember { mutableStateOf<String?>(null) }
    var info by remember { mutableStateOf<String?>(null) }
    var infoBusy by remember { mutableStateOf(false) }
    fun goToChat() = nav.navigate("chat") { launchSingleTop = true; popUpTo("chat") { inclusive = true } }

    actionsFor?.let { s -> SessionActionsDialog(cm, s) { actionsFor = null } }
    if (confirmDelete) AlertDialog(
        onDismissRequest = { if (!deleteBusy) confirmDelete = false },
        title = { Text("Delete project?") },
        text = { Column {
            Text("Archives this project's chats and removes /workspace/$project from the " +
                "server — but ONLY if the directory is empty. A project with files keeps " +
                "them and just leaves this list.")
            if (deleteBusy) {
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp)); Text("Working…")
                }
            }
        } },
        confirmButton = {
            TextButton(enabled = !deleteBusy, onClick = {
                deleteBusy = true
                cm.deleteProject(project) { note ->
                    deleteBusy = false; confirmDelete = false; deleteNote = note
                }
            }) { Text("Delete") }
        },
        dismissButton = { TextButton(onClick = { confirmDelete = false }, enabled = !deleteBusy) { Text("Cancel") } },
    )
    deleteNote?.let { note ->
        AlertDialog(
            onDismissRequest = { deleteNote = null; nav.popBackStack() },
            title = { Text("Project deleted") },
            text = { Text(note) },
            confirmButton = { TextButton(onClick = { deleteNote = null; nav.popBackStack() }) { Text("OK") } },
        )
    }

    val chats = cm.sessions.value.filter {
        ConnectionManager.projectOf(it.cwd) == project &&
            ConnectionManager.sessionKind(it) != SessionKind.ASSISTANT
    }
    Scaffold(topBar = {
        TopAppBar(
            title = { Text(project, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            navigationIcon = {
                IconButton(onClick = { nav.popBackStack() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "back")
                }
            }
        )
    }) { pad ->
        LazyColumn(Modifier.padding(pad).padding(horizontal = 12.dp).fillMaxSize()) {
            item {
                Text("CHATS", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 6.dp, top = 10.dp, bottom = 4.dp))
            }
            item {
                Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)
                    .clickable { cm.newCodeSession(project); goToChat() }) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Add, contentDescription = null)
                        Spacer(Modifier.width(10.dp))
                        Text("New chat", style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
            items(chats, key = { it.sessionId }) { s ->
                Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)
                    .combinedClickable(onClick = { cm.openSession(s.sessionId); goToChat() },
                        onLongClick = { actionsFor = s })) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(s.title.ifBlank { "Untitled chat" }, style = MaterialTheme.typography.titleMedium,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Spacer(Modifier.height(2.dp))
                            Text(listOf("${s.messageCount} msgs", s.model, relativeTime(s.updatedAt))
                                .filter { it.isNotBlank() }.joinToString("  ·  "),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline, maxLines = 1,
                                overflow = TextOverflow.Ellipsis)
                        }
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null,
                            tint = MaterialTheme.colorScheme.outline)
                    }
                }
            }
            item {
                Text("GOOSEHINTS & MEMORY", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 6.dp, top = 18.dp, bottom = 4.dp))
            }
            item {
                Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Column(Modifier.fillMaxWidth().padding(14.dp)) {
                        when {
                            infoBusy -> Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(10.dp))
                                Text("Asking the fast model to read them…",
                                    style = MaterialTheme.typography.bodySmall)
                            }
                            info != null -> {
                                Text(info!!, style = MaterialTheme.typography.bodySmall)
                                Spacer(Modifier.height(6.dp))
                                TextButton(onClick = {
                                    infoBusy = true
                                    cm.fetchProjectInfo(project) { err, text ->
                                        infoBusy = false; info = err ?: text
                                    }
                                }) { Text("Reload") }
                            }
                            else -> {
                                Text("The project's .goosehints and local memory " +
                                    "(.goose/memory) live on the server; loading them runs a " +
                                    "quick fast-model session.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.outline)
                                Spacer(Modifier.height(6.dp))
                                TextButton(onClick = {
                                    infoBusy = true
                                    cm.fetchProjectInfo(project) { err, text ->
                                        infoBusy = false; info = err ?: text
                                    }
                                }) { Text("Load") }
                            }
                        }
                    }
                }
            }
            item {
                TextButton(onClick = { confirmDelete = true },
                    modifier = Modifier.padding(top = 18.dp, bottom = 24.dp)) {
                    Text("Delete project…", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

// ---- Assistant settings ------------------------------------------------------

/** The Assistant control panel. Server-side behavior (schedule, models, prompts, kill
 *  switches) lives in goose's config.yaml, written over ACP and read by deliver.sh per
 *  timer firing — changes apply at the next firing, no restarts. Tests touch a /state drop
 *  file via a direct tool call; a host systemd path unit runs the real pipeline with every
 *  gate bypassed. Thread tools edit the live Assistant session's extension set, which the
 *  daily rotation copies forward. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AssistantSettingsScreen(cm: ConnectionManager, nav: NavController) {
    LaunchedEffect(cm.online.value) {
        if (cm.online.value) {
            cm.loadAssistantExtensions()
            cm.refreshAssistantTools()
            cm.refreshSchedules()
        }
    }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Assistant") },
            navigationIcon = {
                IconButton(onClick = { nav.popBackStack() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "back")
                }
            }
        )
    }) { pad ->
        Column(Modifier.padding(pad).padding(horizontal = 16.dp).fillMaxSize()
            .verticalScroll(rememberScrollState())) {

            // The Morning digest / Updates sections that stood here are GONE (2026-08-01), and
            // so is the "Assistant features" master switch. All three wrote config keys --
            // MORNING_*, BRIEFING_*, ASSISTANT_ENABLED -- that only deliver.sh ever read, and
            // deliver.sh no longer runs the jobs. They rendered current-looking values and
            // changed nothing. Their "Run test now" buttons were worse than useless: they still
            // tripped the systemd path unit that runs deliver.sh, so a test would have delivered
            // a SECOND briefing by the retired route.
            //
            // Everything they claimed to control is real on the Schedules screen: enabled is
            // pause, time is cron, model/provider/prompt are the recipe, and "run test now" is
            // Run now on the actual job.
            SettingsSection("Scheduled jobs") {
                SettingsNavRow("Schedules & recipes",
                    cm.schedules.value.let { j ->
                        if (j.isEmpty()) "none scheduled"
                        else j.count { !it.paused }.toString() + " of " + j.size + " active"
                    }) { nav.navigate("schedules") }
                SettingCaption("The morning digest and the hourly briefing, with their cron, " +
                    "model and prompt. Pausing one here is what \"off\" used to mean.")
            }

                SettingsSection("Thread") {
                    var actions by remember { mutableStateOf(cm.store.assistantActions) }
                    var actMenu by remember { mutableStateOf(false) }
                    fun actLabel(v: String) = when (v) {
                        "auto" -> "Auto-approve (trusted)"; "readonly" -> "Read-only"; else -> "Ask me each time"
                    }
                    Box {
                        SettingsNavRow("Assistant actions", actLabel(actions)) { actMenu = true }
                        DropdownMenu(expanded = actMenu, onDismissRequest = { actMenu = false }) {
                            listOf("confirm", "auto", "readonly").forEach { v ->
                                DropdownMenuItem(text = { Text(actLabel(v)) },
                                    onClick = { actions = v; cm.store.assistantActions = v; actMenu = false })
                            }
                        }
                    }
                    SettingCaption("How the privileged Assistant thread handles write/shell " +
                        "actions. Other chats always ask; voice stays read-only.")
                    HorizontalDivider()
                    var confirmReset by remember { mutableStateOf(false) }
                    SettingsNavRow("Reset assistant thread",
                        "Start fresh now. The old thread is kept, renamed aside. (Happens " +
                        "automatically every morning.)") {
                        confirmReset = true
                    }
                    if (confirmReset) AlertDialog(
                        onDismissRequest = { confirmReset = false },
                        title = { Text("Reset assistant thread?") },
                        text = { Text("Your current assistant conversation is renamed aside " +
                            "(history preserved) and a fresh empty thread takes its place.") },
                        confirmButton = { TextButton(onClick = {
                            confirmReset = false; cm.resetAssistant(); nav.popBackStack()
                        }) { Text("Reset") } },
                        dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Cancel") } },
                    )
                }

                SettingsSection("Thread tools") {
                    SettingCaption("Extensions and their individual tools in the Assistant " +
                        "thread. The daily rotation carries this set forward, so changes " +
                        "stick. Other chats use the global defaults.")
                    if (cm.extensions.value.isEmpty()) {
                        Text("Connect to load the extension list.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline)
                    }
                    cm.extensions.value.sortedBy { it.name.lowercase() }.forEach { ext ->
                        val on = ext.name in cm.assistantExtNames.value
                        SettingsSwitchRow(ext.name, on) { cm.toggleAssistantExtension(ext, it) }
                        if (on) ToolList(cm, ext,
                            active = cm.assistantTools.value[ext.name].orEmpty().toSet(),
                            onExpand = { cm.discoverAssistantTools(ext) }) { sel ->
                            cm.setAssistantTools(ext, sel)
                        }
                    }
                }
            }
            Spacer(Modifier.height(28.dp))
        }
    }

// ---- Settings ---------------------------------------------------------------

// ---- Reusable settings building blocks --------------------------------------

/** A titled group of settings rows, grouped visually in a rounded card. */
@Composable
private fun SettingsSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Text(title.uppercase(), style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 6.dp, top = 22.dp, bottom = 8.dp))
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)),
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), content = content)
    }
}

/** A tappable settings row: label (+ optional subtitle) with a trailing chevron. */
@Composable
private fun SettingsNavRow(label: String, subtitle: String? = null, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline)
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null,
            tint = MaterialTheme.colorScheme.outline)
    }
}

/** Explanatory caption under a setting. */
@Composable
private fun SettingCaption(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline,
        modifier = Modifier.padding(bottom = 2.dp))
}

/** A label + trailing switch row. */
@Composable
private fun SettingsSwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}


/** Expandable per-extension tool list, shared by Settings (global default, writes config.yaml) and
 *  the in-chat sheet (this session only). `onSave` is the only difference between the two.
 *
 *  The full catalogue is not directly queryable -- goose reports only ALLOWED tools -- so expanding
 *  triggers ConnectionManager.discoverTools, which briefly runs the extension unfiltered in this
 *  session to read the whole set. That is why the list can take a moment to populate the first time,
 *  and why it is cached afterwards. */
@Composable
fun ToolList(cm: ConnectionManager, e: ExtInfo, active: Set<String>,
             onExpand: (() -> Unit)? = null, onSave: (Set<String>) -> Unit) {
    // Only mcp-backed extensions namespace their tools as `name__tool`, so only those can be mapped
    // back to an owner. Builtins (developer -> shell/edit/tree, summon -> delegate) cannot be, and
    // rendering a row for them showed a permanent "0 tools" that opened onto nothing.
    if (!cm.toolsAttributable(e)) return
    var open by remember { mutableStateOf(false) }
    val catalog = cm.toolCatalog.value[e.name]
    // Local echo so a checkbox responds instantly; the server round-trip refreshes it after.
    var sel by remember(e.name, active) { mutableStateOf(active) }

    Row(Modifier.fillMaxWidth().clickable {
            open = !open
            if (open) (onExpand ?: { cm.discoverTools(e) })()
        }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(if (open) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
            contentDescription = if (open) "hide tools" else "show tools")
        Spacer(Modifier.width(6.dp))
        Text(
            when {
                catalog != null -> "${sel.size} of ${catalog.size} tools"
                active.isNotEmpty() -> "${active.size} tools"
                else -> "tools"     // list not back yet — don't claim zero
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
        )
    }
    if (open) {
        if (catalog.isNullOrEmpty()) {
            Text(if (catalog == null) "reading tool list…" else "no tools reported",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(start = 30.dp, bottom = 6.dp))
        } else {
            Column(Modifier.padding(start = 30.dp, bottom = 8.dp)) {
                catalog.sorted().forEach { t ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 1.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text(t, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        Checkbox(checked = t in sel, onCheckedChange = { on ->
                            sel = if (on) sel + t else sel - t
                            onSave(sel)
                        })
                    }
                }
            }
        }
    }
}

/** Unwrap the hosting Activity from a Compose context (needed for the UnifiedPush distributor picker). */
private fun Context.findActivity(): android.app.Activity? {
    var c = this
    while (c is android.content.ContextWrapper) { if (c is android.app.Activity) return c; c = c.baseContext }
    return null
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(cm: ConnectionManager, nav: NavController, onOpenDrawer: () -> Unit) {
    val ctx = LocalContext.current
    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Settings") },
            navigationIcon = {
                IconButton(onClick = onOpenDrawer) { Icon(Icons.Filled.Menu, contentDescription = "menu") }
            }
        )
    }) { pad ->
        Column(Modifier.padding(pad).padding(horizontal = 16.dp).fillMaxSize()
            .verticalScroll(rememberScrollState())) {

            // Status header: connection + current model at a glance.
            Spacer(Modifier.height(4.dp))
            run {
                val model = cm.config.value.firstOrNull { it.id == "model" }?.currentValue
                    ?.takeIf { it.isNotBlank() && it != "current" } ?: "—"
                val isOnline = cm.online.value
                Card(shape = RoundedCornerShape(16.dp)) {
                    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (isOnline) Icons.Filled.Check else Icons.Filled.Close, contentDescription = null,
                            tint = if (isOnline) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(if (isOnline) "Connected" else cm.status.value.replaceFirstChar { it.uppercase() },
                                style = MaterialTheme.typography.titleSmall)
                            Text("${cm.store.host}:${cm.store.port}  ·  $model",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                        }
                        if (!isOnline) TextButton(onClick = { cm.connectSaved() }) { Text("Connect") }
                    }
                }
            }

            SettingsSection("Server") {
                SettingsNavRow("Instance", "${cm.store.host}:${cm.store.port}" +
                    if (cm.online.value) "  ·  connected" else "  ·  offline") {
                    nav.navigate("instance")
                }
                SettingsNavRow("Providers", "Chat model, vision, speech") {
                    nav.navigate("providers")
                }
                SettingsNavRow("Tools", "Extensions and the tools they expose") {
                    nav.navigate("extensions")
                }
                SettingsNavRow("Assistant", "The persistent thread and its scheduled jobs") {
                    nav.navigate("assistant_settings")
                }
            }

            SettingsSection("Appearance") {
                SettingsSwitchRow("Material You dynamic color", cm.dynamicColor.value) { cm.setDynamicColor(it) }
                SettingCaption("Off uses the built-in goose-green palette.")
            }

            SettingsSection("Security") {
                // Recomputed each recomposition (NOT remembered) so enrolling a biometric while the
                // screen is open reflects immediately. Only affects the caption, not the toggle.
                val bioAvailable = ctx.findActivity()
                    ?.let { it is androidx.fragment.app.FragmentActivity && Biometric.available(it) } ?: false
                var bioLock by remember { mutableStateOf(cm.store.biometricLock) }
                // The switch shows and controls the REAL stored value (not ANDed with availability),
                // so it can always be turned back off — and turning it on when no authenticator is
                // enrolled is harmless (the lock only engages when Biometric.available is true).
                SettingsSwitchRow("Require biometric unlock", bioLock) { on ->
                    bioLock = on; cm.store.biometricLock = on
                }
                SettingCaption("Off by default. When on, opening the app (or reconnecting with the " +
                    "saved key) needs your fingerprint/face or device PIN; takes effect next launch." +
                    if (!bioAvailable) " (No authenticator is enrolled on this device yet, so it " +
                        "won't engage until you add one.)" else "")
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

// ---- Extensions -------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExtensionsScreen(cm: ConnectionManager, nav: NavController) {
    LaunchedEffect(Unit) { cm.loadExtensions() }
    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Tools") },
            navigationIcon = {
                IconButton(onClick = { nav.popBackStack() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "back")
                }
            },
            actions = {
                if (cm.extensionsBusy.value)
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(12.dp))
            }
        )
    }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            Text("Turn off extensions you don't use to shrink the context every chat carries. " +
                "Changes apply to new chats.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(16.dp))
            LazyColumn(Modifier.fillMaxSize()) {
                items(cm.extensions.value) { e ->
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f).padding(end = 12.dp)) {
                            Text(e.name, style = MaterialTheme.typography.bodyLarge)
                            if (e.description.isNotBlank())
                                Text(e.description, style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.outline, maxLines = 2)
                        }
                        Switch(checked = e.enabled, enabled = !cm.extensionsBusy.value,
                            onCheckedChange = { cm.toggleExtension(e, it) })
                    }
                    if (e.enabled) Box(Modifier.padding(horizontal = 16.dp)) {
                        // Seed the checkboxes from the SAVED global allowlist (e.raw), not from the
                        // current session's tool state. The session reflects whatever this chat
                        // happens to be running (and after a catalogue discovery, the FULL set), so
                        // seeding from it made every box show checked again after a save that had in
                        // fact persisted -- "my pruning didn't save" when config.yaml said otherwise.
                        // Empty allowlist means "all tools", so fall back to the catalogue then.
                        val saved = (e.raw["available_tools"] as? JsonArray)
                            ?.mapNotNull { it.jsonPrimitive.contentOrNull }?.toSet().orEmpty()
                        val active = if (saved.isEmpty())
                            cm.toolCatalog.value[e.name]?.toSet()
                                ?: cm.sessionTools.value[e.name].orEmpty().toSet()
                        else saved
                        ToolList(cm, e, active) {
                            cm.setDefaultTools(e, it)   // config.yaml; applies to new chats
                        }
                    }
                    HorizontalDivider()
                }
                if (cm.extensions.value.isEmpty() && !cm.extensionsBusy.value) item {
                    Text("Couldn't load extensions — make sure you're connected.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(16.dp))
                }
            }
        }
    }
}

// ---- Model pickers ----------------------------------------------------------

@Composable
fun ModelBar(options: List<ConfigOption>, usage: AcpEvent.Usage?, expanded: Boolean, onToggle: () -> Unit) {
    fun cur(id: String) = options.firstOrNull { it.id == id }?.let { o ->
        if (o.currentValue == "current") "Provider default"
        else o.choices.firstOrNull { it.value == o.currentValue }?.label ?: o.currentValue
    }
    val summary = when {
        options.isEmpty() -> "loading model…"
        else -> listOfNotNull(cur("model"), cur("mode")).joinToString(" · ").ifBlank { "model settings" }
    }
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
    ) {
        Row(
            Modifier.fillMaxWidth().clickable { onToggle() }.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Filled.Tune, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(summary, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f),
                maxLines = 1)
            if (usage != null && usage.size > 0) {
                Spacer(Modifier.width(6.dp))
                Text("${fmtTokens(usage.used)}/${fmtTokens(usage.size)}",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
            }
            Spacer(Modifier.width(6.dp))
            Icon(Icons.Filled.KeyboardArrowDown, contentDescription = null,
                modifier = Modifier.rotate(if (expanded) 180f else 0f),
                tint = MaterialTheme.colorScheme.outline)
        }
    }
}

private fun fmtTokens(n: Int): String = when {
    n >= 1_000_000 -> "%.1fM".format(n / 1_000_000.0)
    n >= 1_000 -> "${n / 1000}k"
    else -> "$n"
}

/** "2h ago" style timestamp from goose's ISO updatedAt; falls back to the raw value. */
private fun relativeTime(iso: String): String = runCatching {
    val inst = runCatching { java.time.Instant.parse(iso) }
        .recoverCatching { java.time.OffsetDateTime.parse(iso).toInstant() }
        .recoverCatching { java.time.LocalDateTime.parse(iso).toInstant(java.time.ZoneOffset.UTC) }
        .getOrThrow()
    val secs = java.time.Duration.between(inst, java.time.Instant.now()).seconds
    when {
        secs < 60 -> "just now"
        secs < 3600 -> "${secs / 60}m ago"
        secs < 86400 -> "${secs / 3600}h ago"
        secs < 604800 -> "${secs / 86400}d ago"
        else -> iso.take(10)
    }
}.getOrElse { iso.take(16).replace('T', ' ') }


/** One-time hint on the assistant thread explaining what this privileged conversation is. */
@Composable
private fun AssistantHint(onDismiss: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Row(Modifier.padding(start = 14.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Your always-on assistant", style = MaterialTheme.typography.titleSmall)
                Text("Briefings, alerts, and voice all land here. Let it act on your behalf in " +
                    "Settings › Assistant › Assistant actions.", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, contentDescription = "dismiss") }
        }
    }
}

@Composable
fun ConfigPanel(
    options: List<ConfigOption>,
    showAllProviders: Boolean,
    configured: Set<String>,
    knownModels: Set<String>,
    onPick: (String, String) -> Unit,
    onCompact: () -> Unit,
    compacting: Boolean = false,
) {
    if (options.isEmpty()) {
        Text("loading model options…", style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(vertical = 8.dp))
        return
    }
    val byId = options.associateBy { it.id }
    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        for (id in CONFIG_IDS) byId[id]?.let { opt ->
            when (id) {
                // Dropdown of goose's featured models + ones seen before for the current provider.
                "model" -> ModelDropdown(opt, knownModels, onPick)
                // Hide unconfigured providers unless the user opted into the full catalog.
                "provider" -> ConfigDropdown(
                    if (showAllProviders) opt
                    else opt.copy(choices = opt.choices.filter { it.value in configured || it.value == opt.currentValue }),
                    onPick)
                else -> ConfigDropdown(opt, onPick)
            }
        }
        OutlinedButton(onClick = onCompact, enabled = !compacting,
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
            if (compacting) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
            }
            Text(if (compacting) "Compacting…" else "Compact conversation")
        }
        HorizontalDivider(Modifier.padding(top = 8.dp))
    }
}

/** Model picker: a READ-ONLY dropdown. Pick from goose's featured models + ones seen before for the
 *  CURRENT provider (provider-scoped, so LocalAI and OpenRouter never mix, and now also live-fetched
 *  from the provider's actual backend -- see AcpClient.listSupportedModels/ConnectionManager), or
 *  pick "Custom model…" to type any model id in a dialog. Free text is needed for OpenRouter slugs
 *  goose doesn't "feature" (e.g. poolside/laguna-s-2.1) and any local model id not yet in the list. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelDropdown(opt: ConfigOption, knownModels: Set<String>, onPick: (String, String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    var showCustomDialog by remember { mutableStateOf(false) }
    val featured = opt.choices.map { it.value }
    val entries = (featured + knownModels.filter { it !in featured }).distinct()
    fun labelFor(v: String) = if (v == "current") "Provider default"
        else opt.choices.firstOrNull { it.value == v }?.label ?: v
    fun commit(v: String) { expanded = false; onPick("model", v) }
    val currentLabel = if (opt.currentValue.isBlank()) "Provider default" else labelFor(opt.currentValue)
    ExposedDropdownMenuBox(
        expanded = expanded, onExpandedChange = { expanded = it },
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
    ) {
        OutlinedTextField(
            value = currentLabel,
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            label = { Text("model") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.menuAnchor().fillMaxWidth()
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            // NO "Provider default" entry. It committed the literal string "current", which
            // goose forwards verbatim to the backend, and LocalAI answers
            //   404  model "current" not found. To see available models, call GET /v1/models
            // so selecting it killed the chat until another model was picked. It was also
            // conceptually empty: setConfigOption("model", X) makes goose WRITE X into its
            // config.yaml, so whatever this picker last chose IS the provider default.
            entries.forEach { v ->
                DropdownMenuItem(text = { Text(labelFor(v)) }, onClick = { commit(v) })
            }
            DropdownMenuItem(
                text = { Text("Custom model…") },
                onClick = { expanded = false; showCustomDialog = true },
            )
        }
    }
    if (showCustomDialog) {
        CustomModelDialog(
            initial = opt.currentValue,
            onConfirm = { v -> showCustomDialog = false; if (v.isNotBlank()) commit(v) },
            onDismiss = { showCustomDialog = false },
        )
    }
}

@Composable
private fun CustomModelDialog(initial: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var text by rememberSaveable { mutableStateOf(if (initial == "current") "" else initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Custom model") },
        text = {
            OutlinedTextField(
                value = text, onValueChange = { text = it }, singleLine = true,
                label = { Text("model id") },
                placeholder = { Text("e.g. poolside/laguna-s-2.1") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onConfirm(text.trim()) }),
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = { TextButton(onClick = { onConfirm(text.trim()) }, enabled = text.isNotBlank()) { Text("Set") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** goose reports which values a knob accepts, and it is model-dependent -- do NOT second-guess it
 *  with a hardcoded list. `thinking_effort` offers only ["off"] on a model without extended
 *  thinking (Qwen3.6-35B-A3B on LocalAI) and ["off","low","medium","high","max"] on one that has it
 *  (z-ai/glm-5.2, deepseek-r1). A fallback list lived here briefly on the mistaken belief that goose
 *  sent no options at all -- that came from reading the wrong JSON key (`choices`; goose sends
 *  `options`) and it also omitted "max". Showing only "off" is correct, not a bug. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfigDropdown(opt: ConfigOption, onPick: (String, String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val label = opt.choices.firstOrNull { it.value == opt.currentValue }?.label ?: opt.currentValue
    ExposedDropdownMenuBox(
        expanded = expanded, onExpandedChange = { expanded = it },
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
    ) {
        OutlinedTextField(
            value = label, onValueChange = {}, readOnly = true, singleLine = true,
            label = { Text(opt.name) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.menuAnchor().fillMaxWidth()
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            opt.choices.forEach { c ->
                DropdownMenuItem(text = { Text(c.label) },
                    onClick = { expanded = false; onPick(opt.id, c.value) })
            }
        }
    }
}

// ---- Message bubbles --------------------------------------------------------

@Composable
fun MessageBubble(m: ChatMessage, streaming: Boolean = false, usage: AcpEvent.MessageUsage? = null) {
    when (m.role) {
        "user" -> UserBubble(m)
        "thought" -> ThoughtBubble(m.text)
        "tool" -> ToolChip(m)
        "error" -> ErrorBubble(m.text)
        "chart" -> ChartView(m.text)
        else -> AssistantBubble(m.text, streaming, usage)
    }
}

/** Renders an autovisualiser chart spec (Chart.js JSON) in a WebView with bundled Chart.js. */
@android.annotation.SuppressLint("SetJavaScriptEnabled")
@Composable
private fun ChartView(spec: String) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(10.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
    ) {
        AndroidView(
            factory = { ctx ->
                android.webkit.WebView(ctx).apply {
                    settings.javaScriptEnabled = true
                    setBackgroundColor(android.graphics.Color.TRANSPARENT)
                    isVerticalScrollBarEnabled = false
                    isHorizontalScrollBarEnabled = false
                    loadDataWithBaseURL("file:///android_asset/", chartHtml(spec), "text/html", "utf-8", null)
                }
            },
            modifier = Modifier.fillMaxWidth().height(240.dp).padding(8.dp)
        )
    }
}

private fun chartHtml(spec: String): String = CHART_TEMPLATE.replace("__SPEC__", spec)

private val CHART_TEMPLATE = """
<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
<script src="chart.min.js"></script>
<style>html,body{margin:0;padding:0;background:transparent}.wrap{position:relative;height:224px}</style></head>
<body><div class="wrap"><canvas id="c"></canvas></div><script>
try {
  const spec = __SPEC__;
  const dark = matchMedia('(prefers-color-scheme: dark)').matches;
  Chart.defaults.color = dark ? '#e2e2e2' : '#303030';
  Chart.defaults.borderColor = dark ? '#404040' : '#e2e2e2';
  new Chart(document.getElementById('c'), {
    type: spec.type || 'bar',
    data: { labels: spec.labels || [], datasets: spec.datasets || [] },
    options: { responsive: true, maintainAspectRatio: false,
      plugins: { title: { display: !!spec.title, text: spec.title || '' },
                 legend: { display: (spec.datasets||[]).length > 1 } } }
  });
} catch(e) { document.body.innerHTML = '<pre style="color:#c0392b">chart error: '+e+'</pre>'; }
</script></body></html>
""".trimIndent()

@Composable
private fun Markdownish(text: String) {
    RichText(modifier = Modifier.fillMaxWidth()) { Markdown(text) }
}

/** Long-press any message to copy its text. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Modifier.copyOnLongPress(text: String): Modifier {
    val clip = LocalClipboardManager.current
    val ctx = LocalContext.current
    return combinedClickable(onClick = {}, onLongClick = {
        clip.setText(AnnotatedString(text)); Toast.makeText(ctx, "Copied", Toast.LENGTH_SHORT).show()
    })
}

@Composable
private fun UserBubble(m: ChatMessage) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.End) {
        Surface(
            color = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            shape = RoundedCornerShape(16.dp, 16.dp, 4.dp, 16.dp),
            modifier = Modifier.widthIn(max = 320.dp)
        ) {
            Column(Modifier.copyOnLongPress(m.text).padding(horizontal = 6.dp, vertical = 6.dp)) {
                m.images.forEach { img ->
                    val bmp = remember(img.dataB64) { decodeImageBlock(img.dataB64) }
                    if (bmp != null) Image(
                        bitmap = bmp, contentDescription = "attached image",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.padding(bottom = if (m.text.isNotBlank()) 6.dp else 0.dp)
                            .heightIn(max = 220.dp).clip(RoundedCornerShape(10.dp)))
                }
                if (m.text.isNotBlank())
                    Box(Modifier.padding(horizontal = 6.dp, vertical = 2.dp)) { Markdownish(m.text) }
            }
        }
    }
}

/** Decode an ImageBlock's base64 (NO_WRAP) payload to an ImageBitmap for the chat bubble. */
private fun decodeImageBlock(b64: String): androidx.compose.ui.graphics.ImageBitmap? = try {
    val bytes = android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
    android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
} catch (e: Exception) { null }

@Composable
private fun AssistantBubble(text: String, streaming: Boolean = false, usage: AcpEvent.MessageUsage? = null) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Box(Modifier.copyOnLongPress(text).padding(horizontal = 2.dp, vertical = 4.dp)) {
            // Plain text while streaming — re-parsing the growing Markdown every token is O(n²).
            // The bubble re-renders once with full Markdown when the turn finishes.
            if (streaming) Text(text) else Markdownish(text)
        }
        if (usage != null) {
            val tps = usage.outputTokens * 1000.0 / usage.elapsedMs
            val bits = mutableListOf("%.1f tok/s".format(tps))
            if (usage.ttftMs > 0) bits += "TTFT ${usage.ttftMs}ms"
            usage.cost?.let { if (it > 0) bits += "$" + "%.4f".format(it) }
            Text(bits.joinToString("  ·  "), style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(start = 2.dp, top = 1.dp))
        }
    }
}

@Composable
private fun ThoughtBubble(text: String) {
    var expanded by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Row(
            Modifier.clickable { expanded = !expanded }.padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                if (expanded) Icons.Filled.KeyboardArrowDown else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null, modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.outline
            )
            Icon(Icons.Filled.Psychology, contentDescription = null, modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.outline)
            Spacer(Modifier.width(6.dp))
            Text("Thinking", style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.outline)
        }
        AnimatedVisibility(expanded) {
            Text(text, style = MaterialTheme.typography.bodySmall.copy(fontStyle = FontStyle.Italic),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 24.dp, bottom = 4.dp))
        }
    }
}

/** A run of consecutive same-role messages for display: a lone message, or 2+ consecutive tool
 *  calls collapsed into one dropdown (goose often fires 5-10 shell/read calls back to back — a
 *  wall of individual chips otherwise, see the "grouping" ask). */
private sealed interface ChatItem {
    val firstId: Long
    data class Msg(val m: ChatMessage) : ChatItem { override val firstId get() = m.id }
    data class Tools(val items: List<ChatMessage>) : ChatItem { override val firstId get() = items.first().id }
}

/** Walk messages in chronological order, merging consecutive role=="tool" runs of 2+ into one
 *  ChatItem.Tools; everything else (including a lone tool call) stays a ChatItem.Msg. */
private fun groupChatItems(messages: List<ChatMessage>): List<ChatItem> {
    val out = mutableListOf<ChatItem>()
    var i = 0
    while (i < messages.size) {
        val m = messages[i]
        if (m.role == "tool") {
            var j = i + 1
            while (j < messages.size && messages[j].role == "tool") j++
            out += if (j - i > 1) ChatItem.Tools(messages.subList(i, j).toList()) else ChatItem.Msg(m)
            i = j
        } else {
            out += ChatItem.Msg(m); i++
        }
    }
    return out
}

/** ACP tool titles look like "shell · ls -laR /some/long/path". Split into a short badge label
 *  and the (often long) detail, so a collapsed group header can show just the label. */
private fun splitToolTitle(title: String): Pair<String, String> {
    val idx = title.indexOf(" · ")
    return if (idx >= 0) title.take(idx) to title.substring(idx + 3) else title to ""
}

/** Collapsed: one chip, "N× <tool>" (or "N tool calls" for a mixed run). Tap to expand into the
 *  individual calls, oldest first, each rendered like a normal ToolChip. */
@Composable
private fun ToolChipGroup(items: List<ChatMessage>) {
    var expanded by remember { mutableStateOf(false) }
    val names = items.map { splitToolTitle(it.text).first }.distinct()
    val label = if (names.size == 1) "${items.size}× ${names[0]}" else "${items.size} tool calls"
    Column(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Surface(
            color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f),
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.clickable { expanded = !expanded }
        ) {
            Row(Modifier.padding(horizontal = 12.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (expanded) Icons.Filled.KeyboardArrowDown else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null, modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.outline
                )
                Spacer(Modifier.width(2.dp))
                Icon(Icons.Filled.Build, contentDescription = null, modifier = Modifier.size(13.dp),
                    tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text(label.replace('_', ' '), style = MaterialTheme.typography.labelLarge)
            }
        }
        AnimatedVisibility(expanded) {
            Column(Modifier.padding(start = 20.dp, top = 2.dp)) {
                items.forEach { ToolChip(it) }
            }
        }
    }
}

/** Tap to expand and see the tool's rawInput (command/args) — Desktop shows this inline; here it's
 *  collapsed by default (matches ThoughtBubble's pattern) so a wall of tool calls stays scannable. */
@Composable
private fun ToolChip(m: ChatMessage) {
    val title = m.text; val detail = m.detail
    var expanded by remember { mutableStateOf(false) }
    val (name, inlineDetail) = splitToolTitle(title)
    val hasBody = detail.isNotBlank() || m.output.isNotBlank()
    Column(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Surface(color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f),
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.let { if (hasBody) it.clickable { expanded = !expanded } else it }) {
            Row(Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically) {
                when (m.status) {
                    // Live lifecycle from tool_call_update: spinner while running, error tint on
                    // failure, plain wrench once done (or for replayed history, which has no status).
                    "in_progress" -> CircularProgressIndicator(Modifier.size(13.dp), strokeWidth = 1.5.dp)
                    "failed" -> Icon(Icons.Filled.Close, contentDescription = "failed",
                        modifier = Modifier.size(13.dp), tint = MaterialTheme.colorScheme.error)
                    else -> Icon(Icons.Filled.Build, contentDescription = null,
                        modifier = Modifier.size(13.dp), tint = MaterialTheme.colorScheme.primary)
                }
                Spacer(Modifier.width(8.dp))
                Text(name.replace('_', ' '), style = MaterialTheme.typography.labelLarge)
                if (inlineDetail.isNotBlank()) {
                    Spacer(Modifier.width(8.dp))
                    Text(inlineDetail, style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.6f),
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                } else Spacer(Modifier.weight(1f))
                if (hasBody) Icon(
                    if (expanded) Icons.Filled.KeyboardArrowDown else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null, modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.outline)
            }
        }
        if (hasBody) AnimatedVisibility(expanded) {
            Surface(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.padding(start = 16.dp, top = 3.dp, bottom = 2.dp).fillMaxWidth()) {
                Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                    val mono = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                    if (detail.isNotBlank()) {
                        Text("input", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary)
                        Text(detail, style = mono, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (m.output.isNotBlank()) {
                        if (detail.isNotBlank()) Spacer(Modifier.height(6.dp))
                        Text("output", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary)
                        // Cap the rendered output — tool results can be enormous.
                        Text(m.output.take(4000), style = mono,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (m.output.length > 4000) Text("… (${m.output.length} chars total)",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline)
                    }
                }
            }
        }
    }
}

@Composable
private fun ErrorBubble(text: String) {
    Surface(color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer, shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(10.dp))
    }
}

@Composable
private fun TypingIndicator() {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(8.dp))
        Text("Grouse is thinking…", style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.outline)
    }
}

// ---- Schedules and recipes ------------------------------------------------------------------
//
// These two screens are one feature seen from both ends. A SCHEDULE is a cron entry; a RECIPE is
// what it runs. goose keeps them separately and links them only by file path, so the list screen
// shows jobs and the detail screen edits the recipe behind one -- editing a recipe changes what
// the job does on its next run, with nothing to re-register.
//
// Goose Desktop deliberately does less than this: its schedule detail view prints the recipe's
// PATH and stops, because Desktop can open the file with a native picker and Grouse cannot. A
// phone has no filesystem in common with the server, which is why the recipe library API
// (recipes/list returns whole recipes) is the only workable way to show any of this here.

/** "0 0 7-22 * * *" -> "hourly, 07:00-22:00". Falls back to the raw expression, which is the
 *  honest thing to show for anything this does not recognise -- a wrong plain-English reading of
 *  a cron is worse than the cron. */
fun cronInEnglish(cron: String): String {
    val f = cron.trim().split(Regex("\\s+"))
    // 5-field crons are legal too; goose prefixes a "0" seconds field for them.
    val p = when (f.size) { 6 -> f; 5 -> listOf("0") + f; else -> return cron }
    val (sec, min, hour) = Triple(p[0], p[1], p[2])
    if (sec != "0" || !min.all { it.isDigit() }) return cron
    val m = min.toIntOrNull() ?: return cron
    fun hhmm(h: Int) = "%02d:%02d".format(h, m)
    return when {
        hour.all { it.isDigit() } -> "daily at ${hhmm(hour.toInt())}"
        hour == "*" -> "hourly at :%02d".format(m)
        hour.matches(Regex("\\d+-\\d+")) -> {
            val (a, b) = hour.split("-").map { it.toInt() }
            "hourly, ${hhmm(a)}-${hhmm(b)}"
        }
        else -> cron
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SchedulesScreen(cm: ConnectionManager, nav: NavController) {
    LaunchedEffect(cm.online.value) { if (cm.online.value) cm.refreshSchedules() }
    var note by remember { mutableStateOf<String?>(null) }
    val jobs = cm.schedules.value
    val recipes = cm.recipes.value
    val scheduledPaths = jobs.map { it.source }.toSet()

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Schedules") },
            navigationIcon = {
                IconButton(onClick = { nav.popBackStack() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "back")
                }
            },
            actions = {
                IconButton(onClick = { cm.refreshSchedules() }) {
                    Icon(Icons.Filled.Refresh, contentDescription = "refresh")
                }
            },
        )
    }) { pad ->
        Column(Modifier.padding(pad).padding(horizontal = 16.dp).fillMaxSize()
            .verticalScroll(rememberScrollState())) {

            note?.let {
                Text(it, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(vertical = 8.dp))
            }

            SettingsSection("Scheduled") {
                if (jobs.isEmpty()) {
                    SettingCaption("Nothing scheduled. Give a recipe below a time to start one.")
                }
                jobs.forEach { job ->
                    val recipe = cm.recipeFor(job)
                    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f).clickable(enabled = recipe != null) {
                            recipe?.let { nav.navigate("recipe/${it.id}") }
                        }) {
                            Text(recipe?.title ?: job.id)
                            Text(
                                buildString {
                                    append(cronInEnglish(job.cron))
                                    if (job.running) append("  ·  running now")
                                    else if (job.paused) append("  ·  paused")
                                    job.lastRun?.let { append("  ·  last ${it.take(16).replace('T', ' ')}") }
                                    // A job whose recipe is not in the library was created with an
                                    // inline copy; there is nothing here to open or edit.
                                    if (recipe == null) append("  ·  ${job.recipeFile}")
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline,
                            )
                        }
                        if (job.running) {
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        } else {
                            Switch(checked = !job.paused,
                                onCheckedChange = { cm.setSchedulePaused(job.id, !it) })
                        }
                    }
                    Row {
                        TextButton(enabled = !job.running, onClick = {
                            cm.runScheduleNow(job.id)
                            // run-now blocks server-side for the whole run, so the reply is the
                            // finish. Say what will actually happen rather than "started".
                            note = "Running ${recipe?.title ?: job.id} — a briefing takes a few " +
                                "minutes and notifies when it has something."
                        }) { Text("Run now") }
                        TextButton(onClick = { cm.deleteSchedule(job.id) }) { Text("Unschedule") }
                    }
                    HorizontalDivider()
                }
            }

            SettingsSection("Recipes") {
                if (recipes.isEmpty()) {
                    SettingCaption("No saved recipes on the server.")
                }
                recipes.forEach { r ->
                    val isScheduled = r.filePath in scheduledPaths
                    SettingsNavRow(
                        r.title,
                        listOfNotNull(
                            r.model,
                            if (isScheduled) null else "not scheduled",
                        ).joinToString(" · ").ifBlank { r.description.take(60) },
                    ) { nav.navigate("recipe/${r.id}") }
                }
                SettingCaption("A recipe is what a schedule runs. Editing one here changes its " +
                    "next run — there is nothing to re-register.")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecipeScreen(cm: ConnectionManager, nav: NavController, recipeId: String) {
    LaunchedEffect(cm.online.value) { if (cm.online.value) cm.refreshSchedules() }
    val r = cm.recipes.value.firstOrNull { it.id == recipeId }
    val job = cm.schedules.value.firstOrNull { it.source == r?.filePath }
    var confirmDelete by remember { mutableStateOf(false) }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text(r?.title ?: "Recipe") },
            navigationIcon = {
                IconButton(onClick = { nav.popBackStack() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "back")
                }
            },
        )
    }) { pad ->
        if (r == null) {
            Box(Modifier.padding(pad).fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Recipe not found.", color = MaterialTheme.colorScheme.outline)
            }
            return@Scaffold
        }
        Column(Modifier.padding(pad).padding(horizontal = 16.dp).fillMaxSize()
            .verticalScroll(rememberScrollState())) {

            if (r.description.isNotBlank()) {
                Text(r.description, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(vertical = 8.dp))
            }

            SettingsSection("Schedule") {
                var cron by remember(r.id, job?.cron) { mutableStateOf(job?.cron ?: "") }
                OutlinedTextField(cron, { cron = it }, singleLine = true,
                    label = { Text("Cron") },
                    supportingText = {
                        Text(if (cron.isBlank()) "Empty = not scheduled"
                             else cronInEnglish(cron))
                    },
                    modifier = Modifier.fillMaxWidth())
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(enabled = cron.trim() != (job?.cron ?: ""), onClick = {
                        cm.setRecipeCron(r.id, cron.trim().ifBlank { null })
                    }) { Text(if (cron.isBlank()) "Unschedule" else "Save schedule") }
                }
                SettingCaption("Six fields, seconds first — \"0 0 6 * * *\" is 06:00 daily. " +
                    "Server local time.")
                job?.let {
                    SettingsSwitchRow("Enabled", !it.paused) { on ->
                        cm.setSchedulePaused(it.id, !on)
                    }
                }
            }

            SettingsSection("Model") {
                var model by remember(r.id, r.model) { mutableStateOf(r.model.orEmpty()) }
                var provider by remember(r.id, r.provider) { mutableStateOf(r.provider.orEmpty()) }
                OutlinedTextField(model, { model = it }, singleLine = true,
                    label = { Text("Model") },
                    placeholder = { Text("blank = the server default") },
                    modifier = Modifier.fillMaxWidth())
                var open by remember { mutableStateOf(false) }
                Box {
                    SettingsNavRow("Provider", provider.ifBlank { "(server default)" }) { open = true }
                    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                        listOf("", "openai", "openrouter", "openrouter_custom").forEach { p ->
                            DropdownMenuItem(
                                text = { Text(when (p) {
                                    "" -> "(server default)"
                                    "openai" -> "openai (local)"
                                    else -> p
                                }) },
                                onClick = { provider = p; open = false })
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(
                        enabled = model != r.model.orEmpty() || provider != r.provider.orEmpty(),
                        onClick = {
                            // Two writes, model first: each save replaces the whole recipe, so
                            // they have to be applied in sequence off the same base or the second
                            // reverts the first.
                            var dto = cm.recipeWithSetting(r, "goose_model", model.trim())
                            dto = cm.recipeWithSetting(
                                r.copy(raw = dto), "goose_provider", provider.trim())
                            cm.saveRecipe(r.id, dto)
                        }) { Text("Save model") }
                }
                SettingCaption("A recipe's own pin wins over the server default. A cloud model " +
                    "under provider \"openai\" is sent to LocalAI and 404s — move both together.")
            }

            SettingsSection("Prompt") {
                var prompt by remember(r.id, r.prompt) { mutableStateOf(r.prompt.orEmpty()) }
                OutlinedTextField(prompt, { prompt = it }, minLines = 2, maxLines = 8,
                    label = { Text("Prompt") },
                    supportingText = { Text("The message the run starts from.") },
                    modifier = Modifier.fillMaxWidth())
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(enabled = prompt != r.prompt.orEmpty(), onClick = {
                        cm.saveRecipe(r.id, cm.recipeWith(r, "prompt", prompt))
                    }) { Text("Save prompt") }
                }
            }

            SettingsSection("Instructions") {
                var instr by remember(r.id, r.instructions) {
                    mutableStateOf(r.instructions.orEmpty())
                }
                OutlinedTextField(instr, { instr = it }, minLines = 4, maxLines = 20,
                    label = { Text("Instructions") },
                    supportingText = { Text("The system prompt for this run.") },
                    modifier = Modifier.fillMaxWidth())
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(enabled = instr != r.instructions.orEmpty(), onClick = {
                        cm.saveRecipe(r.id, cm.recipeWith(r, "instructions", instr))
                    }) { Text("Save instructions") }
                }
            }

            // Read-only from here down. These are structural -- a sub-recipe's delegate wiring and
            // an extension's tool allowlist are what make a briefing read-only and scoped, and a
            // phone-sized editor for them would be a good way to break a job silently. They are
            // shown because "what does this actually run" is the question the screen exists to
            // answer.
            if (r.extensions.isNotEmpty()) {
                SettingsSection("Extensions") {
                    r.extensions.forEach { Text("· $it", Modifier.padding(vertical = 2.dp)) }
                    SettingCaption("The tools this recipe can reach. Edit in the recipe file.")
                }
            }

            if (r.subRecipes.isNotEmpty()) {
                SettingsSection("Sub-recipes") {
                    r.subRecipes.forEach { Text("· $it", Modifier.padding(vertical = 2.dp)) }
                    SettingCaption("Delegated checks, each with its own small context. Their " +
                        "tools come from THIS recipe's extensions, not their own files.")
                }
            }

            if (r.parameters.isNotEmpty()) {
                SettingsSection("Parameters") {
                    r.parameters.forEach { p ->
                        Text("· ${p.key}" + if (p.requirement == "optional") " (optional)" else "",
                            Modifier.padding(top = 4.dp))
                        if (p.description.isNotBlank()) {
                            Text(p.description, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline)
                        }
                    }
                    SettingCaption("A schedule freezes parameter values when it is created, so " +
                        "anything that changes per run has to be a tool call, not a parameter.")
                }
            }

            SettingsSection("Danger") {
                TextButton(onClick = { confirmDelete = true }) { Text("Delete recipe") }
                SettingCaption(r.filePath)
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete ${r?.title}?") },
            text = { Text("The recipe file is removed from the server. Any schedule running it " +
                "stops working.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    r?.let { rec -> cm.deleteRecipe(rec.id) }
                    nav.popBackStack()
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Cancel") }
            },
        )
    }
}

// ---- Settings subpages ------------------------------------------------------
//
// Settings used to be one long scroll where Connection, Voice, Models and Images sat next
// to Appearance and Security. These are the same sections, grouped by what they configure:
// the SERVER you talk to, the MODELS it runs, the TOOLS it exposes, and the ASSISTANT.
// Device-local preferences (notifications, theme, biometric lock) stay on the main page,
// because they are not settings of the goose at all.

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InstanceScreen(cm: ConnectionManager, nav: NavController) {
    var host by remember { mutableStateOf(cm.store.host) }
    var port by remember { mutableStateOf(cm.store.port) }
    var newKey by remember { mutableStateOf("") }
    var persistent by remember { mutableStateOf(cm.persistent) }
    val ctx = LocalContext.current
    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Instance") },
            navigationIcon = {
                IconButton(onClick = { nav.popBackStack() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "back")
                }
            },
        )
    }) { pad ->
        Column(Modifier.padding(pad).padding(horizontal = 16.dp).fillMaxSize()
            .verticalScroll(rememberScrollState())) {
            SettingsSection("Connection") {
                OutlinedTextField(host, { host = it }, label = { Text("Host") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth())
                OutlinedTextField(port, { port = it }, label = { Text("Port") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                OutlinedTextField(newKey, { newKey = it }, label = { Text("Replace secret key (optional)") },
                    singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                Button(onClick = {
                    val key = newKey.trim().ifBlank { cm.store.secretKey }
                    cm.connect(host.trim(), port.trim(), key); nav.popBackStack()
                }, modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) { Text("Save & reconnect") }
            }

            SettingsSection("Notifications & background") {
                var pushOn by remember { mutableStateOf(cm.store.pushEnabled) }
                SettingsSwitchRow("Push notifications", pushOn) { on ->
                    pushOn = on
                    val act = ctx.findActivity()
                    if (on && act != null) Push.enable(act) else Push.disable(ctx)
                }
                SettingCaption("Server-pushed briefings/alerts via your distributor (NextPush) — " +
                    "no always-on socket, no FCM.")
                val endpoint = cm.store.pushEndpoint
                if (endpoint.isNotBlank()) {
                    SelectionContainer {
                        Text("Endpoint: $endpoint", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline)
                    }
                }
                HorizontalDivider(Modifier.padding(vertical = 6.dp))
                SettingsSwitchRow("Keep connection alive", persistent) { persistent = it; cm.setPersistent(it) }
                SettingCaption("On: stay connected in the background (persistent notification, more battery). " +
                    "Off: connect while active; you still get a finished-turn notification.")
            }


            Spacer(Modifier.height(28.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProvidersScreen(cm: ConnectionManager, nav: NavController) {
    var showAll by remember { mutableStateOf(cm.showAllProviders.value) }
    val ctx = LocalContext.current
    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Providers") },
            navigationIcon = {
                IconButton(onClick = { nav.popBackStack() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "back")
                }
            },
        )
    }) { pad ->
        Column(Modifier.padding(pad).padding(horizontal = 16.dp).fillMaxSize()
            .verticalScroll(rememberScrollState())) {
            SettingsSection("Chat model") {
                // The DEFAULT for new chats -- GOOSE_PROVIDER/GOOSE_MODEL in config.yaml. The
                // picker above the message box sets the CURRENT chat only
                // (session/set_config_option), which is why both exist and why this one says
                // "new chats": changing it never re-points a conversation already under way.
                LaunchedEffect(cm.online.value) {
                    if (cm.online.value) cm.readServerConfig("GOOSE_PROVIDER", "GOOSE_MODEL")
                }
                var provOpen by remember { mutableStateOf(false) }
                val curProv = cm.serverConfig["GOOSE_PROVIDER"].orEmpty().ifBlank { "—" }
                Box {
                    SettingsNavRow("Default provider", curProv) { provOpen = true }
                    DropdownMenu(expanded = provOpen, onDismissRequest = { provOpen = false }) {
                        listOf("openai", "openrouter", "openrouter_custom").forEach { pv ->
                            DropdownMenuItem(
                                text = { Text(if (pv == "openai") "openai (local)" else pv) },
                                onClick = { cm.setServerConfig("GOOSE_PROVIDER", pv); provOpen = false })
                        }
                    }
                }
                var modelDraft by remember(cm.serverConfig["GOOSE_MODEL"]) {
                    mutableStateOf(cm.serverConfig["GOOSE_MODEL"].orEmpty())
                }
                OutlinedTextField(modelDraft, { modelDraft = it }, singleLine = true,
                    label = { Text("Default model") },
                    trailingIcon = {
                        if (modelDraft.isNotBlank() && modelDraft != cm.serverConfig["GOOSE_MODEL"])
                            TextButton(onClick = {
                                cm.setServerConfig("GOOSE_MODEL", modelDraft.trim())
                            }) { Text("Save") }
                    },
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
                SettingCaption("Used by new chats. A cloud model under provider \"openai\" is " +
                    "sent to the local server and 404s — move both together.")
                HorizontalDivider(Modifier.padding(vertical = 6.dp))
                SettingsSwitchRow("Show all providers", showAll) { showAll = it; cm.setShowAllProviders(it) }
                SettingCaption("Off shows only providers set up on your goose. On lists goose's " +
                    "full catalog.")
                HorizontalDivider()
                // App-editable global goose settings (config.yaml over ACP). Loaded on entry.
                LaunchedEffect(cm.online.value) { if (cm.online.value) cm.loadServerConfig() }
                var ctxLimit by remember { mutableStateOf("") }
                // Seed the field from the server value only while it's still empty — once loaded (or
                // the user starts typing) a later async config/read reply must not clobber the input.
                LaunchedEffect(cm.serverContextLimit.value) {
                    if (cm.serverContextLimit.value.isNotBlank() && ctxLimit.isBlank())
                        ctxLimit = cm.serverContextLimit.value
                }
                OutlinedTextField(
                    value = ctxLimit, onValueChange = { ctxLimit = it.filter(Char::isDigit) },
                    label = { Text("Context limit (tokens)") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    trailingIcon = {
                        val cur = cm.serverContextLimit.value
                        if (ctxLimit.isNotBlank() && ctxLimit != cur)
                            TextButton(onClick = { cm.setServerConfig("GOOSE_CONTEXT_LIMIT", ctxLimit) }) { Text("Save") }
                    },
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
                SettingCaption("goose's working window before it compacts. Keep it BELOW the model's " +
                    "context size (leave room to reply) — and the fast model must be at least this " +
                    "big to compact. Takes effect on new chats. Fast model: " +
                    cm.serverFastModel.value.ifBlank { "—" } + ".")
            }

            SettingsSection("Vision") {
                var describe by remember { mutableStateOf(cm.store.describeImages) }
                SettingsSwitchRow("Describe images before sending", describe) {
                    describe = it; cm.store.describeImages = it
                }
                SettingCaption("Normally OFF: the server's proxy already turns images into " +
                    "text for every client. Turn this on only if this goose does not run that " +
                    "proxy — then the image goes into the prompt as-is, which works only if " +
                    "the chat model can see, and one that can't will answer around it rather " +
                    "than say so.")
            }

            SettingsSection("Speech") {
                SettingsSwitchRow("Speak replies aloud", cm.speakReplies.value) { cm.setSpeakReplies(it) }
                SettingCaption("Read each finished reply with text-to-speech.")

                // The box's own speech models -- what does the listening and the talking, as
                // opposed to which model thinks. Grouse calls LocalAI directly for both: goose has no
                // TTS at all, and its dictation methods transcribe for goose's own UI rather than
                // returning text to an ACP client.
                var srvTts by remember { mutableStateOf(cm.store.serverTts) }
                var srvStt by remember { mutableStateOf(cm.store.serverStt) }
                var ttsM by remember { mutableStateOf(cm.store.ttsModel) }
                var sttM by remember { mutableStateOf(cm.store.sttModel) }
                var laUrl by remember { mutableStateOf(cm.store.localAiUrl) }
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Text("Speech models", style = MaterialTheme.typography.titleSmall)
                SettingsSwitchRow("Speak with LocalAI", srvTts) { srvTts = it; cm.store.serverTts = it }
                if (srvTts) OutlinedTextField(ttsM, { ttsM = it; cm.store.ttsModel = it },
                    label = { Text("TTS model") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
                SettingsSwitchRow("Transcribe with LocalAI", srvStt) { srvStt = it; cm.store.serverStt = it }
                if (srvStt) OutlinedTextField(sttM, { sttM = it; cm.store.sttModel = it },
                    label = { Text("STT model") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
                if (srvTts || srvStt) {
                    OutlinedTextField(laUrl, { laUrl = it; cm.store.localAiUrl = it },
                        label = { Text("LocalAI URL") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
                    SettingCaption("Off by default — Android's own speech works offline and streams " +
                        "words as you say them. LocalAI sounds better and transcribes better, but " +
                        "needs the network and only shows the text once you stop talking. If a " +
                        "request fails, replies fall back to the device voice.")
                }
                HorizontalDivider(Modifier.padding(vertical = 8.dp))

                // The per-voice-turn model override that stood here is GONE. It existed to dodge
                // self-hosted latency by running voice turns on a faster cloud model; the default
                // chat model IS a fast cloud model now, so it was a third place to set a model
                // that agreed with the other two. Removed rather than hidden -- SecureStore clears
                // any value a previous install left behind, so nobody keeps an invisible override.
                HorizontalDivider(Modifier.padding(top = 8.dp))
                SettingsNavRow("Set Grouse as device assistant",
                    "Assist gesture / power-button hold opens voice Grouse (read-only).") {
                    runCatching {
                        ctx.startActivity(android.content.Intent(
                            android.provider.Settings.ACTION_VOICE_INPUT_SETTINGS)
                            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                }
            }


            Spacer(Modifier.height(28.dp))
        }
    }
}
