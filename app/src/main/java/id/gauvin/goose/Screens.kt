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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.KeyboardArrowDown
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavController
import com.halilibo.richtext.markdown.Markdown
import com.halilibo.richtext.ui.material3.RichText

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

    fun doSend() {
        if (input.isBlank() && attachments.isEmpty()) return
        cm.send(input.trim(), attachments.toList())
        input = ""; attachments.clear()
    }

    LaunchedEffect(cm.messages.size, cm.busy.value) {
        val n = cm.messages.size + if (cm.busy.value) 1 else 0
        if (n > 0) listState.animateScrollToItem(n - 1)
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
            ModelBar(cm.config.value, showConfig) { showConfig = !showConfig }
            if (showConfig) ConfigPanel(cm.config.value, cm.showAllProviders.value,
                cm.configuredProviders, cm::setOption)
            LazyColumn(state = listState, modifier = Modifier.weight(1f).fillMaxWidth()) {
                items(cm.messages) { m -> MessageBubble(m) }
                if (cm.busy.value) item { TypingIndicator() }
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

private fun prettyOption(raw: String) = when (raw) {
    "allow_once" -> "Allow once"
    "allow_always" -> "Always allow"
    "reject_once" -> "Reject"
    "reject_always" -> "Always reject"
    else -> raw.replace('_', ' ').replaceFirstChar { it.uppercase() }
}

private fun readImage(context: Context, uri: Uri): ImageBlock? = runCatching {
    val mime = context.contentResolver.getType(uri) ?: "image/jpeg"
    val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
    ImageBlock(mime, Base64.encodeToString(bytes, Base64.NO_WRAP))
}.getOrNull()

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

// ---- Model pickers ----------------------------------------------------------

@Composable
fun ModelBar(options: List<ConfigOption>, expanded: Boolean, onToggle: () -> Unit) {
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
            Text(summary, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(if (expanded) "▲" else "▼", style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
fun ConfigPanel(
    options: List<ConfigOption>,
    showAllProviders: Boolean,
    configured: Set<String>,
    onPick: (String, String) -> Unit,
) {
    if (options.isEmpty()) {
        Text("loading model options…", style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(vertical = 8.dp))
        return
    }
    val byId = options.associateBy { it.id }
    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        for (id in CONFIG_IDS) byId[id]?.let { opt ->
            // Hide unconfigured providers unless the user opted into the full catalog.
            val shown = if (id == "provider" && !showAllProviders)
                opt.copy(choices = opt.choices.filter { it.value in configured || it.value == opt.currentValue })
            else opt
            ConfigDropdown(shown, onPick)
        }
        HorizontalDivider(Modifier.padding(top = 8.dp))
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
        else -> AssistantBubble(m.text)
    }
}

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
