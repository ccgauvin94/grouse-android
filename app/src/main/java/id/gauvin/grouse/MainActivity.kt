package id.gauvin.grouse

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.core.content.IntentCompat
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import id.gauvin.grouse.ui.theme.GooseTheme

class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val cm = ConnectionManager.get(this)
        handleEntry(intent, cm)
        setContent {
            GooseTheme(dynamicColor = cm.dynamicColor.value) {
                AppRoot(this@MainActivity, cm)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleEntry(intent, ConnectionManager.get(this))
    }

    /** Route share-sheet / shortcut / tile intents into ConnectionManager for the UI to pick up. */
    private fun handleEntry(intent: Intent?, cm: ConnectionManager) {
        when (intent?.action) {
            Intent.ACTION_SEND -> {
                intent.getStringExtra(Intent.EXTRA_TEXT)?.let { cm.pendingShareText.value = it }
                IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                    ?.let { readImage(this, it)?.let(cm.pendingShareImages::add) }
            }
            Intent.ACTION_SEND_MULTIPLE ->
                IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                    ?.forEach { readImage(this, it)?.let(cm.pendingShareImages::add) }
            ACTION_NEW_CHAT -> cm.pendingNewChat.value = true
            ACTION_OPEN_SESSION ->
                intent.getStringExtra(EXTRA_SESSION_ID)?.let { cm.pendingOpenSession.value = it }
        }
    }

    companion object {
        const val ACTION_NEW_CHAT = "id.gauvin.grouse.NEW_CHAT"
        const val ACTION_OPEN_SESSION = "id.gauvin.grouse.OPEN_SESSION"
        const val EXTRA_SESSION_ID = "id.gauvin.grouse.extra.SESSION_ID"
    }
}

@Composable
fun AppRoot(activity: FragmentActivity, cm: ConnectionManager) {
    // Lock the app behind biometrics only when the user has opted in (Settings › Security,
    // default off) AND a key is stored AND an authenticator is enrolled.
    val needsLock = remember { cm.store.biometricLock && cm.configured && Biometric.available(activity) }
    // Plain `remember` (NOT rememberSaveable): a saved `unlocked=true` would survive process death
    // and let the app reopen without a prompt. Any recreation must re-lock.
    var unlocked by remember { mutableStateOf(!needsLock) }
    var error by remember { mutableStateOf<String?>(null) }
    // True while a prompt is on screen, so a device-credential screen (which stops our activity)
    // doesn't trigger a re-lock / re-prompt loop.
    var authenticating by remember { mutableStateOf(false) }

    fun authenticate() {
        if (authenticating) return
        authenticating = true
        Biometric.prompt(
            activity,
            onSuccess = { authenticating = false; unlocked = true; error = null },
            onFail = { authenticating = false; error = it },
        )
    }

    // Re-lock when backgrounded; (re)prompt when foregrounded while locked. On observer
    // registration the Lifecycle replays up to the current state, so this fires the initial
    // cold-start prompt too.
    val lockOwner = LocalLifecycleOwner.current
    DisposableEffect(lockOwner, needsLock) {
        val obs = LifecycleEventObserver { _, e ->
            if (!needsLock) return@LifecycleEventObserver
            when (e) {
                Lifecycle.Event.ON_STOP -> if (!authenticating) unlocked = false
                Lifecycle.Event.ON_RESUME -> if (!unlocked && !authenticating) authenticate()
                else -> {}
            }
        }
        lockOwner.lifecycle.addObserver(obs)
        onDispose { lockOwner.lifecycle.removeObserver(obs) }
    }

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

    // Ask for mic up front: push-to-talk needs it, and the assistant VoiceInteractionSession
    // can't request runtime permissions itself — so the app must obtain it through the Activity.
    val micPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) {
        if (ContextCompat.checkSelfPermission(activity, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            micPerm.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    val nav = rememberNavController()
    LaunchedEffect(Unit) { cm.connectHome() }   // auto-connect + land on the Assistant thread
    // "New chat" from a shortcut/tile: start fresh and land on the chat screen.
    LaunchedEffect(cm.pendingNewChat.value) {
        if (cm.pendingNewChat.value && cm.configured) {
            cm.pendingNewChat.value = false
            cm.newSession()
            nav.navigate("chat") { popUpTo("chat") { inclusive = true } }
        }
    }
    // A finished-turn notification tap: open that session and land on the chat screen.
    LaunchedEffect(cm.pendingOpenSession.value) {
        cm.pendingOpenSession.value?.let { sid ->
            if (cm.configured) {
                cm.pendingOpenSession.value = null
                cm.openSession(sid)
                nav.navigate("chat") { popUpTo("chat") { inclusive = true } }
            }
        }
    }
    NavHost(nav, startDestination = if (cm.configured) "chat" else "connect") {
        composable("connect") {
            ConnectScreen(cm) { nav.navigate("chat") { popUpTo("connect") { inclusive = true } } }
        }
        composable("chat") { ChatScreen(cm, nav) }
        composable("sessions") { SessionsScreen(cm, nav) }
        composable("settings") { SettingsScreen(cm, nav) }
        composable("extensions") { ExtensionsScreen(cm, nav) }
        composable("proactive") { ProactiveScreen(cm, nav) }
    }
}

@Composable
fun LockScreen(error: String?, onUnlock: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Filled.Lock, contentDescription = null, modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(10.dp))
            Text("Grouse is locked", style = MaterialTheme.typography.titleMedium)
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
