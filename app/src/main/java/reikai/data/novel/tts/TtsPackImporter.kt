package reikai.data.novel.tts

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import reikai.domain.novel.tts.TtsAudioRepository
import java.io.File
import java.util.zip.ZipFile

/**
 * Keeps the audio of a `.ttspack` a PC server sent for one chapter. The pack is untrusted: only entries its
 * manifest names, under `audio/`, for paragraphs Reikai asked for, within the size limits, are copied, and
 * each is copied to a name Reikai chooses, so no path in the pack ever reaches the file system.
 */
class TtsPackImporter(
    private val store: TtsAudioRepository,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {

    /**
     * Copies [pack]'s audio for the paragraph keys in [wanted] into [dir] and records it under [signature].
     * Returns how many paragraphs it kept; a pack with a missing or unreadable manifest keeps none.
     */
    suspend fun import(pack: File, chapterId: Long, wanted: Set<String>, signature: String, dir: File): Int {
        val manifest = ZipFile(pack).use { zip ->
            val entry = zip.getEntry(MANIFEST) ?: return 0
            if (entry.size > MAX_MANIFEST_BYTES) return 0
            val text = java.io.ByteArrayOutputStream()
            val withinCap = zip.getInputStream(entry).use { copyCapped(it, text, MAX_MANIFEST_BYTES) }
            if (!withinCap) return 0
            runCatching { json.decodeFromString<PackManifest>(text.toString(Charsets.UTF_8.name())) }.getOrNull()
                ?: return 0
        }
        if (manifest.format != FORMAT) return 0
        dir.mkdirs()
        var kept = 0
        ZipFile(pack).use { zip ->
            for (paragraph in manifest.chapters.flatMap { it.paragraphs }) {
                if (paragraph.sha256 !in wanted || !FILE_NAME.matches(paragraph.file)) continue
                val entry = zip.getEntry(paragraph.file) ?: continue
                if (entry.size > MAX_FILE_BYTES) continue
                val out = File(dir, "${paragraph.sha256}-pc.opus")
                val copied = zip.getInputStream(entry).use { input ->
                    out.outputStream().use { output -> copyCapped(input, output, MAX_FILE_BYTES) }
                }
                if (!copied) {
                    out.delete()
                    continue
                }
                store.add(
                    paragraph.sha256,
                    signature,
                    chapterId,
                    out.path,
                    out.length(),
                    paragraph.durationMs,
                    System.currentTimeMillis(),
                )
                kept++
            }
        }
        return kept
    }

    private fun copyCapped(input: java.io.InputStream, output: java.io.OutputStream, cap: Long): Boolean {
        val buffer = ByteArray(BUFFER)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) return true
            total += read
            if (total > cap) return false
            output.write(buffer, 0, read)
        }
    }

    companion object {
        const val MAX_PACK_BYTES = 256L * 1024 * 1024
        private const val MAX_FILE_BYTES = 5L * 1024 * 1024
        private const val MAX_MANIFEST_BYTES = 8L * 1024 * 1024
        private const val MANIFEST = "manifest.json"
        private const val FORMAT = 1
        private const val BUFFER = 64 * 1024
        private val FILE_NAME = Regex("audio/[0-9]{1,6}\\.opus")
    }
}

@Serializable
internal data class PackManifest(
    val format: Int,
    val chapters: List<PackChapter> = emptyList(),
)

@Serializable
internal data class PackChapter(val url: String = "", val paragraphs: List<PackParagraph> = emptyList())

@Serializable
internal data class PackParagraph(
    val index: Int,
    val sha256: String,
    val file: String,
    val durationMs: Long,
)
