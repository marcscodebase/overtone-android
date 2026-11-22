package com.musicplayer.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.musicplayer.core.database.entity.ArtistEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ArtistDao {

    @Query("SELECT * FROM artists ORDER BY name COLLATE NOCASE ASC")
    fun observeAll(): Flow<List<ArtistEntity>>

    @Query("SELECT * FROM artists WHERE id = :artistId")
    fun observeById(artistId: String): Flow<ArtistEntity?>

    @Upsert
    suspend fun upsertAll(artists: List<ArtistEntity>)

    @Query("UPDATE artists SET isFavorite = :isFavorite WHERE id = :artistId")
    suspend fun setFavorite(artistId: String, isFavorite: Boolean)

    @Query("DELETE FROM artists")
    suspend fun clearAll()
}
