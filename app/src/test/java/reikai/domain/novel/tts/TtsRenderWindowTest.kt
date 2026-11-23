package reikai.domain.novel.tts

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class TtsRenderWindowTest {

    private val order = listOf(10L, 11L, 12L, 13L, 14L, 15L, 16L)

    @Test
    fun `the current chapter and the ones ahead are rendered, nearest first`() {
        TtsRenderWindow.plan(order, current = 13L, ahead = 2, keepBehind = 2).render shouldBe listOf(13L, 14L, 15L)
    }

    @Test
    fun `the window stops at the last chapter`() {
        TtsRenderWindow.plan(order, current = 15L, ahead = 5, keepBehind = 2).render shouldBe listOf(15L, 16L)
    }

    @Test
    fun `chapters further behind than keep behind lose their audio`() {
        TtsRenderWindow.plan(order, current = 14L, ahead = 3, keepBehind = 2).evict shouldBe listOf(10L, 11L)
    }

    @Test
    fun `moving forward one chapter evicts the one that falls out of keep behind`() {
        TtsRenderWindow.plan(order, current = 15L, ahead = 3, keepBehind = 2).evict shouldBe listOf(10L, 11L, 12L)
    }

    @Test
    fun `render ahead off renders nothing`() {
        TtsRenderWindow.plan(order, current = 13L, ahead = 0, keepBehind = 2).render shouldBe emptyList()
    }

    @Test
    fun `a chapter outside the reading order plans nothing`() {
        TtsRenderWindow.plan(order, current = 99L, ahead = 3, keepBehind = 0) shouldBe
            TtsRenderWindow.Plan(emptyList(), emptyList())
    }
}
