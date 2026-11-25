package com.musicplayer.data.local

import com.musicplayer.core.common.AppDispatcher
import com.musicplayer.core.common.Dispatcher
import com.musicplayer.core.database.dao.DownloadDao
import com.musicplayer.core.database.dao.TrackDao
import com.musicplayer.core.database.entity.DownloadRecordEntity
import com.musicplayer.core.database.entity.DownloadRefEntity
import com.musicplayer.core.database.entity.TrackEntity
import com.musicplayer.core.model.DownloadOwnerType
import com.musicplayer.core.model.DownloadQualityMode
import com.musicplayer.core.model.DownloadStatus
import com.musicplayer.core.model.TranscodeSource
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

data class ReconcileResult(
    val removedMissingCount: Int = 0,
    val adoptedExistingCount: Int = 0,
    val deduplicatedCount: Int = 0,
)

/**
 * Reconciles Room download records against public MediaStore files under `Music/music-player/`:
 * 1. Prunes Room records whose underlying files were deleted externally.
 * 2. Discovers and verifies pre-existing audio files in `Music/music-player/` (e.g. from a
 *    previous app installation) using embedded Track-ID tags or strict path + duration + metadata
 *    verification, adopting them into Room automatically.
 * 3. Deduplicates `(1)` collision copies when the canonical file and duplicate have matching
 *    identity / SHA-256 checksum.
 */
