package reikai.data.novel.tts

import android.content.Context
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import reikai.domain.novel.tts.TtsAudioRepository
import tachiyomi.core.common.util.lang.withIOContext
import java.io.File

/** Pre-rendered read-aloud audio as a whole, for the settings that show its size and clear it. */
@Inject
@SingleIn(AppScope::class)
class RenderedTtsAudio(
    private val context: Context,
    private val store: TtsAudioRepository,
) {

    suspend fun usedBytes(): Long = withIOContext { store.totalBytes() }

    /** Rows first, so a render finishing meanwhile cannot leave a row pointing at a deleted file. */
    suspend fun clear() {
        withIOContext {
            store.clear()
            File(context.filesDir, PhoneParagraphRenderer.AUDIO_DIR).deleteRecursively()
        }
    }
}
