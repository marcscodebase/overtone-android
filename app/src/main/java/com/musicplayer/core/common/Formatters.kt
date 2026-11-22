package com.musicplayer.core.common

import java.util.Locale

object Formatters {

    private val LOSSLESS_FORMATS = setOf("FLAC", "ALAC", "WAV", "AIFF", "APE", "DSD", "PCM", "WV")

    fun formatDuration(durationMs: Long): String {
        val totalSeconds = (durationMs.coerceAtLeast(0L) / 1000L)
        val hours = totalSeconds / 3600L
        val minutes = (totalSeconds % 3600L) / 60L
        val seconds = totalSeconds % 60L
        return if (hours > 0L) {
            String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.US, "%d:%02d", minutes, seconds)
        }
    }

    fun formatAudioQualityBadge(
        container: String?,
        codec: String?,
        bitrate: Int?,
        sampleRate: Int?,
        bitDepth: Int?,
        isLocal: Boolean = false,
    ): String {
        val formatLabel = (codec ?: container)
            ?.trim()
            ?.uppercase(Locale.US)
            ?.takeIf { it.isNotEmpty() }
            ?: "AUDIO"

        val parts = mutableListOf(formatLabel)
        val isLossless = formatLabel in LOSSLESS_FORMATS

        if (isLossless) {
            val detail = buildLosslessSpec(bitDepth = bitDepth, sampleRate = sampleRate)
            parts += detail ?: "Lossless"
        } else if (bitrate != null && bitrate > 0) {
            val kbps = if (bitrate >= 1000) bitrate / 1000 else bitrate
            parts += "$kbps kbps"
        }

        if (isLocal) {
            parts += "Local"
        }

        return parts.joinToString(" • ")
    }

    private fun buildLosslessSpec(bitDepth: Int?, sampleRate: Int?): String? {
        val rateText = sampleRate?.takeIf { it > 0 }?.let { hz ->
            val khz = hz / 1000.0
            if (hz % 1000 == 0) {
                "${hz / 1000} kHz"
            } else {
                String.format(Locale.US, "%.1f kHz", khz)
            }
        }
        return when {
            bitDepth != null && bitDepth > 0 && rateText != null -> "${bitDepth}-bit/$rateText"
            bitDepth != null && bitDepth > 0 -> "${bitDepth}-bit"
            rateText != null -> rateText
            else -> null
        }
    }
}
