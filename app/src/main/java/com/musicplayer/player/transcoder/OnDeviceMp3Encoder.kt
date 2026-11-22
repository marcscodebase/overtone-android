package com.musicplayer.player.transcoder

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import com.musicplayer.core.common.AppDispatcher
import com.musicplayer.core.common.Dispatcher
import de.sciss.jump3r.mp3.BitStream
import de.sciss.jump3r.mp3.GainAnalysis
import de.sciss.jump3r.mp3.GetAudio
import de.sciss.jump3r.mp3.ID3Tag
import de.sciss.jump3r.mp3.Lame
import de.sciss.jump3r.mp3.LameGlobalFlags
import de.sciss.jump3r.mp3.MPEGMode
import de.sciss.jump3r.mp3.Parse
import de.sciss.jump3r.mp3.Presets
import de.sciss.jump3r.mp3.PsyModel
import de.sciss.jump3r.mp3.Quantize
import de.sciss.jump3r.mp3.QuantizePVT
import de.sciss.jump3r.mp3.Reservoir
import de.sciss.jump3r.mp3.Takehiro
import de.sciss.jump3r.mp3.VBRTag
import de.sciss.jump3r.mp3.VbrMode
import de.sciss.jump3r.mp3.Version
import de.sciss.jump3r.mpg.Common
import de.sciss.jump3r.mpg.Interface
import de.sciss.jump3r.mpg.MPGLib
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlin.math.roundToInt

interface OnDeviceMp3Encoder {
    suspend fun encodeToMp3(
        sourceFile: File,
        targetMp3File: File,
        bitrateKbps: Int,
    ): Boolean
}

/**
 * Pure JVM/Android LAME MP3 encoder wrapping jump3r's internal MP3 engine without any
 * javax.sound dependencies. Encodes interleaved 16-bit signed little-endian PCM streams.
 */
