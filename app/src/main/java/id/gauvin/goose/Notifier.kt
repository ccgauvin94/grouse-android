package id.gauvin.goose

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput

/** Builds/posts the app's notifications: the ongoing "connected" one and finished-turn alerts. */
class Notifier(context: Context) {
    private val app = context.applicationContext
    private val nm = app.getSystemService(NotificationManager::class.java)
    private val goosePerson = Person.Builder().setName("Goose").setKey("goose").build()
    private val youPerson = Person.Builder().setName("You").setKey("you").build()

    init {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CH_ONGOING, "Connection", NotificationManager.IMPORTANCE_LOW)
                    .apply { setShowBadge(false) })
            nm.createNotificationChannel(
                NotificationChannel(CH_ALERT, "Replies", NotificationManager.IMPORTANCE_HIGH))
        }
    }

    private fun openApp(): PendingIntent {
        val i = Intent(app, MainActivity::class.java)
            .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(app, 0, i, flags(mutable = false))
    }

    /** The persistent low-priority notification the foreground service must show. */
    fun ongoing(text: String): Notification =
        NotificationCompat.Builder(app, CH_ONGOING)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle("Goose")
            .setContentText(text)
            .setContentIntent(openApp())
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

    /**
     * A MessagingStyle notification with an inline reply + (invisible) mark-as-read action.
     * MessagingStyle + the SEMANTIC_ACTION_REPLY/MARK_AS_READ actions are what Android Auto reads
     * aloud and turns into a voice reply; on the phone it renders like a chat message.
     */
    private fun postReplyable(title: String, text: String, requestCode: Int, id: Int) {
        val remote = RemoteInput.Builder(KEY_REPLY).setLabel("Reply to goose").build()
        val replyPi = PendingIntent.getBroadcast(app, requestCode,
            Intent(app, ReplyReceiver::class.java).setAction(ACTION_REPLY).putExtra(EXTRA_NOTIF_ID, id),
            flags(mutable = true))
        val replyAction = NotificationCompat.Action.Builder(R.drawable.ic_launcher_monochrome, "Reply", replyPi)
            .addRemoteInput(remote)
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
            .setShowsUserInterface(false)
            .build()
        val markReadPi = PendingIntent.getBroadcast(app, requestCode + 100,
            Intent(app, ReplyReceiver::class.java).setAction(ACTION_MARK_READ).putExtra(EXTRA_NOTIF_ID, id),
            flags(mutable = false))
        val markReadAction = NotificationCompat.Action.Builder(R.drawable.ic_launcher_monochrome, "Mark read", markReadPi)
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_MARK_AS_READ)
            .setShowsUserInterface(false)
            .build()
        val style = NotificationCompat.MessagingStyle(youPerson)
            .setConversationTitle(title)
            .addMessage(text.take(1500), System.currentTimeMillis(), goosePerson)
        val n = NotificationCompat.Builder(app, CH_ALERT)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setStyle(style)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setContentIntent(openApp())
            .setAutoCancel(true)
            .addAction(replyAction)
            .addInvisibleAction(markReadAction)
            .build()
        nm.notify(id, n)
    }

    /** Turn finished while backgrounded: show the reply + an inline reply action. */
    fun postReply(text: String) = postReplyable("Goose replied", text, 1, ID_ALERT)

    /** goose is blocked on a tool approval while backgrounded. */
    fun postApprovalNeeded(tool: String) {
        val n = NotificationCompat.Builder(app, CH_ALERT)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle("Goose needs approval")
            .setContentText("Allow “$tool”? Open to decide.")
            .setContentIntent(openApp())
            .setAutoCancel(true)
            .build()
        nm.notify(ID_ALERT, n)
    }

    /** A scheduled proactive check found something worth surfacing. */
    fun postProactive(text: String) = postReplyable("Goose briefing", text, 2, ID_PROACTIVE)

    fun cancelAlert() = nm.cancel(ID_ALERT)

    private fun flags(mutable: Boolean): Int {
        val base = PendingIntent.FLAG_UPDATE_CURRENT
        return if (mutable) base or PendingIntent.FLAG_MUTABLE else base or PendingIntent.FLAG_IMMUTABLE
    }

    companion object {
        const val CH_ONGOING = "goose_ongoing"
        const val CH_ALERT = "goose_alert"
        const val ID_ONGOING = 1
        const val ID_ALERT = 2
        const val ID_PROACTIVE = 3
        const val KEY_REPLY = "goose_reply_text"
        const val ACTION_REPLY = "id.gauvin.goose.action.REPLY"
        const val ACTION_MARK_READ = "id.gauvin.goose.action.MARK_READ"
        const val EXTRA_NOTIF_ID = "id.gauvin.goose.extra.NOTIF_ID"
    }
}
