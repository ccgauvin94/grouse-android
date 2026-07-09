package id.gauvin.goose

import android.content.Context
import android.net.Uri
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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

private val CONFIG_IDS = listOf("provider", "model", "mode", "thinking_effort")

// ---- Connect (onboarding) ---------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectScreen(cm: ConnectionManager, onConnected: () -> Unit) {
    var host by remember { mutableStateOf(cm.store.host) }
    var port by remember { mutableStateOf(cm.store.port) }
    var key by remember { mutableStateOf("") }
    Scaffold(topBar = { TopAppBar(title = { Text("Connect to Goose") }) }) { pad ->
        Column(Modifier.padding(pad).padding(16.dp).fillMaxWidth()) {
            OutlinedTextField(host, { host = it }, label = { Text("host") }, singleLine = true,
                modifier = Modifier.fillMaxWidth())
            OutlinedTextField(port, { port = it }, label = { Text("port") }, singleLine = true,
                modifier = Modifier.fillMaxWidth())
            OutlinedTextField(key, { key = it }, label = { Text("X-Secret-Key") }, singleLine = true,
                modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = { cm.connect(host.trim(), port.trim(), key.trim()); onConnected() },
                enabled = key.isNotBlank(), modifier = Modifier.fillMaxWidth()
            ) { Text("Connect") }
        }
    }
}

