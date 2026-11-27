package com.musicplayer.player.engine

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioMixerAttributes
import android.os.Build
import androidx.annotation.RequiresApi
import com.musicplayer.core.model.BitPerfectStatus
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.Executors
import javax.inject.Inject
import javax.inject.Singleton

data class BitPerfectMixerCandidate(
    val sampleRateHz: Int,
    val channelCount: Int,
    val encoding: Int,
    val bitDepth: Int,
)

/**
 * Pure matching logic for Android 14+ `MIXER_BEHAVIOR_BIT_PERFECT` descriptors.
 * Enforces exact sample rate, channel count, and AudioTrack encoding match so Android's
 * AudioPolicyManager actually routes the opened AudioTrack onto the bit-perfect USB mixer path
 * without resampling or truncation.
 */
object BitPerfectMixerMatcher {

    fun bitDepthForAndroidEncoding(encoding: Int): Int? = when (encoding) {
        AudioFormat.ENCODING_PCM_8BIT -> 8
        AudioFormat.ENCODING_PCM_16BIT -> 16
        AudioFormat.ENCODING_PCM_24BIT_PACKED -> 24
        AudioFormat.ENCODING_PCM_32BIT -> 32
        AudioFormat.ENCODING_PCM_FLOAT -> 24 // IEEE 754 float carries exact 24-bit integer precision
        else -> null
    }

    fun selectBestMatchIndex(
        candidates: List<BitPerfectMixerCandidate>,
        targetSampleRateHz: Int,
        targetChannelCount: Int,
        targetEncoding: Int,
    ): Int? {
        if (targetSampleRateHz <= 0 || targetChannelCount <= 0) return null
        return candidates
            .withIndex()
            .firstOrNull { (_, c) ->
                c.sampleRateHz == targetSampleRateHz &&
                    c.channelCount == targetChannelCount &&
                    c.encoding == targetEncoding
            }
            ?.index
    }
}

/**
 * Manages Android 14+ (API 34+) native bit-perfect USB audio output via
 * [AudioManager.setPreferredMixerAttributes] and [AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT].
 */
