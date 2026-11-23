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
import kotlinx.coroutines.withTimeoutOrNull
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
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration.Companion.seconds

/**
 * [NovelTtsEngine] that plays a paragraph from a file rendered ahead of time when one exists, and hands it
 * to [live] when not. Voices, engines and splitting all stay [live]'s. The audio source is read on every
 * [speak], so switching it takes effect at the next paragraph. With the phone as the source, a paragraph
 * of the prepared chapter that has no file is rendered before it plays (live only past [MISS_RENDER_WAIT]),
 * and the next few are rendered while it plays. A piece's start inside a file is placed by its share of
 * the paragraph's characters.
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
    private val phone = PhoneParagraphRenderer(context, enginePackage, preferences, store)

    /** Keys that failed to render this session, so a paragraph the engine refuses is not retried forever. */
    private val failedKeys: MutableSet<String> = ConcurrentHashMap.newKeySet()

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
            val signature = if (source == TtsAudioSource.PC) TtsRenderSignature.pc(preferences) else phone.signature
            suspend fun lookup() = withContext(io) { store.find(key, signature)?.takeIf { File(it.path).isFile } }
            var audio = lookup()
            // Rendered first rather than spoken live: Android serves one engine request at a time and feeds
            // live speech at playback pace, so live speech would hold every render behind it.
            if (audio == null && source == TtsAudioSource.PHONE && renderAheadOf(text, includingIt = true)) {
                audio = withTimeoutOrNull(MISS_RENDER_WAIT) {
                    var found = lookup()
                    while (found == null && key !in failedKeys) {
                        delay(MISS_RENDER_POLL_MS)
                        found = lookup()
                    }
                    found
                }
            }
            if (spoken != generation) return@launch
            if (audio == null) {
                live.speak(pieces, onPieceStart, onDone)
            } else {
                play(audio, text, pieces, spoken, onPieceStart, onDone)
            }
            renderAheadOf(text, includingIt = false)
        }
    }

    override fun stop() {
        stopPlayback()
        live.stop()
    }

    override fun shutdown() {
        stopPlayback()
        renderJob?.cancel()
        phone.shutdown()
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

    /**
     * Keeps the [RENDER_AHEAD] paragraphs from [text], or from the one after it, rendered one at a time
     * while the phone is the source. False when [text] is not in the prepared chapter, so nothing renders.
     */
    private fun renderAheadOf(text: String, includingIt: Boolean): Boolean {
        val at = chapterParagraphs.indexOf(text)
        if (at < 0) return false
        renderFrom = if (includingIt) at else at + 1
        if (renderJob?.isActive == true) return true
        renderJob = scope.launch(io) {
            while (isActive) {
                if (preferences.readerTtsAudioSource().get() != TtsAudioSource.PHONE) break
                val id = chapterId ?: break
                val next = nextToRender() ?: break
                if (!phone.render(id, next)) failedKeys += TtsParagraphKey.of(next)
            }
        }
        return true
    }

    private suspend fun nextToRender(): String? {
        val paragraphs = chapterParagraphs
        val from = renderFrom
        for (index in from until minOf(from + RENDER_AHEAD, paragraphs.size)) {
            val text = paragraphs[index]
            if (TtsParagraphKey.of(text) in failedKeys) continue
            if (!phone.isRendered(text)) return text
        }
        return null
    }

    private companion object {
        const val RENDER_AHEAD = 5
        const val PROGRESS_POLL_MS = 50L
        const val MISS_RENDER_POLL_MS = 100L

        /** How long a paragraph with no file waits for its render before it is spoken live instead. */
        val MISS_RENDER_WAIT = 10.seconds
    }
}
