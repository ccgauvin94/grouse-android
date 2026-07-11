package id.gauvin.goose

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.Locale

/**
 * Push-to-talk speech-to-text over [SpeechRecognizer]. Streams partial results so the chat input
 * updates live. All SpeechRecognizer calls must happen on the main thread — Compose callbacks are,
 * so drive this from composables only. Prefers on-device recognition (API 33+) when available.
 */
class VoiceInput(private val context: Context) {
    private var recognizer: SpeechRecognizer? = null
    var listening by mutableStateOf(false)
        private set

    fun start(onPartial: (String) -> Unit, onFinal: (String) -> Unit, onError: (String) -> Unit) {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) { onError("No speech recognizer installed"); return }
        stop()
        val r = if (Build.VERSION.SDK_INT >= 33 && SpeechRecognizer.isOnDeviceRecognitionAvailable(context))
            SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        else SpeechRecognizer.createSpeechRecognizer(context)
        recognizer = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { listening = true }
            override fun onPartialResults(results: Bundle) = first(results)?.let(onPartial) ?: Unit
            override fun onResults(results: Bundle) { listening = false; first(results)?.let(onFinal) }
            override fun onError(error: Int) { listening = false; onError(errText(error)) }
            override fun onEndOfSpeech() { listening = false }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        r.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
        })
    }

    fun stop() {
        recognizer?.apply { runCatching { cancel() }; runCatching { destroy() } }
        recognizer = null
        listening = false
    }

    private fun first(b: Bundle): String? =
        b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.takeIf { it.isNotBlank() }

    private fun errText(code: Int) = when (code) {
        SpeechRecognizer.ERROR_NO_MATCH -> "didn't catch that"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "no speech"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "mic permission needed"
        else -> "speech error $code"
    }
}

/** Speaks agent replies aloud via [TextToSpeech]. Ready-gated; call [shutdown] on dispose. */
class Speaker(context: Context) {
    private var tts: TextToSpeech? = null
    private var ready = false
    private val onDone = HashMap<String, () -> Unit>()   // utteranceId → completion callback

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.getDefault(); ready = true
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String) {}
                    override fun onDone(id: String) { onDone.remove(id)?.invoke() }
                    @Deprecated("") override fun onError(id: String) { onDone.remove(id)?.invoke() }
                    override fun onError(id: String, code: Int) { onDone.remove(id)?.invoke() }
                })
            }
        }
    }

    /** Speak [text]; [whenDone] fires (once) when speech finishes or errors. If TTS isn't ready or
     *  text is blank, [whenDone] runs immediately so callers can chain (e.g. re-listen). */
    fun speak(text: String, whenDone: (() -> Unit)? = null) {
        if (!ready || text.isBlank()) { whenDone?.invoke(); return }
        val id = "goose-${text.hashCode()}-${System.nanoTime()}"
        whenDone?.let { onDone[id] = it }
        tts?.speak(text.take(4000), TextToSpeech.QUEUE_FLUSH, null, id)
    }

    fun stop() { tts?.stop() }
    fun shutdown() { tts?.stop(); tts?.shutdown(); tts = null; onDone.clear() }
}
