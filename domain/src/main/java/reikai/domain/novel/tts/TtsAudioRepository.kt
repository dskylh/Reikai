package reikai.domain.novel.tts

/** A paragraph's rendered audio file and how long it plays at normal speed. */
data class RenderedParagraph(val path: String, val durationMs: Long)

/**
 * The index of pre-rendered read-aloud audio, one row per paragraph and voice. A row is found by the
 * paragraph's key and the voice's signature, so a voice change misses instead of playing the old voice.
 * The files themselves belong to the caller: the methods that drop rows hand back the paths to delete.
 * See `data/novel_tts_audio.sq`.
 */
interface TtsAudioRepository {

    suspend fun find(key: String, signature: String): RenderedParagraph?

    /** Records a finished render, replacing any earlier row for the same key and signature. */
    suspend fun add(
        key: String,
        signature: String,
        chapterId: Long,
        path: String,
        sizeBytes: Long,
        durationMs: Long,
        createdAt: Long,
    )

    /** Drops the oldest rows until the files total at most [maxBytes]; returns the dropped files' paths. */
    suspend fun evictOldest(maxBytes: Long): List<String>

    suspend fun totalBytes(): Long

    /** Drops every row, the render queue's included. */
    suspend fun clear()

    /**
     * Queues [chapterIds] for rendering. A chapter already known is queued again so its audio is checked
     * against the current voice, keeping the higher of its two [pinned] and the earlier [priority].
     */
    suspend fun enqueue(chapterIds: List<Long>, pinned: Boolean, priority: TtsRenderPriority, requestedAt: Long)

    /** The queued chapter to render next, window chapters first, oldest request first within each. */
    suspend fun nextQueued(): TtsQueuedChapter?

    suspend fun isQueued(chapterId: Long): Boolean

    suspend fun finish(chapterId: Long, rendered: Boolean)

    /** Takes queued chapters off the queue, except ones picked by hand. */
    suspend fun cancelQueued(chapterIds: List<Long>)

    /** Forgets [chapterIds] entirely, audio and queue alike; returns the files to delete. */
    suspend fun dropChapters(chapterIds: List<Long>): List<String>
}

/** Where a chapter waits in the render queue: the sliding window's ahead of chapters picked by hand. */
enum class TtsRenderPriority(val rank: Long) {
    WINDOW(0),
    MANUAL(1),
}

data class TtsQueuedChapter(val chapterId: Long, val priority: TtsRenderPriority)
