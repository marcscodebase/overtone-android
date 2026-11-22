package com.musicplayer.player.queue

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import com.musicplayer.core.common.AppDispatcher
import com.musicplayer.core.common.Dispatcher
import com.musicplayer.core.database.dao.DownloadDao
import com.musicplayer.core.database.dao.PlaybackQueueDao
import com.musicplayer.core.database.dao.TrackDao
import com.musicplayer.core.database.entity.PlaybackQueueEntity
import com.musicplayer.core.datastore.UserPrefsDataStore
import com.musicplayer.core.model.Track
import com.musicplayer.data.remote.JellyfinArtworkUrlBuilder
import com.musicplayer.data.remote.JellyfinMappers.toDomain
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

data class PersistedQueueSnapshot(
    val tracks: List<Track>,
    val currentIndex: Int,
    val positionMs: Long,
)

@Singleton
class QueuePersistenceManager @Inject constructor(
    private val playbackQueueDao: PlaybackQueueDao,
    private val trackDao: TrackDao,
    private val downloadDao: DownloadDao,
    private val artworkUrlBuilder: JellyfinArtworkUrlBuilder,
    @param:UserPrefsDataStore private val dataStore: DataStore<Preferences>,
    @param:Dispatcher(AppDispatcher.IO) private val ioDispatcher: CoroutineDispatcher,
) {

    suspend fun saveQueue(
        tracks: List<Track>,
        currentIndex: Int,
        positionMs: Long,
    ) = withContext(ioDispatcher) {
        val entities = tracks.mapIndexed { index, track ->
            PlaybackQueueEntity(
                position = index,
                trackId = track.id,
                queueItemId = "${track.id}_${index}_${UUID.randomUUID().toString().take(8)}",
            )
        }
        playbackQueueDao.replaceQueue(entities)
        dataStore.edit { prefs ->
            prefs[KEY_QUEUE_INDEX] = currentIndex
            prefs[KEY_QUEUE_POSITION_MS] = positionMs.coerceAtLeast(0L)
        }
    }

    suspend fun savePlaybackPosition(currentIndex: Int, positionMs: Long) = withContext(ioDispatcher) {
        dataStore.edit { prefs ->
            prefs[KEY_QUEUE_INDEX] = currentIndex
            prefs[KEY_QUEUE_POSITION_MS] = positionMs.coerceAtLeast(0L)
        }
    }

    suspend fun clear() = withContext(ioDispatcher) {
        playbackQueueDao.clearQueue()
        dataStore.edit { prefs ->
            prefs.remove(KEY_QUEUE_INDEX)
            prefs.remove(KEY_QUEUE_POSITION_MS)
        }
    }

    suspend fun restoreQueue(): PersistedQueueSnapshot? = withContext(ioDispatcher) {
        val queueRows = playbackQueueDao.getQueueSnapshot()
        if (queueRows.isEmpty()) return@withContext null

        val downloads = downloadDao.observeAllRecords().first().associateBy { it.trackId }
        val tracks = queueRows.mapNotNull { row ->
            val entity = trackDao.getById(row.trackId) ?: return@mapNotNull null
            entity.toDomain(
                artworkUrl = artworkUrlBuilder.buildTrackArtworkUrl(
                    trackId = entity.id,
                    albumId = entity.albumId,
                    imageTag = entity.primaryImageTag,
                ),
                downloadRecord = downloads[entity.id],
            )
        }
        if (tracks.isEmpty()) return@withContext null

        val prefs = dataStore.data.first()
        val savedIndex = (prefs[KEY_QUEUE_INDEX] ?: 0).coerceIn(0, tracks.lastIndex)
        val savedPositionMs = (prefs[KEY_QUEUE_POSITION_MS] ?: 0L).coerceAtLeast(0L)

        PersistedQueueSnapshot(
            tracks = tracks,
            currentIndex = savedIndex,
            positionMs = savedPositionMs,
        )
    }

    companion object {
        private val KEY_QUEUE_INDEX = intPreferencesKey("persisted_queue_index")
        private val KEY_QUEUE_POSITION_MS = longPreferencesKey("persisted_queue_position_ms")
    }
}
