package com.musicplayer.player.engine

import com.musicplayer.core.database.dao.DownloadDao
import com.musicplayer.core.datastore.UserPreferencesDataStore
import com.musicplayer.core.model.CellularBitrateCap
import com.musicplayer.core.model.DownloadQualityMode
import com.musicplayer.core.model.DownloadStatus
import com.musicplayer.core.model.Mp3Bitrate
import com.musicplayer.core.model.StreamQualitySelection
import com.musicplayer.core.model.Track
import com.musicplayer.core.network.JellyfinClientProvider
import com.musicplayer.core.network.NetworkMonitor
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

data class ResolvedAudioSource(
    val uri: String,
    val isLocal: Boolean,
    val transcodedBitrateKbps: Int? = null,
)

@Singleton
class StreamUrlResolver @Inject constructor(
    private val clientProvider: JellyfinClientProvider,
    private val networkMonitor: NetworkMonitor,
    private val preferencesDataStore: UserPreferencesDataStore,
    private val downloadDao: DownloadDao,
) {
    suspend fun resolve(
        track: Track,
        qualitySelection: StreamQualitySelection = StreamQualitySelection.AUTO,
    ): ResolvedAudioSource? {
        val prefs = preferencesDataStore.userPreferences.first()
        val netState = networkMonitor.networkState.value
        val isOffline = prefs.offlineModeEnabled || !netState.isConnected

        val localRecord = downloadDao.getByTrackId(track.id)
        val hasValidLocal = localRecord != null &&
            localRecord.status == DownloadStatus.COMPLETED &&
            !localRecord.mediaStoreUri.isNullOrBlank()

        val localSource = if (hasValidLocal) {
            ResolvedAudioSource(
                uri = localRecord.mediaStoreUri,
                isLocal = true,
                transcodedBitrateKbps = if (localRecord.qualityMode == DownloadQualityMode.MP3) {
                    localRecord.bitrateKbps
                } else {
                    null
                },
            )
        } else {
            null
        }

        // Offline or preferring local (AUTO/LOCAL): immediately use local copy if downloaded
        if (localSource != null &&
            (isOffline || qualitySelection == StreamQualitySelection.AUTO || qualitySelection == StreamQualitySelection.LOCAL)
        ) {
            return localSource
        }

        if (isOffline) {
            return null
        }

        return resolveForContext(
            track = track,
            baseUrl = clientProvider.getClient().baseUrl,
            isMetered = netState.isMetered,
            cellularBitrateCap = prefs.cellularBitrateCap,
            lowQualityBitrate = prefs.lowQualityBitrate,
            qualitySelection = qualitySelection,
            isOffline = false,
        )
    }

    companion object {
        fun resolveForContext(
            track: Track,
            baseUrl: String?,
            isMetered: Boolean = false,
            cellularBitrateCap: CellularBitrateCap = CellularBitrateCap.ORIGINAL,
            lowQualityBitrate: Mp3Bitrate = Mp3Bitrate.KBPS_128,
            qualitySelection: StreamQualitySelection = StreamQualitySelection.AUTO,
            isOffline: Boolean = false,
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
                StreamQualitySelection.LOW -> lowQualityBitrate.kbps
                StreamQualitySelection.AUTO,
                StreamQualitySelection.LOCAL,
                -> if (isMetered) cellularBitrateCap.kbps else null
            }

            return if (targetTranscodeKbps != null) {
                val maxBitrateBps = targetTranscodeKbps * 1000
                val transcodeUrl = "$cleanBase/Audio/${track.id}/universal" +
                    "?container=mp3,aac,opus" +
                    "&audioCodec=mp3" +
                    "&maxStreamingBitrate=$maxBitrateBps" +
                    "&transcodingContainer=mp3" +
                    "&transcodingProtocol=http"
                ResolvedAudioSource(
                    uri = transcodeUrl,
                    isLocal = false,
                    transcodedBitrateKbps = targetTranscodeKbps,
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
