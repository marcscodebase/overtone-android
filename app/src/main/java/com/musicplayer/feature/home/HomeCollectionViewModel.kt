package com.musicplayer.feature.home

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.musicplayer.core.model.Album
import com.musicplayer.core.model.Track
import com.musicplayer.core.network.OfflineStateProvider
import com.musicplayer.core.network.OfflineStatus
import com.musicplayer.data.repository.LibraryRepository
import com.musicplayer.navigation.HomeCollectionDestination
import com.musicplayer.player.PlaybackController
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

enum class HomeCollectionSection(val routeKey: String) {
    RECENTLY_ADDED("recently_added"),
    RECENTLY_PLAYED("recently_played"),
    MORE_FROM_ARTISTS_YOU_LOVE("more_from_artists"),
    REDISCOVER_ALBUMS("rediscover_albums"),
    UNPLAYED_GEMS("unplayed_gems"),
    ;

    val isAlbumCollection: Boolean
        get() = this != RECENTLY_PLAYED

    companion object {
        fun fromRouteKey(key: String?): HomeCollectionSection =
            entries.firstOrNull { it.routeKey.equals(key, ignoreCase = true) } ?: RECENTLY_ADDED
    }
}

data class HomeCollectionUiState(
    val section: HomeCollectionSection = HomeCollectionSection.RECENTLY_ADDED,
    val albums: List<Album> = emptyList(),
    val albumSubtitles: Map<String, String> = emptyMap(),
    val tracks: List<Track> = emptyList(),
    val activeTrackId: String? = null,
    val offlineStatus: OfflineStatus = OfflineStatus(),
) {
    val isOffline: Boolean get() = offlineStatus.isOffline
    val playableTracks: List<Track>
        get() = if (isOffline) tracks.filter { it.isDownloaded } else tracks
}

@HiltViewModel
class HomeCollectionViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    libraryRepository: LibraryRepository,
    private val playbackController: PlaybackController,
    offlineStateProvider: OfflineStateProvider,
) : ViewModel() {

    private val destination = savedStateHandle.toRoute<HomeCollectionDestination>()
    private val section = HomeCollectionSection.fromRouteKey(destination.section)

    private val albumsBundleFlow = when (section) {
        HomeCollectionSection.RECENTLY_ADDED -> libraryRepository.observeAllAlbums().map { albums ->
            albums.sortedByDescending { it.dateAdded } to emptyMap<String, String>()
        }

        HomeCollectionSection.MORE_FROM_ARTISTS_YOU_LOVE -> combine(
            libraryRepository.observeAllAlbums(),
            libraryRepository.observeAllTracks(),
            libraryRepository.observeAllArtists(),
            offlineStateProvider.offlineStatus,
        ) { albums, tracks, artists, offline ->
            val pool = filterAlbumsForOffline(albums, tracks, offline.isOffline)
            HomeDiscoveryEngine.buildMoreFromArtistsYouLove(pool, tracks, artists) to emptyMap()
        }

        HomeCollectionSection.REDISCOVER_ALBUMS -> combine(
            libraryRepository.observeAllAlbums(),
            libraryRepository.observeAllTracks(),
            offlineStateProvider.offlineStatus,
        ) { albums, tracks, offline ->
            val pool = filterAlbumsForOffline(albums, tracks, offline.isOffline)
            val rediscover = HomeDiscoveryEngine.buildRediscoverAlbums(pool, tracks)
            val subtitles = rediscover.associate { item ->
                item.album.id to listOfNotNull(
                    item.album.artistName,
                    formatRelativeDaysAgo(item.daysSinceLastPlayed),
                ).joinToString(" • ")
            }
            rediscover.map { it.album } to subtitles
        }

        HomeCollectionSection.UNPLAYED_GEMS -> combine(
            libraryRepository.observeAllAlbums(),
            libraryRepository.observeAllTracks(),
            offlineStateProvider.offlineStatus,
        ) { albums, tracks, offline ->
            val pool = filterAlbumsForOffline(albums, tracks, offline.isOffline)
            HomeDiscoveryEngine.buildUnplayedGems(pool, tracks) to emptyMap()
        }

        HomeCollectionSection.RECENTLY_PLAYED -> flowOf(emptyList<Album>() to emptyMap())
    }

    private val tracksFlow = if (section == HomeCollectionSection.RECENTLY_PLAYED) {
        libraryRepository.observeRecentlyPlayedTracks(limit = RECENTLY_PLAYED_LIMIT)
    } else {
        flowOf(emptyList())
    }

    val uiState: StateFlow<HomeCollectionUiState> = combine(
        albumsBundleFlow,
        tracksFlow,
        playbackController.playbackState.map { it.currentTrack?.id }.distinctUntilChanged(),
        offlineStateProvider.offlineStatus,
    ) { (albums, subtitles), tracks, activeId, offline ->
        HomeCollectionUiState(
            section = section,
            albums = albums,
            albumSubtitles = subtitles,
            tracks = tracks,
            activeTrackId = activeId,
            offlineStatus = offline,
        )
    }
        .flowOn(Dispatchers.Default)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = HomeCollectionUiState(section = section),
        )

    private fun filterAlbumsForOffline(
        albums: List<Album>,
        tracks: List<Track>,
        isOffline: Boolean,
    ): List<Album> {
        if (!isOffline) return albums
        val downloadedAlbumIds = tracks.asSequence()
            .filter { it.isDownloaded }
            .mapNotNull { it.albumId }
            .toSet()
        return if (downloadedAlbumIds.isNotEmpty()) {
            albums.filter { it.id in downloadedAlbumIds }
        } else {
            albums
        }
    }

    fun onPlayAll() {
        val playable = uiState.value.playableTracks
        if (playable.isNotEmpty()) {
            playbackController.playTracks(
                tracks = playable,
                startIndex = 0,
                shuffle = false,
                contextLabel = "Recently Played",
            )
        }
    }

    fun onShuffleAll() {
        val playable = uiState.value.playableTracks
        if (playable.isNotEmpty()) {
            playbackController.playTracks(
                tracks = playable,
                startIndex = 0,
                shuffle = true,
                contextLabel = "Recently Played",
            )
        }
    }

    fun onTrackClick(index: Int) {
        val state = uiState.value
        val tapped = state.tracks.getOrNull(index) ?: return
        if (state.isOffline && !tapped.isDownloaded) return
        val playable = state.playableTracks
        val startIdx = playable.indexOfFirst { it.id == tapped.id }.coerceAtLeast(0)
        if (playable.isNotEmpty()) {
            playbackController.playTracks(
                tracks = playable,
                startIndex = startIdx,
                shuffle = false,
                contextLabel = "Recently Played",
            )
        }
    }

    fun onAddToQueue(track: Track) = playbackController.addToQueue(track)

    companion object {
        private const val RECENTLY_PLAYED_LIMIT = 100
    }
}

internal fun formatRelativeDaysAgo(days: Int): String? = when {
    days <= 0 -> null
    days == 1 -> "1 day ago"
    days < 14 -> "$days days ago"
    days < 60 -> "${days / 7} wk ago"
    days < 365 -> "${days / 30} mo ago"
    else -> {
        val years = days / 365
        if (years == 1) "1 yr ago" else "$years yrs ago"
    }
}
