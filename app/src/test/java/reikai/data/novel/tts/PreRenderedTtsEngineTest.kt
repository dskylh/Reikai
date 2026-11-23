package reikai.data.novel.tts

import io.kotest.matchers.shouldBe
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import reikai.domain.novel.NovelPreferences
import reikai.domain.novel.tts.NovelTtsEngine
import reikai.domain.novel.tts.RenderedParagraph
import reikai.domain.novel.tts.TtsAudioRepository
import reikai.domain.novel.tts.TtsAudioSource
import reikai.domain.novel.tts.TtsEngineInfo
import reikai.domain.novel.tts.TtsPiece
import reikai.domain.novel.tts.TtsVoice
import reikai.presentation.recents.EmittingPreferenceStore

/** The paths that never reach a file: Live, and a paragraph nothing was rendered for. */
class PreRenderedTtsEngineTest {

    private val preferences = NovelPreferences(EmittingPreferenceStore())
    private val live = FakeLive()
    private val store = FakeStore()

    private fun TestScope.engine(): PreRenderedTtsEngine {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        return PreRenderedTtsEngine(
            live = live,
            context = mockk(relaxed = true),
            enginePackage = "engine",
            preferences = preferences,
            store = store,
            scope = CoroutineScope(dispatcher + Job()),
            io = dispatcher,
        )
    }

    private fun NovelTtsEngine.say(text: String) = speak(pieces(text, bySentence = false), {}, {})

    @Test
    fun `live never looks for a rendered file`() = runTest {
        preferences.readerTtsAudioSource().set(TtsAudioSource.LIVE)
        engine().say("One paragraph.")
        store.finds shouldBe 0
    }

    @Test
    fun `live hands the paragraph to the system voice`() = runTest {
        preferences.readerTtsAudioSource().set(TtsAudioSource.LIVE)
        engine().say("One paragraph.")
        live.spoken shouldBe listOf("One paragraph.")
    }

    @Test
    fun `a paragraph with no rendered file is spoken live`() = runTest {
        preferences.readerTtsAudioSource().set(TtsAudioSource.PHONE)
        engine().say("Not rendered yet.")
        live.spoken shouldBe listOf("Not rendered yet.")
    }

    @Test
    fun `switching the source takes effect at the next paragraph`() = runTest {
        val engine = engine()
        preferences.readerTtsAudioSource().set(TtsAudioSource.LIVE)
        engine.say("First.")
        preferences.readerTtsAudioSource().set(TtsAudioSource.PHONE)
        engine.say("Second.")
        store.finds shouldBe 1
    }

    private class FakeStore : TtsAudioRepository {
        var finds = 0

        override suspend fun find(key: String, signature: String): RenderedParagraph? {
            finds++
            return null
        }

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
    }

    private class FakeLive : NovelTtsEngine {
        val spoken = mutableListOf<String>()

        override fun availableEngines() = emptyList<TtsEngineInfo>()
        override fun availableVoices() = emptyList<TtsVoice>()
        override fun setVoice(voiceName: String) = Unit
        override fun setRate(rate: Float) = Unit
        override fun setPitch(pitch: Float) = Unit
        override fun pieces(text: String, bySentence: Boolean) = listOf(TtsPiece(text, 0, text.length))

        override fun speak(pieces: List<TtsPiece>, onPieceStart: (index: Int) -> Unit, onDone: () -> Unit) {
            spoken += pieces.joinToString(" ") { it.text }
        }

        override fun stop() = Unit
        override fun shutdown() = Unit
    }
}
