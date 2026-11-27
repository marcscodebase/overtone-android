package com.musicplayer.player.engine

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import com.musicplayer.core.model.ReplayGainMode
import java.nio.ByteBuffer
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlin.math.tanh

object ReplayGainMath {

    private const val MIN_GAIN_DB = -24f
    private const val MAX_GAIN_DB = 12f
    private const val KNEE_START = 0.95f
    private const val KNEE_HEADROOM = 1f - KNEE_START

    fun computeLinearGain(
        mode: ReplayGainMode,
        trackGainDb: Float?,
        albumGainDb: Float?,
        preampDb: Float,
    ): Float {
        if (mode == ReplayGainMode.OFF) return 1f
        val safePreampDb = if (preampDb.isFinite()) preampDb else 0f
        val safeTrackDb = trackGainDb?.takeIf { it.isFinite() }
        val safeAlbumDb = albumGainDb?.takeIf { it.isFinite() }

        val selectedGainDb = when (mode) {
            ReplayGainMode.OFF -> return 1f
            ReplayGainMode.TRACK -> safeTrackDb ?: safeAlbumDb
            ReplayGainMode.ALBUM -> safeAlbumDb ?: safeTrackDb
        }

        val totalDb = if (selectedGainDb != null) {
            (selectedGainDb + safePreampDb).coerceIn(MIN_GAIN_DB, MAX_GAIN_DB)
        } else {
            // Never blindly boost tracks that lack ReplayGain tags; allow negative preamp only
            safePreampDb.coerceIn(MIN_GAIN_DB, 0f)
        }

        val linear = 10.0.pow(totalDb / 20.0).toFloat()
        return if (linear.isFinite()) linear else 1f
    }

    /**
     * Applies linear gain transparently up to 0.95 full-scale and engages a smooth tanh soft
     * peak limiter above 0.95 so boosted tracks never hard-clip.
     */
    fun applyGainWithSoftLimiter(sample: Float, gain: Float): Float {
        if (!sample.isFinite() || !gain.isFinite()) return 0f
        val scaled = sample * gain
        val magnitude = abs(scaled)
        if (magnitude <= KNEE_START) return scaled
        val excess = (magnitude - KNEE_START) / KNEE_HEADROOM
        val limited = (KNEE_START + KNEE_HEADROOM * tanh(excess.toDouble()).toFloat()).coerceAtMost(1f)
        return limited * sign(scaled)
    }
}

/**
 * Custom Media3 AudioProcessor applying ReplayGain loudness normalization with smooth
 * gain ramping and soft peak limiting. Passes audio bit-exact when gain == 1.0.
 */
@UnstableApi
@Singleton
class ReplayGainAudioProcessor @Inject constructor() : BaseAudioProcessor() {

    @Volatile
    private var targetGain: Float = 1f

    @Volatile
    private var bypassAll: Boolean = false

    private var smoothedGain: Float = 1f

    fun setTargetGain(linearGain: Float) {
        targetGain = if (linearGain.isFinite()) linearGain.coerceIn(0.05f, 4.0f) else 1f
    }

    fun getTargetGain(): Float = targetGain

    fun setBypass(bypass: Boolean) {
        bypassAll = bypass
    }

    override fun onConfigure(
        inputAudioFormat: AudioProcessor.AudioFormat,
    ): AudioProcessor.AudioFormat {
        if (bypassAll) {
            return AudioProcessor.AudioFormat.NOT_SET
        }
        val encoding = inputAudioFormat.encoding
        if (encoding != C.ENCODING_PCM_16BIT &&
            encoding != C.ENCODING_PCM_24BIT &&
            encoding != C.ENCODING_PCM_32BIT &&
            encoding != C.ENCODING_PCM_FLOAT
        ) {
            return AudioProcessor.AudioFormat.NOT_SET
        }
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return

        val outputBuffer = replaceOutputBuffer(remaining)
        val target = targetGain

        // Bit-exact fast path when ReplayGain is Off (gain == 1.0) and ramp has settled
        if (abs(target - 1f) < GAIN_EPSILON && abs(smoothedGain - 1f) < GAIN_EPSILON) {
            smoothedGain = 1f
            outputBuffer.put(inputBuffer)
            outputBuffer.flip()
            return
        }

        val channels = inputAudioFormat.channelCount.coerceAtLeast(1)
        when (inputAudioFormat.encoding) {
            C.ENCODING_PCM_16BIT -> processPcm16(inputBuffer, outputBuffer, channels, target)
            C.ENCODING_PCM_24BIT -> processPcm24(inputBuffer, outputBuffer, channels, target)
            C.ENCODING_PCM_32BIT -> processPcm32(inputBuffer, outputBuffer, channels, target)
            C.ENCODING_PCM_FLOAT -> processPcmFloat(inputBuffer, outputBuffer, channels, target)
            else -> outputBuffer.put(inputBuffer)
        }
        outputBuffer.flip()
    }

