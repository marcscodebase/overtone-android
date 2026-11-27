package com.musicplayer.player.engine

import android.media.AudioFormat
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import com.musicplayer.core.model.BitPerfectStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BitPerfectMixerMatcherTest {

    @Test
    fun matchesExactSampleRateChannelsAndEncoding() {
        val candidates = listOf(
            BitPerfectMixerCandidate(44_100, 2, AudioFormat.ENCODING_PCM_16BIT, 16),
            BitPerfectMixerCandidate(48_000, 2, AudioFormat.ENCODING_PCM_16BIT, 16),
            BitPerfectMixerCandidate(96_000, 2, AudioFormat.ENCODING_PCM_FLOAT, 24),
        )

        assertEquals(
            0,
            BitPerfectMixerMatcher.selectBestMatchIndex(
                candidates = candidates,
                targetSampleRateHz = 44_100,
                targetChannelCount = 2,
                targetEncoding = AudioFormat.ENCODING_PCM_16BIT,
            ),
        )
        assertEquals(
            2,
            BitPerfectMixerMatcher.selectBestMatchIndex(
                candidates = candidates,
                targetSampleRateHz = 96_000,
                targetChannelCount = 2,
                targetEncoding = AudioFormat.ENCODING_PCM_FLOAT,
            ),
        )
    }

    @Test
    fun returnsNullWhenSampleRateOrEncodingDoesNotMatchExactly() {
        val candidates = listOf(
            BitPerfectMixerCandidate(48_000, 2, AudioFormat.ENCODING_PCM_16BIT, 16),
            BitPerfectMixerCandidate(96_000, 2, AudioFormat.ENCODING_PCM_24BIT_PACKED, 24),
        )

        // Never resamples 44.1 kHz to 48 kHz
        assertNull(
            BitPerfectMixerMatcher.selectBestMatchIndex(
                candidates = candidates,
                targetSampleRateHz = 44_100,
                targetChannelCount = 2,
                targetEncoding = AudioFormat.ENCODING_PCM_16BIT,
            ),
        )

        // Never mismatches encoding (AudioPolicyManager requires exact AudioTrack encoding match)
        assertNull(
            BitPerfectMixerMatcher.selectBestMatchIndex(
                candidates = candidates,
                targetSampleRateHz = 96_000,
                targetChannelCount = 2,
                targetEncoding = AudioFormat.ENCODING_PCM_FLOAT,
            ),
        )

        // Channel count mismatch rejected
        assertNull(
            BitPerfectMixerMatcher.selectBestMatchIndex(
                candidates = candidates,
                targetSampleRateHz = 48_000,
                targetChannelCount = 1,
                targetEncoding = AudioFormat.ENCODING_PCM_16BIT,
            ),
        )
    }

    @Test
    fun formatsBitPerfectStatusLabelsCleanly() {
        val active44 = BitPerfectStatus(
            isSupportedByOs = true,
            isEnabled = true,
            isActive = true,
            deviceName = "FiiO KA13",
            sampleRateHz = 44_100,
            bitDepth = 16,
        )
        assertEquals("16-bit/44.1 kHz", active44.formatLabel)

        val active96 = active44.copy(sampleRateHz = 96_000, bitDepth = 24)
        assertEquals("24-bit/96 kHz", active96.formatLabel)

        val inactive = active96.copy(isActive = false)
        assertNull(inactive.formatLabel)
    }

    @Test
    fun replayGainProcessorBypassesCompletelyWhenBitPerfectBypassEnabled() {
        val processor = ReplayGainAudioProcessor()
        val pcm16 = AudioProcessor.AudioFormat(44_100, 2, C.ENCODING_PCM_16BIT)

        processor.setBypass(false)
        processor.configure(pcm16)
        assertTrue(processor.isActive)

        processor.setBypass(true)
        processor.configure(pcm16)
        assertFalse(processor.isActive)
    }
}