class PcmMp3Encoder(
    sampleRateHz: Int,
    channelCount: Int,
    bitrateKbps: Int,
) : AutoCloseable {

    private val channels = channelCount.coerceIn(1, 2)
    private val lame: Lame
    private val gfp: LameGlobalFlags
    private var closed = false

    init {
        val lameEngine = Lame()
        val getAudio = GetAudio()
        val gainAnalysis = GainAnalysis()
        val bitStream = BitStream()
        val presets = Presets()
        val quantizePvt = QuantizePVT()
        val quantize = Quantize()
        val vbrTag = VBRTag()
        val version = Version()
        val id3Tag = ID3Tag()
        val reservoir = Reservoir()
        val takehiro = Takehiro()
        val parse = Parse()
        val psyModel = PsyModel()
        val mpgLib = MPGLib()
        val mpgInterface = Interface()
        val mpgCommon = Common()

        lameEngine.setModules(
            gainAnalysis,
            bitStream,
            presets,
            quantizePvt,
            quantize,
            vbrTag,
            version,
            id3Tag,
            mpgLib,
        )
        bitStream.setModules(gainAnalysis, mpgLib, version, vbrTag)
        id3Tag.setModules(bitStream, version)
        presets.setModules(lameEngine)
        quantize.setModules(bitStream, reservoir, quantizePvt, takehiro)
        quantizePvt.setModules(takehiro, reservoir, psyModel)
        reservoir.setModules(bitStream)
        takehiro.setModules(quantizePvt)
        vbrTag.setModules(lameEngine, bitStream, version)
        getAudio.setModules(parse, mpgLib)
        parse.setModules(version, id3Tag, presets)
        mpgLib.setModules(mpgInterface, mpgCommon)
        mpgInterface.setModules(vbrTag, mpgCommon)

        val flags = lameEngine.lame_init()
        id3Tag.id3tag_init(flags)
        flags.write_id3tag_automatic = false
        flags.num_channels = channels
        flags.in_samplerate = sampleRateHz.coerceAtLeast(8000)
        flags.out_samplerate = resolveOutputSampleRate(sampleRateHz)
        flags.brate = bitrateKbps.coerceIn(64, 320)
        flags.quality = 2
        flags.VBR = VbrMode.vbr_off
        flags.bWriteVbrTag = false
        flags.mode = if (channels == 1) MPEGMode.MONO else MPEGMode.JOINT_STEREO

        val initResult = lameEngine.lame_init_params(flags)
        if (initResult < 0) {
            lameEngine.lame_close(flags)
            throw IOException("Failed to initialize LAME encoder (code=$initResult)")
        }

        lame = lameEngine
        gfp = flags
    }

    fun encodePcm16Le(
        pcmBytes: ByteArray,
        offset: Int = 0,
        length: Int = pcmBytes.size,
        output: OutputStream,
    ) {
        check(!closed) { "Encoder is already closed" }
        val bytesPerFrame = 2 * channels
        val sampleFrames = length / bytesPerFrame
        if (sampleFrames <= 0) return

        val left = IntArray(sampleFrames)
        val right = IntArray(sampleFrames)

        var readIndex = offset
        if (channels == 1) {
            for (i in 0 until sampleFrames) {
                val sample = decodePcm16LeScaled(pcmBytes, readIndex)
                left[i] = sample
                right[i] = sample
                readIndex += 2
            }
        } else {
            for (i in 0 until sampleFrames) {
                left[i] = decodePcm16LeScaled(pcmBytes, readIndex)
                right[i] = decodePcm16LeScaled(pcmBytes, readIndex + 2)
                readIndex += 4
            }
        }

        val mp3BufferSize = max(7200, (1.25 * sampleFrames + 7200).toInt())
        val mp3Buffer = ByteArray(mp3BufferSize)
        val encodedBytes = lame.lame_encode_buffer_int(
            gfp,
            left,
            right,
            sampleFrames,
            mp3Buffer,
            0,
            mp3Buffer.size,
        )
        if (encodedBytes < 0) {
            throw IOException("LAME encode failed (code=$encodedBytes)")
        }
        if (encodedBytes > 0) {
            output.write(mp3Buffer, 0, encodedBytes)
        }
    }

    fun flush(output: OutputStream) {
        if (closed) return
        val flushBuffer = ByteArray(7200)
        val flushedBytes = lame.lame_encode_flush(gfp, flushBuffer, 0, flushBuffer.size)
        if (flushedBytes > 0) {
            output.write(flushBuffer, 0, flushedBytes)
        }
    }

    override fun close() {
        if (!closed) {
            closed = true
            lame.lame_close(gfp)
        }
    }

    private fun decodePcm16LeScaled(bytes: ByteArray, index: Int): Int {
        val low = bytes[index].toInt() and 0xFF
        val high = bytes[index + 1].toInt()
        return (low shl 16) or (high shl 24)
    }

    companion object {
        internal fun resolveOutputSampleRate(inputRateHz: Int): Int = when {
            inputRateHz > 48000 -> if (inputRateHz % 44100 == 0) 44100 else 48000
            inputRateHz in setOf(8000, 11025, 12000, 16000, 22050, 24000, 32000, 44100, 48000) -> inputRateHz
            else -> 44100
        }
    }
}

/**
 * Android on-device transcoder that decodes any ExoPlayer/MediaCodec-supported audio container
 * (FLAC, ALAC, WAV, Opus, AAC, Vorbis, etc.) to PCM and encodes CBR MP3 using [PcmMp3Encoder].
 */
