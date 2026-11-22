package com.musicplayer.core.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.musicplayer.core.database.dao.AlbumDao
import com.musicplayer.core.database.dao.ArtistDao
import com.musicplayer.core.database.dao.DownloadDao
import com.musicplayer.core.database.dao.PendingSyncActionDao
import com.musicplayer.core.database.dao.PlaybackQueueDao
import com.musicplayer.core.database.dao.PlaylistDao
import com.musicplayer.core.database.dao.TrackDao
import com.musicplayer.core.database.entity.AlbumEntity
import com.musicplayer.core.database.entity.ArtistEntity
import com.musicplayer.core.database.entity.DownloadRecordEntity
import com.musicplayer.core.database.entity.DownloadRefEntity
import com.musicplayer.core.database.entity.PendingSyncActionEntity
import com.musicplayer.core.database.entity.PlaybackQueueEntity
import com.musicplayer.core.database.entity.PlaylistEntity
import com.musicplayer.core.database.entity.PlaylistTrackCrossRef
import com.musicplayer.core.database.entity.TrackEntity

@Database(
    entities = [
        TrackEntity::class,
        AlbumEntity::class,
        ArtistEntity::class,
        PlaylistEntity::class,
        PlaylistTrackCrossRef::class,
        DownloadRecordEntity::class,
        DownloadRefEntity::class,
        PlaybackQueueEntity::class,
        PendingSyncActionEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
@TypeConverters(DatabaseConverters::class)
abstract class MusicPlayerDatabase : RoomDatabase() {
    abstract fun trackDao(): TrackDao
    abstract fun albumDao(): AlbumDao
    abstract fun artistDao(): ArtistDao
    abstract fun playlistDao(): PlaylistDao
    abstract fun downloadDao(): DownloadDao
    abstract fun playbackQueueDao(): PlaybackQueueDao
    abstract fun pendingSyncActionDao(): PendingSyncActionDao

    companion object {
        const val DATABASE_NAME = "music_player.db"
    }
}
