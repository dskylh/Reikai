package reikai.data.novel.tts

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Turns the WAV a speech engine renders into AAC in an MP4 container, about a tenth of the size. AAC
 * rather than the pack format's Opus because Android's Opus encoder takes only 8, 12, 16, 24 and 48 kHz,
 * and many system voices render at 22.05 kHz. The player reads both.
 */
internal object TtsAudioEncoder {

    /** The encoded audio's length in milliseconds, or null when [wav] is not 16-bit PCM or encoding fails. */
    fun wavToAac(wav: File, out: File): Long? {
        val pcm = WavPcm.read(wav) ?: return null
        return runCatching { encode(wav, pcm, out) }.getOrElse {
            out.delete()
            null
        }
    }

    private fun encode(wav: File, pcm: WavPcm, out: File): Long {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, pcm.sampleRate, pcm.channels).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, INPUT_CHUNK)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        val muxer = MediaMuxer(out.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var muxing = false
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            RandomAccessFile(wav, "r").use { input ->
                input.seek(pcm.dataOffset)
                val chunk = ByteArray(INPUT_CHUNK)
                val bytesPerFrame = pcm.channels * 2L
                var fed = 0L
                var inputDone = false
                var track = -1
                val info = MediaCodec.BufferInfo()
                // Each pass fills every free input buffer and drains every ready output without waiting,
                // and blocks only when neither moved: a wait per AAC frame is slower than the engine.
                drain@ while (true) {
                    var moved = false
                    while (!inputDone) {
                        val index = codec.dequeueInputBuffer(0)
                        if (index < 0) break
                        moved = true
                        val buffer = codec.getInputBuffer(index)!!
                        val want = minOf(buffer.capacity().toLong(), chunk.size.toLong(), pcm.dataLength - fed).toInt()
                        val read = if (want > 0) input.read(chunk, 0, want) else -1
                        val timeUs = fed / bytesPerFrame * 1_000_000L / pcm.sampleRate
                        if (read <= 0) {
                            codec.queueInputBuffer(index, 0, 0, timeUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            buffer.clear()
                            buffer.put(chunk, 0, read)
                            codec.queueInputBuffer(index, 0, read, timeUs, 0)
                            fed += read
                        }
                    }
                    while (true) {
                        val outIndex = codec.dequeueOutputBuffer(info, if (moved) 0 else TIMEOUT_US)
                        if (outIndex == MediaCodec.INFO_TRY_AGAIN_LATER) break
                        moved = true
                        if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                            track = muxer.addTrack(codec.outputFormat)
                            muxer.start()
                            muxing = true
                        } else if (outIndex >= 0) {
                            val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                            if (info.size > 0 && !isConfig && muxing) {
                                val buffer = codec.getOutputBuffer(outIndex)!!
                                buffer.position(info.offset)
                                buffer.limit(info.offset + info.size)
                                muxer.writeSampleData(track, buffer, info)
                            }
                            codec.releaseOutputBuffer(outIndex, false)
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break@drain
                        }
                    }
                }
            }
        } finally {
            runCatching { codec.stop() }
            codec.release()
            if (muxing) runCatching { muxer.stop() }
            muxer.release()
        }
        return pcm.durationMs
    }

    private const val BIT_RATE = 48_000
    private const val INPUT_CHUNK = 16 * 1024
    private const val TIMEOUT_US = 10_000L
}

/** Where a WAV file's 16-bit PCM samples sit, read from its RIFF chunks. */
internal data class WavPcm(
    val sampleRate: Int,
    val channels: Int,
    val dataOffset: Long,
    val dataLength: Long,
) {
    val durationMs: Long get() = dataLength * 1000L / (sampleRate.toLong() * channels * 2)

    companion object {

        /**
         * Null for anything but 16-bit PCM. A data chunk whose stated size overruns the file, which some
         * engines leave as a placeholder, is read to the end of the file instead.
         */
        fun read(file: File): WavPcm? = runCatching {
            RandomAccessFile(file, "r").use { input ->
                val header = ByteArray(12)
                input.readFully(header)
                if (String(header, 0, 4) != "RIFF" || String(header, 8, 4) != "WAVE") return null
                var sampleRate = 0
                var channels = 0
                var bits = 0
                var format = 0
                val chunkHeader = ByteArray(8)
                while (input.filePointer + 8 <= input.length()) {
                    input.readFully(chunkHeader)
                    val id = String(chunkHeader, 0, 4)
                    val size =
                        ByteBuffer.wrap(chunkHeader, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL
                    when (id) {
                        "fmt " -> {
                            val fmt = ByteArray(16)
                            input.readFully(fmt)
                            val b = ByteBuffer.wrap(fmt).order(ByteOrder.LITTLE_ENDIAN)
                            format = b.short.toInt()
                            channels = b.short.toInt()
                            sampleRate = b.int
                            b.int
                            b.short
                            bits = b.short.toInt()
                            input.seek(input.filePointer + size - 16 + (size and 1))
                        }
                        "data" -> {
                            if (format != 1 || bits != 16 || channels < 1 || sampleRate <= 0) return null
                            val offset = input.filePointer
                            val length = minOf(size, input.length() - offset)
                            return WavPcm(sampleRate, channels, offset, length - length % (channels * 2))
                        }
                        else -> input.seek(input.filePointer + size + (size and 1))
                    }
                }
                null
            }
        }.getOrNull()
    }
}
