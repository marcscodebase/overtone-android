package com.musicplayer.core.database

import android.content.Context
import androidx.room.Room
import com.musicplayer.core.database.dao.AlbumDao
import com.musicplayer.core.database.dao.ArtistDao
import com.musicplayer.core.database.dao.DownloadDao
import com.musicplayer.core.database.dao.PendingSyncActionDao
import com.musicplayer.core.database.dao.PlaybackQueueDao
import com.musicplayer.core.database.dao.PlaylistDao
import com.musicplayer.core.database.dao.TrackDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideMusicPlayerDatabase(
        @ApplicationContext context: Context,
    ): MusicPlayerDatabase = Room.databaseBuilder(
        context,
        MusicPlayerDatabase::class.java,
        MusicPlayerDatabase.DATABASE_NAME,
    )
        .addMigrations(
            MusicPlayerDatabase.MIGRATION_1_2,
            MusicPlayerDatabase.MIGRATION_2_3,
        )
        .build()

    @Provides
    fun provideTrackDao(db: MusicPlayerDatabase): TrackDao = db.trackDao()

    @Provides
    fun provideAlbumDao(db: MusicPlayerDatabase): AlbumDao = db.albumDao()

    @Provides
    fun provideArtistDao(db: MusicPlayerDatabase): ArtistDao = db.artistDao()

    @Provides
    fun providePlaylistDao(db: MusicPlayerDatabase): PlaylistDao = db.playlistDao()

    @Provides
    fun provideDownloadDao(db: MusicPlayerDatabase): DownloadDao = db.downloadDao()

    @Provides
    fun providePlaybackQueueDao(db: MusicPlayerDatabase): PlaybackQueueDao = db.playbackQueueDao()

    @Provides
    fun providePendingSyncActionDao(db: MusicPlayerDatabase): PendingSyncActionDao =
        db.pendingSyncActionDao()
}