@Singleton
class UsbBitPerfectController @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    private val audioManager: AudioManager? = context.getSystemService(AudioManager::class.java)
    private val listenerExecutor = Executors.newSingleThreadExecutor()
    private val lock = Any()

    val isSupportedByOs: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE

    private val _status = MutableStateFlow(
        BitPerfectStatus(isSupportedByOs = isSupportedByOs),
    )
    val status: StateFlow<BitPerfectStatus> = _status.asStateFlow()

    @Volatile
    private var enabled: Boolean = false

    @Volatile
    private var replayGainActive: Boolean = false

    private var lastSampleRateHz: Int? = null
    private var lastChannelCount: Int? = null
    private var lastEncoding: Int? = null
    private var appliedDevice: AudioDeviceInfo? = null
    private var appliedMixerAttributes: Any? = null

    private var mixerListenerRegistered = false

    init {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            registerMixerAttributesListenerApi34()
        }
    }

    fun updateSettings(
        bitPerfectEnabled: Boolean,
        isReplayGainActive: Boolean,
    ) {
        synchronized(lock) {
            enabled = bitPerfectEnabled
            replayGainActive = isReplayGainActive
            reapplyLocked()
        }
    }

    /**
     * Called synchronously right before Media3 constructs an [android.media.AudioTrack] so Android
     * opens the track directly onto the USB DAC's bit-perfect mixer path on the first sample.
     */
    fun configureForAudioTrack(
        sampleRateHz: Int,
        channelConfig: Int,
        encoding: Int,
    ): Boolean {
        val channelCount = Integer.bitCount(channelConfig).coerceAtLeast(1)
        synchronized(lock) {
            lastSampleRateHz = sampleRateHz
            lastChannelCount = channelCount
            lastEncoding = encoding
            return reapplyLocked()
        }
    }

    fun refreshForActiveDevice() {
        synchronized(lock) {
            reapplyLocked()
        }
    }

    fun clear() {
        synchronized(lock) {
            clearAppliedLocked()
            lastSampleRateHz = null
            lastChannelCount = null
            lastEncoding = null
            _status.value = BitPerfectStatus(
                isSupportedByOs = isSupportedByOs,
                isEnabled = enabled,
                isActive = false,
                pausedByReplayGain = false,
            )
        }
    }

    private fun reapplyLocked(): Boolean {
        if (!isSupportedByOs) {
            _status.value = BitPerfectStatus(isSupportedByOs = false, isEnabled = false)
            return false
        }

        val usbDevice = findActiveUsbDevice()
        if (!enabled) {
            clearAppliedLocked()
            _status.value = BitPerfectStatus(
                isSupportedByOs = true,
                isEnabled = false,
                isActive = false,
                deviceName = usbDevice?.displayName(),
            )
            return false
        }

        if (usbDevice == null) {
            clearAppliedLocked()
            _status.value = BitPerfectStatus(
                isSupportedByOs = true,
                isEnabled = true,
                isActive = false,
                deviceName = null,
            )
            return false
        }

        if (replayGainActive) {
            clearAppliedLocked()
            _status.value = BitPerfectStatus(
                isSupportedByOs = true,
                isEnabled = true,
                isActive = false,
                deviceName = usbDevice.displayName(),
                pausedByReplayGain = true,
            )
            return false
        }

        val sampleRate = lastSampleRateHz
        val channelCount = lastChannelCount
        val encoding = lastEncoding
        if (sampleRate == null || channelCount == null || encoding == null) {
            _status.value = BitPerfectStatus(
                isSupportedByOs = true,
                isEnabled = true,
                isActive = false,
                deviceName = usbDevice.displayName(),
            )
            return false
        }

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            applyBitPerfectApi34Locked(
                usbDevice = usbDevice,
                sampleRateHz = sampleRate,
                channelCount = channelCount,
                encoding = encoding,
            )
        } else {
            false
        }
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun applyBitPerfectApi34Locked(
        usbDevice: AudioDeviceInfo,
        sampleRateHz: Int,
        channelCount: Int,
        encoding: Int,
    ): Boolean {
        val manager = audioManager ?: return false
        val supported = runCatching {
            manager.getSupportedMixerAttributes(usbDevice)
        }.getOrDefault(emptyList())

        val bitPerfectAttrs = supported.filter {
            it.mixerBehavior == AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT
        }
        val candidates = bitPerfectAttrs.map { attr ->
            val fmt = attr.format
            BitPerfectMixerCandidate(
                sampleRateHz = fmt.sampleRate,
                channelCount = fmt.channelCount,
                encoding = fmt.encoding,
                bitDepth = BitPerfectMixerMatcher.bitDepthForAndroidEncoding(fmt.encoding) ?: 0,
            )
        }

        val matchedIndex = BitPerfectMixerMatcher.selectBestMatchIndex(
            candidates = candidates,
            targetSampleRateHz = sampleRateHz,
            targetChannelCount = channelCount,
            targetEncoding = encoding,
        )

        if (matchedIndex == null) {
            clearAppliedLocked()
            _status.value = BitPerfectStatus(
                isSupportedByOs = true,
                isEnabled = true,
                isActive = false,
                deviceName = usbDevice.displayName(),
            )
            return false
        }

        val chosenAttr = bitPerfectAttrs[matchedIndex]
        val chosenCandidate = candidates[matchedIndex]

        if (appliedDevice?.id == usbDevice.id && appliedMixerAttributes == chosenAttr) {
            _status.value = BitPerfectStatus(
                isSupportedByOs = true,
                isEnabled = true,
                isActive = true,
                deviceName = usbDevice.displayName(),
                sampleRateHz = chosenCandidate.sampleRateHz,
                bitDepth = chosenCandidate.bitDepth.takeIf { it > 0 },
            )
            return true
        }

        if (appliedDevice != null && appliedDevice?.id != usbDevice.id) {
            clearAppliedLocked()
        }

        val accepted = runCatching {
            manager.setPreferredMixerAttributes(MUSIC_ATTRIBUTES, usbDevice, chosenAttr)
        }.getOrDefault(false)

        if (accepted) {
            appliedDevice = usbDevice
            appliedMixerAttributes = chosenAttr
            _status.value = BitPerfectStatus(
                isSupportedByOs = true,
                isEnabled = true,
                isActive = true,
                deviceName = usbDevice.displayName(),
                sampleRateHz = chosenCandidate.sampleRateHz,
                bitDepth = chosenCandidate.bitDepth.takeIf { it > 0 },
            )
        } else {
            clearAppliedLocked()
            _status.value = BitPerfectStatus(
                isSupportedByOs = true,
                isEnabled = true,
                isActive = false,
                deviceName = usbDevice.displayName(),
            )
        }
        return accepted
    }

    private fun clearAppliedLocked() {
        val prevDevice = appliedDevice
        appliedDevice = null
        appliedMixerAttributes = null
        if (prevDevice != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            runCatching {
                audioManager?.clearPreferredMixerAttributes(MUSIC_ATTRIBUTES, prevDevice)
            }
        }
    }

    private fun findActiveUsbDevice(): AudioDeviceInfo? {
        val manager = audioManager ?: return null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val routed = runCatching {
                manager.getAudioDevicesForAttributes(MUSIC_ATTRIBUTES)
            }.getOrDefault(emptyList())
            if (routed.isNotEmpty()) {
                return routed.firstOrNull(::isUsbAudioDevice)
            }
        }
        return runCatching {
            manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull(::isUsbAudioDevice)
        }.getOrNull()
    }

    private fun isUsbAudioDevice(info: AudioDeviceInfo): Boolean =
        info.type == AudioDeviceInfo.TYPE_USB_DEVICE ||
            info.type == AudioDeviceInfo.TYPE_USB_HEADSET ||
            info.type == AudioDeviceInfo.TYPE_USB_ACCESSORY

    private fun AudioDeviceInfo.displayName(): String {
        val raw = productName?.toString()?.trim().orEmpty()
        return if (raw.isBlank() || raw.equals(Build.MODEL, ignoreCase = true)) {
            "USB Audio DAC"
        } else {
            raw
        }
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun registerMixerAttributesListenerApi34() {
        if (mixerListenerRegistered) return
        val manager = audioManager ?: return
        runCatching {
            manager.addOnPreferredMixerAttributesChangedListener(
                listenerExecutor,
            ) { attributes, device, mixerAttributes ->
                if (attributes.usage != AudioAttributes.USAGE_MEDIA) return@addOnPreferredMixerAttributesChangedListener
                synchronized(lock) {
                    if (appliedDevice?.id == device.id && mixerAttributes == null) {
                        appliedDevice = null
                        appliedMixerAttributes = null
                        _status.value = _status.value.copy(
                            isActive = false,
                            sampleRateHz = null,
                            bitDepth = null,
                        )
                    }
                }
            }
            mixerListenerRegistered = true
        }
    }

    companion object {
        private val MUSIC_ATTRIBUTES: AudioAttributes by lazy {
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
        }
    }
}
