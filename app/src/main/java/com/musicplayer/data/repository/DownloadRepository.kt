package com.musicplayer.data.repository

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.musicplayer.core.common.AppDispatcher
import com.musicplayer.core.common.Dispatcher
import com.musicplayer.core.database.dao.DownloadDao
import com.musicplayer.core.database.dao.PlaylistDao
import com.musicplayer.core.database.dao.TrackDao
import com.musicplayer.core.database.entity.DownloadRecordEntity
import com.musicplayer.core.database.entity.DownloadRefEntity
import com.musicplayer.core.datastore.UserPreferencesDataStore
import com.musicplayer.core.model.DownloadOwnerType
import com.musicplayer.core.model.DownloadQuality
import com.musicplayer.core.model.DownloadQualityMode
import com.musicplayer.core.model.DownloadStatus
import com.musicplayer.core.model.Track
import com.musicplayer.data.local.MediaStoreAudioWriter
import com.musicplayer.data.local.MediaStoreReconciler
import com.musicplayer.data.remote.JellyfinArtworkUrlBuilder
import com.musicplayer.data.remote.JellyfinMappers.toDomain
import com.musicplayer.data.worker.DownloadWorker
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

data class DownloadedTrackItem(
    val track: Track,
    val record: DownloadRecordEntity,
)

data class StorageSummary(
    val totalBytes: Long = 0L,
    val completedCount: Int = 0,
    val activeCount: Int = 0,
    val failedCount: Int = 0,
    val losslessCount: Int = 0,
    val mp3Count: Int = 0,
    val items: List<DownloadedTrackItem> = emptyList(),
)

/**
 * Pure reference-counting orphan evaluation helper for unit-tested deletion semantics.
 */
object DownloadRefCleanupPolicy {

    fun findOrphanedTrackIdsAfterRemovingOwner(
        allRefs: List<DownloadRefEntity>,
        ownerType: DownloadOwnerType,
        ownerId: String,
    ): Set<String> {
        val affectedTracks = allRefs
            .filter { it.ownerType == ownerType && it.ownerId == ownerId }
            .map { it.trackId }
            .toSet()

        if (affectedTracks.isEmpty()) return emptySet()

        val remainingTrackIds = allRefs
            .filterNot { it.ownerType == ownerType && it.ownerId == ownerId }
            .map { it.trackId }
            .toSet()

        return affectedTracks - remainingTrackIds
    }

    fun isTrackOrphanedAfterRemovingRef(
        allRefs: List<DownloadRefEntity>,
        trackId: String,
        ownerType: DownloadOwnerType,
        ownerId: String,
    ): Boolean {
        return allRefs.none {
            it.trackId == trackId && !(it.ownerType == ownerType && it.ownerId == ownerId)
        }
    }
}

interface DownloadRepository {
    fun observeAllRecords(): Flow<Map<String, DownloadRecordEntity>>
    fun observeTrackRecord(trackId: String): Flow<DownloadRecordEntity?>
    fun observeOwnerHasDownloadRefs(ownerType: DownloadOwnerType, ownerId: String): Flow<Boolean>
    fun observeStorageSummary(): Flow<StorageSummary>

    suspend fun downloadTrack(trackId: String, qualityOverride: DownloadQuality? = null)
    suspend fun downloadAlbum(albumId: String, qualityOverride: DownloadQuality? = null): Int
    suspend fun downloadPlaylist(playlistId: String, qualityOverride: DownloadQuality? = null): Int

    suspend fun removeTrackDownload(trackId: String)
    suspend fun removeAlbumDownload(albumId: String): Int
    suspend fun removePlaylistDownload(playlistId: String): Int
    suspend fun forceDeleteTrackDownload(trackId: String)
    suspend fun retryTrackDownload(trackId: String)
    suspend fun cancelTrackDownload(trackId: String)
    suspend fun clearAllDownloads(): Int
    suspend fun reconcileDownloads(): Int
}