@Singleton
class AndroidMediaCodecMp3Encoder @Inject constructor(
    @param:Dispatcher(AppDispatcher.Default) private val defaultDispatcher: CoroutineDispatcher,
) : OnDeviceMp3Encoder {

    override suspend fun encodeToMp3(
        sourceFile: File,
        targetMp3File: File,
        bitrateKbps: Int,
    ): Boolean = withContext(defaultDispatcher) {
        if (!sourceFile.exists() || sourceFile.length() == 0L) return@withContext false

        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        var encoder: PcmMp3Encoder? = null

        try {
            extractor.setDataSource(sourceFile.absolutePath)
            val audioTrackIndex = findAudioTrackIndex(extractor)
            if (audioTrackIndex < 0) return@withContext false

            extractor.selectTrack(audioTrackIndex)
            val inputFormat = extractor.getTrackFormat(audioTrackIndex)
            val mime = inputFormat.getString(MediaFormat.KEY_MIME) ?: return@withContext false

            var sampleRate = inputFormat.getIntegerOrDefault(MediaFormat.KEY_SAMPLE_RATE, 44100)
            var channelCount = inputFormat.getIntegerOrDefault(MediaFormat.KEY_CHANNEL_COUNT, 2)
            var pcmEncoding = inputFormat.getIntegerOrDefault(
                MediaFormat.KEY_PCM_ENCODING,
                AudioFormat.ENCODING_PCM_16BIT,
            )

            val decoder = MediaCodec.createDecoderByType(mime)
            codec = decoder
            decoder.configure(inputFormat, null, null, 0)
            decoder.start()

            val bufferInfo = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false

            targetMp3File.parentFile?.mkdirs()
            targetMp3File.outputStream().buffered(64 * 1024).use { mp3Out ->
                while (!outputDone) {
                    currentCoroutineContext().ensureActive()

                    if (!inputDone) {
                        val inputIndex = decoder.dequeueInputBuffer(TIMEOUT_US)
                        if (inputIndex >= 0) {
                            val inputBuffer = decoder.getInputBuffer(inputIndex)
                            if (inputBuffer != null) {
                                val sampleSize = extractor.readSampleData(inputBuffer, 0)
                                if (sampleSize < 0) {
                                    decoder.queueInputBuffer(
                                        inputIndex,
                                        0,
                                        0,
                                        0L,
                                        MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                                    )
                                    inputDone = true
                                } else {
                                    decoder.queueInputBuffer(
                                        inputIndex,
                                        0,
                                        sampleSize,
                                        extractor.sampleTime,
                                        0,
                                    )
                                    extractor.advance()
                                }
                            }
                        }
                    }

                    val outputIndex = decoder.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
                    when {
                        outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val outFormat = decoder.outputFormat
                            sampleRate = outFormat.getIntegerOrDefault(MediaFormat.KEY_SAMPLE_RATE, sampleRate)
                            channelCount = outFormat.getIntegerOrDefault(MediaFormat.KEY_CHANNEL_COUNT, channelCount)
                            pcmEncoding = outFormat.getIntegerOrDefault(
                                MediaFormat.KEY_PCM_ENCODING,
                                pcmEncoding,
                            )
                        }

                        outputIndex >= 0 -> {
                            val outBuffer = decoder.getOutputBuffer(outputIndex)
                            if (outBuffer != null && bufferInfo.size > 0) {
                                outBuffer.position(bufferInfo.offset)
                                outBuffer.limit(bufferInfo.offset + bufferInfo.size)

                                val activeEncoder = encoder ?: PcmMp3Encoder(
                                    sampleRateHz = sampleRate,
                                    channelCount = channelCount.coerceAtMost(2),
                                    bitrateKbps = bitrateKbps,
                                ).also { encoder = it }

                                val pcm16Bytes = convertToPcm16Le(
                                    buffer = outBuffer,
                                    size = bufferInfo.size,
                                    pcmEncoding = pcmEncoding,
                                    sourceChannels = channelCount,
                                )
                                if (pcm16Bytes.isNotEmpty()) {
                                    activeEncoder.encodePcm16Le(
                                        pcmBytes = pcm16Bytes,
                                        output = mp3Out,
                                    )
                                }
                            }
                            decoder.releaseOutputBuffer(outputIndex, false)
                            if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                                outputDone = true
                            }
                        }
                    }
                }

                encoder?.flush(mp3Out)
                mp3Out.flush()
            }

            targetMp3File.exists() && targetMp3File.length() > 0L
        } catch (_: Throwable) {
            targetMp3File.delete()
            false
        } finally {
            runCatching { encoder?.close() }
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }

    private fun findAudioTrackIndex(extractor: MediaExtractor): Int {
        for (i in 0 until extractor.trackCount) {
            val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)
            if (mime != null && mime.startsWith("audio/")) {
                return i
            }
        }
        return -1
    }

    private fun MediaFormat.getIntegerOrDefault(key: String, defaultValue: Int): Int {
        return if (containsKey(key)) getInteger(key) else defaultValue
    }

    private fun convertToPcm16Le(
        buffer: ByteBuffer,
        size: Int,
        pcmEncoding: Int,
        sourceChannels: Int,
    ): ByteArray {
        val raw = ByteArray(size)
        buffer.get(raw)
        val outChannels = sourceChannels.coerceIn(1, 2)

        return when (pcmEncoding) {
            AudioFormat.ENCODING_PCM_FLOAT -> {
                val floatCount = size / 4
                val sampleFrames = floatCount / sourceChannels.coerceAtLeast(1)
                val out = ByteArray(sampleFrames * outChannels * 2)
                val floatBuf = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
                var outIdx = 0
                for (frame in 0 until sampleFrames) {
                    val base = frame * sourceChannels
                    for (ch in 0 until outChannels) {
                        val clamped = floatBuf.get(base + ch).coerceIn(-1f, 1f)
                        val sample = (clamped * 32767f).roundToInt().coerceIn(-32768, 32767)
                        out[outIdx++] = (sample and 0xFF).toByte()
                        out[outIdx++] = ((sample shr 8) and 0xFF).toByte()
                    }
                }
                out
            }

            AudioFormat.ENCODING_PCM_24BIT_PACKED -> {
                val sampleCount = size / 3
                val sampleFrames = sampleCount / sourceChannels.coerceAtLeast(1)
                val out = ByteArray(sampleFrames * outChannels * 2)
                var outIdx = 0
                for (frame in 0 until sampleFrames) {
                    val frameOffset = frame * sourceChannels * 3
                    for (ch in 0 until outChannels) {
                        val sOff = frameOffset + ch * 3
                        // Take top 16 bits of 24-bit little-endian sample (bytes 1 and 2)
                        out[outIdx++] = raw[sOff + 1]
                        out[outIdx++] = raw[sOff + 2]
                    }
                }
                out
            }

            AudioFormat.ENCODING_PCM_32BIT -> {
                val sampleCount = size / 4
                val sampleFrames = sampleCount / sourceChannels.coerceAtLeast(1)
                val out = ByteArray(sampleFrames * outChannels * 2)
                var outIdx = 0
                for (frame in 0 until sampleFrames) {
                    val frameOffset = frame * sourceChannels * 4
                    for (ch in 0 until outChannels) {
                        val sOff = frameOffset + ch * 4
                        // Take top 16 bits of 32-bit little-endian sample (bytes 2 and 3)
                        out[outIdx++] = raw[sOff + 2]
                        out[outIdx++] = raw[sOff + 3]
                    }
                }
                out
            }

            else -> {
                if (sourceChannels <= 2) {
                    raw
                } else {
                    val sampleFrames = size / (sourceChannels * 2)
                    val out = ByteArray(sampleFrames * 4)
                    var outIdx = 0
                    for (frame in 0 until sampleFrames) {
                        val base = frame * sourceChannels * 2
                        out[outIdx++] = raw[base]
                        out[outIdx++] = raw[base + 1]
                        out[outIdx++] = raw[base + 2]
                        out[outIdx++] = raw[base + 3]
                    }
                    out
                }
            }
        }
    }

    companion object {
        private const val TIMEOUT_US = 10_000L
    }
}
