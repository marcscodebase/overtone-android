package com.musicplayer.core.model

import java.util.Locale

enum class AudioOutputType {
    PHONE_SPEAKER,
    WIRED_HEADSET,
    USB_AUDIO,
    BLUETOOTH_A2DP,
    BLUETOOTH_LE_AUDIO,
    HDMI_OR_LINE,
    OTHER,
    ;

    val isBluetooth: Boolean
        get() = this == BLUETOOTH_A2DP || this == BLUETOOTH_LE_AUDIO
}

data class AudioOutputDevice(
    val id: Int,
    val name: String,
    val type: AudioOutputType,
    val maxSampleRateHz: Int? = null,
    val supportsHiResPcm: Boolean = false,
    val isActive: Boolean = false,
) {
    /** Stable key used to store optional per-device Hi-Res/LDAC preference. */
    val preferenceKey: String
        get() = "${type.name}:${name.trim().lowercase(Locale.US)}"
}

data class AudioOutputCapabilities(
    val codecLabel: String,
    val maxQualityLabel: String,
    val maxBitrateKbps: Int?,
    val isLosslessCapable: Boolean,
)

data class BitPerfectStatus(
    val isSupportedByOs: Boolean = false,
    val isEnabled: Boolean = false,
    val isActive: Boolean = false,
    val deviceName: String? = null,
    val sampleRateHz: Int? = null,
    val bitDepth: Int? = null,
    val pausedByReplayGain: Boolean = false,
) {
    val formatLabel: String?
        get() {
            if (!isActive) return null
            val depth = bitDepth?.takeIf { it > 0 }?.let { "$it-bit" }
            val rate = sampleRateHz?.takeIf { it > 0 }?.let { hz ->
                val khz = hz / 1000.0
                if (khz % 1.0 == 0.0) {
                    "${khz.toInt()} kHz"
                } else {
                    String.format(Locale.US, "%.1f kHz", khz)
                }
            }
            return listOfNotNull(depth, rate).joinToString("/").ifEmpty { null }
        }
}

data class AudioOutputState(
    val activeDevice: AudioOutputDevice = AudioOutputDevice(
        id = 0,
        name = "Phone Speaker",
        type = AudioOutputType.PHONE_SPEAKER,
        isActive = true,
    ),
    val availableDevices: List<AudioOutputDevice> = listOf(
        AudioOutputDevice(
            id = 0,
            name = "Phone Speaker",
            type = AudioOutputType.PHONE_SPEAKER,
            isActive = true,
        ),
    ),
    val bitPerfectStatus: BitPerfectStatus = BitPerfectStatus(),
) {
    val hasMultipleDevices: Boolean
        get() = availableDevices.size > 1
}

object AudioOutputQualityPolicy {

    const val STANDARD_BLUETOOTH_CAP_KBPS = 320

    fun evaluate(
        device: AudioOutputDevice,
        hiResBluetoothDevices: Set<String> = emptySet(),
    ): AudioOutputCapabilities {
        val isHiResBt = device.type.isBluetooth && device.preferenceKey in hiResBluetoothDevices
        val pcmRateLabel = formatSampleRate(device.maxSampleRateHz)

        return when (device.type) {
            AudioOutputType.USB_AUDIO -> {
                val depth = if (device.supportsHiResPcm) "24-bit" else "16-bit"
                val detail = listOfNotNull(depth, pcmRateLabel).joinToString("/")
                AudioOutputCapabilities(
                    codecLabel = "USB DAC",
                    maxQualityLabel = if (detail.isNotEmpty()) "Lossless ($detail)" else "Lossless",
                    maxBitrateKbps = null,
                    isLosslessCapable = true,
                )
            }

            AudioOutputType.WIRED_HEADSET -> {
                val detail = pcmRateLabel?.let { "Lossless ($it)" } ?: "Lossless"
                AudioOutputCapabilities(
                    codecLabel = "Wired PCM",
                    maxQualityLabel = detail,
                    maxBitrateKbps = null,
                    isLosslessCapable = true,
                )
            }

            AudioOutputType.HDMI_OR_LINE -> {
                val detail = pcmRateLabel?.let { "Lossless ($it)" } ?: "Lossless"
                AudioOutputCapabilities(
                    codecLabel = "Digital/Line",
                    maxQualityLabel = detail,
                    maxBitrateKbps = null,
                    isLosslessCapable = true,
                )
            }

            AudioOutputType.PHONE_SPEAKER -> {
                AudioOutputCapabilities(
                    codecLabel = "PCM",
                    maxQualityLabel = "Lossless",
                    maxBitrateKbps = null,
                    isLosslessCapable = true,
                )
            }

            AudioOutputType.BLUETOOTH_LE_AUDIO -> {
                if (isHiResBt) {
                    AudioOutputCapabilities(
                        codecLabel = "LE Audio • Hi-Res",
                        maxQualityLabel = "Lossless",
                        maxBitrateKbps = null,
                        isLosslessCapable = true,
                    )
                } else {
                    AudioOutputCapabilities(
                        codecLabel = "LE Audio (LC3)",
                        maxQualityLabel = "≤ $STANDARD_BLUETOOTH_CAP_KBPS kbps",
                        maxBitrateKbps = STANDARD_BLUETOOTH_CAP_KBPS,
                        isLosslessCapable = false,
                    )
                }
            }

            AudioOutputType.BLUETOOTH_A2DP -> {
                if (isHiResBt) {
                    AudioOutputCapabilities(
                        codecLabel = "Bluetooth (Hi-Res/LDAC)",
                        maxQualityLabel = "Lossless",
                        maxBitrateKbps = null,
                        isLosslessCapable = true,
                    )
                } else {
                    AudioOutputCapabilities(
                        codecLabel = "Bluetooth",
                        maxQualityLabel = "≤ $STANDARD_BLUETOOTH_CAP_KBPS kbps",
                        maxBitrateKbps = STANDARD_BLUETOOTH_CAP_KBPS,
                        isLosslessCapable = false,
                    )
                }
            }

            AudioOutputType.OTHER -> {
                AudioOutputCapabilities(
                    codecLabel = "Audio",
                    maxQualityLabel = "Lossless",
                    maxBitrateKbps = null,
                    isLosslessCapable = true,
                )
            }
        }
    }

    fun resolveAutoBitrateCapKbps(
        autoMatchEnabled: Boolean,
        activeDevice: AudioOutputDevice?,
        hiResBluetoothDevices: Set<String> = emptySet(),
    ): Int? {
        if (!autoMatchEnabled || activeDevice == null) return null
        return evaluate(activeDevice, hiResBluetoothDevices).maxBitrateKbps
    }

    private fun formatSampleRate(sampleRateHz: Int?): String? {
        if (sampleRateHz == null || sampleRateHz <= 0) return null
        val khz = sampleRateHz / 1000.0
        return if (khz % 1.0 == 0.0) {
            "${khz.toInt()} kHz"
        } else {
            String.format(Locale.US, "%.1f kHz", khz)
        }
    }
}
