package reikai.data.novel.tts

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class WavPcmTest {

    @TempDir
    lateinit var dir: File

    private fun wav(
        bits: Int = 16,
        format: Int = 1,
        dataSize: Int = 44_100,
        samples: Int = 22_050,
        extra: Boolean = false,
    ): File {
        val fmt = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
            .putShort(format.toShort()).putShort(1).putInt(22_050).putInt(44_100).putShort(2).putShort(bits.toShort())
            .array()
        val list = if (extra) "LIST".toByteArray() + le(4) + ByteArray(4) else ByteArray(0)
        val body = "WAVE".toByteArray() + "fmt ".toByteArray() + le(16) + fmt + list +
            "data".toByteArray() + le(dataSize) + ByteArray(samples * 2)
        return File(dir, "a.wav").apply { writeBytes("RIFF".toByteArray() + le(body.size) + body) }
    }

    private fun le(value: Int) = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()

    @Test
    fun `a 16-bit mono file reads its rate and length`() {
        WavPcm.read(wav())?.durationMs shouldBe 1000L
    }

    @Test
    fun `a chunk between fmt and data is skipped`() {
        WavPcm.read(wav(extra = true))?.durationMs shouldBe 1000L
    }

    @Test
    fun `a placeholder data size past the end reads to the end of the file`() {
        WavPcm.read(wav(dataSize = Int.MAX_VALUE))?.durationMs shouldBe 1000L
    }

    @Test
    fun `8-bit audio is refused`() {
        WavPcm.read(wav(bits = 8)) shouldBe null
    }
}
