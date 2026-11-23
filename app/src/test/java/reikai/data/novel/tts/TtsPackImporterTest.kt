package reikai.data.novel.tts

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import reikai.domain.novel.tts.RenderedParagraph
import reikai.domain.novel.tts.TtsAudioRepository
import reikai.domain.novel.tts.TtsQueuedChapter
import reikai.domain.novel.tts.TtsRenderPriority
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** A pack comes from the network, so only what Reikai asked for, where it expects it, is kept. */
class TtsPackImporterTest {

    @TempDir
    lateinit var dir: File

    private val store = RecordingStore()
    private val key = "a".repeat(64)
    private val other = "b".repeat(64)

    private fun pack(file: String, sha256: String = key, format: Int = 1): File {
        val manifest = """{"format":$format,"chapters":[{"url":"u","paragraphs":[""" +
            """{"index":0,"sha256":"$sha256","file":"$file","durationMs":1200}]}]}"""
        return File(dir, "p.ttspack").apply {
            ZipOutputStream(outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("manifest.json"))
                zip.write(manifest.toByteArray())
                zip.putNextEntry(ZipEntry(file))
                zip.write(ByteArray(10))
            }
        }
    }

    private suspend fun import(pack: File) =
        TtsPackImporter(
            store,
        ).import(pack, chapterId = 1L, wanted = setOf(key), signature = "pc", dir = File(dir, "out"))

    @Test
    fun `a paragraph that was asked for is kept`() = runTest {
        import(pack("audio/0000.opus")) shouldBe 1
    }

    @Test
    fun `a path leaving the audio folder is refused`() = runTest {
        import(pack("audio/../../evil.opus")) shouldBe 0
    }

    @Test
    fun `a paragraph nobody asked for is refused`() = runTest {
        import(pack("audio/0000.opus", sha256 = other)) shouldBe 0
    }

    @Test
    fun `an unknown pack format is refused`() = runTest {
        import(pack("audio/0000.opus", format = 2)) shouldBe 0
    }

    private class RecordingStore : TtsAudioRepository {
        override suspend fun find(key: String, signature: String): RenderedParagraph? = null
        override suspend fun add(
            key: String,
            signature: String,
            chapterId: Long,
            path: String,
            sizeBytes: Long,
            durationMs: Long,
            createdAt: Long,
        ) = Unit

        override suspend fun evictOldest(maxBytes: Long) = emptyList<String>()
        override suspend fun totalBytes() = 0L
        override suspend fun clear() = Unit
        override suspend fun enqueue(
            chapterIds: List<Long>,
            pinned: Boolean,
            priority: TtsRenderPriority,
            requestedAt: Long,
        ) = Unit

        override suspend fun nextQueued(): TtsQueuedChapter? = null
        override suspend fun isQueued(chapterId: Long) = false
        override suspend fun finish(chapterId: Long, rendered: Boolean) = Unit
        override suspend fun cancelQueued(chapterIds: List<Long>) = Unit
        override suspend fun dropChapters(chapterIds: List<Long>) = emptyList<String>()
    }
}
