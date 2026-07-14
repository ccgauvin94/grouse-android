package id.gauvin.grouse

import android.app.Activity
import android.content.Context
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.unifiedpush.android.connector.FailedReason
import org.unifiedpush.android.connector.PushService
import org.unifiedpush.android.connector.UnifiedPush
import org.unifiedpush.android.connector.data.PushEndpoint
import org.unifiedpush.android.connector.data.PushMessage
import java.util.concurrent.Executors

/**
 * UnifiedPush wiring. The distributor (NextPush, backed by the uppush app on the user's Nextcloud)
 * holds the one battery-friendly connection; phaethon POSTs to the endpoint URL to wake us — no
 * FCM, no per-app foreground socket needed just to receive alerts.
 */
object Push {
    /** Turn push on: ensure a distributor is chosen, then register (→ GoosePushService.onNewEndpoint). */
    fun enable(activity: Activity) {
        SecureStore(activity).pushEnabled = true
        UnifiedPush.tryUseCurrentOrDefaultDistributor(activity) { ok ->
            if (ok) UnifiedPush.register(activity)
            else UnifiedPush.tryPickDistributor(activity) { picked -> if (picked) UnifiedPush.register(activity) }
        }
    }

    fun disable(context: Context) {
        SecureStore(context).apply { pushEnabled = false; pushEndpoint = "" }
        UnifiedPush.unregister(context)
    }

    /** Re-register on app start so the endpoint is refreshed (endpoints can rotate). */
    fun refresh(context: Context) {
        val store = SecureStore(context)
        if (store.pushEnabled && UnifiedPush.getSavedDistributor(context) != null) UnifiedPush.register(context)
    }
}

/** Receives UnifiedPush events: renders pushes as notifications, records/publishes the endpoint. */
class GoosePushService : PushService() {
    override fun onMessage(message: PushMessage, instance: String) {
        val raw = String(message.content).trim()
        if (raw.isEmpty()) return
        val cm = ConnectionManager.get(this)
        // Suppress when the app is foregrounded — you're already watching.
        if (cm.isForeground) return
        // Envelope {type,session,text}; plain text falls back to always-show (e.g. proactive briefings).
        val (type, session, text) = parsePush(raw)
        // A "turn" nudge fires for EVERY goose turn (Desktop too). Only surface it when it's this
        // phone's own session — drop other clients' turns.
        if (type == "turn" && session != null && session != cm.store.lastSessionId) return
        // During a voice interaction the assistant speaks the reply itself — don't also notify.
        if (type == "turn" && cm.recentVoice()) return
        // Finished-turn alert → "Grouse replied", tap deep-links to that session. Briefings carry
        // the persistent "goose-assistant" thread id → tap lands in that ongoing chat.
        val notifier = Notifier(this)
        if (type == "turn") notifier.postReply(text, session) else {
            SecureStore(this).apply {                       // feed the Assistant status card
                lastBriefingAt = System.currentTimeMillis(); lastBriefingText = text
            }
            notifier.postProactive(text, session)
        }
    }

    private fun parsePush(raw: String): Triple<String?, String?, String> = try {
        val o = Json.parseToJsonElement(raw).jsonObject
        Triple(
            o["type"]?.jsonPrimitive?.contentOrNull,
            o["session"]?.jsonPrimitive?.contentOrNull,
            o["text"]?.jsonPrimitive?.contentOrNull ?: raw,
        )
    } catch (e: Exception) {
        Triple(null, null, raw)
    }

    override fun onNewEndpoint(endpoint: PushEndpoint, instance: String) {
        SecureStore(this).pushEndpoint = endpoint.url
        PushRegistry.publish(this, endpoint.url)
    }

    override fun onRegistrationFailed(reason: FailedReason, instance: String) {}

    override fun onUnregistered(instance: String) { SecureStore(this).pushEndpoint = "" }
}

/** Best-effort: tell phaethon our endpoint URL so its senders know where to POST. No-op until a
 *  registry URL is configured (SecureStore.pushRegistryUrl). */
object PushRegistry {
    private val io = Executors.newSingleThreadExecutor()
    fun publish(context: Context, endpoint: String) {
        val url = SecureStore(context).pushRegistryUrl.ifBlank { return }
        io.execute {
            runCatching {
                val body = endpoint.toRequestBody("text/plain".toMediaTypeOrNull())
                Net.builder().build().newCall(Request.Builder().url(url).post(body).build())
                    .execute().close()
            }
        }
    }
}
