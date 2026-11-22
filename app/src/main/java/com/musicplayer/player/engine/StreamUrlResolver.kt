package com.musicplayer.player.engine

import com.musicplayer.core.database.dao.DownloadDao
import com.musicplayer.core.datastore.UserPreferencesDataStore
import com.musicplayer.core.model.CellularBitrateCap
import com.musicplayer.core.model.DownloadQualityMode
import com.musicplayer.core.model.DownloadStatus
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
    suspend fun resolve(track: Track): ResolvedAudioSource? {
        val localRecord = downloadDao.getByTrackId(track.id)
        if (localRecord != null &&
            localRecord.status == DownloadStatus.COMPLETED &&
            !localRecord.mediaStoreUri.isNullOrBlank()
        ) {
            return ResolvedAudioSource(
                uri = localRecord.mediaStoreUri,
                isLocal = true,
                transcodedBitrateKbps = if (localRecord.qualityMode == DownloadQualityMode.MP3) {
                    localRecord.bitrateKbps
                } else {
                    null
                },
            )
        }

        val prefs = preferencesDataStore.userPreferences.first()
        val isMetered = networkMonitor.networkState.value.isMetered
        return resolveForContext(
            track = track,
            baseUrl = clientProvider.getClient().baseUrl,
            isMetered = isMetered,
            cellularBitrateCap = prefs.cellularBitrateCap,
        )
    }

    companion object {
        fun resolveForContext(
            track: Track,
            baseUrl: String?,
            isMetered: Boolean = false,
            cellularBitrateCap: CellularBitrateCap = CellularBitrateCap.ORIGINAL,
        ): ResolvedAudioSource? {
            if (track.isDownloaded && !track.localUri.isNullOrBlank()) {
                return ResolvedAudioSource(uri = track.localUri, isLocal = true)
            }

            val cleanBase = baseUrl?.trimEnd('/')?.takeIf { it.isNotEmpty() } ?: return null
            val cappedKbps = if (isMetered) cellularBitrateCap.kbps else null

            return if (cappedKbps != null) {
                val maxBitrateBps = cappedKbps * 1000
                val transcodeUrl = "$cleanBase/Audio/${track.id}/universal" +
                    "?container=mp3,aac,opus" +
                    "&audioCodec=mp3" +
                    "&maxStreamingBitrate=$maxBitrateBps" +
                    "&transcodingContainer=mp3" +
                    "&transcodingProtocol=http"
                ResolvedAudioSource(
                    uri = transcodeUrl,
                    isLocal = false,
                    transcodedBitrateKbps = cappedKbps,
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
