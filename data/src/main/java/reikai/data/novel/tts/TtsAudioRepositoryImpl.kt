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
import tachiyomi.data.Database

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class TtsAudioRepositoryImpl(
    database: Database,
) : TtsAudioRepository {

    private val queries = database.novel_tts_audioQueries

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
    }

    private companion object {
        const val EVICT_BATCH = 50L
    }
}