    private fun processPcm16(
        input: ByteBuffer,
        output: ByteBuffer,
        channels: Int,
        target: Float,
    ) {
        val bytesPerFrame = channels * 2
        while (input.remaining() >= bytesPerFrame) {
            smoothedGain += (target - smoothedGain) * RAMP_ALPHA
            val g = smoothedGain
            for (ch in 0 until channels) {
                val sampleShort = input.short
                val normalized = sampleShort / 32768f
                val processed = ReplayGainMath.applyGainWithSoftLimiter(normalized, g)
                val outInt = (processed * 32767f).roundToInt().coerceIn(-32768, 32767)
                output.putShort(outInt.toShort())
            }
        }
        while (input.hasRemaining()) {
            output.put(input.get())
        }
    }

    private fun processPcm24(
        input: ByteBuffer,
        output: ByteBuffer,
        channels: Int,
        target: Float,
    ) {
        val bytesPerFrame = channels * 3
        while (input.remaining() >= bytesPerFrame) {
            smoothedGain += (target - smoothedGain) * RAMP_ALPHA
            val g = smoothedGain
            for (ch in 0 until channels) {
                val b0 = input.get().toInt() and 0xFF
                val b1 = input.get().toInt() and 0xFF
                val b2 = input.get().toInt()
                val sample24 = (b2 shl 16) or (b1 shl 8) or b0
                val normalized = sample24 / 8388608f
                val processed = ReplayGainMath.applyGainWithSoftLimiter(normalized, g)
                val outInt = (processed * 8388607f).roundToInt().coerceIn(-8388608, 8388607)
                output.put((outInt and 0xFF).toByte())
                output.put(((outInt ushr 8) and 0xFF).toByte())
                output.put(((outInt ushr 16) and 0xFF).toByte())
            }
        }
        while (input.hasRemaining()) {
            output.put(input.get())
        }
    }

    private fun processPcm32(
        input: ByteBuffer,
        output: ByteBuffer,
        channels: Int,
        target: Float,
    ) {
        val bytesPerFrame = channels * 4
        while (input.remaining() >= bytesPerFrame) {
            smoothedGain += (target - smoothedGain) * RAMP_ALPHA
            val g = smoothedGain
            for (ch in 0 until channels) {
                val sample32 = input.int
                val normalized = (sample32.toDouble() / 2147483648.0).toFloat()
                val processed = ReplayGainMath.applyGainWithSoftLimiter(normalized, g)
                val outInt = (processed.toDouble() * 2147483647.0).toLong()
                    .coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong())
                    .toInt()
                output.putInt(outInt)
            }
        }
        while (input.hasRemaining()) {
            output.put(input.get())
        }
    }

    private fun processPcmFloat(
        input: ByteBuffer,
        output: ByteBuffer,
        channels: Int,
        target: Float,
    ) {
        val bytesPerFrame = channels * 4
        while (input.remaining() >= bytesPerFrame) {
            smoothedGain += (target - smoothedGain) * RAMP_ALPHA
            val g = smoothedGain
            for (ch in 0 until channels) {
                val sample = input.float
                output.putFloat(ReplayGainMath.applyGainWithSoftLimiter(sample, g))
            }
        }
        while (input.hasRemaining()) {
            output.put(input.get())
        }
    }

    override fun onFlush() {
        smoothedGain = targetGain
    }

    override fun onReset() {
        smoothedGain = targetGain
    }

    companion object {
        private const val GAIN_EPSILON = 1e-4f
        private const val RAMP_ALPHA = 0.005f
    }
}
