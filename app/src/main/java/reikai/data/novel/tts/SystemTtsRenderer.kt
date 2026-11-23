package reikai.data.novel.tts

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.minutes

/**
 * Renders paragraphs to WAV files through Android's [TextToSpeech], on an instance of its own so a render
 * never interrupts the voice being listened to. Always at speed 1.0: the player applies the reading speed,
 * so one render serves every speed.
 */
class SystemTtsRenderer(context: Context, enginePackage: String) {

    private val ready = CompletableDeferred<Boolean>()
    private val pending = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()
    private var appliedVoice: String? = null
    private var appliedPitch: Float? = null

    private val tts = TextToSpeech(
        context.applicationContext,
        { status -> ready.complete(status == TextToSpeech.SUCCESS) },
        enginePackage.ifBlank { null },
    ).apply {
        setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit

            override fun onDone(utteranceId: String?) {
                utteranceId?.let(pending::remove)?.complete(true)
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                utteranceId?.let(pending::remove)?.complete(false)
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                utteranceId?.let(pending::remove)?.complete(false)
            }
        })
    }

    /**
     * Renders [text] in [voiceName] (blank for the engine's default) at [pitch] into [wav]. False when the
     * engine failed to start, refused the text or did not finish in time; one past the engine's input limit
     * is refused up front, and is spoken live instead.
     */
    suspend fun render(text: String, voiceName: String, pitch: Float, wav: File): Boolean {
        if (!ready.await()) return false
        if (text.length > TextToSpeech.getMaxSpeechInputLength()) return false
        applyVoice(voiceName, pitch)
        val id = UUID.randomUUID().toString()
        val done = CompletableDeferred<Boolean>()
        pending[id] = done
        if (tts.synthesizeToFile(text, Bundle(), wav, id) != TextToSpeech.SUCCESS) {
            pending.remove(id)
            return false
        }
        val finished = withTimeoutOrNull(RENDER_TIMEOUT) { done.await() }
        if (finished == null) {
            pending.remove(id)
            runCatching { tts.stop() }
        }
        return finished == true && wav.length() > 0
    }

    fun shutdown() {
        pending.values.forEach { it.complete(false) }
        pending.clear()
        runCatching { tts.shutdown() }
    }

    // Asking the engine for its voices is a call into its process, so it is made only on a change.
    private fun applyVoice(voiceName: String, pitch: Float) {
        if (voiceName != appliedVoice) {
            if (voiceName.isNotBlank()) {
                runCatching { tts.voices?.firstOrNull { it.name == voiceName } }.getOrNull()
                    ?.let { voice -> runCatching { tts.voice = voice } }
            }
            appliedVoice = voiceName
        }
        if (pitch != appliedPitch) {
            tts.setPitch(pitch.coerceIn(0.1f, 5.0f))
            tts.setSpeechRate(1.0f)
            appliedPitch = pitch
        }
    }

    private companion object {
        val RENDER_TIMEOUT = 2.minutes
    }
}