@Singleton
class JellyfinDownloadRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val downloadDao: DownloadDao,
    private val trackDao: TrackDao,
    private val playlistDao: PlaylistDao,
    private val preferencesDataStore: UserPreferencesDataStore,
    private val artworkUrlBuilder: JellyfinArtworkUrlBuilder,
    private val mediaStoreAudioWriter: MediaStoreAudioWriter,
    private val mediaStoreReconciler: MediaStoreReconciler,
    @param:Dispatcher(AppDispatcher.IO) private val ioDispatcher: CoroutineDispatcher,
) : DownloadRepository {

    private val workManager: WorkManager
        get() = WorkManager.getInstance(context)

    override fun observeAllRecords(): Flow<Map<String, DownloadRecordEntity>> =
        downloadDao.observeAllRecords().map { list -> list.associateBy { it.trackId } }

    override fun observeTrackRecord(trackId: String): Flow<DownloadRecordEntity?> =
        downloadDao.observeByTrackId(trackId)

    override fun observeOwnerHasDownloadRefs(
        ownerType: DownloadOwnerType,
        ownerId: String,
    ): Flow<Boolean> = downloadDao.observeRefsByOwner(ownerType, ownerId).map { it.isNotEmpty() }

    override fun observeStorageSummary(): Flow<StorageSummary> = combine(
        downloadDao.observeAllRecords(),
        trackDao.observeAll(),
    ) { records, tracks ->
        val tracksById = tracks.associateBy { it.id }
        val items = records
            .sortedWith(
                compareBy<DownloadRecordEntity> { statusSortOrder(it.status) }
                    .thenByDescending { it.downloadedAt ?: 0L },
            )
            .mapNotNull { record ->
                val entity = tracksById[record.trackId] ?: return@mapNotNull null
                val track = entity.toDomain(
                    artworkUrl = artworkUrlBuilder.buildTrackArtworkUrl(
                        trackId = entity.id,
                        albumId = entity.albumId,
                        imageTag = entity.primaryImageTag,
                    ),
                    downloadRecord = record,
                )
                DownloadedTrackItem(track = track, record = record)
            }

        val completed = records.filter { it.status == DownloadStatus.COMPLETED }
        val active = records.count {
            it.status == DownloadStatus.QUEUED ||
                it.status == DownloadStatus.DOWNLOADING ||
                it.status == DownloadStatus.TRANSCODING
        }
        val failed = records.count { it.status == DownloadStatus.FAILED }
        val losslessCount = completed.count { it.qualityMode == DownloadQualityMode.LOSSLESS }
        val mp3Count = completed.count { it.qualityMode == DownloadQualityMode.MP3 }

        StorageSummary(
            totalBytes = completed.sumOf { it.fileSizeBytes },
            completedCount = completed.size,
            activeCount = active,
            failedCount = failed,
            losslessCount = losslessCount,
            mp3Count = mp3Count,
            items = items,
        )
    }

    override suspend fun downloadTrack(
        trackId: String,
        qualityOverride: DownloadQuality?,
    ) {
        withContext(ioDispatcher) {
            val prefs = preferencesDataStore.userPreferences.first()
            val quality = qualityOverride ?: prefs.defaultDownloadQuality
            downloadDao.upsertRef(
                DownloadRefEntity(
                    trackId = trackId,
                    ownerType = DownloadOwnerType.TRACK,
                    ownerId = trackId,
                ),
            )
            enqueueTrackDownload(
                trackId = trackId,
                quality = quality,
                wifiOnly = prefs.wifiOnlyDownloads,
                replaceIfDifferentQuality = qualityOverride != null,
            )
        }
    }

    override suspend fun downloadAlbum(
        albumId: String,
        qualityOverride: DownloadQuality?,
    ): Int = withContext(ioDispatcher) {
        val tracks = trackDao.getByAlbum(albumId)
        if (tracks.isEmpty()) return@withContext 0

        val prefs = preferencesDataStore.userPreferences.first()
        val quality = qualityOverride ?: prefs.defaultDownloadQuality
        downloadDao.upsertRefs(
            tracks.map { track ->
                DownloadRefEntity(
                    trackId = track.id,
                    ownerType = DownloadOwnerType.ALBUM,
                    ownerId = albumId,
                )
            },
        )
        var enqueued = 0
        for (track in tracks) {
            if (enqueueTrackDownload(
                    trackId = track.id,
                    quality = quality,
                    wifiOnly = prefs.wifiOnlyDownloads,
                    replaceIfDifferentQuality = qualityOverride != null,
                )
            ) {
                enqueued++
            }
        }
        enqueued
    }

    override suspend fun downloadPlaylist(
        playlistId: String,
        qualityOverride: DownloadQuality?,
    ): Int = withContext(ioDispatcher) {
        val refs = playlistDao.getTrackRefsSnapshot(playlistId)
        val trackIds = refs.map { it.trackId }.distinct()
        if (trackIds.isEmpty()) return@withContext 0

        val prefs = preferencesDataStore.userPreferences.first()
        val quality = qualityOverride ?: prefs.defaultDownloadQuality
        downloadDao.upsertRefs(
            trackIds.map { trackId ->
                DownloadRefEntity(
                    trackId = trackId,
                    ownerType = DownloadOwnerType.PLAYLIST,
                    ownerId = playlistId,
                )
            },
        )
        var enqueued = 0
        for (trackId in trackIds) {
            if (enqueueTrackDownload(
                    trackId = trackId,
                    quality = quality,
                    wifiOnly = prefs.wifiOnlyDownloads,
                    replaceIfDifferentQuality = qualityOverride != null,
                )
            ) {
                enqueued++
            }
        }
        enqueued
    }

    override suspend fun removeTrackDownload(trackId: String) = withContext(ioDispatcher) {
        downloadDao.deleteRef(trackId, DownloadOwnerType.TRACK, trackId)
        if (downloadDao.countRefsForTrack(trackId) == 0) {
            purgeTrackFileAndRecord(trackId)
        }
    }

    override suspend fun removeAlbumDownload(albumId: String): Int = withContext(ioDispatcher) {
        removeOwnerDownloads(DownloadOwnerType.ALBUM, albumId)
    }

    override suspend fun removePlaylistDownload(playlistId: String): Int = withContext(ioDispatcher) {
        removeOwnerDownloads(DownloadOwnerType.PLAYLIST, playlistId)
    }

    override suspend fun forceDeleteTrackDownload(trackId: String) = withContext(ioDispatcher) {
        downloadDao.deleteAllRefsForTrack(trackId)
        purgeTrackFileAndRecord(trackId)
    }

    override suspend fun retryTrackDownload(trackId: String) {
        withContext(ioDispatcher) {
            val existing = downloadDao.getByTrackId(trackId)
            val prefs = preferencesDataStore.userPreferences.first()
            val quality = if (existing != null) {
                DownloadQuality(
                    mode = existing.qualityMode,
                    mp3Bitrate = com.musicplayer.core.model.Mp3Bitrate.fromKbps(existing.bitrateKbps),
                )
            } else {
                prefs.defaultDownloadQuality
            }
            if (downloadDao.countRefsForTrack(trackId) == 0) {
                downloadDao.upsertRef(
                    DownloadRefEntity(
                        trackId = trackId,
                        ownerType = DownloadOwnerType.TRACK,
                        ownerId = trackId,
                    ),
                )
            }
            enqueueTrackDownload(
                trackId = trackId,
                quality = quality,
                wifiOnly = prefs.wifiOnlyDownloads,
                replaceIfDifferentQuality = true,
            )
        }
    }

    override suspend fun cancelTrackDownload(trackId: String) = withContext(ioDispatcher) {
        workManager.cancelUniqueWork(DownloadWorker.uniqueWorkName(trackId))
        val record = downloadDao.getByTrackId(trackId)
        if (record != null && record.status != DownloadStatus.COMPLETED) {
            downloadDao.deleteAllRefsForTrack(trackId)
            downloadDao.deleteRecord(trackId)
        }
    }

    override suspend fun clearAllDownloads(): Int = withContext(ioDispatcher) {
        workManager.cancelAllWorkByTag(DownloadWorker.TAG_DOWNLOAD)
        val records = downloadDao.getAllRecordsSnapshot()
        records.forEach { record ->
            mediaStoreAudioWriter.delete(record.mediaStoreUri)
        }
        downloadDao.deleteAllRefs()
        downloadDao.deleteAllRecords()
        records.size
    }

    override suspend fun reconcileDownloads(): Int = mediaStoreReconciler.reconcile()

    private suspend fun removeOwnerDownloads(
        ownerType: DownloadOwnerType,
        ownerId: String,
    ): Int {
        downloadDao.deleteRefsForOwner(ownerType, ownerId)
        val orphans = downloadDao.findOrphanedTrackIds()
        orphans.forEach { trackId ->
            purgeTrackFileAndRecord(trackId)
        }
        return orphans.size
    }

    private suspend fun purgeTrackFileAndRecord(trackId: String) {
        workManager.cancelUniqueWork(DownloadWorker.uniqueWorkName(trackId))
        val record = downloadDao.getByTrackId(trackId)
        if (record != null) {
            mediaStoreAudioWriter.delete(record.mediaStoreUri)
            downloadDao.deleteRecord(trackId)
        }
    }

    private suspend fun enqueueTrackDownload(
        trackId: String,
        quality: DownloadQuality,
        wifiOnly: Boolean,
        replaceIfDifferentQuality: Boolean,
    ): Boolean {
        val existing = downloadDao.getByTrackId(trackId)
        val alreadyMatchesQuality = existing != null &&
            existing.status == DownloadStatus.COMPLETED &&
            !existing.mediaStoreUri.isNullOrBlank() &&
            existing.qualityMode == quality.mode &&
            (quality.mode == DownloadQualityMode.LOSSLESS || existing.bitrateKbps == quality.mp3Bitrate.kbps)

        if (alreadyMatchesQuality) {
            return false
        }

        val isAlreadyInFlight = existing != null &&
            (existing.status == DownloadStatus.QUEUED ||
                existing.status == DownloadStatus.DOWNLOADING ||
                existing.status == DownloadStatus.TRANSCODING)

        if (isAlreadyInFlight && !replaceIfDifferentQuality) {
            return false
        }

        downloadDao.upsertRecord(
            DownloadRecordEntity(
                trackId = trackId,
                mediaStoreUri = existing?.mediaStoreUri,
                relativePath = existing?.relativePath,
                status = DownloadStatus.QUEUED,
                qualityMode = quality.mode,
                bitrateKbps = if (quality.mode == DownloadQualityMode.MP3) quality.mp3Bitrate.kbps else null,
                fileSizeBytes = existing?.fileSizeBytes ?: 0L,
                errorMessage = null,
            ),
        )

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(
                if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED,
            )
            .build()

        val request = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setInputData(DownloadWorker.buildInputData(trackId, quality))
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.SECONDS)
            .addTag(DownloadWorker.TAG_DOWNLOAD)
            .build()

        workManager.enqueueUniqueWork(
            DownloadWorker.uniqueWorkName(trackId),
            if (replaceIfDifferentQuality) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
            request,
        )
        return true
    }

    private fun statusSortOrder(status: DownloadStatus): Int = when (status) {
        DownloadStatus.DOWNLOADING -> 0
        DownloadStatus.TRANSCODING -> 1
        DownloadStatus.QUEUED -> 2
        DownloadStatus.FAILED -> 3
        DownloadStatus.COMPLETED -> 4
    }
}
