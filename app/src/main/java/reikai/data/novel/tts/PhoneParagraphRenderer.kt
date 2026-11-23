package reikai.data.novel.tts

import android.content.Context
import android.os.SystemClock
import logcat.LogPriority
import reikai.domain.novel.NovelPreferences
import reikai.domain.novel.tts.TtsAudioRepository
import reikai.domain.novel.tts.TtsParagraphKey
import tachiyomi.core.common.util.system.logcat
import java.io.File

/**
 * Renders a paragraph on this phone with the system voice and keeps it in the store, for the reader's
 * render-ahead and the background queue alike. Holds a [SystemTtsRenderer] of its own once first used,
 * so whoever builds one shuts it down.
 */
class PhoneParagraphRenderer(
    private val context: Context,
    private val enginePackage: String,
    private val preferences: NovelPreferences,
    private val store: TtsAudioRepository,
) {

    @Volatile
    private var renderer: SystemTtsRenderer? = null

    val signature: String get() = TtsRenderSignature.phone(enginePackage, preferences)

    suspend fun isRendered(text: String): Boolean =
        store.find(TtsParagraphKey.of(text), signature)?.let { File(it.path).isFile } == true

    /** False when the engine refused or failed the paragraph. Call one at a time. */
    suspend fun render(chapterId: Long, text: String): Boolean {
        val synthesized = synthesize(chapterId, text) ?: return false
        return keep(synthesized)
    }

    /**
     * The engine's half of a render: [text] spoken into a WAV, or null when the engine refused or failed it.
     * Call one at a time; [keep] may run beside the next one, which is how a queue keeps the engine busy.
     */
    suspend fun synthesize(chapterId: Long, text: String): Synthesized? {
        val signature = signature
        val key = TtsParagraphKey.of(text)
        val dir = File(context.filesDir, "$AUDIO_DIR/$chapterId").apply { mkdirs() }
        val name = "$key-${signature.hashCode().toUInt().toString(16)}"
        val wav = File(dir, "$name.wav")
        val engine = renderer ?: SystemTtsRenderer(context, enginePackage).also { renderer = it }
        val started = SystemClock.elapsedRealtime()
        val voice = preferences.readerTtsVoice().get()
        if (!engine.render(text, voice, preferences.readerTtsPitch().get(), wav)) {
            wav.delete()
            return null
        }
        return Synthesized(chapterId, key, signature, text.length, wav, SystemClock.elapsedRealtime() - started)
    }

    /** Encodes and stores what [synthesize] made, and deletes its WAV either way. */
    suspend fun keep(synthesized: Synthesized): Boolean {
        val wav = synthesized.wav
        val out = File(wav.parentFile, wav.nameWithoutExtension + ".m4a")
        try {
            val started = SystemClock.elapsedRealtime()
            val durationMs = TtsAudioEncoder.wavToAac(wav, out) ?: return false
            val encoded = SystemClock.elapsedRealtime()
            with(synthesized) {
                store.add(key, signature, chapterId, out.path, out.length(), durationMs, System.currentTimeMillis())
            }
            val capBytes = preferences.readerTtsRenderCapMb().get() * BYTES_PER_MB
            store.evictOldest(capBytes).forEach { File(it).delete() }
            // Numbers only, never the text: render speed is what an on-device test measures.
            logcat(LogPriority.INFO) {
                "tts render: ${synthesized.chars} chars, ${durationMs}ms audio, synth ${synthesized.synthMs}ms, " +
                    "encode ${encoded - started}ms, store ${SystemClock.elapsedRealtime() - encoded}ms"
            }
            return true
        } finally {
            wav.delete()
        }
    }

    fun shutdown() {
        renderer?.shutdown()
        renderer = null
    }

    companion object {
        /** Under `filesDir`, one folder per chapter. */
        const val AUDIO_DIR = "tts"
        private const val BYTES_PER_MB = 1024L * 1024L
    }
}

/** A paragraph the engine has spoken into [wav], waiting to be encoded and stored. */
class Synthesized(
    val chapterId: Long,
    val key: String,
    val signature: String,
    val chars: Int,
    val wav: File,
    val synthMs: Long,
)

/** The voice a render was made in, so a change of voice misses old renders instead of playing them. */
object TtsRenderSignature {

    /** Engine, voice and pitch; speed is applied at playback, so it is not part of it. */
    fun phone(enginePackage: String, preferences: NovelPreferences): String =
        "$enginePackage|${preferences.readerTtsVoice().get()}|${preferences.readerTtsPitch().get()}"

    fun pc(preferences: NovelPreferences): String =
        "pc|${preferences.readerTtsServerModel().get()}|${preferences.readerTtsServerVoice().get()}"
}
