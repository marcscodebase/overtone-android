package com.musicplayer.data.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.musicplayer.core.common.AppResult
import com.musicplayer.core.database.dao.PlaylistDao
import com.musicplayer.core.datastore.UserPreferencesDataStore
import com.musicplayer.core.network.JellyfinClientProvider
import com.musicplayer.data.repository.AuthRepository
import com.musicplayer.data.repository.DownloadRepository
import com.musicplayer.data.repository.LibraryRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

interface PlaylistSyncScheduler {
    fun syncPlaylistNow(playlistId: String)
    fun syncAllAutoSyncPlaylistsNow()
    fun ensurePeriodicSyncScheduled(wifiOnly: Boolean)
    fun cancelPeriodicSync()
}

@Singleton
class WorkManagerPlaylistSyncScheduler @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : PlaylistSyncScheduler {

    private val workManager: WorkManager
        get() = WorkManager.getInstance(context)

    override fun syncPlaylistNow(playlistId: String) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val request = OneTimeWorkRequestBuilder<PlaylistSyncWorker>()
            .setInputData(workDataOf(PlaylistSyncWorker.KEY_PLAYLIST_ID to playlistId))
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(PlaylistSyncWorker.TAG_PLAYLIST_SYNC)
            .build()

        workManager.enqueueUniqueWork(
            "${PlaylistSyncWorker.ONE_TIME_PREFIX}$playlistId",
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }

    override fun syncAllAutoSyncPlaylistsNow() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val request = OneTimeWorkRequestBuilder<PlaylistSyncWorker>()
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(PlaylistSyncWorker.TAG_PLAYLIST_SYNC)
            .build()

        workManager.enqueueUniqueWork(
            PlaylistSyncWorker.ONE_TIME_ALL_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }

    override fun ensurePeriodicSyncScheduled(wifiOnly: Boolean) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(
                if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED,
            )
            .build()

        val request = PeriodicWorkRequestBuilder<PlaylistSyncWorker>(6, TimeUnit.HOURS)
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
            .addTag(PlaylistSyncWorker.TAG_PLAYLIST_SYNC)
            .build()

        workManager.enqueueUniquePeriodicWork(
            PlaylistSyncWorker.PERIODIC_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    override fun cancelPeriodicSync() {
        workManager.cancelUniqueWork(PlaylistSyncWorker.PERIODIC_WORK_NAME)
    }
}

@HiltWorker
class PlaylistSyncWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val authRepository: AuthRepository,
    private val clientProvider: JellyfinClientProvider,
    private val preferencesDataStore: UserPreferencesDataStore,
    private val playlistDao: PlaylistDao,
    private val libraryRepository: LibraryRepository,
    private val downloadRepository: DownloadRepository,
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

        val specificPlaylistId = inputData.getString(KEY_PLAYLIST_ID)?.takeIf { it.isNotBlank() }
        val targetIds = if (specificPlaylistId != null) {
            listOf(specificPlaylistId)
        } else {
            playlistDao.getAutoSyncPlaylists().map { it.id }
        }

        if (targetIds.isEmpty()) return Result.success()

        var hadTransientFailure = false
        for (playlistId in targetIds) {
            when (libraryRepository.syncPlaylistTracks(playlistId)) {
                is AppResult.Success -> {
                    val remoteTrackIds = playlistDao.getTrackRefsSnapshot(playlistId)
                        .map { it.trackId }
                        .toSet()
                    downloadRepository.syncPlaylistDownloads(
                        playlistId = playlistId,
                        remoteTrackIds = remoteTrackIds,
                    )
                    playlistDao.updateLastSyncedAt(playlistId, System.currentTimeMillis())
                }

                is AppResult.Error -> {
                    hadTransientFailure = true
                }
            }
        }

        return if (hadTransientFailure && runAttemptCount < MAX_RETRIES) {
            Result.retry()
        } else {
            Result.success()
        }
    }

    companion object {
        const val KEY_PLAYLIST_ID = "playlist_id"
        const val PERIODIC_WORK_NAME = "playlist_auto_sync_periodic"
        const val ONE_TIME_ALL_WORK_NAME = "playlist_auto_sync_now_all"
        const val ONE_TIME_PREFIX = "playlist_auto_sync_now_"
        const val TAG_PLAYLIST_SYNC = "music_player_playlist_sync"
        private const val MAX_RETRIES = 3
    }
}
