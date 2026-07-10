package id.gauvin.goose

import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Voice Tier 2 — registering Goose as the device's digital assistant. Selecting Goose in
 * Settings › Default apps › Digital assistant lets the assist gesture / power-button-hold open a
 * voice-first Goose. Always-on "Hey Goose" hotword is OEM/privileged-gated and not guaranteed on
 * retail phones; the gesture-launch path works.
 *
 * The [GooseVoiceSession] listens, sends the transcript to goosed over a fresh headless ACP
 * connection, streams the reply on a minimal overlay, and speaks it. It runs READ-ONLY: every
 * tool-approval request is denied (same safety stance as the proactive worker), so a hands-free
 * command can read and report but never act — open the app for write/shell actions.
 */
class GooseVoiceInteractionService : VoiceInteractionService()

class GooseVoiceSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = GooseVoiceSession(this)
}

class GooseVoiceSession(context: Context) : VoiceInteractionSession(context) {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var statusView: TextView
    private lateinit var replyView: TextView
    private var voice: VoiceInput? = null
    private var speaker: Speaker? = null
    private var client: AcpClient? = null

    override fun onCreateContentView(): View {
        val ctx = context
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setPadding(64, 96, 64, 96)
            setBackgroundColor(0xF00E1A12.toInt())
            setOnClickListener { hide() }
        }
        statusView = TextView(ctx).apply {
            textSize = 20f; setTextColor(Color.WHITE); text = "Listening…"
        }
        replyView = TextView(ctx).apply {
            textSize = 16f; setTextColor(0xFFB8C7BD.toInt())
            setPadding(0, 40, 0, 0)
        }
        root.addView(statusView)
        root.addView(replyView)
        return root
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        speaker = Speaker(context)
        voice = VoiceInput(context).also {
            it.start(
                onPartial = { t -> statusView.text = t },
                onFinal = { t -> statusView.text = t; runPrompt(t) },
                onError = { e -> statusView.text = e; main.postDelayed({ hide() }, 1500) },
            )
        }
    }

    private fun runPrompt(text: String) {
        val store = SecureStore(context)
        if (!store.hasKey()) { statusView.text = "Set up Goose first"; return }
        val sb = StringBuilder()
        client = AcpClient("wss://${store.host}:${store.port}/acp", store.secretKey) { ev ->
            main.post {
                when (ev) {
                    is AcpEvent.Ready -> client?.sendPrompt(text)
                    is AcpEvent.AgentChunk -> { sb.append(ev.text); replyView.text = sb.toString() }
                    is AcpEvent.Permission -> client?.respondPermission(ev.toolCallId, null) // read-only
                    is AcpEvent.TurnDone -> speaker?.speak(sb.toString())
                    is AcpEvent.Error -> replyView.text = ev.text
                    else -> {}
                }
            }
        }.also {
            it.resumeSessionId = store.lastSessionId   // continue the last chat if there is one
            it.suppressReplay = true
            it.connect()
        }
    }

    override fun onHide() {
        voice?.stop(); voice = null
        speaker?.shutdown(); speaker = null
        client?.close(); client = null
        super.onHide()
    }
}

/**
 * Required by the voice-interaction manifest (a VIA must declare a RecognitionService). The session
 * above drives recognition itself via [VoiceInput], so this is a stub; if the system routes generic
 * recognition here it fails cleanly rather than hanging.
 */
class GooseRecognitionService : android.speech.RecognitionService() {
    override fun onStartListening(recognizerIntent: android.content.Intent, listener: Callback) {
        listener.error(android.speech.SpeechRecognizer.ERROR_CLIENT)
    }
    override fun onStopListening(listener: Callback) {}
    override fun onCancel(listener: Callback) {}
}
