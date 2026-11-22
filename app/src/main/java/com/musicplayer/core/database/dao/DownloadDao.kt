package com.musicplayer.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.musicplayer.core.database.entity.DownloadRecordEntity
import com.musicplayer.core.database.entity.DownloadRefEntity
import com.musicplayer.core.model.DownloadOwnerType
import com.musicplayer.core.model.DownloadStatus
import kotlinx.coroutines.flow.Flow

@Dao
interface DownloadDao {

    @Query("SELECT * FROM download_records")
    fun observeAllRecords(): Flow<List<DownloadRecordEntity>>

    @Query("SELECT * FROM download_records WHERE status = :status")
    fun observeByStatus(status: DownloadStatus): Flow<List<DownloadRecordEntity>>

    @Query("SELECT * FROM download_records WHERE trackId = :trackId")
    fun observeByTrackId(trackId: String): Flow<DownloadRecordEntity?>

    @Query("SELECT * FROM download_records WHERE trackId = :trackId")
    suspend fun getByTrackId(trackId: String): DownloadRecordEntity?

    @Query("SELECT * FROM download_records WHERE status = 'COMPLETED'")
    suspend fun getCompletedRecords(): List<DownloadRecordEntity>

    @Upsert
    suspend fun upsertRecord(record: DownloadRecordEntity)

    @Query("UPDATE download_records SET status = :status, errorMessage = :errorMessage WHERE trackId = :trackId")
    suspend fun updateStatus(trackId: String, status: DownloadStatus, errorMessage: String? = null)

    @Query("DELETE FROM download_records WHERE trackId = :trackId")
    suspend fun deleteRecord(trackId: String)

    @Upsert
    suspend fun upsertRef(ref: DownloadRefEntity)

    @Upsert
    suspend fun upsertRefs(refs: List<DownloadRefEntity>)

    @Query("DELETE FROM download_refs WHERE trackId = :trackId AND ownerType = :ownerType AND ownerId = :ownerId")
    suspend fun deleteRef(trackId: String, ownerType: DownloadOwnerType, ownerId: String)

    @Query("DELETE FROM download_refs WHERE ownerType = :ownerType AND ownerId = :ownerId")
    suspend fun deleteRefsForOwner(ownerType: DownloadOwnerType, ownerId: String)

    @Query("DELETE FROM download_refs WHERE trackId = :trackId")
    suspend fun deleteAllRefsForTrack(trackId: String)

    @Query("SELECT * FROM download_refs")
    fun observeAllRefs(): Flow<List<DownloadRefEntity>>

    @Query("SELECT * FROM download_refs WHERE ownerType = :ownerType AND ownerId = :ownerId")
    fun observeRefsByOwner(ownerType: DownloadOwnerType, ownerId: String): Flow<List<DownloadRefEntity>>

    @Query("SELECT * FROM download_refs WHERE ownerType = :ownerType AND ownerId = :ownerId")
    suspend fun getRefsByOwner(ownerType: DownloadOwnerType, ownerId: String): List<DownloadRefEntity>

    @Query("SELECT * FROM download_records")
    suspend fun getAllRecordsSnapshot(): List<DownloadRecordEntity>

    @Query("DELETE FROM download_records")
    suspend fun deleteAllRecords()

    @Query("DELETE FROM download_refs")
    suspend fun deleteAllRefs()

    @Query("SELECT COUNT(*) FROM download_refs WHERE trackId = :trackId")
    suspend fun countRefsForTrack(trackId: String): Int

    @Query(
        """
        SELECT dr.trackId FROM download_records dr
        LEFT JOIN download_refs ref ON dr.trackId = ref.trackId
        WHERE ref.trackId IS NULL
        """,
    )
    suspend fun findOrphanedTrackIds(): List<String>
}
