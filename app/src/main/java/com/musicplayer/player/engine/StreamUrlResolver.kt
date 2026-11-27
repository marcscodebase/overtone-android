package com.musicplayer.player.engine

import androidx.media3.common.MimeTypes
import com.musicplayer.core.common.AppDispatcher
import com.musicplayer.core.common.Dispatcher
import com.musicplayer.core.database.dao.DownloadDao
import com.musicplayer.core.database.entity.DownloadRecordEntity
import com.musicplayer.core.datastore.AuthSessionStore
import com.musicplayer.core.datastore.UserPreferencesDataStore
import com.musicplayer.core.model.AudioOutputQualityPolicy
import com.musicplayer.core.model.CellularBitrateCap
import com.musicplayer.core.model.DownloadQualityMode
import com.musicplayer.core.model.DownloadStatus
import com.musicplayer.core.model.StreamQualitySelection
import com.musicplayer.core.model.Track
import com.musicplayer.core.network.JellyfinClientProvider
import com.musicplayer.core.network.NetworkMonitor
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

data class ResolvedAudioSource(
    val uri: String,
    val isLocal: Boolean,
    val transcodedBitrateKbps: Int? = null,
    val mimeType: String? = null,
)

@Singleton
class StreamUrlResolver @Inject constructor(
    private val clientProvider: JellyfinClientProvider,
    private val authSessionStore: AuthSessionStore,
    private val networkMonitor: NetworkMonitor,
    private val audioOutputMonitor: AudioOutputMonitor,
    private val preferencesDataStore: UserPreferencesDataStore,
    private val downloadDao: DownloadDao,
    @param:Dispatcher(AppDispatcher.IO) private val ioDispatcher: CoroutineDispatcher,
) {
    suspend fun resolve(
        track: Track,
        qualitySelection: StreamQualitySelection = StreamQualitySelection.AUTO,
    ): ResolvedAudioSource? = withContext(ioDispatcher) {
        val prefs = preferencesDataStore.userPreferences.first()
        val netState = networkMonitor.currentNetworkState()
        val isOffline = prefs.offlineModeEnabled || !netState.isConnected

        val localRecord = downloadDao.getByTrackId(track.id)
        val localSource = localRecord?.toValidLocalSource()

        // Offline or preferring local (AUTO/LOCAL): immediately use local copy if downloaded
        if (localSource != null &&
            (isOffline || qualitySelection == StreamQualitySelection.AUTO || qualitySelection == StreamQualitySelection.LOCAL)
        ) {
            return@withContext localSource
        }

        if (isOffline) {
            return@withContext null
        }

        // Refresh audio routing right before resolving so the latest connected device is always used
        audioOutputMonitor.refreshDevices()
        val api = clientProvider.getClient()
        val userId = authSessionStore.getSession()?.userId
        val outputDeviceCapKbps = AudioOutputQualityPolicy.resolveAutoBitrateCapKbps(
            autoMatchEnabled = prefs.autoMatchOutputQuality,
            activeDevice = audioOutputMonitor.outputState.value.activeDevice,
            hiResBluetoothDevices = prefs.hiResBluetoothDevices,
        )
        resolveForContext(
            track = track,
            baseUrl = api.baseUrl,
            isMetered = netState.isMetered,
            cellularBitrateCap = prefs.cellularBitrateCap,
            outputDeviceMaxBitrateKbps = outputDeviceCapKbps,
            qualitySelection = qualitySelection,
            isOffline = false,
            userId = userId,
            deviceId = api.deviceInfo.id,
        )
    }

    /**
     * Resolves stream/local sources for an entire queue in a single pass, reading preferences,
     * connectivity, audio routing, auth session, and completed downloads only once off the main thread.
     */
    suspend fun resolveBatch(
        tracks: List<Track>,
        qualitySelection: StreamQualitySelection = StreamQualitySelection.AUTO,
        fallbackToAuto: Boolean = false,
    ): Map<String, ResolvedAudioSource> = withContext(ioDispatcher) {
        if (tracks.isEmpty()) return@withContext emptyMap()

        val prefs = preferencesDataStore.userPreferences.first()
        val netState = networkMonitor.currentNetworkState()
        val isOffline = prefs.offlineModeEnabled || !netState.isConnected
        val completedDownloadsById = downloadDao.getCompletedRecords().associateBy { it.trackId }

        var baseUrl: String? = null
        var userId: String? = null
        var deviceId: String? = null
        var outputDeviceCapKbps: Int? = null
        if (!isOffline) {
            audioOutputMonitor.refreshDevices()
            val api = clientProvider.getClient()
            baseUrl = api.baseUrl
            deviceId = api.deviceInfo.id
            userId = authSessionStore.getSession()?.userId
            outputDeviceCapKbps = AudioOutputQualityPolicy.resolveAutoBitrateCapKbps(
                autoMatchEnabled = prefs.autoMatchOutputQuality,
                activeDevice = audioOutputMonitor.outputState.value.activeDevice,
                hiResBluetoothDevices = prefs.hiResBluetoothDevices,
            )
        }

        val resolved = LinkedHashMap<String, ResolvedAudioSource>(tracks.size)
        for (track in tracks) {
            val localSource = completedDownloadsById[track.id]?.toValidLocalSource()
            val source = resolveSingleFromSnapshot(
                track = track,
                localSource = localSource,
                qualitySelection = qualitySelection,
                isOffline = isOffline,
                baseUrl = baseUrl,
                isMetered = netState.isMetered,
                cellularBitrateCap = prefs.cellularBitrateCap,
                outputDeviceCapKbps = outputDeviceCapKbps,
                userId = userId,
                deviceId = deviceId,
            ) ?: if (fallbackToAuto && qualitySelection != StreamQualitySelection.AUTO) {
                resolveSingleFromSnapshot(
                    track = track,
                    localSource = localSource,
                    qualitySelection = StreamQualitySelection.AUTO,
                    isOffline = isOffline,
                    baseUrl = baseUrl,
                    isMetered = netState.isMetered,
                    cellularBitrateCap = prefs.cellularBitrateCap,
                    outputDeviceCapKbps = outputDeviceCapKbps,
                    userId = userId,
                    deviceId = deviceId,
                )
            } else {
                null
            }
            if (source != null) {
                resolved[track.id] = source
            }
        }
        resolved
    }

    private fun resolveSingleFromSnapshot(
        track: Track,
        localSource: ResolvedAudioSource?,
        qualitySelection: StreamQualitySelection,
        isOffline: Boolean,
        baseUrl: String?,
        isMetered: Boolean,
        cellularBitrateCap: CellularBitrateCap,
        outputDeviceCapKbps: Int?,
        userId: String?,
        deviceId: String?,
    ): ResolvedAudioSource? {
        if (localSource != null &&
            (isOffline || qualitySelection == StreamQualitySelection.AUTO || qualitySelection == StreamQualitySelection.LOCAL)
        ) {
            return localSource
        }
        if (isOffline) return null
        return resolveForContext(
            track = track,
            baseUrl = baseUrl,
            isMetered = isMetered,
            cellularBitrateCap = cellularBitrateCap,
            outputDeviceMaxBitrateKbps = outputDeviceCapKbps,
            qualitySelection = qualitySelection,
            isOffline = false,
            userId = userId,
            deviceId = deviceId,
        )
    }

    private fun DownloadRecordEntity.toValidLocalSource(): ResolvedAudioSource? {
        if (status != DownloadStatus.COMPLETED || mediaStoreUri.isNullOrBlank()) return null
        return ResolvedAudioSource(
            uri = mediaStoreUri,
            isLocal = true,
            transcodedBitrateKbps = if (qualityMode == DownloadQualityMode.MP3) bitrateKbps else null,
        )
    }

    companion object {
        fun resolveForContext(
            track: Track,
            baseUrl: String?,
            isMetered: Boolean = false,
            cellularBitrateCap: CellularBitrateCap = CellularBitrateCap.ORIGINAL,
            outputDeviceMaxBitrateKbps: Int? = null,
            qualitySelection: StreamQualitySelection = StreamQualitySelection.AUTO,
            isOffline: Boolean = false,
            userId: String? = null,
            deviceId: String? = null,
        ): ResolvedAudioSource? {
            val hasTrackLocal = track.isDownloaded && !track.localUri.isNullOrBlank()
            if (hasTrackLocal &&
                (isOffline || qualitySelection == StreamQualitySelection.AUTO || qualitySelection == StreamQualitySelection.LOCAL)
            ) {
                return ResolvedAudioSource(uri = track.localUri, isLocal = true)
            }
            if (isOffline) return null

            val cleanBase = baseUrl?.trimEnd('/')?.takeIf { it.isNotEmpty() } ?: return null
            val targetTranscodeKbps: Int? = when (qualitySelection) {
                StreamQualitySelection.HIGH -> null
                StreamQualitySelection.MP3_320,
                StreamQualitySelection.MP3_256,
                StreamQualitySelection.MP3_192,
                StreamQualitySelection.MP3_128,
                -> qualitySelection.explicitBitrateKbps
                StreamQualitySelection.AUTO,
                StreamQualitySelection.LOCAL,
                -> {
                    val cellularCap = if (isMetered) cellularBitrateCap.kbps else null
                    listOfNotNull(cellularCap, outputDeviceMaxBitrateKbps).minOrNull()
                }
            }

            return if (targetTranscodeKbps != null) {
                val maxBitrateBps = targetTranscodeKbps * 1000
                // Use HLS VOD transcoding so ExoPlayer knows full duration and can seek
                // seamlessly to any millisecond (including mid-track quality switches).
                val params = buildList {
                    if (!userId.isNullOrBlank()) add("userId=$userId")
                    if (!deviceId.isNullOrBlank()) add("deviceId=$deviceId")
                    add("container=ts")
                    add("audioCodec=mp3")
                    add("audioBitRate=$maxBitrateBps")
                    add("maxStreamingBitrate=$maxBitrateBps")
                    add("transcodingContainer=ts")
                    add("transcodingProtocol=hls")
                    add("enableRedirection=true")
                }.joinToString("&")
                val transcodeUrl = "$cleanBase/Audio/${track.id}/universal?$params"
                ResolvedAudioSource(
                    uri = transcodeUrl,
                    isLocal = false,
                    transcodedBitrateKbps = targetTranscodeKbps,
                    mimeType = MimeTypes.APPLICATION_M3U8,
                )
            } else {
                val directPlayUrl = "$cleanBase/Audio/${track.id}/stream?static=true"
                ResolvedAudioSource(
                    uri = directPlayUrl,
                    isLocal = false,
                    transcodedBitrateKbps = null,
                )
            }
        }
    }
}
