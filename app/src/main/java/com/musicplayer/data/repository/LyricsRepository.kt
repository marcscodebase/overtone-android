package com.musicplayer.data.repository

import com.musicplayer.core.common.AppDispatcher
import com.musicplayer.core.common.Dispatcher
import com.musicplayer.core.database.dao.DownloadDao
import com.musicplayer.core.model.DownloadStatus
import com.musicplayer.core.model.LyricLine
import com.musicplayer.core.model.LyricsParser
import com.musicplayer.core.model.LyricsSource
import com.musicplayer.core.model.TrackLyrics
import com.musicplayer.core.network.JellyfinClientProvider
import com.musicplayer.core.network.OfflineStateProvider
import com.musicplayer.data.local.EmbeddedLyricsReader
import com.musicplayer.player.PlaybackOkHttpClient
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject
import javax.inject.Singleton

interface LyricsRepository {
    suspend fun getLyrics(trackId: String): TrackLyrics?
    fun clearCache() {}
}

@Serializable
internal data class JellyfinLyricsResponseDto(
    @SerialName("Lyrics") val lyrics: List<JellyfinLyricLineDto> = emptyList(),
)

@Serializable
internal data class JellyfinLyricLineDto(
    @SerialName("Text") val text: String? = null,
    @SerialName("Start") val startTicks: Long? = null,
)

object JellyfinLyricsMapper {
    private const val TICKS_PER_MS = 10_000L

    internal fun map(dto: JellyfinLyricsResponseDto): TrackLyrics? {
        val rawItems = dto.lyrics.filter { !it.text.isNullOrBlank() }
        if (rawItems.isEmpty()) return null

        val hasTicks = rawItems.any { it.startTicks != null }
        if (hasTicks) {
            val lines = rawItems.map { item ->
                LyricLine(
                    text = item.text!!.trim(),
                    startTimeMs = item.startTicks?.div(TICKS_PER_MS)?.coerceAtLeast(0L),
                )
            }
            return TrackLyrics(
                lines = lines,
                isSynced = true,
                source = LyricsSource.JELLYFIN,
            )
        }

        // If Jellyfin returned unsynced text blocks, still attempt LRC parsing in case lines carry tags
        val joined = rawItems.joinToString("\n") { it.text!!.trim() }
        return LyricsParser.parse(joined, LyricsSource.JELLYFIN)
    }
}

@Singleton
class JellyfinLyricsRepository @Inject constructor(
    private val clientProvider: JellyfinClientProvider,
    private val offlineStateProvider: OfflineStateProvider,
    private val downloadDao: DownloadDao,
    private val embeddedLyricsReader: EmbeddedLyricsReader,
    @param:PlaybackOkHttpClient private val okHttpClient: OkHttpClient,
    private val json: Json,
    @param:Dispatcher(AppDispatcher.IO) private val ioDispatcher: CoroutineDispatcher,
) : LyricsRepository {

    private val cacheLock = Any()
    private val cache = object : LinkedHashMap<String, TrackLyrics?>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, TrackLyrics?>?): Boolean {
            return size > MAX_CACHE_ENTRIES
        }
    }

    override fun clearCache() {
        synchronized(cacheLock) {
            cache.clear()
        }
    }

    override suspend fun getLyrics(trackId: String): TrackLyrics? = withContext(ioDispatcher) {
        synchronized(cacheLock) {
            if (cache.containsKey(trackId)) {
                return@withContext cache[trackId]
            }
        }

        // 1. Prefer Jellyfin Lyrics API when online
        val fromRemote = if (!offlineStateProvider.offlineStatus.value.isOffline) {
            fetchFromJellyfin(trackId)
        } else {
            null
        }

        // 2. Fall back to embedded tags in local MediaStore file
        val resolved = fromRemote ?: fetchFromLocalFile(trackId)

        if (resolved != null || !offlineStateProvider.offlineStatus.value.isOffline) {
            synchronized(cacheLock) {
                cache[trackId] = resolved
            }
        }
        resolved
    }

    private fun fetchFromJellyfin(trackId: String): TrackLyrics? {
        val baseUrl = clientProvider.getClient().baseUrl?.trimEnd('/') ?: return null
        val request = Request.Builder()
            .url("$baseUrl/Audio/$trackId/Lyrics")
            .get()
            .build()

        return runCatching {
            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val body = response.body?.string()?.takeIf { it.isNotBlank() } ?: return@use null
                val dto = json.decodeFromString<JellyfinLyricsResponseDto>(body)
                JellyfinLyricsMapper.map(dto)
            }
        }.getOrNull()
    }

    private suspend fun fetchFromLocalFile(trackId: String): TrackLyrics? {
        val record = downloadDao.getByTrackId(trackId) ?: return null
        if (record.status != DownloadStatus.COMPLETED || record.mediaStoreUri.isNullOrBlank()) {
            return null
        }
        return embeddedLyricsReader.readFromContentUri(record.mediaStoreUri)
    }

    companion object {
        private const val MAX_CACHE_ENTRIES = 32
    }
}
