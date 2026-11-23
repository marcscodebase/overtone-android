package com.musicplayer.core.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.musicplayer.core.model.DownloadOwnerType
import com.musicplayer.core.model.DownloadQualityMode
import com.musicplayer.core.model.DownloadStatus
import com.musicplayer.core.model.SyncActionType
import com.musicplayer.core.model.TranscodeSource

@Entity(
    tableName = "tracks",
    indices = [
        Index(value = ["albumId"]),
        Index(value = ["artistId"]),
        Index(value = ["isFavorite"]),
        Index(value = ["lastPlayedAt"]),
    ],
)
data class TrackEntity(
    @PrimaryKey val id: String,
    val title: String,
    val artistId: String?,
    val artistName: String,
    val artistsJson: String? = null,
    val albumId: String?,
    val albumName: String,
    val trackNumber: Int?,
    val discNumber: Int?,
    val durationMs: Long,
    val container: String?,
    val codec: String?,
    val bitrate: Int?,
    val sampleRate: Int?,
    val bitDepth: Int?,
    val replayGainTrack: Float?,
    val replayGainAlbum: Float?,
    val isFavorite: Boolean = false,
    val playCount: Int = 0,
    val lastPlayedAt: Long? = null,
    val hasLyrics: Boolean = false,
    val primaryImageTag: String? = null,
)

@Entity(
    tableName = "albums",
    indices = [
        Index(value = ["artistId"]),
        Index(value = ["dateAdded"]),
    ],
)
data class AlbumEntity(
    @PrimaryKey val id: String,
    val title: String,
    val artistId: String?,
    val artistName: String,
    val year: Int?,
    val trackCount: Int,
    val isFavorite: Boolean = false,
    val dateAdded: Long = 0L,
    val primaryImageTag: String? = null,
)

@Entity(tableName = "artists")
data class ArtistEntity(
    @PrimaryKey val id: String,
    val name: String,
    val albumCount: Int = 0,
    val isFavorite: Boolean = false,
    val primaryImageTag: String? = null,
)

@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey val id: String,
    val name: String,
    val trackCount: Int = 0,
    val isAutoSyncDownloaded: Boolean = false,
    val downloadQualityOverride: String? = null,
    val lastSyncedAt: Long? = null,
    val primaryImageTag: String? = null,
    val folderName: String? = null,
)

@Entity(
    tableName = "playlist_tracks",
    primaryKeys = ["playlistId", "playlistItemId"],
    indices = [
        Index(value = ["playlistId"]),
        Index(value = ["trackId"]),
    ],
)
data class PlaylistTrackCrossRef(
    val playlistId: String,
    val trackId: String,
    val playlistItemId: String,
    val position: Int,
)

@Entity(
    tableName = "download_records",
    indices = [
        Index(value = ["status"]),
    ],
)
data class DownloadRecordEntity(
    @PrimaryKey val trackId: String,
    val mediaStoreUri: String? = null,
    val relativePath: String? = null,
    val status: DownloadStatus = DownloadStatus.QUEUED,
    val qualityMode: DownloadQualityMode = DownloadQualityMode.LOSSLESS,
    val bitrateKbps: Int? = null,
    val fileSizeBytes: Long = 0L,
    val transcodeSource: TranscodeSource = TranscodeSource.ORIGINAL,
    val downloadedAt: Long? = null,
    val errorMessage: String? = null,
)

@Entity(
    tableName = "download_refs",
    primaryKeys = ["trackId", "ownerType", "ownerId"],
    indices = [
        Index(value = ["ownerType", "ownerId"]),
    ],
)
data class DownloadRefEntity(
    val trackId: String,
    val ownerType: DownloadOwnerType,
    val ownerId: String,
)

@Entity(
    tableName = "playback_queue",
    indices = [
        Index(value = ["trackId"]),
    ],
)
data class PlaybackQueueEntity(
    @PrimaryKey val position: Int,
    val trackId: String,
    val queueItemId: String,
)

@Entity(
    tableName = "pending_sync_actions",
    indices = [
        Index(value = ["createdAt"]),
    ],
)
data class PendingSyncActionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val actionType: SyncActionType,
    val payloadJson: String,
    val createdAt: Long,
    val retryCount: Int = 0,
)
