package reikai.domain.novel.tts

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test

class TtsParagraphKeyTest {

    @Test
    fun `decomposed and composed accents give the same key`() {
        TtsParagraphKey.of("Café au lait") shouldBe TtsParagraphKey.of("Café au lait")
    }

    @Test
    fun `whitespace runs and padding give the same key`() {
        TtsParagraphKey.of("  The rain\n\tkept   falling. ") shouldBe TtsParagraphKey.of("The rain kept falling.")
    }

    @Test
    fun `a one letter change gives a different key`() {
        TtsParagraphKey.of("The rain kept falling.") shouldNotBe TtsParagraphKey.of("The rain kept failing.")
    }

    @Test
    fun `the key is the contract's lowercase hex SHA-256 of the normalized text`() {
        TtsParagraphKey.of(" abc ") shouldBe "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
    }
}
