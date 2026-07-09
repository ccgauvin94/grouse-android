package id.gauvin.goose

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import id.gauvin.goose.ui.theme.GooseTheme

class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val cm = ConnectionManager.get(this)
        setContent {
            GooseTheme(dynamicColor = cm.dynamicColor.value) {
                AppRoot(this@MainActivity, cm)
            }
        }
    }
}

@Composable
fun AppRoot(activity: FragmentActivity, cm: ConnectionManager) {
    // Lock the app behind biometrics whenever a key is stored and a biometric is enrolled.
    val needsLock = remember { cm.configured && Biometric.available(activity) }
    var unlocked by rememberSaveable { mutableStateOf(!needsLock) }
    var error by remember { mutableStateOf<String?>(null) }

    fun authenticate() = Biometric.prompt(
        activity,
        onSuccess = { unlocked = true; error = null },
        onFail = { error = it },
    )

    LaunchedEffect(Unit) { if (!unlocked) authenticate() }

    if (!unlocked) {
        LockScreen(error) { authenticate() }
        return
    }

    // Ask for notification permission so backgrounded turns can alert (API 33+).
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        val notifPerm = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission()) {}
        LaunchedEffect(Unit) { notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS) }
    }

    val nav = rememberNavController()
    LaunchedEffect(Unit) { cm.connectSaved() }   // auto-connect once unlocked
    NavHost(nav, startDestination = if (cm.configured) "chat" else "connect") {
        composable("connect") {
            ConnectScreen(cm) { nav.navigate("chat") { popUpTo("connect") { inclusive = true } } }
        }
        composable("chat") { ChatScreen(cm, nav) }
        composable("sessions") { SessionsScreen(cm, nav) }
        composable("settings") { SettingsScreen(cm, nav) }
    }
}

@Composable
fun LockScreen(error: String?, onUnlock: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Filled.Lock, contentDescription = null, modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(10.dp))
            Text("Goose is locked", style = MaterialTheme.typography.titleMedium)
            if (error != null) {
                Spacer(Modifier.height(4.dp))
                Text(error, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error)
            }
            Spacer(Modifier.height(14.dp))
            Button(onClick = onUnlock) { Text("Unlock") }
        }
    }
}
