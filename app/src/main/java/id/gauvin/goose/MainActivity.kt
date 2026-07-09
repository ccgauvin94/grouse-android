package id.gauvin.goose

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel

// Config knobs we persist + re-apply on reconnect, in display order.
private val CONFIG_IDS = listOf("provider", "model", "mode", "thinking_effort")

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { App() } }
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
            } else {
                if (showConfig) ConfigPanel(vm.config.value, ::pick)
                LazyColumn(state = listState, modifier = Modifier.weight(1f).fillMaxWidth()) {
                    items(vm.messages) { m ->
                        Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                            Column(Modifier.padding(10.dp)) {
                                Text(m.role, style = MaterialTheme.typography.labelSmall)
                                Text(m.text, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
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
