package com.musicplayer.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.musicplayer.core.common.AppResult
import com.musicplayer.core.model.Album
import com.musicplayer.core.model.Playlist
import com.musicplayer.core.model.Track
import com.musicplayer.core.network.OfflineStateProvider
import com.musicplayer.core.network.OfflineStatus
import com.musicplayer.data.repository.DownloadRepository
import com.musicplayer.data.repository.LibraryRepository
import com.musicplayer.data.repository.SyncRepository
import com.musicplayer.player.PlaybackController
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class HomeUiState(
    val daylist: Daylist? = null,
    val recentlyAddedAlbums: List<Album> = emptyList(),
    val recentlyPlayedTracks: List<Track> = emptyList(),
    val favoriteTracks: List<Track> = emptyList(),
    val playlists: List<Playlist> = emptyList(),
    val activeTrackId: String? = null,
    val offlineStatus: OfflineStatus = OfflineStatus(),
    val isRefreshing: Boolean = false,
    val errorMessage: String? = null,
) {
    val isOffline: Boolean get() = offlineStatus.isOffline
    val isEmpty: Boolean
        get() = daylist == null && recentlyAddedAlbums.isEmpty() && recentlyPlayedTracks.isEmpty() &&
            favoriteTracks.isEmpty() && playlists.isEmpty()
}

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val libraryRepository: LibraryRepository,
    private val downloadRepository: DownloadRepository,
    private val syncRepository: SyncRepository,
    private val playbackController: PlaybackController,
    private val offlineStateProvider: OfflineStateProvider,
) : ViewModel() {

    private val isRefreshing = MutableStateFlow(false)
    private val errorMessage = MutableStateFlow<String?>(null)
    private val daylistSlot = MutableStateFlow(DaylistSlot.current())

    private val activeTrackId = playbackController.playbackState
        .map { it.currentTrack?.id }
        .distinctUntilChanged()

    private val daylistFlow = combine(
        libraryRepository.observeAllTracks(),
        daylistSlot,
    ) { allTracks, slot ->
        if (allTracks.isNotEmpty()) {
            DaylistGenerator.generate(allTracks = allTracks, slot = slot)
        } else {
            null
        }
    }

    private val trackFeeds = combine(
        libraryRepository.observeRecentlyPlayedTracks(limit = 10),
        libraryRepository.observeFavoriteTracks(),
    ) { recent, favorites -> recent to favorites.take(10) }

    private val statusBundle = combine(
        activeTrackId,
        offlineStateProvider.offlineStatus,
        isRefreshing,
        errorMessage,
    ) { activeId, offline, refreshing, error ->
        HomeStatusBundle(activeId, offline, refreshing, error)
    }

    val uiState: StateFlow<HomeUiState> = combine(
        daylistFlow,
        libraryRepository.observeRecentlyAddedAlbums(limit = 16),
        trackFeeds,
        libraryRepository.observeAllPlaylists(),
        statusBundle,
    ) { daylist, albums, (recentTracks, favorites), playlists, status ->
        HomeUiState(
            daylist = daylist,
            recentlyAddedAlbums = albums,
            recentlyPlayedTracks = recentTracks,
            favoriteTracks = favorites,
            playlists = playlists,
            activeTrackId = status.activeTrackId,
            offlineStatus = status.offlineStatus,
            isRefreshing = status.isRefreshing,
            errorMessage = status.errorMessage,
        )
    }
        .flowOn(Dispatchers.Default)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = HomeUiState(isRefreshing = true),
        )

    private data class HomeStatusBundle(
        val activeTrackId: String?,
        val offlineStatus: OfflineStatus,
        val isRefreshing: Boolean,
        val errorMessage: String?,
    )

    init {
        refreshLibrary()
    }

    fun checkDaylistSlot() {
        val nowSlot = DaylistSlot.current()
        if (daylistSlot.value != nowSlot) {
            daylistSlot.value = nowSlot
        }
    }

    fun refreshLibrary() {
        checkDaylistSlot()
        if (isRefreshing.value) return
        viewModelScope.launch {
            isRefreshing.value = true
            errorMessage.value = null
            when (val result = libraryRepository.syncLibrary()) {
                is AppResult.Success -> {
                    errorMessage.value = null
                    downloadRepository.reconcileDownloads()
                }
                is AppResult.Error -> errorMessage.value = result.error.message
            }
            isRefreshing.value = false
        }
    }

    fun onPlayDaylist() {
        val state = uiState.value
        val tracks = state.daylist?.tracks.orEmpty()
        val playable = if (state.isOffline) tracks.filter { it.isDownloaded } else tracks
        if (playable.isNotEmpty()) {
            playbackController.playTracks(
                tracks = playable,
                startIndex = 0,
                shuffle = false,
                contextLabel = DaylistViewModel.DAYLIST_CONTEXT_LABEL,
            )
        }
    }

    fun onShuffleDaylist() {
        val state = uiState.value
        val tracks = state.daylist?.tracks.orEmpty()
        val playable = if (state.isOffline) tracks.filter { it.isDownloaded } else tracks
        if (playable.isNotEmpty()) {
            playbackController.playTracks(
                tracks = playable,
                startIndex = 0,
                shuffle = true,
                contextLabel = DaylistViewModel.DAYLIST_CONTEXT_LABEL,
            )
        }
    }

    fun onTrackClick(tracks: List<Track>, index: Int) {
        val tapped = tracks.getOrNull(index) ?: return
        val isOffline = uiState.value.isOffline
        if (isOffline && !tapped.isDownloaded) return
        val playable = if (isOffline) tracks.filter { it.isDownloaded } else tracks
        val startIdx = playable.indexOfFirst { it.id == tapped.id }.coerceAtLeast(0)
        if (playable.isNotEmpty()) {
            playbackController.playTracks(tracks = playable, startIndex = startIdx)
        }
    }

    fun onPlayNext(track: Track) = playbackController.playNext(track)

    fun onAddToQueue(track: Track) = playbackController.addToQueue(track)

    fun onToggleFavorite(track: Track) {
        val target = !track.isFavorite
        playbackController.updateTrackFavorite(track.id, target)
        viewModelScope.launch {
            syncRepository.setTrackFavorite(track.id, target)
        }
    }
}
