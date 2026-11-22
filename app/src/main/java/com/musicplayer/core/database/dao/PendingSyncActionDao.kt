package com.musicplayer.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.musicplayer.core.database.entity.PendingSyncActionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PendingSyncActionDao {

    @Query("SELECT COUNT(*) FROM pending_sync_actions")
    fun observePendingCount(): Flow<Int>

    @Query("SELECT * FROM pending_sync_actions ORDER BY createdAt ASC")
    suspend fun getAllPending(): List<PendingSyncActionEntity>

    @Insert
    suspend fun enqueue(action: PendingSyncActionEntity): Long

    @Query("UPDATE pending_sync_actions SET retryCount = retryCount + 1 WHERE id = :id")
    suspend fun incrementRetry(id: Long)

    @Query("DELETE FROM pending_sync_actions WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM pending_sync_actions")
    suspend fun clearAll()
}
