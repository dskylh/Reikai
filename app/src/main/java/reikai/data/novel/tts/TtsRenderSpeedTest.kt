package reikai.data.novel.tts

import android.content.Context
import android.os.SystemClock
import dev.zacsweers.metro.Inject
import reikai.domain.novel.NovelPreferences
import tachiyomi.core.common.util.lang.withIOContext
import java.io.File

/**
 * Renders one sample paragraph with the chosen engine and voice, exactly as pre-rendering does, and times
 * the engine and the encoding apart. Answers whether slow pre-rendering is the engine or Reikai.
 */
@Inject
class TtsRenderSpeedTest(
    private val context: Context,
    private val preferences: NovelPreferences,
) {

    data class Result(val audioMs: Long, val synthMs: Long, val encodeMs: Long) {
        /** How many times faster than real time the engine rendered. */
        val speed: Double get() = audioMs.toDouble() / synthMs.coerceAtLeast(1)
    }

    /** Null when the engine failed the sample or wrote audio Reikai cannot read. */
    suspend fun run(): Result? = withIOContext {
        val wav = File(context.cacheDir, "tts-speed-test.wav")
        val out = File(context.cacheDir, "tts-speed-test.m4a")
        val renderer = SystemTtsRenderer(context, preferences.readerTtsEngine().get())
        try {
            // Once untimed, so starting the engine and loading its model is not counted as rendering.
            renderer.render(WARM_UP, preferences.readerTtsVoice().get(), preferences.readerTtsPitch().get(), wav)
            val started = SystemClock.elapsedRealtime()
            val rendered = renderer.render(
                SAMPLE,
                preferences.readerTtsVoice().get(),
                preferences.readerTtsPitch().get(),
                wav,
            )
            val synthMs = SystemClock.elapsedRealtime() - started
            if (!rendered) return@withIOContext null
            val encodeStarted = SystemClock.elapsedRealtime()
            val audioMs = TtsAudioEncoder.wavToAac(wav, out) ?: return@withIOContext null
            Result(audioMs, synthMs, SystemClock.elapsedRealtime() - encodeStarted)
        } finally {
            renderer.shutdown()
            wav.delete()
            out.delete()
        }
    }

    private companion object {
        const val WARM_UP = "Ready."
        const val SAMPLE = "The rain had not stopped for three days, and the river had climbed over the old stone " +
            "steps where the children used to sit. Mara watched it from the window of the inn, counting the " +
            "boats that still dared the current, and wondered whether the messenger would come at all. When the " +
            "bell finally rang below, she was already halfway down the stairs, her cloak in one hand and the " +
            "letter she had never sent in the other."
    }
}
