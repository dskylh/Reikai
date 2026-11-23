package reikai.data.novel.tts

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import logcat.LogPriority
import reikai.domain.novel.NovelPreferences
import reikai.domain.novel.tts.NovelTtsEngine
import reikai.domain.novel.tts.RenderedParagraph
import reikai.domain.novel.tts.TtsAudioRepository
import reikai.domain.novel.tts.TtsAudioSource
import reikai.domain.novel.tts.TtsParagraphKey
import reikai.domain.novel.tts.TtsPiece
import tachiyomi.core.common.util.system.logcat
import java.io.File
import kotlin.coroutines.CoroutineContext

/**
 * [NovelTtsEngine] that plays a paragraph from a file rendered ahead of time when one exists, and hands it
 * to [live] when not. Voices, engines and splitting all stay [live]'s. The audio source is read on every
 * [speak], so switching it takes effect at the next paragraph. While a paragraph plays, the next few of
 * the prepared chapter are rendered on this phone, so playback catches up with the renders and stays on
 * files. A piece's start inside a file is placed by its share of the paragraph's characters.
 */
@OptIn(UnstableApi::class)
class PreRenderedTtsEngine(
    private val live: NovelTtsEngine,
    private val context: Context,
    private val enginePackage: String,
    private val preferences: NovelPreferences,
    private val store: TtsAudioRepository,
    private val scope: CoroutineScope,
    private val io: CoroutineContext,
) : NovelTtsEngine by live {

    private var player: ExoPlayer? = null
    private var listener: Player.Listener? = null
    private var rate = 1.0f

    /** The paragraph text the last [pieces] split, which [speak]'s pieces are offsets into. */
    private var lastText: String? = null

    /** Raised by every speak and stop, so a lookup that returns late cannot start stale audio. */
    private var generation = 0
    private var speakJob: Job? = null
    private var progressJob: Job? = null

    // Written on the main thread, read by the render loop on [io].
    @Volatile
    private var chapterId: Long? = null

    @Volatile
    private var chapterParagraphs: List<String> = emptyList()

    /** Where in [chapterParagraphs] rendering should stay ahead of, updated as playback moves. */
    @Volatile
    private var renderFrom = 0
    private var renderJob: Job? = null

    @Volatile
    private var renderer: SystemTtsRenderer? = null

    /** Keys that failed to render this session, so a paragraph the engine refuses is not retried forever. */
    private val failedKeys = mutableSetOf<String>()

    override fun setRate(rate: Float) {
        this.rate = rate
        player?.playbackParameters = PlaybackParameters(rate)
        live.setRate(rate)
    }

    override fun pieces(text: String, bySentence: Boolean): List<TtsPiece> {
        lastText = text
        return live.pieces(text, bySentence)
    }

    override fun prepare(chapterId: Long, paragraphs: List<String>) {
        live.prepare(chapterId, paragraphs)
        if (this.chapterId == chapterId && chapterParagraphs == paragraphs) return
        this.chapterId = chapterId
        chapterParagraphs = paragraphs
        renderFrom = 0
    }

    override fun speak(pieces: List<TtsPiece>, onPieceStart: (index: Int) -> Unit, onDone: () -> Unit) {
        stopPlayback()
        val source = preferences.readerTtsAudioSource().get()
        val text = lastText
        if (source == TtsAudioSource.LIVE || text == null || pieces.isEmpty()) {
            live.speak(pieces, onPieceStart, onDone)
            return
        }
        val spoken = ++generation
        speakJob = scope.launch {
            val key = TtsParagraphKey.of(text)
            val audio = withContext(io) {
                store.find(key, signature())?.takeIf { File(it.path).isFile }
            }
            if (spoken != generation) return@launch
            if (audio == null) {
                live.speak(pieces, onPieceStart, onDone)
            } else {
                play(audio, text, pieces, spoken, onPieceStart, onDone)
            }
            renderAheadOf(text)
        }
    }

    override fun stop() {
        stopPlayback()
        live.stop()
    }

    override fun shutdown() {
        stopPlayback()
        renderJob?.cancel()
        renderer?.shutdown()
        renderer = null
        player?.release()
        player = null
        live.shutdown()
    }

    private fun stopPlayback() {
        generation++
        speakJob?.cancel()
        progressJob?.cancel()
        listener?.let { player?.removeListener(it) }
        listener = null
        player?.stop()
    }

    private fun play(
        audio: RenderedParagraph,
        text: String,
        pieces: List<TtsPiece>,
        spoken: Int,
        onPieceStart: (index: Int) -> Unit,
        onDone: () -> Unit,
    ) {
        val exo = player ?: buildPlayer().also { player = it }
        // Where each piece starts in the file, by its share of the paragraph's characters.
        val starts = pieces.map { audio.durationMs * it.start / text.length.coerceAtLeast(1) }
        var finished = false
        fun finish() {
            if (finished || spoken != generation) return
            finished = true
            progressJob?.cancel()
            onDone()
        }
        val playerListener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) finish()
            }

            // A file that will not play is spoken live from the same place, rather than skipped.
            override fun onPlayerError(error: PlaybackException) {
                logcat(LogPriority.WARN, error) { "Pre-rendered paragraph failed to play" }
                if (finished || spoken != generation) return
                finished = true
                progressJob?.cancel()
                live.speak(pieces, onPieceStart, onDone)
            }
        }
        listener = playerListener
        exo.addListener(playerListener)
        exo.setMediaItem(MediaItem.fromUri(Uri.fromFile(File(audio.path))), starts.first())
        exo.playbackParameters = PlaybackParameters(rate)
        exo.prepare()
        exo.play()
        onPieceStart(0)
        if (pieces.size > 1) {
            progressJob = scope.launch {
                var next = 1
                while (isActive && next < starts.size) {
                    if (exo.currentPosition >= starts[next]) {
                        onPieceStart(next)
                        next++
                    } else {
                        delay(PROGRESS_POLL_MS)
                    }
                }
            }
        }
    }

    private fun buildPlayer(): ExoPlayer = ExoPlayer.Builder(context)
        // NovelTtsService holds audio focus for read-aloud as a whole, live and pre-rendered alike.
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                .build(),
            false,
        )
        .setWakeMode(C.WAKE_MODE_LOCAL)
        .build()

    /** The voice a render is made in, so a change of engine, voice or pitch misses old renders. */
    private fun signature(): String =
        "$enginePackage|${preferences.readerTtsVoice().get()}|${preferences.readerTtsPitch().get()}"

    /** Keeps the [RENDER_AHEAD] paragraphs after [text] rendered, one at a time, while the phone is the source. */
    private fun renderAheadOf(text: String) {
        val at = chapterParagraphs.indexOf(text)
        if (at < 0) return
        renderFrom = at + 1
        if (renderJob?.isActive == true) return
        renderJob = scope.launch(io) {
            while (isActive) {
                if (preferences.readerTtsAudioSource().get() != TtsAudioSource.PHONE) break
                val id = chapterId ?: break
                val next = nextToRender() ?: break
                if (!renderOne(id, next)) failedKeys += TtsParagraphKey.of(next)
            }
        }
    }

    private suspend fun nextToRender(): String? {
        val paragraphs = chapterParagraphs
        val from = renderFrom
        val signature = signature()
        for (index in from until minOf(from + RENDER_AHEAD, paragraphs.size)) {
            val text = paragraphs[index]
            val key = TtsParagraphKey.of(text)
            if (key in failedKeys) continue
            if (store.find(key, signature)?.let { File(it.path).isFile } != true) return text
        }
        return null
    }

    private suspend fun renderOne(chapterId: Long, text: String): Boolean {
        val signature = signature()
        val key = TtsParagraphKey.of(text)
        val dir = File(context.filesDir, "$AUDIO_DIR/$chapterId").apply { mkdirs() }
        val name = "$key-${signature.hashCode().toUInt().toString(16)}"
        val wav = File(dir, "$name.wav")
        val out = File(dir, "$name.m4a")
        val engine = renderer ?: SystemTtsRenderer(context, enginePackage).also { renderer = it }
        try {
            if (!engine.render(text, preferences.readerTtsVoice().get(), preferences.readerTtsPitch().get(), wav)) {
                return false
            }
            val durationMs = TtsAudioEncoder.wavToAac(wav, out) ?: return false
            store.add(key, signature, chapterId, out.path, out.length(), durationMs, System.currentTimeMillis())
            val capBytes = preferences.readerTtsRenderCapMb().get() * BYTES_PER_MB
            store.evictOldest(capBytes).forEach { File(it).delete() }
            return true
        } finally {
            wav.delete()
        }
    }

    companion object {
        /** Under `filesDir`, one folder per chapter. */
        const val AUDIO_DIR = "tts"

        private const val RENDER_AHEAD = 5
        private const val PROGRESS_POLL_MS = 50L
        private const val BYTES_PER_MB = 1024L * 1024L
    }
}