@Singleton
class MediaStoreReconciler @Inject constructor(
    private val downloadDao: DownloadDao,
    private val trackDao: TrackDao,
    private val mediaStoreAudioWriter: MediaStoreAudioWriter,
    @param:Dispatcher(AppDispatcher.IO) private val ioDispatcher: CoroutineDispatcher,
) {
    suspend fun reconcile(): ReconcileResult = withContext(ioDispatcher) {
        // 1. Remove Room records whose MediaStore URI no longer exists on disk
        val completed = downloadDao.getCompletedRecords()
        var removedCount = 0
        for (record in completed) {
            if (!mediaStoreAudioWriter.exists(record.mediaStoreUri)) {
                downloadDao.deleteAllRefsForTrack(record.trackId)
                downloadDao.deleteRecord(record.trackId)
                removedCount++
            }
        }

        // 2. Scan Music/music-player/ and match verified files to library tracks
        val tracks = trackDao.getAllSnapshot()
        if (tracks.isEmpty()) {
            return@withContext ReconcileResult(removedMissingCount = removedCount)
        }

        val scannedFiles = mediaStoreAudioWriter.scanOfflineFolder()
        if (scannedFiles.isEmpty()) {
            return@withContext ReconcileResult(removedMissingCount = removedCount)
        }

        val tracksById = tracks.associateBy { it.id.lowercase(Locale.US) }
        val tracksByPathKey = HashMap<String, MutableList<TrackEntity>>(tracks.size)
        val extensionsSeen = scannedFiles.mapTo(mutableSetOf()) { it.extension.ifEmpty { "flac" } }

        for (track in tracks) {
            for (ext in extensionsSeen) {
                val target = MediaStorePathBuilder.build(
                    artistName = track.artistName,
                    albumName = track.albumName,
                    title = track.title,
                    trackNumber = track.trackNumber,
                    discNumber = track.discNumber,
                    extension = ext,
                )
                val key = pathKey(target.relativePath, target.displayName)
                tracksByPathKey.getOrPut(key) { mutableListOf() } += track
            }
        }

        val matchedByTrackId = linkedMapOf<String, MutableList<ScannedMediaStoreAudio>>()
        for (file in scannedFiles) {
            val embeddedId = file.inspectedTags.trackId?.trim()?.lowercase(Locale.US)
            val matchedTrack = if (!embeddedId.isNullOrEmpty()) {
                tracksById[embeddedId]?.takeIf { OfflineFileVerifier.verifyFileMatchesTrack(file, it) }
            } else {
                val key = pathKey(file.relativePath, file.baseDisplayName)
                tracksByPathKey[key]?.firstOrNull { OfflineFileVerifier.verifyFileMatchesTrack(file, it) }
            }
            if (matchedTrack != null) {
                matchedByTrackId.getOrPut(matchedTrack.id) { mutableListOf() } += file
            }
        }

        val existingRecordsByTrackId = downloadDao.getAllRecordsSnapshot().associateBy { it.trackId }
        var adoptedCount = 0
        var deduplicatedCount = 0

        for ((trackId, candidates) in matchedByTrackId) {
            val canonical = OfflineFileVerifier.chooseCanonicalFile(candidates) ?: continue

            // Deduplicate any `(1)` duplicate files belonging to this exact track
            if (candidates.size > 1) {
                val canonicalSha = mediaStoreAudioWriter.computeContentSha256(canonical.contentUri)
                for (other in candidates) {
                    if (other.contentUri == canonical.contentUri) continue
                    val sameQuality = AudioTagInspector.inferQuality(
                        other.inspectedTags,
                        other.extension,
                        other.sizeBytes,
                        other.durationMs,
                    ) == AudioTagInspector.inferQuality(
                        canonical.inspectedTags,
                        canonical.extension,
                        canonical.sizeBytes,
                        canonical.durationMs,
                    )
                    if (!sameQuality) continue

                    val otherSha = if (canonicalSha != null) {
                        mediaStoreAudioWriter.computeContentSha256(other.contentUri)
                    } else {
                        null
                    }
                    val confirmedDuplicate = other.isDuplicateNumberedName ||
                        (canonicalSha != null && canonicalSha == otherSha)
                    if (confirmedDuplicate && mediaStoreAudioWriter.delete(other.contentUri)) {
                        deduplicatedCount++
                    }
                }
            }

            val existing = existingRecordsByTrackId[trackId]
            val shouldAdoptOrUpdate = existing == null ||
                existing.status == DownloadStatus.FAILED ||
                (existing.status == DownloadStatus.COMPLETED && existing.mediaStoreUri != canonical.contentUri &&
                    !mediaStoreAudioWriter.exists(existing.mediaStoreUri))

            if (shouldAdoptOrUpdate) {
                val quality = AudioTagInspector.inferQuality(
                    inspectedTags = canonical.inspectedTags,
                    extension = canonical.extension,
                    sizeBytes = canonical.sizeBytes,
                    durationMs = canonical.durationMs,
                )
                downloadDao.upsertRecord(
                    DownloadRecordEntity(
                        trackId = trackId,
                        mediaStoreUri = canonical.contentUri,
                        relativePath = canonical.relativePath + canonical.displayName,
                        status = DownloadStatus.COMPLETED,
                        qualityMode = quality.mode,
                        bitrateKbps = quality.bitrateKbps,
                        fileSizeBytes = canonical.sizeBytes,
                        transcodeSource = if (quality.mode == DownloadQualityMode.LOSSLESS) {
                            TranscodeSource.ORIGINAL
                        } else {
                            TranscodeSource.SERVER
                        },
                        downloadedAt = canonical.dateModifiedSeconds * 1000L,
                        errorMessage = null,
                    ),
                )
                if (downloadDao.countRefsForTrack(trackId) == 0) {
                    downloadDao.upsertRef(
                        DownloadRefEntity(
                            trackId = trackId,
                            ownerType = DownloadOwnerType.TRACK,
                            ownerId = trackId,
                        ),
                    )
                }
                if (existing == null || existing.status != DownloadStatus.COMPLETED) {
                    adoptedCount++
                }
            }
        }

        ReconcileResult(
            removedMissingCount = removedCount,
            adoptedExistingCount = adoptedCount,
            deduplicatedCount = deduplicatedCount,
        )
    }

    private fun pathKey(relativePath: String, displayName: String): String =
        "${relativePath.lowercase(Locale.US)}|${displayName.lowercase(Locale.US)}"
}
