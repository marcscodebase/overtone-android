package com.musicplayer.data.remote

import com.musicplayer.core.database.entity.AlbumEntity
import com.musicplayer.core.database.entity.ArtistEntity
import com.musicplayer.core.database.entity.DownloadRecordEntity
import com.musicplayer.core.database.entity.PlaylistEntity
import com.musicplayer.core.database.entity.TrackEntity
import com.musicplayer.core.model.Album
import com.musicplayer.core.model.Artist
import com.musicplayer.core.model.DownloadStatus
import com.musicplayer.core.model.Playlist
import com.musicplayer.core.model.PlaylistFolderCodec
import com.musicplayer.core.model.Track
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.ImageType
import org.jellyfin.sdk.model.api.MediaStreamType
import java.time.ZoneOffset

object JellyfinMappers {

    private const val TICKS_PER_MILLISECOND = 10_000L

    fun toTrackEntity(dto: BaseItemDto): TrackEntity {
        val mediaSource = dto.mediaSources?.firstOrNull()
        val audioStream = mediaSource?.mediaStreams?.firstOrNull {
            it.type == MediaStreamType.AUDIO
        }
        val artistItem = dto.artistItems?.firstOrNull() ?: dto.albumArtists?.firstOrNull()
        val artistName = dto.artists?.firstOrNull()
            ?: dto.albumArtist
            ?: artistItem?.name
            ?: "Unknown Artist"

        val primaryImageTag = dto.imageTags?.get(ImageType.PRIMARY)
            ?: dto.albumPrimaryImageTag

        return TrackEntity(
            id = dto.id.toString(),
            title = dto.name?.takeIf { it.isNotBlank() } ?: "Untitled Track",
            artistId = artistItem?.id?.toString(),
            artistName = artistName,
            albumId = dto.albumId?.toString(),
            albumName = dto.album?.takeIf { it.isNotBlank() } ?: "Unknown Album",
            trackNumber = dto.indexNumber,
            discNumber = dto.parentIndexNumber,
            durationMs = (dto.runTimeTicks ?: 0L) / TICKS_PER_MILLISECOND,
            container = mediaSource?.container ?: dto.container,
            codec = audioStream?.codec,
            bitrate = audioStream?.bitRate ?: mediaSource?.bitrate,
            sampleRate = audioStream?.sampleRate,
            bitDepth = audioStream?.bitDepth,
            replayGainTrack = dto.normalizationGain,
            replayGainAlbum = null,
            isFavorite = dto.userData?.isFavorite ?: false,
            playCount = dto.userData?.playCount ?: 0,
            lastPlayedAt = dto.userData?.lastPlayedDate?.toInstant(ZoneOffset.UTC)?.toEpochMilli(),
            hasLyrics = dto.hasLyrics ?: false,
            primaryImageTag = primaryImageTag,
        )
    }

    fun toAlbumEntity(dto: BaseItemDto): AlbumEntity {
        val artistItem = dto.albumArtists?.firstOrNull() ?: dto.artistItems?.firstOrNull()
        val artistName = dto.albumArtist
            ?: artistItem?.name
            ?: dto.artists?.firstOrNull()
            ?: "Unknown Artist"

        return AlbumEntity(
            id = dto.id.toString(),
            title = dto.name?.takeIf { it.isNotBlank() } ?: "Untitled Album",
            artistId = artistItem?.id?.toString(),
            artistName = artistName,
            year = dto.productionYear,
            trackCount = dto.childCount ?: dto.songCount ?: 0,
            isFavorite = dto.userData?.isFavorite ?: false,
            dateAdded = dto.dateCreated?.toInstant(ZoneOffset.UTC)?.toEpochMilli() ?: 0L,
            primaryImageTag = dto.imageTags?.get(ImageType.PRIMARY),
        )
    }

    fun toArtistEntity(dto: BaseItemDto): ArtistEntity {
        return ArtistEntity(
            id = dto.id.toString(),
            name = dto.name?.takeIf { it.isNotBlank() } ?: "Unknown Artist",
            albumCount = dto.albumCount ?: dto.childCount ?: 0,
            isFavorite = dto.userData?.isFavorite ?: false,
            primaryImageTag = dto.imageTags?.get(ImageType.PRIMARY),
        )
    }

    fun toPlaylistEntity(
        dto: BaseItemDto,
        existingAutoSync: Boolean = false,
        existingDownloadQualityOverride: String? = null,
        existingLastSyncedAt: Long? = null,
    ): PlaylistEntity {
        val parsed = PlaylistFolderCodec.parse(rawName = dto.name, tags = dto.tags)
        return PlaylistEntity(
            id = PlaylistFolderCodec.normalizeJellyfinId(dto.id.toString()),
            name = parsed.displayName,
            trackCount = dto.childCount ?: dto.songCount ?: 0,
            isAutoSyncDownloaded = existingAutoSync,
            downloadQualityOverride = existingDownloadQualityOverride,
            lastSyncedAt = existingLastSyncedAt,
            primaryImageTag = dto.imageTags?.get(ImageType.PRIMARY),
            folderName = parsed.folderName,
        )
    }

    fun TrackEntity.toDomain(
        artworkUrl: String?,
        downloadRecord: DownloadRecordEntity? = null,
    ): Track {
        val isCompletedLocal = downloadRecord?.status == DownloadStatus.COMPLETED &&
            !downloadRecord.mediaStoreUri.isNullOrBlank()
        return Track(
            id = id,
            title = title,
            artistId = artistId,
            artistName = artistName,
            albumId = albumId,
            albumName = albumName,
            trackNumber = trackNumber,
            discNumber = discNumber,
            durationMs = durationMs,
            container = container,
            codec = codec,
            bitrate = bitrate,
            sampleRate = sampleRate,
            bitDepth = bitDepth,
            isFavorite = isFavorite,
            playCount = playCount,
            lastPlayedAt = lastPlayedAt,
            hasLyrics = hasLyrics,
            artworkUrl = artworkUrl,
            isDownloaded = isCompletedLocal,
            localUri = if (isCompletedLocal) downloadRecord.mediaStoreUri else null,
        )
    }

    fun AlbumEntity.toDomain(artworkUrl: String?): Album = Album(
        id = id,
        title = title,
        artistId = artistId,
        artistName = artistName,
        year = year,
        trackCount = trackCount,
        isFavorite = isFavorite,
        dateAdded = dateAdded,
        artworkUrl = artworkUrl,
    )

    fun ArtistEntity.toDomain(artworkUrl: String?): Artist = Artist(
        id = id,
        name = name,
        albumCount = albumCount,
        isFavorite = isFavorite,
        artworkUrl = artworkUrl,
    )

    fun PlaylistEntity.toDomain(artworkUrl: String?): Playlist = Playlist(
        id = id,
        name = name,
        trackCount = trackCount,
        isAutoSyncDownloaded = isAutoSyncDownloaded,
        artworkUrl = artworkUrl,
        folderName = folderName,
    )
}