// ---- Chat -------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(cm: ConnectionManager, nav: NavController) {
    val ctx = LocalContext.current
    var showConfig by remember { mutableStateOf(false) }
    var input by remember { mutableStateOf("") }
    val attachments = remember { mutableStateListOf<ImageBlock>() }
    val listState = rememberLazyListState()

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? -> uri?.let { readImage(ctx, it)?.let(attachments::add) } }

    val voice = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { res ->
        res.data?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)
            ?.firstOrNull()?.let { input = (input.trim() + " " + it).trim() }
    }

    // Content shared into Goose from another app.
    LaunchedEffect(cm.pendingShareText.value) {
        cm.pendingShareText.value?.let { input = it; cm.pendingShareText.value = null }
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
    val atBottom by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()
            last == null || last.index >= info.totalItemsCount - 1
        }
    }

    fun reallySend() {
        cm.send(input.trim(), attachments.toList())
        input = ""; attachments.clear()
    }
    fun doSend() {
        if (input.isBlank() && attachments.isEmpty()) return
        // Sending images to a non-vision model (esp. LocalAI without mmproj) hangs the session.
        if (attachments.isNotEmpty() && !isLikelyVisionModel(currentModel)) { showVisionWarn = true; return }
        reallySend()
    }

    // Follow new content only when already pinned to the bottom (don't yank the user up-scroll).
    val lastLen = cm.messages.lastOrNull()?.text?.length ?: 0
    LaunchedEffect(cm.messages.size, lastLen, cm.busy.value) {
        val total = cm.messages.size + if (cm.busy.value) 1 else 0
        if (total > 0 && atBottom) listState.animateScrollToItem(total - 1)
    }
    // Reconnect (resuming the session) when we return to the foreground.
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) cm.ensureConnected() }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }

    cm.permissions.firstOrNull()?.let { req ->
        PermissionSheet(req, onChoose = { cm.answerPermission(req, it) })
    }

    if (showVisionWarn) AlertDialog(
        onDismissRequest = { showVisionWarn = false },
        title = { Text("Model may not support images") },
        text = { Text("“$currentModel” probably can't read images and may get stuck on them — " +
            "even later text messages. If that happens, start a New chat. Send anyway?") },
        confirmButton = { TextButton(onClick = { showVisionWarn = false; reallySend() }) { Text("Send anyway") } },
        dismissButton = { TextButton(onClick = { showVisionWarn = false }) { Text("Cancel") } },
    )

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Goose · ${cm.status.value}") },
            actions = {
                IconButton(onClick = { cm.listSessions(); nav.navigate("sessions") }) {
                    Icon(Icons.Filled.History, contentDescription = "sessions")
                }
                IconButton(onClick = { showConfig = !showConfig }) {
                    Icon(Icons.Filled.Tune, contentDescription = "model")
                }
                IconButton(onClick = { nav.navigate("settings") }) {
                    Icon(Icons.Filled.Settings, contentDescription = "settings")
                }
            }
        )
    }) { pad ->
        Column(Modifier.padding(pad).padding(horizontal = 12.dp).fillMaxSize()) {
            ModelBar(cm.config.value, cm.usage.value, showConfig) { showConfig = !showConfig }
            if (showConfig) ConfigPanel(cm.config.value, cm.showAllProviders.value,
                cm.configuredProviders, cm.knownModels.value, cm::setOption, cm::compact)
            Box(Modifier.weight(1f).fillMaxWidth()) {
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                    items(cm.messages) { m -> MessageBubble(m) }
                    if (cm.busy.value) item { TypingIndicator() }
                }
                if (!atBottom) SmallFloatingActionButton(
                    onClick = {
                        val total = cm.messages.size + if (cm.busy.value) 1 else 0
                        scope.launch { listState.animateScrollToItem((total - 1).coerceAtLeast(0)) }
                    },
                    modifier = Modifier.align(Alignment.BottomEnd).padding(10.dp)
                ) { Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "scroll to bottom") }
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

            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = {
                    picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }) { Icon(Icons.Filled.Image, contentDescription = "attach image") }
                IconButton(onClick = {
                    val i = android.content.Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                        putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                            android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                        putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT, "Speak to goose")
                    }
                    runCatching { voice.launch(i) }
                }) { Icon(Icons.Filled.Mic, contentDescription = "voice input") }
                OutlinedTextField(input, { input = it }, modifier = Modifier.weight(1f),
                    placeholder = { Text("message goose…") })
                Spacer(Modifier.width(6.dp))
                if (cm.busy.value) {
                    FilledIconButton(onClick = { cm.cancel() }) {
                        Icon(Icons.Filled.Stop, contentDescription = "stop")
                    }
                } else {
                    FilledIconButton(onClick = { doSend() }) {
                        Icon(Icons.Filled.Send, contentDescription = "send")
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
                Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()) {
                    Text(req.detail, style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(10.dp))
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionsScreen(cm: ConnectionManager, nav: NavController) {
    LaunchedEffect(Unit) { cm.listSessions() }
    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Sessions") },
            navigationIcon = {
                IconButton(onClick = { nav.popBackStack() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "back")
                }
            }
        )
    }) { pad ->
        LazyColumn(Modifier.padding(pad).padding(horizontal = 12.dp).fillMaxSize()) {
            item {
                Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)
                    .clickable { cm.newSession(); nav.popBackStack() }) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Add, contentDescription = null)
                        Spacer(Modifier.width(10.dp))
                        Text("New chat", style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
            if (cm.sessions.value.isEmpty()) item {
                Text("no saved sessions", style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(12.dp))
            }
            items(cm.sessions.value) { s ->
                Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)
                    .clickable { cm.openSession(s.sessionId); nav.popBackStack() }) {
                    Column(Modifier.padding(14.dp)) {
                        Text(s.title.ifBlank { s.sessionId }, style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(2.dp))
                        val when_ = s.updatedAt.take(16).replace('T', ' ')
                        val bits = listOf("${s.messageCount} msgs", s.model, when_).filter { it.isNotBlank() }
                        Text(bits.joinToString("  ·  "), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

// ---- Settings ---------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(cm: ConnectionManager, nav: NavController) {
    var host by remember { mutableStateOf(cm.store.host) }
    var port by remember { mutableStateOf(cm.store.port) }
    var newKey by remember { mutableStateOf("") }
    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Settings") },
            navigationIcon = {
                IconButton(onClick = { nav.popBackStack() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "back")
                }
            }
        )
    }) { pad ->
        Column(Modifier.padding(pad).padding(16.dp).fillMaxWidth()) {
            Text("Server", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(host, { host = it }, label = { Text("host") }, singleLine = true,
                modifier = Modifier.fillMaxWidth())
            OutlinedTextField(port, { port = it }, label = { Text("port") }, singleLine = true,
                modifier = Modifier.fillMaxWidth())
            OutlinedTextField(newKey, { newKey = it }, label = { Text("replace X-Secret-Key (optional)") },
                singleLine = true, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = {
                    val key = newKey.trim().ifBlank { cm.store.secretKey }
                    cm.connect(host.trim(), port.trim(), key)
                    nav.popBackStack()
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Save & reconnect") }

            Spacer(Modifier.height(24.dp))
            Text("Assistant", style = MaterialTheme.typography.titleMedium)
            OutlinedButton(onClick = { nav.navigate("proactive") }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                Text("Proactive checks")
            }
            Text("Scheduled read-only check-ins that notify you when something needs attention.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)

            Spacer(Modifier.height(24.dp))
            Text("Extensions", style = MaterialTheme.typography.titleMedium)
            OutlinedButton(onClick = { nav.navigate("extensions") }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                Text("Manage extensions")
            }
            Text("Enable/disable goose's tools to control context per new chat.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)

            Spacer(Modifier.height(24.dp))
            Text("Models", style = MaterialTheme.typography.titleMedium)
            var showAll by remember { mutableStateOf(cm.showAllProviders.value) }
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Show all providers", Modifier.weight(1f))
                Switch(checked = showAll, onCheckedChange = { showAll = it; cm.setShowAllProviders(it) })
            }
            Text("Off shows only providers set up on your goose (openai, openrouter). " +
                "Turn on to pick from goose's full catalog.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)

            Spacer(Modifier.height(24.dp))
            Text("Appearance", style = MaterialTheme.typography.titleMedium)
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Material You dynamic color", Modifier.weight(1f))
                Switch(checked = cm.dynamicColor.value, onCheckedChange = { cm.setDynamicColor(it) })
            }
            Text("Off uses the built-in goose-green palette.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)

            Spacer(Modifier.height(24.dp))
            Text("Background", style = MaterialTheme.typography.titleMedium)
            var persistent by remember { mutableStateOf(cm.persistent) }
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Keep connection alive", Modifier.weight(1f))
                Switch(checked = persistent, onCheckedChange = { persistent = it; cm.setPersistent(it) })
            }
            Text("On: stay connected in the background (a persistent notification, more battery). " +
                "Off: connect while active; you still get a notification when a backgrounded turn finishes.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
        }
    }
}

// ---- Proactive assistant ----------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProactiveScreen(cm: ConnectionManager, nav: NavController) {
    val ctx = LocalContext.current
    val store = cm.store
    var enabled by remember { mutableStateOf(store.proactiveEnabled) }
    var time by remember { mutableStateOf(store.proactiveTime) }
    var prompt by remember { mutableStateOf(store.proactivePrompt) }

    fun save() {
        store.proactiveEnabled = enabled
        store.proactiveTime = time.trim()
        store.proactivePrompt = prompt
        ProactiveScheduler.reschedule(ctx)
    }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Proactive assistant") },
            navigationIcon = {
                IconButton(onClick = { nav.popBackStack() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "back")
                }
            }
        )
    }) { pad ->
        Column(Modifier.padding(pad).padding(16.dp).fillMaxSize().verticalScroll(rememberScrollState())) {
            Text("goose checks in on a schedule and notifies you only when something needs " +
                "attention. Runs read-only in the background — it can look, not act.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
            Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Enabled", Modifier.weight(1f))
                Switch(checked = enabled, onCheckedChange = { enabled = it })
            }
            OutlinedTextField(time, { time = it }, label = { Text("Time (HH:MM, 24h)") },
                singleLine = true, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(prompt, { prompt = it }, label = { Text("Briefing prompt") },
                minLines = 4, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(12.dp))
            Button(onClick = { save() }, modifier = Modifier.fillMaxWidth()) { Text("Save") }
            OutlinedButton(onClick = { save(); ProactiveScheduler.runNow(ctx) },
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) { Text("Run now (test)") }
            Text("A test run may take a minute; you'll get a notification if there's something to report.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(top = 6.dp))
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
            title = { Text("Extensions") },
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
                    HorizontalDivider()
                }
                if (cm.extensions.value.isEmpty() && !cm.extensionsBusy.value) item {
                    Text("Couldn't load extensions. This needs the full goose agent server (:3284).",
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
        o.choices.firstOrNull { it.value == o.currentValue }?.label ?: o.currentValue
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
            Text(if (expanded) "▲" else "▼", style = MaterialTheme.typography.bodyMedium)
        }
    }
}

private fun fmtTokens(n: Int): String = when {
    n >= 1_000_000 -> "%.1fM".format(n / 1_000_000.0)
    n >= 1_000 -> "${n / 1000}k"
    else -> "$n"
}

@Composable
fun ConfigPanel(
    options: List<ConfigOption>,
    showAllProviders: Boolean,
    configured: Set<String>,
    knownModels: Set<String>,
    onPick: (String, String) -> Unit,
    onCompact: () -> Unit,
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
                // Editable: goose only lists "featured" models + a "current" placeholder, so
                // typing an exact slug (e.g. z-ai/glm-5.2) is the only way to pick many models.
                "model" -> ModelField(opt, knownModels, onPick)
                // Hide unconfigured providers unless the user opted into the full catalog.
                "provider" -> ConfigDropdown(
                    if (showAllProviders) opt
                    else opt.copy(choices = opt.choices.filter { it.value in configured || it.value == opt.currentValue }),
                    onPick)
                else -> ConfigDropdown(opt, onPick)
            }
        }
        OutlinedButton(onClick = onCompact, modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
            Text("Compact conversation")
        }
        HorizontalDivider(Modifier.padding(top = 8.dp))
    }
}

