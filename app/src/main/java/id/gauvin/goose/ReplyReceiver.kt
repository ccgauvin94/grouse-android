package id.gauvin.goose

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.RemoteInput

/** Handles the inline "Reply" action on a finished-turn notification. */
class ReplyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Notifier.ACTION_REPLY) return
        val text = RemoteInput.getResultsFromIntent(intent)
            ?.getCharSequence(Notifier.KEY_REPLY)?.toString()?.trim()
        if (!text.isNullOrBlank()) ConnectionManager.get(context).sendWhenReady(text)
    }
}
