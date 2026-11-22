package com.musicplayer.feature.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.musicplayer.core.common.AppResult
import com.musicplayer.core.model.SearchResults
import com.musicplayer.core.model.Track
import com.musicplayer.core.network.OfflineStateProvider
import com.musicplayer.core.network.OfflineStatus
import com.musicplayer.data.repository.LibraryRepository
import com.musicplayer.data.repository.SyncRepository
import com.musicplayer.player.PlaybackController
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SearchUiState(
    val query: String = "",
    val isSearching: Boolean = false,
    val results: SearchResults = SearchResults(),
    val activeTrackId: String? = null,
    val offlineStatus: OfflineStatus = OfflineStatus(),
    val errorMessage: String? = null,
) {
    val isOffline: Boolean get() = offlineStatus.isOffline
}

@OptIn(FlowPreview::class)
@HiltViewModel
class SearchViewModel @Inject constructor(
    private val libraryRepository: LibraryRepository,
    private val syncRepository: SyncRepository,
    private val playbackController: PlaybackController,
    private val offlineStateProvider: OfflineStateProvider,
) : ViewModel() {

    private val queryFlow = MutableStateFlow("")
    private val _uiState = MutableStateFlow(SearchUiState())
    val uiState: StateFlow<SearchUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            offlineStateProvider.offlineStatus.collectLatest { status ->
                _uiState.update { it.copy(offlineStatus = status) }
            }
        }
        viewModelScope.launch {
            playbackController.playbackState
                .map { it.currentTrack?.id }
                .distinctUntilChanged()
                .collectLatest { trackId ->
                    _uiState.update { it.copy(activeTrackId = trackId) }
                }
        }

        viewModelScope.launch {
            queryFlow
                .debounce(300L)
                .distinctUntilChanged()
                .collectLatest { query ->
                    if (query.isBlank()) {
                        _uiState.update {
                            it.copy(
                                isSearching = false,
                                results = SearchResults(),
                                errorMessage = null,
                            )
                        }
                        return@collectLatest
                    }

                    _uiState.update { it.copy(isSearching = true, errorMessage = null) }
                    when (val result = libraryRepository.search(query)) {
                        is AppResult.Success -> {
                            _uiState.update {
                                it.copy(
                                    isSearching = false,
                                    results = result.data,
                                    errorMessage = null,
                                )
                            }
                        }

                        is AppResult.Error -> {
                            _uiState.update {
                                it.copy(
                                    isSearching = false,
                                    errorMessage = result.error.message,
                                )
                            }
                        }
                    }
                }
        }
    }

    fun onQueryChanged(value: String) {
        _uiState.update { it.copy(query = value) }
        queryFlow.value = value
    }

    fun onClearQuery() {
        onQueryChanged("")
    }

    fun onPlayTrack(tracks: List<Track>, index: Int) {
        val tapped = tracks.getOrNull(index) ?: return
        val isOffline = uiState.value.isOffline
        if (isOffline && !tapped.isDownloaded) return
        val playable = if (isOffline) tracks.filter { it.isDownloaded } else tracks
        val resolvedIndex = playable.indexOfFirst { it.id == tapped.id }.coerceAtLeast(0)
        if (playable.isNotEmpty()) {
            playbackController.playTracks(tracks = playable, startIndex = resolvedIndex)
        }
    }

    fun onPlayNext(track: Track) = playbackController.playNext(track)

    fun onAddToQueue(track: Track) = playbackController.addToQueue(track)

    fun onToggleFavorite(track: Track) {
        val target = !track.isFavorite
        playbackController.updateTrackFavorite(track.id, target)
        _uiState.update { state ->
            state.copy(
                results = state.results.copy(
                    tracks = state.results.tracks.map {
                        if (it.id == track.id) it.copy(isFavorite = target) else it
                    },
                ),
            )
        }
        viewModelScope.launch {
            syncRepository.setTrackFavorite(track.id, target)
        }
    }
}
