package com.musicplayer.player.engine

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.MediaRoute2Info
import android.media.MediaRouter2
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import com.musicplayer.core.model.AudioOutputDevice
import com.musicplayer.core.model.AudioOutputState
import com.musicplayer.core.model.AudioOutputType
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.Executors
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Observes the currently active Android audio output device and all connected output routes
 * using [AudioManager] and [MediaRouter2] (API 31+).
 */
@Singleton
class AudioOutputMonitor @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val usbBitPerfectController: UsbBitPerfectController,
) {
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val mediaRouter2: MediaRouter2? = runCatching { MediaRouter2.getInstance(context) }.getOrNull()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val routerExecutor = Executors.newSingleThreadExecutor()

    private val _outputState = MutableStateFlow(AudioOutputState())
    val outputState: StateFlow<AudioOutputState> = _outputState.asStateFlow()

    private val audioDeviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
            refreshDevices()
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
            refreshDevices()
        }
    }

    private val routeCallback = object : MediaRouter2.RouteCallback() {
        override fun onRoutesUpdated(routes: List<MediaRoute2Info>) {
            refreshDevices()
        }
    }

    private val transferCallback = object : MediaRouter2.TransferCallback() {
        override fun onTransfer(
            oldController: MediaRouter2.RoutingController,
            newController: MediaRouter2.RoutingController,
        ) {
            refreshDevices()
        }

        override fun onStop(controller: MediaRouter2.RoutingController) {
            refreshDevices()
        }
    }

    init {
        runCatching {
            audioManager?.registerAudioDeviceCallback(audioDeviceCallback, mainHandler)
        }
        runCatching {
            mediaRouter2?.registerRouteCallback(
                routerExecutor,
                routeCallback,
                android.media.RouteDiscoveryPreference.Builder(
                    listOf(MediaRoute2Info.FEATURE_LIVE_AUDIO),
                    false,
                ).build(),
            )
            mediaRouter2?.registerTransferCallback(routerExecutor, transferCallback)
        }
        refreshDevices()
    }

    fun refreshDevices() {
        val rawOutputs = runCatching {
            audioManager?.getDevices(AudioManager.GET_DEVICES_OUTPUTS)?.toList().orEmpty()
        }.getOrDefault(emptyList())

        // Android 13+ (API 33+) exposes the exact active output device for USAGE_MEDIA playback.
        val activeMediaOutput = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            runCatching {
                audioManager
                    ?.getAudioDevicesForAttributes(MUSIC_ATTRIBUTES)
                    ?.firstNotNullOfOrNull(::mapAudioDeviceInfo)
            }.getOrNull()
        } else {
            null
        }

        val selectedRoute = runCatching {
            mediaRouter2?.systemController?.selectedRoutes?.firstOrNull()
        }.getOrNull()

        val mapped = rawOutputs
            .mapNotNull(::mapAudioDeviceInfo)
            .distinctBy { it.type to it.name.lowercase() }

        val candidates = mapped.ifEmpty {
            listOf(
                activeMediaOutput ?: AudioOutputDevice(
                    id = 0,
                    name = "Phone Speaker",
                    type = AudioOutputType.PHONE_SPEAKER,
                    isActive = true,
                ),
            )
        }

        val activeDevice = selectActiveDevice(candidates, activeMediaOutput, selectedRoute)
        val marked = candidates
            .map { it.copy(isActive = it.id == activeDevice.id && it.type == activeDevice.type) }
            .sortedByDescending { it.isActive }

        _outputState.value = AudioOutputState(
            activeDevice = activeDevice.copy(isActive = true),
            availableDevices = marked,
            bitPerfectStatus = usbBitPerfectController.status.value,
        )
        usbBitPerfectController.refreshForActiveDevice()
    }

    /**
     * Opens the native Android Media Output Switcher dialog (as used by Spotify & YouTube Music),
     * with clean fallbacks for OEMs that don't expose SystemUI's media output intent.
     */
    fun openSystemOutputSwitcher(activityContext: Context) {
        val mediaOutputIntent = Intent("com.android.systemui.action.LAUNCH_MEDIA_OUTPUT_DIALOG").apply {
            setPackage("com.android.systemui")
            putExtra("package_name", activityContext.packageName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (tryStartActivity(activityContext, mediaOutputIntent)) return

        val volumePanelIntent = Intent(Settings.Panel.ACTION_VOLUME).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (tryStartActivity(activityContext, volumePanelIntent)) return

        val btSettingsIntent = Intent(Settings.ACTION_BLUETOOTH_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        tryStartActivity(activityContext, btSettingsIntent)
    }

    private fun tryStartActivity(ctx: Context, intent: Intent): Boolean =
        runCatching {
            ctx.startActivity(intent)
            true
        }.getOrDefault(false)

    private fun selectActiveDevice(
        devices: List<AudioOutputDevice>,
        activeMediaOutput: AudioOutputDevice?,
        selectedRoute: MediaRoute2Info?,
    ): AudioOutputDevice {
        if (activeMediaOutput != null) {
            devices.firstOrNull { it.id == activeMediaOutput.id }?.let { return it }
            devices.firstOrNull {
                it.type == activeMediaOutput.type && it.name.equals(activeMediaOutput.name, ignoreCase = true)
            }?.let { return it }
            devices.firstOrNull { it.type == activeMediaOutput.type }?.let { return it }
            return activeMediaOutput
        }

        if (selectedRoute != null) {
            val routeType = mapRouteType(selectedRoute.type)
            val routeName = selectedRoute.name.toString().trim()
            val exactMatch = devices.firstOrNull {
                it.type == routeType && routeName.isNotEmpty() && it.name.equals(routeName, ignoreCase = true)
            }
            if (exactMatch != null) return exactMatch

            val typeMatch = devices.firstOrNull { it.type == routeType }
            if (typeMatch != null) return typeMatch
        }

        // Standard Android routing priority fallback when MediaRouter2 route is unavailable
        val priority = listOf(
            AudioOutputType.USB_AUDIO,
            AudioOutputType.WIRED_HEADSET,
            AudioOutputType.BLUETOOTH_LE_AUDIO,
            AudioOutputType.BLUETOOTH_A2DP,
            AudioOutputType.HDMI_OR_LINE,
            AudioOutputType.PHONE_SPEAKER,
            AudioOutputType.OTHER,
        )
        for (type in priority) {
            devices.firstOrNull { it.type == type }?.let { return it }
        }
        return devices.first()
    }

    private fun mapRouteType(routeType: Int): AudioOutputType = when (routeType) {
        MediaRoute2Info.TYPE_BLE_HEADSET -> AudioOutputType.BLUETOOTH_LE_AUDIO
        MediaRoute2Info.TYPE_BLUETOOTH_A2DP -> AudioOutputType.BLUETOOTH_A2DP
        MediaRoute2Info.TYPE_USB_DEVICE,
        MediaRoute2Info.TYPE_USB_HEADSET,
        MediaRoute2Info.TYPE_USB_ACCESSORY,
        -> AudioOutputType.USB_AUDIO
        MediaRoute2Info.TYPE_WIRED_HEADSET,
        MediaRoute2Info.TYPE_WIRED_HEADPHONES,
        -> AudioOutputType.WIRED_HEADSET
        MediaRoute2Info.TYPE_HDMI,
        MediaRoute2Info.TYPE_HDMI_ARC,
        MediaRoute2Info.TYPE_HDMI_EARC,
        -> AudioOutputType.HDMI_OR_LINE
        MediaRoute2Info.TYPE_BUILTIN_SPEAKER -> AudioOutputType.PHONE_SPEAKER
        else -> AudioOutputType.OTHER
    }

    private fun mapAudioDeviceInfo(info: AudioDeviceInfo): AudioOutputDevice? {
        val mappedType = when (info.type) {
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> AudioOutputType.PHONE_SPEAKER
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            -> AudioOutputType.WIRED_HEADSET
            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_USB_ACCESSORY,
            -> AudioOutputType.USB_AUDIO
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> AudioOutputType.BLUETOOTH_A2DP
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_BLE_SPEAKER,
            AudioDeviceInfo.TYPE_BLE_BROADCAST,
            -> AudioOutputType.BLUETOOTH_LE_AUDIO
            AudioDeviceInfo.TYPE_HDMI,
            AudioDeviceInfo.TYPE_HDMI_ARC,
            AudioDeviceInfo.TYPE_HDMI_EARC,
            AudioDeviceInfo.TYPE_LINE_ANALOG,
            AudioDeviceInfo.TYPE_LINE_DIGITAL,
            -> AudioOutputType.HDMI_OR_LINE
            // Ignore telephony earpiece / SCO voice call / internal non-media sinks
            else -> return null
        }

        val rawProductName = info.productName?.toString()?.trim().orEmpty()
        val displayName = when {
            mappedType == AudioOutputType.PHONE_SPEAKER -> "Phone Speaker"
            mappedType == AudioOutputType.WIRED_HEADSET && (rawProductName.isBlank() || rawProductName.equals(Build.MODEL, ignoreCase = true)) ->
                "Wired Headphones"
            mappedType == AudioOutputType.USB_AUDIO && (rawProductName.isBlank() || rawProductName.equals(Build.MODEL, ignoreCase = true)) ->
                "USB Audio DAC"
            rawProductName.isNotBlank() -> rawProductName
            mappedType == AudioOutputType.BLUETOOTH_LE_AUDIO -> "Bluetooth LE Audio"
            mappedType == AudioOutputType.BLUETOOTH_A2DP -> "Bluetooth Audio"
            mappedType == AudioOutputType.HDMI_OR_LINE -> "Digital Output"
            else -> "Audio Output"
        }

        val maxSampleRate = info.sampleRates.maxOrNull()?.takeIf { it > 0 }
        val encodings = info.encodings.toSet()
        val hiResPcm = AudioFormat.ENCODING_PCM_24BIT_PACKED in encodings ||
            AudioFormat.ENCODING_PCM_32BIT in encodings ||
            AudioFormat.ENCODING_PCM_FLOAT in encodings

        return AudioOutputDevice(
            id = info.id,
            name = displayName,
            type = mappedType,
            maxSampleRateHz = maxSampleRate,
            supportsHiResPcm = hiResPcm,
        )
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
