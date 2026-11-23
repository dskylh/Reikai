package reikai.presentation.reader.text

import io.kotest.matchers.shouldBe
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Rendering ahead hashes these, so they must be the paragraphs read-aloud is handed in the reader. */
@RunWith(RobolectricTestRunner::class)
class ChapterParagraphsTest {

    private fun paragraphs(html: String) = NovelTextRenderer.paragraphsOf(html, baseUrl = null, targetWidth = 1080)

    @Test
    fun `each block is one paragraph with its whitespace collapsed`() {
        paragraphs("<p>First   one.</p><p>Second\none.</p>") shouldBe listOf("First one.", "Second one.")
    }

    @Test
    fun `a line break starts a new paragraph`() {
        paragraphs("<p>Line one<br>Line two</p>") shouldBe listOf("Line one", "Line two")
    }

    @Test
    fun `a picture's line is not a paragraph`() {
        paragraphs("<p>Before</p><img src=\"a.png\"><p>After</p>") shouldBe listOf("Before", "After")
    }

    @Test
    fun `ruby readings are left out`() {
        paragraphs("<p><ruby>漢<rt>かん</rt></ruby>字</p>") shouldBe listOf("漢字")
    }
}
