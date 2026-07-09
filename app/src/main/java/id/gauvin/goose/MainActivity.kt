package id.gauvin.goose

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { App() } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App(vm: ChatViewModel = viewModel()) {
    var host by remember { mutableStateOf("192.168.1.5") }
    var port by remember { mutableStateOf("3285") }
    var key by remember { mutableStateOf("") }
    var connected by remember { mutableStateOf(false) }
    var input by remember { mutableStateOf("") }

    Scaffold(topBar = { TopAppBar(title = { Text("Goose · ${vm.status.value}") }) }) { pad ->
        Column(
            Modifier.padding(pad).padding(horizontal = 12.dp).fillMaxSize()
        ) {
            if (!connected) {
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(host, { host = it }, label = { Text("host") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth())
                OutlinedTextField(port, { port = it }, label = { Text("port") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth())
                OutlinedTextField(key, { key = it }, label = { Text("X-Secret-Key") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = { vm.connect(host.trim(), port.trim(), key.trim()); connected = true },
                    enabled = key.isNotBlank(),
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Connect") }
            } else {
                LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
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
