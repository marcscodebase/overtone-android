package com.musicplayer.data.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.musicplayer.R
import com.musicplayer.core.database.dao.AlbumDao
import com.musicplayer.core.database.dao.DownloadDao
import com.musicplayer.core.database.dao.TrackDao
import com.musicplayer.core.database.entity.DownloadRecordEntity
import com.musicplayer.core.datastore.AuthSessionStore
import com.musicplayer.core.model.DownloadQuality
import com.musicplayer.core.model.DownloadQualityMode
import com.musicplayer.core.model.DownloadStatus
import com.musicplayer.core.model.Mp3Bitrate
import com.musicplayer.core.network.JellyfinClientProvider
import com.musicplayer.data.local.MediaStoreAudioWriter
import com.musicplayer.data.local.MediaStorePathBuilder
import com.musicplayer.data.repository.AuthRepository
import com.musicplayer.player.transcoder.HybridAudioDownloader
import com.musicplayer.player.transcoder.HybridDownloadRequest
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import java.io.File
import java.io.IOException

@HiltWorker
class DownloadWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val trackDao: TrackDao,
    private val albumDao: AlbumDao,
    private val downloadDao: DownloadDao,
    private val authRepository: AuthRepository,
    private val authSessionStore: AuthSessionStore,
    private val clientProvider: JellyfinClientProvider,
    private val hybridAudioDownloader: HybridAudioDownloader,
    private val mediaStoreAudioWriter: MediaStoreAudioWriter,
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val trackId = inputData.getString(KEY_TRACK_ID)?.takeIf { it.isNotBlank() }
            ?: return Result.failure()

        val qualityMode = inputData.getString(KEY_QUALITY_MODE)
            ?.let { runCatching { DownloadQualityMode.valueOf(it) }.getOrNull() }
            ?: DownloadQualityMode.LOSSLESS
        val mp3Bitrate = Mp3Bitrate.fromKbps(inputData.getInt(KEY_MP3_BITRATE_KBPS, 320))
        val quality = DownloadQuality(mode = qualityMode, mp3Bitrate = mp3Bitrate)

        // Ensure Jellyfin session is initialized even if WorkManager launched in a cold process
        if (clientProvider.getClient().accessToken.isNullOrBlank()) {
            authRepository.restoreSession()
        }
        val baseUrl = clientProvider.getClient().baseUrl
        if (baseUrl.isNullOrBlank()) {
            downloadDao.updateStatus(trackId, DownloadStatus.FAILED, "Not signed in to Jellyfin")
            return Result.failure()
        }

        val track = trackDao.getById(trackId)
        if (track == null) {
            downloadDao.updateStatus(trackId, DownloadStatus.FAILED, "Track metadata missing")
            return Result.failure()
        }
        val album = track.albumId?.let { albumDao.getById(it) }

        val workDir = File(applicationContext.cacheDir, "downloads/$trackId")
        val notificationId = NOTIFICATION_ID_BASE + (trackId.hashCode() and 0x7FFF)

        ensureNotificationChannel()
        updateForegroundSafe(
            notificationId = notificationId,
            title = applicationContext.getString(R.string.download_notification_downloading, track.title),
            subtitle = track.artistName,
            progressPercent = -1,
        )

        var lastProgressUpdateMs = 0L

        return try {
            val existing = downloadDao.getByTrackId(trackId)
            downloadDao.upsertRecord(
                (existing ?: DownloadRecordEntity(trackId = trackId)).copy(
                    status = DownloadStatus.DOWNLOADING,
                    qualityMode = quality.mode,
                    bitrateKbps = if (quality.mode == DownloadQualityMode.MP3) quality.mp3Bitrate.kbps else null,
                    errorMessage = null,
                ),
            )

            val api = clientProvider.getClient()
            val userId = authSessionStore.getSession()?.userId
            val prepared = hybridAudioDownloader.downloadAndPrepare(
                request = HybridDownloadRequest(
                    trackId = track.id,
                    baseUrl = baseUrl,
                    userId = userId,
                    deviceId = api.deviceInfo.id,
                    title = track.title,
                    artist = track.artistName,
                    album = track.albumName,
                    trackNumber = track.trackNumber,
                    discNumber = track.discNumber,
                    year = album?.year,
                    containerHint = track.container,
                    sourceBitrateKbps = track.bitrate?.div(1000)?.takeIf { it > 0 },
                    replayGainTrackDb = track.replayGainTrack,
                    artworkItemId = track.albumId ?: track.id,
                    quality = quality,
                ),
                workDir = workDir,
                onStatusChange = { status ->
                    downloadDao.updateStatus(trackId, status, null)
                    if (status == DownloadStatus.TRANSCODING) {
                        updateForegroundSafe(
                            notificationId = notificationId,
                            title = applicationContext.getString(
                                R.string.download_notification_transcoding,
                                track.title,
                            ),
                            subtitle = track.artistName,
                            progressPercent = -1,
                        )
                    }
                },
                onProgress = { bytesRead, totalBytes ->
                    val now = System.currentTimeMillis()
                    if (totalBytes > 0L && now - lastProgressUpdateMs >= PROGRESS_THROTTLE_MS) {
                        lastProgressUpdateMs = now
                        val percent = ((bytesRead * 100L) / totalBytes).toInt().coerceIn(0, 100)
                        setProgressAsync(
                            workDataOf(
                                KEY_PROGRESS_PERCENT to percent,
                                KEY_TRACK_ID to trackId,
                            ),
                        )
                    }
                },
            )

            val target = MediaStorePathBuilder.build(
                artistName = track.artistName,
                albumName = track.albumName,
                title = track.title,
                trackNumber = track.trackNumber,
                discNumber = track.discNumber,
                extension = prepared.extension,
            )

            val published = mediaStoreAudioWriter.publishAudioFile(
                sourceFile = prepared.file,
                target = target,
                title = track.title,
                artist = track.artistName,
                album = track.albumName,
                durationMs = track.durationMs,
            )

            downloadDao.upsertRecord(
                DownloadRecordEntity(
                    trackId = trackId,
                    mediaStoreUri = published.contentUri,
                    relativePath = published.relativePath,
                    status = DownloadStatus.COMPLETED,
                    qualityMode = quality.mode,
                    bitrateKbps = prepared.bitrateKbps,
                    fileSizeBytes = published.fileSizeBytes,
                    transcodeSource = prepared.transcodeSource,
                    downloadedAt = System.currentTimeMillis(),
                    errorMessage = null,
                ),
            )

            Result.success()
        } catch (ce: CancellationException) {
            throw ce
        } catch (ioe: IOException) {
            if (runAttemptCount < MAX_RETRIES) {
                downloadDao.updateStatus(trackId, DownloadStatus.QUEUED, ioe.message)
                Result.retry()
            } else {
                downloadDao.updateStatus(
                    trackId,
                    DownloadStatus.FAILED,
                    ioe.message ?: "Download failed",
                )
                Result.failure()
            }
        } catch (t: Throwable) {
            downloadDao.updateStatus(
                trackId,
                DownloadStatus.FAILED,
                t.message ?: "Download failed",
            )
            Result.failure()
        } finally {
            workDir.deleteRecursively()
        }
    }

    private suspend fun updateForegroundSafe(
        notificationId: Int,
        title: String,
        subtitle: String,
        progressPercent: Int,
    ) {
        runCatching {
            val builder = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(title)
                .setContentText(subtitle)
                .setOngoing(true)
                .setSilent(true)
                .setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)

            if (progressPercent in 0..100) {
                builder.setProgress(100, progressPercent, false)
            } else {
                builder.setProgress(0, 0, true)
            }

            val notification = builder.build()
            val foregroundInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ForegroundInfo(
                    notificationId,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
                )
            } else {
                ForegroundInfo(notificationId, notification)
            }
            setForeground(foregroundInfo)
        }
    }

    private fun ensureNotificationChannel() {
        val manager = applicationContext.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            applicationContext.getString(R.string.download_notification_channel),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    companion object {
        const val KEY_TRACK_ID = "track_id"
        const val KEY_QUALITY_MODE = "quality_mode"
        const val KEY_MP3_BITRATE_KBPS = "mp3_bitrate_kbps"
        const val KEY_PROGRESS_PERCENT = "progress_percent"
        const val TAG_DOWNLOAD = "music_player_download"

        private const val CHANNEL_ID = "music_player_downloads"
        private const val NOTIFICATION_ID_BASE = 4100
        private const val MAX_RETRIES = 3
        private const val PROGRESS_THROTTLE_MS = 500L

        fun uniqueWorkName(trackId: String): String = "download_track_$trackId"

        fun buildInputData(trackId: String, quality: DownloadQuality): Data = workDataOf(
            KEY_TRACK_ID to trackId,
            KEY_QUALITY_MODE to quality.mode.name,
            KEY_MP3_BITRATE_KBPS to quality.mp3Bitrate.kbps,
        )
    }
}
