package com.musicplayer.player.engine

import com.musicplayer.core.model.ReplayGainMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class ReplayGainAudioProcessorTest {

    @Test
    fun `off mode returns unity gain regardless of tags or preamp`() {
        val gain = ReplayGainMath.computeLinearGain(
            mode = ReplayGainMode.OFF,
            trackGainDb = -8.5f,
            albumGainDb = -6.0f,
            preampDb = 4.0f,
        )
        assertEquals(1.0f, gain, 1e-5f)
    }

    @Test
    fun `track and album modes compute accurate linear gain with preamp`() {
        val trackGain = ReplayGainMath.computeLinearGain(
            mode = ReplayGainMode.TRACK,
            trackGainDb = -6.0206f,
            albumGainDb = -3.0f,
            preampDb = 0f,
        )
        assertEquals(0.5f, trackGain, 0.005f)

        val albumWithPreamp = ReplayGainMath.computeLinearGain(
            mode = ReplayGainMode.ALBUM,
            trackGainDb = -9.0f,
            albumGainDb = -6.0f,
            preampDb = 6.0f,
        )
        assertEquals(1.0f, albumWithPreamp, 0.005f)
    }

    @Test
    fun `missing tags never apply positive boost but honor negative preamp`() {
        val positivePreamp = ReplayGainMath.computeLinearGain(
            mode = ReplayGainMode.TRACK,
            trackGainDb = null,
            albumGainDb = null,
            preampDb = 4.0f,
        )
        assertEquals(1.0f, positivePreamp, 1e-5f)

        val negativePreamp = ReplayGainMath.computeLinearGain(
            mode = ReplayGainMode.TRACK,
            trackGainDb = null,
            albumGainDb = null,
            preampDb = -6.0206f,
        )
        assertEquals(0.5f, negativePreamp, 0.005f)
    }

    @Test
    fun `soft peak limiter stays transparent below knee and prevents clipping`() {
        // Below 0.95 knee -> exact sample * gain
        val clean = ReplayGainMath.applyGainWithSoftLimiter(sample = 0.4f, gain = 2.0f)
        assertEquals(0.8f, clean, 1e-5f)

        // Moderate over-knee peak smoothly compressed below 1.0
        val gentleLimit = ReplayGainMath.applyGainWithSoftLimiter(sample = 0.5f, gain = 2.0f)
        assertTrue("Soft knee should compress 1.0 below 1.0: $gentleLimit", gentleLimit > 0.95f && gentleLimit < 1.0f)

        // Massive +12 dB boost on full-scale peak -> strictly within [-1.0, 1.0]
        val limitedPos = ReplayGainMath.applyGainWithSoftLimiter(sample = 1.0f, gain = 4.0f)
        val limitedNeg = ReplayGainMath.applyGainWithSoftLimiter(sample = -1.0f, gain = 4.0f)
        assertTrue("Positive peak must not exceed 1.0: $limitedPos", limitedPos > 0.95f && limitedPos <= 1.0f)
        assertTrue("Negative peak must not exceed -1.0: $limitedNeg", limitedNeg < -0.95f && limitedNeg >= -1.0f)
        assertEquals(abs(limitedPos), abs(limitedNeg), 1e-5f)
    }
}
