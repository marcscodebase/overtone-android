package com.musicplayer.data.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.musicplayer.core.database.dao.PendingSyncActionDao
import com.musicplayer.core.datastore.UserPreferencesDataStore
import com.musicplayer.core.network.JellyfinClientProvider
import com.musicplayer.data.repository.AuthRepository
import com.musicplayer.data.repository.SyncRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

interface OfflineSyncScheduler {
    fun scheduleFlush()
}

@Singleton
class WorkManagerOfflineSyncScheduler @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : OfflineSyncScheduler {

    override fun scheduleFlush() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val request = OneTimeWorkRequestBuilder<OfflineSyncWorker>()
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(OfflineSyncWorker.TAG_OFFLINE_SYNC)
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            OfflineSyncWorker.UNIQUE_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }
}

@HiltWorker
class OfflineSyncWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val authRepository: AuthRepository,
    private val clientProvider: JellyfinClientProvider,
    private val preferencesDataStore: UserPreferencesDataStore,
    private val pendingSyncActionDao: PendingSyncActionDao,
    private val syncRepository: SyncRepository,
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        if (preferencesDataStore.userPreferences.first().offlineModeEnabled) {
            return Result.success()
        }

        if (clientProvider.getClient().accessToken.isNullOrBlank()) {
            authRepository.restoreSession()
        }
        if (clientProvider.getClient().accessToken.isNullOrBlank()) {
            return Result.success()
        }

        syncRepository.flushPendingSyncActions()

        val remaining = pendingSyncActionDao.getAllPending()
        return if (remaining.isNotEmpty() && runAttemptCount < MAX_WORKER_RETRIES) {
            Result.retry()
        } else {
            Result.success()
        }
    }

    companion object {
        const val UNIQUE_WORK_NAME = "offline_sync_outbox"
        const val TAG_OFFLINE_SYNC = "music_player_offline_sync"
        private const val MAX_WORKER_RETRIES = 5
    }
}
