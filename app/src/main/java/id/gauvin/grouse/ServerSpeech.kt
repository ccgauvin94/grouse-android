package id.gauvin.grouse

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaPlayer
import android.media.MediaRecorder
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * Speech through the box's own models instead of Android's, talking to LocalAI directly.
 *
 * goose is deliberately NOT in this path. It exposes dictation methods, but they transcribe for
 * goose's own UI rather than handing text back to an ACP client, and it has no TTS at all. LocalAI
 * publishes 0.0.0.0:8080, so the phone can call it -- same LAN/tailnet trust boundary as the goose
 * socket itself, no new exposure.
 *
 * Endpoints (both verified against this box, TTS output fed straight back into STT):
 *   POST /v1/audio/speech          {model, input}     -> WAV bytes
 *   POST /v1/audio/transcriptions  multipart file     -> {"text": ...}
 */
object ServerSpeech {

    // ---- TTS ------------------------------------------------------------------------------

    /** Synthesize `text` and play it. `onDone` fires on completion OR failure, so a caller that
     *  chains on it (the voice sheet) can never hang. Returns the player so it can be stopped. */
    fun speak(
        baseUrl: String, model: String, text: String,
        cacheDir: File, onError: (String) -> Unit, onDone: () -> Unit,
    ): MediaPlayer? {
        if (text.isBlank()) { onDone(); return null }
        val player = MediaPlayer()
        thread {
            try {
                val body = JsonBody.obj("model" to model, "input" to text)
                val wav = post("$baseUrl/v1/audio/speech", "application/json", body.toByteArray())
                // MediaPlayer wants a file or a stream it can seek; a temp file is the simple path.
                val f = File.createTempFile("tts", ".wav", cacheDir)
                f.writeBytes(wav)
                player.setDataSource(f.absolutePath)
                player.setOnCompletionListener { f.delete(); onDone() }
                player.setOnErrorListener { _, _, _ -> f.delete(); onDone(); true }
                player.prepare()
                player.start()
            } catch (e: Exception) {
                onError(e.message ?: "speech failed")
                onDone()
            }
        }
        return player
    }

    // ---- STT ------------------------------------------------------------------------------

    private const val SAMPLE_RATE = 16000   // what Whisper wants; avoids a server-side resample

