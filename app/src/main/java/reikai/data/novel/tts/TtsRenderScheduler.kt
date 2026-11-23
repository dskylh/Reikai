package reikai.data.novel.tts

import android.content.Context
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import reikai.domain.novel.NovelPreferences
import reikai.domain.novel.tts.TtsAudioRepository
import reikai.domain.novel.tts.TtsAudioSource
import reikai.domain.novel.tts.TtsRenderPriority
import reikai.domain.novel.tts.TtsRenderWindow
import java.io.File

/** Fills the read-aloud render queue: the sliding window as chapters open, and chapters picked by hand. */
@Inject
@SingleIn(AppScope::class)
class TtsRenderScheduler(
    private val context: Context,
    private val preferences: NovelPreferences,
    private val store: TtsAudioRepository,
) {

    /**
     * Moves the window to [chapterId] in [readingOrder]: queues it and the chapters after it, cancels the
     * window's queued chapters that fell out, and deletes audio too far behind. Nothing while speaking live.
     */
    suspend fun onChapterOpened(readingOrder: List<Long>, chapterId: Long) {
        val source = preferences.readerTtsAudioSource().get()
        if (source == TtsAudioSource.LIVE) return
        val plan = TtsRenderWindow.plan(
            readingOrder,
            chapterId,
            ahead = preferences.readerTtsRenderAhead().get(),
            keepBehind = preferences.readerTtsKeepBehind().get(),
        )
        val outside = readingOrder.toSet() - plan.render.toSet()
        store.cancelQueued(outside.toList())
        store.dropChapters(plan.evict).forEach { File(it).delete() }
        if (plan.render.isEmpty()) return
        store.enqueue(plan.render, pinned = false, TtsRenderPriority.WINDOW, System.currentTimeMillis())
        TtsRenderWorker.start(context, source)
    }

    /** Queues [chapterIds] in this order, pinned so the window leaves them alone until they are read past. */
    suspend fun preRender(chapterIds: List<Long>) {
        if (chapterIds.isEmpty()) return
        store.enqueue(chapterIds, pinned = true, TtsRenderPriority.MANUAL, System.currentTimeMillis())
        TtsRenderWorker.start(context, preferences.readerTtsAudioSource().get())
    }
}
