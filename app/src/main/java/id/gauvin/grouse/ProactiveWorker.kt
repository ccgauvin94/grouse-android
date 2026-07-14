package id.gauvin.grouse

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Runs a scheduled proactive check headlessly: connect, ask goose the briefing prompt, collect
 * the reply, and notify only if it's not "all clear". Tool-approval requests are REJECTED, so an
 * unattended run can only use auto-approved read tools — it can read and report, never act.
 */
class ProactiveWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val store = SecureStore(applicationContext)
        if (!store.hasKey()) return Result.success()
        val url = "wss://${store.host}:${store.port}/acp"
        val reply = withTimeoutOrNull(TIMEOUT_MS) {
            runPrompt(url, store.secretKey, store.proactivePrompt, store)
        }.orEmpty().trim()
        if (reply.isNotEmpty() && !reply.take(40).lowercase().contains("all clear")) {
            Notifier(applicationContext).postProactive(reply)
        }
        return Result.success()
    }

    private suspend fun runPrompt(url: String, key: String, prompt: String, store: SecureStore): String =
        suspendCancellableCoroutine { cont ->
            val sb = StringBuilder()
            var done = false
            lateinit var client: AcpClient
            fun finish(result: String) {
                if (done) return
                done = true
                runCatching { client.close() }
                if (cont.isActive) cont.resume(result)
            }
            client = AcpClient(url, key) { ev ->
                when (ev) {
                    is AcpEvent.Ready -> { store.lastSessionId = ev.sessionId; client.sendPrompt(prompt) }
                    is AcpEvent.AgentChunk -> sb.append(ev.text)
                    is AcpEvent.TurnDone -> finish(sb.toString())
                    is AcpEvent.Error -> finish(sb.toString())
                    is AcpEvent.Permission -> client.respondPermission(ev.toolCallId, null) // read-only
                    else -> {}
                }
            }
            client.connect()
            cont.invokeOnCancellation { runCatching { client.close() } }
        }

    companion object { private const val TIMEOUT_MS = 5 * 60_000L }
}