    /**
     * Records 16 kHz mono PCM until [Recording.stop], then uploads it for transcription.
     *
     * Raw AudioRecord + a hand-written WAV header, NOT MediaRecorder: MediaRecorder only emits
     * container formats (m4a/3gp/amr) whose decoding depends on what ffmpeg support the LocalAI
     * image happens to carry, whereas 16-bit PCM WAV is what whisper.cpp reads natively. The cost
     * is ~40 lines of header writing; the benefit is no silent format dependency.
     */
    @SuppressLint("MissingPermission")   // caller checks RECORD_AUDIO; see ChatScreen's mic button
    fun record(cacheDir: File): Recording {
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
        ).coerceAtLeast(4096)
        val rec = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBuf * 2,
        )
        val pcm = ByteArrayOutputStream()
        // AtomicBoolean, not a @Volatile local: @Volatile only applies to properties, and the read
        // loop runs on another thread than the stopper.
        val running = java.util.concurrent.atomic.AtomicBoolean(true)
        rec.startRecording()
        val t = thread {
            val buf = ByteArray(minBuf)
            while (running.get()) {
                val n = rec.read(buf, 0, buf.size)
                if (n > 0) synchronized(pcm) { pcm.write(buf, 0, n) }
            }
        }
        return Recording(rec, t, pcm, cacheDir) { running.set(false) }
    }

    class Recording(
        private val rec: AudioRecord,
        private val worker: Thread,
        private val pcm: ByteArrayOutputStream,
        private val cacheDir: File,
        private val halt: () -> Unit,
    ) {
        /** Stop recording and transcribe. Callbacks fire on a background thread. */
        fun stop(baseUrl: String, model: String, onText: (String) -> Unit, onError: (String) -> Unit) {
            halt()
            runCatching { worker.join(2000) }
            runCatching { rec.stop() }
            runCatching { rec.release() }
            thread {
                try {
                    val f = File.createTempFile("stt", ".wav", cacheDir)
                    writeWav(f, synchronized(pcm) { pcm.toByteArray() })
                    val reply = postMultipartFile(
                        "$baseUrl/v1/audio/transcriptions", f, mapOf("model" to model),
                    )
                    f.delete()
                    val text = Regex("\"text\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"")
                        .find(reply)?.groupValues?.get(1)
                        ?.replace("\\\"", "\"")?.replace("\\n", "\n")?.trim()
                    if (text.isNullOrBlank()) onError("no speech recognized") else onText(text)
                } catch (e: Exception) {
                    onError(e.message ?: "transcription failed")
                }
            }
        }

        /** Abandon the recording without transcribing. */
        fun cancel() {
            halt(); runCatching { worker.join(1000) }
            runCatching { rec.stop() }; runCatching { rec.release() }
        }
    }

    /** 44-byte canonical WAV header + PCM payload. */
    private fun writeWav(f: File, pcm: ByteArray) {
        val ch = 1; val bits = 16
        val byteRate = SAMPLE_RATE * ch * bits / 8
        f.outputStream().use { out ->
            fun le32(v: Int) = byteArrayOf(
                (v and 0xff).toByte(), (v shr 8 and 0xff).toByte(),
                (v shr 16 and 0xff).toByte(), (v shr 24 and 0xff).toByte())
            fun le16(v: Int) = byteArrayOf((v and 0xff).toByte(), (v shr 8 and 0xff).toByte())
            out.write("RIFF".toByteArray()); out.write(le32(36 + pcm.size))
            out.write("WAVE".toByteArray()); out.write("fmt ".toByteArray())
            out.write(le32(16)); out.write(le16(1)); out.write(le16(ch))
            out.write(le32(SAMPLE_RATE)); out.write(le32(byteRate))
            out.write(le16(ch * bits / 8)); out.write(le16(bits))
            out.write("data".toByteArray()); out.write(le32(pcm.size))
            out.write(pcm)
        }
    }

    // ---- plumbing --------------------------------------------------------------------------

    private fun post(url: String, contentType: String, body: ByteArray): ByteArray {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 15000
            readTimeout = 180000            // Kokoro took ~4s for a sentence; long replies are slower
            setRequestProperty("Content-Type", contentType)
        }
        c.outputStream.use { it.write(body) }
        if (c.responseCode !in 200..299)
            throw RuntimeException("HTTP ${c.responseCode}: ${c.errorStream?.readBytes()?.decodeToString()?.take(200)}")
        return c.inputStream.use { it.readBytes() }
    }

    private fun postMultipartFile(url: String, file: File, fields: Map<String, String>): String {
        val boundary = "----grouse${System.currentTimeMillis()}"
        val out = ByteArrayOutputStream()
        fun w(s: String) = out.write(s.toByteArray())
        for ((k, v) in fields) {
            w("--$boundary\r\nContent-Disposition: form-data; name=\"$k\"\r\n\r\n$v\r\n")
        }
        w("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"${file.name}\"\r\n")
        w("Content-Type: audio/wav\r\n\r\n")
        out.write(file.readBytes())
        w("\r\n--$boundary--\r\n")
        return post(url, "multipart/form-data; boundary=$boundary", out.toByteArray()).decodeToString()
    }

    /** Minimal JSON writer — one object of string fields, correctly escaped. */
    private object JsonBody {
        fun obj(vararg pairs: Pair<String, String>): String =
            pairs.joinToString(",", "{", "}") { (k, v) -> "\"$k\":\"${esc(v)}\"" }
        private fun esc(s: String) = buildString {
            for (c in s) when (c) {
                '"' -> append("\\\""); '\\' -> append("\\\\")
                '\n' -> append("\\n"); '\r' -> append("\\r"); '\t' -> append("\\t")
                else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
            }
        }
    }
}
