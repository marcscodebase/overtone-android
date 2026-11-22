package com.musicplayer.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.musicplayer.core.database.entity.AlbumEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AlbumDao {

    @Query("SELECT * FROM albums ORDER BY title COLLATE NOCASE ASC")
    fun observeAll(): Flow<List<AlbumEntity>>

    @Query("SELECT * FROM albums ORDER BY dateAdded DESC LIMIT :limit")
    fun observeRecentlyAdded(limit: Int = 20): Flow<List<AlbumEntity>>

    @Query("SELECT * FROM albums WHERE artistId = :artistId ORDER BY COALESCE(year, 0) DESC, title COLLATE NOCASE ASC")
    fun observeByArtist(artistId: String): Flow<List<AlbumEntity>>

    @Query("SELECT * FROM albums WHERE id = :albumId")
    fun observeById(albumId: String): Flow<AlbumEntity?>

    @Query("SELECT * FROM albums WHERE id = :albumId")
    suspend fun getById(albumId: String): AlbumEntity?

    @Upsert
    suspend fun upsertAll(albums: List<AlbumEntity>)

    @Query("UPDATE albums SET isFavorite = :isFavorite WHERE id = :albumId")
    suspend fun setFavorite(albumId: String, isFavorite: Boolean)

    @Query("DELETE FROM albums")
    suspend fun clearAll()
}
