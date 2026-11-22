package com.musicplayer.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.musicplayer.core.database.entity.PlaybackQueueEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PlaybackQueueDao {

    @Query("SELECT * FROM playback_queue ORDER BY position ASC")
    fun observeQueue(): Flow<List<PlaybackQueueEntity>>

    @Query("SELECT * FROM playback_queue ORDER BY position ASC")
    suspend fun getQueueSnapshot(): List<PlaybackQueueEntity>

    @Upsert
    suspend fun upsertAll(items: List<PlaybackQueueEntity>)

    @Query("DELETE FROM playback_queue")
    suspend fun clearQueue()

    @Transaction
    suspend fun replaceQueue(items: List<PlaybackQueueEntity>) {
        clearQueue()
        upsertAll(items)
    }
}
