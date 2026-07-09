package id.gauvin.goose

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Psychology
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
import androidx.lifecycle.viewmodel.compose.viewModel
import com.halilibo.richtext.markdown.Markdown
import com.halilibo.richtext.ui.material3.RichText
import id.gauvin.goose.ui.theme.GooseTheme

// Config knobs we persist + re-apply on reconnect, in display order.
private val CONFIG_IDS = listOf("provider", "model", "mode", "thinking_effort")

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { GooseTheme { App() } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App(vm: ChatViewModel = viewModel()) {
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("goose", Context.MODE_PRIVATE) }

    var host by remember { mutableStateOf(prefs.getString("host", "192.168.1.5") ?: "") }
    var port by remember { mutableStateOf(prefs.getString("port", "3285") ?: "") }
    var key by remember { mutableStateOf(prefs.getString("key", "") ?: "") }
    var connected by remember { mutableStateOf(false) }
    var showConfig by remember { mutableStateOf(false) }
    var showSessions by remember { mutableStateOf(false) }
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    fun savedOptions(): Map<String, String> =
        CONFIG_IDS.mapNotNull { id -> prefs.getString("opt_$id", null)?.let { id to it } }.toMap()

    // auto-connect on launch if we already have a saved key
    LaunchedEffect(Unit) {
        if (key.isNotBlank() && !connected) {
            vm.connect(host.trim(), port.trim(), key.trim(), savedOptions()); connected = true
        }
    }
    // keep the chat pinned to the newest message
    LaunchedEffect(vm.messages.size) {
        if (vm.messages.isNotEmpty()) listState.animateScrollToItem(vm.messages.lastIndex)
    }
    // Android drops the socket when we background — reconnect (and resume the session) on return.
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner, connected) {
        val obs = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && connected) vm.ensureConnected()
        }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }

    fun doConnect() {
        prefs.edit()
            .putString("host", host.trim())
            .putString("port", port.trim())
            .putString("key", key.trim())
            .apply()
        vm.connect(host.trim(), port.trim(), key.trim(), savedOptions())
        connected = true
    }

    fun pick(id: String, value: String) {
        prefs.edit().putString("opt_$id", value).apply()   // persist across reconnects
        vm.setOption(id, value)
    }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Goose · ${vm.status.value}") },
            actions = {
                if (connected) {
                    IconButton(onClick = {
                        showSessions = !showSessions
                        if (showSessions) vm.listSessions()
                    }) { Icon(Icons.Filled.History, contentDescription = "sessions") }
                    IconButton(onClick = { showConfig = !showConfig }) {
                        Icon(Icons.Filled.Tune, contentDescription = "model settings")
                    }
                    TextButton(onClick = { connected = false }) { Text("Edit") }
                }
            }
        )
    }) { pad ->
        Column(Modifier.padding(pad).padding(horizontal = 12.dp).fillMaxSize()) {
            if (!connected) {
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(host, { host = it }, label = { Text("host") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth())
                OutlinedTextField(port, { port = it }, label = { Text("port") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth())
                OutlinedTextField(key, { key = it }, label = { Text("X-Secret-Key") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                Button(onClick = { doConnect() }, enabled = key.isNotBlank(),
                    modifier = Modifier.fillMaxWidth()) { Text("Connect") }
            } else if (showSessions) {
                SessionsScreen(
                    sessions = vm.sessions.value,
                    onNew = { vm.newSession(); showSessions = false },
                    onOpen = { id -> vm.openSession(id); showSessions = false },
                )
            } else {
                ModelBar(vm.config.value, showConfig) { showConfig = !showConfig }
                if (showConfig) ConfigPanel(vm.config.value, ::pick)
                LazyColumn(state = listState, modifier = Modifier.weight(1f).fillMaxWidth()) {
                    items(vm.messages) { m -> MessageBubble(m) }
                    if (vm.busy.value) item { TypingIndicator() }
                }
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(input, { input = it }, modifier = Modifier.weight(1f),
                        placeholder = { Text("message goose…") })
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = { if (input.isNotBlank()) { vm.send(input.trim()); input = "" } }) { Text("Send") }
                }
            }
        }
    }
}

/** List of resumable server-side sessions + a "new chat" action. */
@Composable
fun SessionsScreen(sessions: List<SessionInfo>, onNew: () -> Unit, onOpen: (String) -> Unit) {
    LazyColumn(Modifier.fillMaxSize().padding(top = 8.dp)) {
        item {
            Card(Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable { onNew() }) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Add, contentDescription = null)
                    Spacer(Modifier.width(10.dp))
                    Text("New chat", style = MaterialTheme.typography.titleMedium)
                }
            }
        }
        if (sessions.isEmpty()) item {
            Text("no saved sessions", style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(12.dp))
        }
        items(sessions) { s ->
            Card(Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable { onOpen(s.sessionId) }) {
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

/** Always-visible one-line summary of the active model; tap to open/close the pickers. */
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

/** The provider/model/mode/thinking_effort dropdowns, ordered, shown above the chat. */
@Composable
fun ConfigPanel(options: List<ConfigOption>, onPick: (String, String) -> Unit) {
    if (options.isEmpty()) {
        Text("loading model options…", style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(vertical = 8.dp))
        return
    }
    val byId = options.associateBy { it.id }
    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        for (id in CONFIG_IDS) byId[id]?.let { ConfigDropdown(it, onPick) }
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
                DropdownMenuItem(
                    text = { Text(c.label) },
                    onClick = { expanded = false; onPick(opt.id, c.value) }
                )
            }
        }
    }
}

// ---- Chat message rendering -------------------------------------------------

/** Renders one message by role: user / assistant / thought / tool / error. */
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
    // goose emits markdown; render it (headers, bold, lists, fenced code).
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
        ) {
            Box(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) { Markdownish(text) }
        }
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
                if (expanded) Icons.Filled.KeyboardArrowDown else Icons.Filled.KeyboardArrowRight,
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
            Text(
                text, style = MaterialTheme.typography.bodySmall.copy(fontStyle = FontStyle.Italic),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 24.dp, bottom = 4.dp)
            )
        }
    }
}

@Composable
private fun ToolChip(title: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            shape = RoundedCornerShape(8.dp)
        ) {
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
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
    ) {
        Text(text, style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(10.dp))
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