/** Editable model field: type any model id or pick a featured/remembered one from the menu. */
@Composable
fun ModelField(opt: ConfigOption, knownModels: Set<String>, onPick: (String, String) -> Unit) {
    var text by remember(opt.currentValue) { mutableStateOf(opt.currentValue) }
    var menu by remember { mutableStateOf(false) }
    val dirty = text.trim() != opt.currentValue && text.isNotBlank()
    // goose's featured list + models we've seen before (so e.g. z-ai/glm-5.2 stays selectable).
    val featured = opt.choices.map { it.value }
    val entries = (featured + knownModels.filter { it !in featured }).distinct()
    Box(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        OutlinedTextField(
            value = text, onValueChange = { text = it }, singleLine = true,
            label = { Text("model") },
            placeholder = { Text("type a model id, e.g. z-ai/glm-5.2") },
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Done),
            keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                onDone = { if (dirty) onPick("model", text.trim()) }),
            trailingIcon = {
                Row {
                    if (dirty) IconButton(onClick = { onPick("model", text.trim()) }) {
                        Icon(Icons.Filled.Check, contentDescription = "set model")
                    }
                    IconButton(onClick = { menu = true }) {
                        Icon(Icons.Filled.ArrowDropDown, contentDescription = "model list")
                    }
                }
            },
            modifier = Modifier.fillMaxWidth()
        )
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            entries.forEach { v ->
                val label = if (v == "current") "Provider default"
                    else opt.choices.firstOrNull { it.value == v }?.label ?: v
                DropdownMenuItem(text = { Text(label) },
                    onClick = { menu = false; text = v; onPick("model", v) })
            }
        }
    }
}

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
fun MessageBubble(m: ChatMessage) {
    when (m.role) {
        "user" -> UserBubble(m.text)
        "thought" -> ThoughtBubble(m.text)
        "tool" -> ToolChip(m.text)
        "error" -> ErrorBubble(m.text)
        "chart" -> ChartView(m.text)
        else -> AssistantBubble(m.text)
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

@Composable
private fun UserBubble(text: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.End) {
        Surface(
            color = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            shape = RoundedCornerShape(16.dp, 16.dp, 4.dp, 16.dp),
            modifier = Modifier.widthIn(max = 320.dp)
        ) { Box(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) { Markdownish(text) } }
    }
}

@Composable
private fun AssistantBubble(text: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Box(Modifier.padding(horizontal = 2.dp, vertical = 4.dp)) { Markdownish(text) }
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

@Composable
private fun ToolChip(title: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Surface(color = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant, shape = RoundedCornerShape(8.dp)) {
            Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Build, contentDescription = null, modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(6.dp))
                Text(title, style = MaterialTheme.typography.labelMedium)
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
        Text("goose is thinking…", style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.outline)
    }
}
