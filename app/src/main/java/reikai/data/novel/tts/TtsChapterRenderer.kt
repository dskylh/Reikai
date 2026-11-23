package reikai.data.novel.tts

import android.content.Context
import dev.zacsweers.metro.Inject
import eu.kanade.tachiyomi.util.lang.Hash
import kotlinx.coroutines.delay
import reikai.domain.novel.NovelChapterRepository
import reikai.domain.novel.NovelPreferences
import reikai.domain.novel.NovelRepository
import reikai.domain.novel.model.NovelChapter
import reikai.domain.novel.tts.TtsAudioRepository
import reikai.domain.novel.tts.TtsAudioSource
import reikai.domain.novel.tts.TtsParagraphKey
import reikai.domain.novel.tts.TtsPlayback
import reikai.domain.novel.tts.TtsRenderPriority
import reikai.novel.download.NovelDownloadManager
import reikai.novel.install.LnPluginInstaller
import reikai.novel.source.NovelChapterTextCache
import reikai.novel.source.NovelChapterTextLoader
import reikai.novel.source.NovelSourceManager
import reikai.presentation.reader.text.NovelTextRenderer
import java.io.File
import kotlin.time.Duration.Companion.seconds

/**
 * Renders a whole chapter for the render queue: its text loaded the way the reader loads it, split into
 * the paragraphs read-aloud speaks, then each one missing rendered on this phone or by the PC server.
 * Built per worker run, since the text loader and the phone renderer hold per-run state.
 */
@Inject
class TtsChapterRenderer(
    private val context: Context,
    private val preferences: NovelPreferences,
    private val store: TtsAudioRepository,
    private val server: TtsServerClient,
    private val novelRepo: NovelRepository,
    private val chapterRepo: NovelChapterRepository,
    sourceManager: NovelSourceManager,
    installer: LnPluginInstaller,
    textCache: NovelChapterTextCache,
    downloadManager: () -> NovelDownloadManager,
) {

    private val loader = NovelChapterTextLoader(
        context = context,
        novelRepo = novelRepo,
        sourceManager = sourceManager,
        installer = installer,
        preferences = preferences,
        textCache = textCache,
        readDownloaded = { novel, chapter -> downloadManager().getChapterText(novel, chapter) },
    )

    private var phone: PhoneParagraphRenderer? = null

    /** The chapter's name, for the progress notification. */
    suspend fun nameOf(chapterId: Long): String = chapterRepo.getById(chapterId)?.name.orEmpty()

    /**
     * True once every paragraph has audio in the chosen source's voice. [isWanted] is asked between
     * paragraphs, so a chapter the window dropped stops early.
     */
    suspend fun render(chapterId: Long, priority: TtsRenderPriority, isWanted: suspend () -> Boolean): Boolean {
        val chapter = chapterRepo.getById(chapterId) ?: return false
        val (html, baseUrl) = loader.load(chapter)
        val paragraphs = NovelTextRenderer.paragraphsOf(html, baseUrl, context.resources.displayMetrics.widthPixels)
        return if (preferences.readerTtsAudioSource().get() == TtsAudioSource.PC) {
            renderOnPc(chapter, paragraphs, priority, isWanted)
        } else {
            renderOnPhone(chapterId, paragraphs, isWanted)
        }
    }

    fun shutdown() {
        phone?.shutdown()
        phone = null
    }

    private suspend fun renderOnPhone(
        chapterId: Long,
        paragraphs: List<String>,
        isWanted: suspend () -> Boolean,
    ): Boolean {
        val renderer = phone ?: PhoneParagraphRenderer(
            context,
            preferences.readerTtsEngine().get(),
            preferences,
            store,
        ).also { phone = it }
        var complete = true
        for (text in paragraphs.distinct()) {
            if (!isWanted()) return false
            if (renderer.isRendered(text)) continue
            if (!renderer.render(chapterId, text)) complete = false
        }
        return complete
    }

    private suspend fun renderOnPc(
        chapter: NovelChapter,
        paragraphs: List<String>,
        priority: TtsRenderPriority,
        isWanted: suspend () -> Boolean,
    ): Boolean {
        val signature = TtsRenderSignature.pc(preferences)
        val missing = paragraphs.withIndex()
            .map { (index, text) -> JobParagraph(index, TtsParagraphKey.of(text), text) }
            .distinctBy { it.key }
            .filter { store.find(it.key, signature)?.let { audio -> File(audio.path).isFile } != true }
        if (missing.isEmpty()) return true
        val novel = novelRepo.getById(chapter.novelId) ?: return false
        val job = server.submit(
            JobRequest(
                book = JobBook("${novel.source}:${Hash.sha256(novel.url)}", novel.source, novel.url, novel.title),
                engine = JobEngine(preferences.readerTtsServerModel().get(), preferences.readerTtsServerVoice().get()),
                splitter = TtsParagraphKey.SPLITTER_VERSION,
                priority = if (priority == TtsRenderPriority.WINDOW) JobPriority.WINDOW else JobPriority.MANUAL,
                chapters = listOf(JobChapter(chapter.url, chapter.name, missing)),
            ),
        )
        val wanted = missing.mapTo(HashSet()) { it.key }
        val importer = TtsPackImporter(store)
        val dir = File(context.filesDir, "${PhoneParagraphRenderer.AUDIO_DIR}/${chapter.id}")
        var imported = 0
        while (true) {
            if (!isWanted()) {
                runCatching { server.cancel(job.job) }
                return false
            }
            val status = server.status(job.job).chapters.firstOrNull() ?: return false
            // Only fetched when more is finished, since every fetch sends the whole chapter so far.
            if (status.done > imported) {
                val pack = File.createTempFile("tts", ".ttspack", context.cacheDir)
                try {
                    if (server.downloadPack(job.job, 0, pack)) importer.import(pack, chapter.id, wanted, signature, dir)
                } finally {
                    pack.delete()
                }
                imported = status.done
            }
            if (status.isFinished) return status.state == "done"
            delay(if (isPlaying()) POLL_PLAYING else POLL_IDLE)
        }
    }

    private fun isPlaying() = NovelTtsSession.state.value.playback == TtsPlayback.Playing

    private companion object {
        val POLL_PLAYING = 3.seconds
        val POLL_IDLE = 60.seconds
    }
}
