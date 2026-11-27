package com.musicplayer.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.musicplayer.core.database.entity.TrackEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface TrackDao {

    @Query("SELECT * FROM tracks ORDER BY title COLLATE NOCASE ASC")
    fun observeAll(): Flow<List<TrackEntity>>

    @Query("SELECT * FROM tracks")
    suspend fun getAllSnapshot(): List<TrackEntity>

    @Query("SELECT * FROM tracks WHERE albumId = :albumId ORDER BY COALESCE(discNumber, 1) ASC, COALESCE(trackNumber, 0) ASC, title COLLATE NOCASE ASC")
    fun observeByAlbum(albumId: String): Flow<List<TrackEntity>>

    @Query("SELECT * FROM tracks WHERE albumId = :albumId ORDER BY COALESCE(discNumber, 1) ASC, COALESCE(trackNumber, 0) ASC, title COLLATE NOCASE ASC")
    suspend fun getByAlbum(albumId: String): List<TrackEntity>

    @Query(
        "SELECT * FROM tracks WHERE artistId = :artistId OR artistsJson LIKE '%' || :artistId || '%' " +
            "ORDER BY albumName COLLATE NOCASE ASC, COALESCE(discNumber, 1) ASC, COALESCE(trackNumber, 0) ASC",
    )
    fun observeByArtist(artistId: String): Flow<List<TrackEntity>>

    @Query("SELECT * FROM tracks WHERE isFavorite = 1 ORDER BY title COLLATE NOCASE ASC")
    fun observeFavorites(): Flow<List<TrackEntity>>

    @Query("SELECT * FROM tracks WHERE lastPlayedAt IS NOT NULL ORDER BY lastPlayedAt DESC LIMIT :limit")
    fun observeRecentlyPlayed(limit: Int = 20): Flow<List<TrackEntity>>

    @Query("SELECT * FROM tracks WHERE id = :trackId")
    suspend fun getById(trackId: String): TrackEntity?

    @Query("SELECT * FROM tracks WHERE id IN (:trackIds)")
    suspend fun getByIds(trackIds: List<String>): List<TrackEntity>

    @Query("SELECT * FROM tracks ORDER BY RANDOM() LIMIT :limit")
    suspend fun getRandomTracks(limit: Int): List<TrackEntity>

    @Query(
        """
        SELECT t.* FROM tracks t
        INNER JOIN download_records d ON t.id = d.trackId
        WHERE d.status = 'COMPLETED' AND d.mediaStoreUri IS NOT NULL AND d.mediaStoreUri != ''
        ORDER BY RANDOM()
        LIMIT :limit
        """,
    )
    suspend fun getRandomDownloadedTracks(limit: Int): List<TrackEntity>

    @Query("SELECT * FROM tracks WHERE title LIKE '%' || :query || '%' OR artistName LIKE '%' || :query || '%' OR albumName LIKE '%' || :query || '%' ORDER BY title COLLATE NOCASE ASC LIMIT :limit")
    fun search(query: String, limit: Int = 50): Flow<List<TrackEntity>>

    @Upsert
    suspend fun upsertAll(tracks: List<TrackEntity>)

    @Query("UPDATE tracks SET isFavorite = :isFavorite WHERE id = :trackId")
    suspend fun setFavorite(trackId: String, isFavorite: Boolean)

    @Query("UPDATE tracks SET playCount = playCount + 1, lastPlayedAt = :playedAt WHERE id = :trackId")
    suspend fun recordPlay(trackId: String, playedAt: Long)

    @Query("DELETE FROM tracks")
    suspend fun clearAll()
}
