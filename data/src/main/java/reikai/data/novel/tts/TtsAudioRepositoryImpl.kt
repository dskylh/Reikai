package reikai.data.novel.tts

import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.async.coroutines.awaitAsOne
import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import reikai.domain.novel.tts.RenderedParagraph
import reikai.domain.novel.tts.TtsAudioRepository
import reikai.domain.novel.tts.TtsQueuedChapter
import reikai.domain.novel.tts.TtsRenderPriority
import tachiyomi.data.Database

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class TtsAudioRepositoryImpl(
    database: Database,
) : TtsAudioRepository {

    private val queries = database.novel_tts_audioQueries
    private val queue = database.novel_tts_chapterQueries

    override suspend fun find(key: String, signature: String): RenderedParagraph? =
        queries.find(key, signature) { path, durationMs -> RenderedParagraph(path, durationMs) }.awaitAsOneOrNull()

    override suspend fun add(
        key: String,
        signature: String,
        chapterId: Long,
        path: String,
        sizeBytes: Long,
        durationMs: Long,
        createdAt: Long,
    ) {
        queries.upsert(key, signature, chapterId, path, sizeBytes, durationMs, createdAt)
    }

    override suspend fun evictOldest(maxBytes: Long): List<String> {
        var total = totalBytes()
        val dropped = mutableListOf<String>()
        while (total > maxBytes) {
            val batch = queries.oldest(EVICT_BATCH).awaitAsList()
            if (batch.isEmpty()) break
            for (row in batch) {
                if (total <= maxBytes) break
                queries.deleteById(row._id)
                dropped += row.file_path
                total -= row.size_bytes
            }
        }
        return dropped
    }

    override suspend fun totalBytes(): Long = queries.totalBytes().awaitAsOne()

    override suspend fun clear() {
        queries.deleteAll()
        queue.deleteAll()
    }

    override suspend fun enqueue(
        chapterIds: List<Long>,
        pinned: Boolean,
        priority: TtsRenderPriority,
        requestedAt: Long,
    ) {
        val pin = if (pinned) 1L else 0L
        // In order, a millisecond apart, so the queue keeps the order the chapters were asked in.
        chapterIds.forEachIndexed { index, id ->
            queue.insertIfAbsent(id, pin, priority.rank, requestedAt + index)
            queue.requeue(pin, priority.rank, id)
        }
    }

    override suspend fun nextQueued(): TtsQueuedChapter? = queue.nextQueued { chapterId, priority ->
        TtsQueuedChapter(
            chapterId,
            TtsRenderPriority.entries.firstOrNull { it.rank == priority } ?: TtsRenderPriority.MANUAL,
        )
    }.awaitAsOneOrNull()

    override suspend fun isQueued(chapterId: Long): Boolean = queue.isQueued(chapterId).awaitAsOne() > 0

    override suspend fun finish(chapterId: Long, rendered: Boolean) {
        queue.setState(if (rendered) STATE_RENDERED else STATE_FAILED, chapterId)
    }

    override suspend fun cancelQueued(chapterIds: List<Long>) {
        chapterIds.chunked(SQL_CHUNK).forEach { queue.cancelUnpinnedQueued(it) }
    }

    override suspend fun dropChapters(chapterIds: List<Long>): List<String> = chapterIds.chunked(SQL_CHUNK).flatMap {
        val paths = queries.pathsOfChapters(it).awaitAsList()
        queries.deleteOfChapters(it)
        queue.deleteChapters(it)
        paths
    }

    private companion object {
        const val EVICT_BATCH = 50L
        const val STATE_RENDERED = 1L
        const val STATE_FAILED = 2L

        // Under SQLite's oldest bound on query parameters.
        const val SQL_CHUNK = 500
    }
}
