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

    /** Drops every row. */
    suspend fun clear()
}
