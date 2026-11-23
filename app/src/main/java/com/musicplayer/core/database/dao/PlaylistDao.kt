package com.musicplayer.core.database.dao

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.musicplayer.core.database.entity.PlaylistEntity
import com.musicplayer.core.database.entity.PlaylistTrackCrossRef
import com.musicplayer.core.database.entity.TrackEntity
import kotlinx.coroutines.flow.Flow

data class PlaylistTrackRow(
    @Embedded val track: TrackEntity,
    val playlistItemId: String,
    val position: Int,
)

@Dao
interface PlaylistDao {

    @Query("SELECT * FROM playlists ORDER BY name COLLATE NOCASE ASC")
    fun observeAll(): Flow<List<PlaylistEntity>>

    @Query("SELECT * FROM playlists ORDER BY name COLLATE NOCASE ASC")
    suspend fun getAllSnapshot(): List<PlaylistEntity>

    @Query("SELECT * FROM playlists WHERE id = :playlistId")
    fun observeById(playlistId: String): Flow<PlaylistEntity?>

    @Query("SELECT * FROM playlists WHERE id = :playlistId")
    suspend fun getByIdSnapshot(playlistId: String): PlaylistEntity?

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

    @Query(
        """
        SELECT t.*, pt.playlistItemId, pt.position FROM tracks t
        INNER JOIN playlist_tracks pt ON t.id = pt.trackId
        WHERE pt.playlistId = :playlistId
        ORDER BY pt.position ASC
        """,
    )
    fun observeTrackEntriesForPlaylist(playlistId: String): Flow<List<PlaylistTrackRow>>

    @Query("SELECT * FROM playlist_tracks WHERE playlistId = :playlistId ORDER BY position ASC")
    suspend fun getTrackRefsSnapshot(playlistId: String): List<PlaylistTrackCrossRef>

    @Query("SELECT * FROM playlist_tracks WHERE trackId = :trackId")
    fun observeRefsForTrack(trackId: String): Flow<List<PlaylistTrackCrossRef>>

    @Query("SELECT DISTINCT trackId FROM playlist_tracks")
    fun observeAllTrackIdsInPlaylists(): Flow<List<String>>

    @Upsert
    suspend fun upsertAll(playlists: List<PlaylistEntity>)

    @Upsert
    suspend fun upsert(playlist: PlaylistEntity)

    @Query("UPDATE playlists SET name = :name WHERE id = :playlistId")
    suspend fun updateName(playlistId: String, name: String)

    @Query("UPDATE playlists SET name = :name, folderName = :folderName WHERE id = :playlistId")
    suspend fun updateNameAndFolder(playlistId: String, name: String, folderName: String?)

    @Transaction
    suspend fun syncPlaylistsAuthoritative(playlists: List<PlaylistEntity>) {
        val remoteIds = playlists.map { it.id }.toSet()
        val existingIds = getAllSnapshot().map { it.id }
        for (staleId in existingIds) {
            if (staleId !in remoteIds) {
                deletePlaylistAndRefs(staleId)
            }
        }
        if (playlists.isNotEmpty()) {
            upsertAll(playlists)
        }
    }

    @Query("UPDATE playlists SET trackCount = :trackCount WHERE id = :playlistId")
    suspend fun updateTrackCount(playlistId: String, trackCount: Int)

    @Query("DELETE FROM playlist_tracks WHERE playlistId = :playlistId")
    suspend fun deleteTrackRefsForPlaylist(playlistId: String)

    @Query("DELETE FROM playlist_tracks WHERE playlistId = :playlistId AND playlistItemId = :playlistItemId")
    suspend fun deleteTrackRef(playlistId: String, playlistItemId: String)

    @Upsert
    suspend fun upsertTrackRefs(refs: List<PlaylistTrackCrossRef>)

    @Transaction
    suspend fun replacePlaylistTracks(
        playlistId: String,
        refs: List<PlaylistTrackCrossRef>,
    ) {
        deleteTrackRefsForPlaylist(playlistId)
        upsertTrackRefs(refs)
        updateTrackCount(playlistId, refs.size)
    }

    @Query("UPDATE playlists SET isAutoSyncDownloaded = :enabled WHERE id = :playlistId")
    suspend fun setAutoSyncDownloaded(playlistId: String, enabled: Boolean)

    @Query("UPDATE playlists SET lastSyncedAt = :syncedAt WHERE id = :playlistId")
    suspend fun updateLastSyncedAt(playlistId: String, syncedAt: Long)

    @Query("DELETE FROM playlists WHERE id = :playlistId")
    suspend fun deleteById(playlistId: String)

    @Transaction
    suspend fun deletePlaylistAndRefs(playlistId: String) {
        deleteTrackRefsForPlaylist(playlistId)
        deleteById(playlistId)
    }

    @Query("DELETE FROM playlists")
    suspend fun clearAll()
}
