package id.gauvin.grouse

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * Voice Tier 2 — Goose as the device's digital assistant. Selecting Goose in Settings › Default
 * apps › Digital assistant lets the assist gesture / power-button-hold open a voice-first Goose.
 *
 * [GooseVoiceSession] is a bottom-sheet pop-up (transparent scrim — it does not darken the whole
 * screen). It listens, sends the transcript through the shared [ConnectionManager] via sendVoice,
 * which SPEAKS the reply itself (so it survives this sheet closing) and suppresses the finished-turn
 * notification — the point of voice is to just talk back, not open a chat or ping you. Optionally
 * the turn runs on a faster voice model (Settings). READ-ONLY: approvals are auto-denied.
 */
class GooseVoiceInteractionService : VoiceInteractionService()

class GooseVoiceSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = GooseVoiceSession(this)
}

class GooseVoiceSession(context: Context) : VoiceInteractionSession(context) {
    private val scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())
    private val cm by lazy { ConnectionManager.get(context) }

    private lateinit var card: LinearLayout
    private lateinit var statusView: TextView
    private lateinit var youView: TextView
    private lateinit var gooseView: TextView
    private lateinit var micButton: TextView

    private var voice: VoiceInput? = null
    private var awaitingReply = false

    override fun onCreateContentView(): View {
        val ctx = context
        // Transparent scrim (NO dimming of the screen); tap outside the card to dismiss.
        val scrim = FrameLayout(ctx).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener { if (!awaitingReply) hide() }
        }
        card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM
            ).apply { setMargins(24, 0, 24, 56) }   // lift it off the nav bar / screen edges
            background = GradientDrawable().apply {
                setColor(0xFF10231A.toInt()); cornerRadius = 56f
            }
            elevation = 24f
            setPadding(56, 36, 56, 56)
            isClickable = true   // swallow taps so the card itself doesn't dismiss
        }
        card.addView(View(ctx).apply {   // grab handle
            layoutParams = LinearLayout.LayoutParams(96, 10).apply {
                gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = 26
            }
            background = GradientDrawable().apply { setColor(0xFF3A5346.toInt()); cornerRadius = 6f }
        })
        statusView = TextView(ctx).apply { textSize = 21f; setTextColor(Color.WHITE); text = "…" }
        youView = TextView(ctx).apply {
            textSize = 15f; setTextColor(0xFF8FB3A0.toInt()); setPadding(0, 22, 0, 0)
        }
        val gooseScroll = ScrollView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = 18 }
            minimumHeight = 340   // taller sheet so it reads comfortably, sits higher
            isVerticalScrollBarEnabled = true
        }
        gooseView = TextView(ctx).apply {
            textSize = 17f; setTextColor(0xFFEAF3EC.toInt()); setLineSpacing(6f, 1f)
        }
        gooseScroll.addView(gooseView)
        micButton = TextView(ctx).apply {
            textSize = 16f; setTextColor(Color.WHITE); text = "🎤  Tap to talk"
            gravity = Gravity.CENTER; setPadding(0, 32, 0, 32)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = 26 }
            background = GradientDrawable().apply { setColor(0xFF1E7A4B.toInt()); cornerRadius = 30f }
            setOnClickListener { startListening() }
        }
        card.addView(statusView); card.addView(youView); card.addView(gooseScroll); card.addView(micButton)
        scrim.addView(card)
        return scrim
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        cm.voiceActive = true
        card.post { card.translationY = card.height.toFloat(); card.animate().translationY(0f).setDuration(180).start() }
        observe()
        if (!cm.configured) {
            statusView.text = "Open Grouse and connect first"
            micButton.text = "Close"; micButton.setOnClickListener { hide() }
            return
        }
        startListening()
    }

    /** Observe the shared ConnectionManager to reflect the turn on the sheet while it's up. CM does
     *  the actual speaking (in sendVoice/TurnDone), so it works even if this sheet has closed. */
    private fun observe() {
        scope.launch {
            snapshotFlow { cm.busy.value }.collect { busy ->
                if (awaitingReply && !busy) {
                    awaitingReply = false
                    val last = cm.messages.lastOrNull()
                    gooseView.text = when (last?.role) {
                        "assistant" -> last.text
                        "error" -> "Error: ${last.text}"
                        else -> cm.messages.lastOrNull { it.role == "assistant" }?.text ?: "(no response)"
                    }
                    statusView.text = "Grouse"
                    micButton.text = "🎤  Ask a follow-up"
                }
            }
        }
        scope.launch {
            snapshotFlow { cm.messages.lastOrNull { it.role == "assistant" }?.text ?: "" }.collect { streamed ->
                if (awaitingReply && streamed.isNotBlank()) { statusView.text = "Grouse"; gooseView.text = streamed }
            }
        }
        scope.launch {
            snapshotFlow { cm.permissions.toList() }.collect { perms ->
                perms.forEach { cm.answerPermission(it, null) }   // read-only voice
            }
        }
    }

    private fun startListening() {
        statusView.text = "Listening…"; youView.text = ""; micButton.text = "🎤  Listening…"
        voice?.stop()
        voice = VoiceInput(context).also {
            it.start(
                onPartial = { t -> youView.text = t },
                onFinal = { t -> youView.text = t; ask(t) },
                onError = { e -> statusView.text = "Mic: $e"; micButton.text = "🎤  Tap to try again" },
            )
        }
    }

    private fun ask(text: String) {
        if (text.isBlank()) { startListening(); return }
        statusView.text = "Thinking…"; gooseView.text = ""
        awaitingReply = true
        cm.sendVoice(text)   // CM speaks the reply + suppresses the notification + swaps voice model
    }

    override fun onHide() {
        cm.voiceActive = false
        scope.cancel()
        voice?.stop(); voice = null
        awaitingReply = false
        super.onHide()
    }
}

/**
 * Required by the voice-interaction manifest (a VIA must declare a RecognitionService). The session
 * drives recognition itself via [VoiceInput]; this is a stub that fails cleanly if the system ever
 * routes generic recognition here.
 */
class GooseRecognitionService : android.speech.RecognitionService() {
    override fun onStartListening(recognizerIntent: android.content.Intent, listener: Callback) {
        listener.error(android.speech.SpeechRecognizer.ERROR_CLIENT)
    }
    override fun onStopListening(listener: Callback) {}
    override fun onCancel(listener: Callback) {}
}
