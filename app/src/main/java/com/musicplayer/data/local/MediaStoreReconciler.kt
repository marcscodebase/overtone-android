package com.musicplayer.data.local

import com.musicplayer.core.common.AppDispatcher
import com.musicplayer.core.common.Dispatcher
import com.musicplayer.core.database.dao.DownloadDao
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reconciles Room download records against actual public MediaStore files so externally
 * deleted tracks cleanly revert to streamable state.
 */
@Singleton
class MediaStoreReconciler @Inject constructor(
    private val downloadDao: DownloadDao,
    private val mediaStoreAudioWriter: MediaStoreAudioWriter,
    @param:Dispatcher(AppDispatcher.IO) private val ioDispatcher: CoroutineDispatcher,
) {
    suspend fun reconcile(): Int = withContext(ioDispatcher) {
        val completed = downloadDao.getCompletedRecords()
        var removedCount = 0
        for (record in completed) {
            if (!mediaStoreAudioWriter.exists(record.mediaStoreUri)) {
                downloadDao.deleteAllRefsForTrack(record.trackId)
                downloadDao.deleteRecord(record.trackId)
                removedCount++
            }
        }
        removedCount
    }
}
