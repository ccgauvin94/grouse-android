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
        // Envelope {type,session,text}; plain text (no type) is treated as a briefing.
        val (type, session, text) = parsePush(raw)
        if (type == "turn") {
            // Finished-turn nudge (fires for every goose turn, Desktop too -- the server can't tell
            // clients apart). Only show it for a turn THIS device actually sent and is still waiting
            // on -- comparing against "the session I have open" isn't enough, since the Assistant
            // thread is shared by title match across every client, so Desktop typing there would
            // match too. Skip when you're already watching (foreground) or right after a voice turn
            // (the assistant already spoke). Tap deep-links to that session.
            if (cm.isForeground) return
            if (session == null || session != cm.store.pendingPushSessionId) return
            cm.store.pendingPushSessionId = null
            if (cm.recentVoice()) return
            Notifier(this).postReply(text, session)
        } else {
            // Briefing/proactive: ALWAYS record for the Assistant status/dialog — even when
            // foreground, or a briefing that lands while you're in the app is lost and the dialog
            // wrongly reads "none yet" (that bug is why test pushes "didn't arrive"). Only raise a
            // notification when backgrounded. Tap lands in the persistent goose-assistant thread.
            SecureStore(this).apply { lastBriefingAt = System.currentTimeMillis(); lastBriefingText = text }
            if (!cm.isForeground) Notifier(this).postProactive(text, session)
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
        // Self-heal for rotation (2026-07-28: six APK reinstalls minted a second uppush
        // registration; phaethon kept POSTing the dead one and the morning push vanished):
        // publish the endpoint into goose's server-side config over the ACP socket, where
        // deliver.sh reads it before every push. Best-effort — if the socket is down now,
        // the next app start re-registers (Push.refresh) and lands here again.
        ConnectionManager.get(this).publishPushEndpoint(endpoint.url)
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
