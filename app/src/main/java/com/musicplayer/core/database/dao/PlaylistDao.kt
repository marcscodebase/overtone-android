package com.musicplayer.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.musicplayer.core.database.entity.PlaylistEntity
import com.musicplayer.core.database.entity.PlaylistTrackCrossRef
import com.musicplayer.core.database.entity.TrackEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PlaylistDao {

    @Query("SELECT * FROM playlists ORDER BY name COLLATE NOCASE ASC")
    fun observeAll(): Flow<List<PlaylistEntity>>

    @Query("SELECT * FROM playlists WHERE id = :playlistId")
    fun observeById(playlistId: String): Flow<PlaylistEntity?>

    @Query("SELECT * FROM playlists WHERE isAutoSyncDownloaded = 1")
    suspend fun getAutoSyncPlaylists(): List<PlaylistEntity>

    @Query(
        """
        SELECT t.* FROM tracks t
        INNER JOIN playlist_tracks pt ON t.id = pt.trackId
        WHERE pt.playlistId = :playlistId
        ORDER BY pt.position ASC
        """,
    )
    fun observeTracksForPlaylist(playlistId: String): Flow<List<TrackEntity>>

    @Upsert
    suspend fun upsertAll(playlists: List<PlaylistEntity>)

    @Query("DELETE FROM playlist_tracks WHERE playlistId = :playlistId")
    suspend fun deleteTrackRefsForPlaylist(playlistId: String)

    @Upsert
    suspend fun upsertTrackRefs(refs: List<PlaylistTrackCrossRef>)

    @Transaction
    suspend fun replacePlaylistTracks(
        playlistId: String,
        refs: List<PlaylistTrackCrossRef>,
    ) {
        deleteTrackRefsForPlaylist(playlistId)
        upsertTrackRefs(refs)
    }

    @Query("UPDATE playlists SET isAutoSyncDownloaded = :enabled WHERE id = :playlistId")
    suspend fun setAutoSyncDownloaded(playlistId: String, enabled: Boolean)

    @Query("DELETE FROM playlists WHERE id = :playlistId")
    suspend fun deleteById(playlistId: String)

    @Query("DELETE FROM playlists")
    suspend fun clearAll()
}
