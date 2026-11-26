package com.musicplayer.feature.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.musicplayer.core.model.Album
import com.musicplayer.core.model.Artist
import com.musicplayer.core.model.Playlist
import com.musicplayer.core.model.PlaylistFolderCodec
import com.musicplayer.core.model.PlaylistFolderGroup
import com.musicplayer.core.model.Track
import com.musicplayer.core.network.OfflineStateProvider
import com.musicplayer.core.network.OfflineStatus
import com.musicplayer.data.repository.LibraryRepository
import com.musicplayer.data.repository.PlaylistRepository
import com.musicplayer.data.repository.SyncRepository
import com.musicplayer.player.PlaybackController
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class LibraryFilter {
    DOWNLOADED,
    PLAYLISTS,
    ALBUMS,
    ARTISTS,
    SONGS,
}

enum class LibrarySort {
    ALPHABETICAL,
    RECENTLY_ADDED,
}

data class LibraryUiState(
    val selectedFilter: LibraryFilter = LibraryFilter.ALBUMS,
    val selectedSort: LibrarySort = LibrarySort.ALPHABETICAL,
    val selectedPlaylistFolder: String? = null,
    val downloadedTracks: List<Track> = emptyList(),
    val playlists: List<Playlist> = emptyList(),
    val playlistFolders: List<PlaylistFolderGroup> = emptyList(),
    val rootPlaylists: List<Playlist> = emptyList(),
    val albums: List<Album> = emptyList(),
    val artists: List<Artist> = emptyList(),
    val tracks: List<Track> = emptyList(),
    val activeTrackId: String? = null,
    val offlineStatus: OfflineStatus = OfflineStatus(),
) {
    val isOffline: Boolean get() = offlineStatus.isOffline

    val activeFolderGroup: PlaylistFolderGroup?
        get() = selectedPlaylistFolder?.let { name ->
            playlistFolders.firstOrNull { it.name.equals(name, ignoreCase = true) }
        }
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val libraryRepository: LibraryRepository,
    private val playlistRepository: PlaylistRepository,
    private val syncRepository: SyncRepository,
    private val playbackController: PlaybackController,
    private val offlineStateProvider: OfflineStateProvider,
) : ViewModel() {

    val offlineStatus: StateFlow<OfflineStatus> = offlineStateProvider.offlineStatus

    val existingPlaylistFolders: StateFlow<List<String>> = libraryRepository.observeAllPlaylists()
        .map(PlaylistFolderCodec::distinctFolderNames)
        .distinctUntilChanged()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList(),
        )

    private val selectedFilter = MutableStateFlow(LibraryFilter.ALBUMS)
    private val selectedSort = MutableStateFlow(LibrarySort.ALPHABETICAL)
    private val selectedPlaylistFolder = MutableStateFlow<String?>(null)

    private val activeTrackId = playbackController.playbackState
        .map { it.currentTrack?.id }
        .distinctUntilChanged()

    private val activeCollectionState: Flow<LibraryUiState> =
        combine(selectedFilter, selectedSort) { filter, sort -> filter to sort }
            .flatMapLatest { (filter, sort) ->
                when (filter) {
                    LibraryFilter.DOWNLOADED -> libraryRepository.observeDownloadedTracks().map { tracks ->
                        LibraryUiState(
                            selectedFilter = filter,
                            selectedSort = sort,
                            downloadedTracks = sortTracks(tracks, sort),
                        )
                    }

                    LibraryFilter.PLAYLISTS -> libraryRepository.observeAllPlaylists().map { playlists ->
                        val (folders, root) = PlaylistFolderCodec.groupPlaylists(playlists)
                        LibraryUiState(
                            selectedFilter = filter,
                            selectedSort = sort,
                            playlists = playlists,
                            playlistFolders = folders,
                            rootPlaylists = root,
                        )
                    }

                    LibraryFilter.ALBUMS -> libraryRepository.observeAllAlbums().map { albums ->
                        val sorted = when (sort) {
                            LibrarySort.ALPHABETICAL -> albums.sortedBy { it.title.lowercase() }
                            LibrarySort.RECENTLY_ADDED -> albums.sortedByDescending { it.dateAdded }
                        }
                        LibraryUiState(
                            selectedFilter = filter,
                            selectedSort = sort,
                            albums = sorted,
                        )
                    }

                    LibraryFilter.ARTISTS -> libraryRepository.observeAllArtists().map { artists ->
                        LibraryUiState(
                            selectedFilter = filter,
                            selectedSort = sort,
                            artists = artists,
                        )
                    }

                    LibraryFilter.SONGS -> libraryRepository.observeAllTracks().map { tracks ->
                        LibraryUiState(
                            selectedFilter = filter,
                            selectedSort = sort,
                            tracks = sortTracks(tracks, sort),
                        )
                    }
                }
            }
            .flowOn(Dispatchers.Default)

    val uiState: StateFlow<LibraryUiState> = combine(
        activeCollectionState,
        selectedPlaylistFolder,
        activeTrackId,
        offlineStateProvider.offlineStatus,
    ) { state, folder, activeId, offline ->
        val resolvedFolder = folder?.takeIf { target ->
            state.selectedFilter == LibraryFilter.PLAYLISTS &&
                state.playlistFolders.any { it.name.equals(target, ignoreCase = true) }
        }
        state.copy(
            selectedPlaylistFolder = resolvedFolder,
            activeTrackId = activeId,
            offlineStatus = offline,
        )
    }
        .distinctUntilChanged()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = LibraryUiState(),
        )

    fun onToggleOfflineMode() {
        val target = !offlineStateProvider.offlineStatus.value.manualOfflineMode
        if (target) {
            selectedFilter.value = LibraryFilter.DOWNLOADED
            selectedPlaylistFolder.value = null
        }
        viewModelScope.launch {
            offlineStateProvider.setManualOfflineMode(target)
        }
    }

    fun onDisableManualOfflineMode() {
        viewModelScope.launch {
            offlineStateProvider.setManualOfflineMode(false)
        }
    }

    fun onFilterSelected(filter: LibraryFilter) {
        if (filter != LibraryFilter.PLAYLISTS) {
            selectedPlaylistFolder.value = null
        }
        selectedFilter.value = filter
    }

    fun onOpenPlaylistFolder(folderName: String) {
        selectedPlaylistFolder.value = folderName
    }

    fun onClosePlaylistFolder() {
        selectedPlaylistFolder.value = null
    }

    fun onToggleSort() {
        selectedSort.value = when (selectedSort.value) {
            LibrarySort.ALPHABETICAL -> LibrarySort.RECENTLY_ADDED
            LibrarySort.RECENTLY_ADDED -> LibrarySort.ALPHABETICAL
        }
    }

    fun onPlayTracks(tracks: List<Track>, startIndex: Int) {
        val tapped = tracks.getOrNull(startIndex) ?: return
        val isOffline = uiState.value.isOffline
        if (isOffline && !tapped.isDownloaded) return
        val playable = if (isOffline) tracks.filter { it.isDownloaded } else tracks
        val resolvedIndex = playable.indexOfFirst { it.id == tapped.id }.coerceAtLeast(0)
        if (playable.isNotEmpty()) {
            playbackController.playTracks(tracks = playable, startIndex = resolvedIndex)
        }
    }

    fun onShuffleLibrary() {
        viewModelScope.launch {
            val state = uiState.value
            val useDownloadedOnly = state.isOffline || state.selectedFilter == LibraryFilter.DOWNLOADED
            val tracks = if (useDownloadedOnly) {
                libraryRepository.observeDownloadedTracks().first()
            } else {
                libraryRepository.observeAllTracks().first()
            }
            val playable = if (state.isOffline) tracks.filter { it.isDownloaded } else tracks
            if (playable.isNotEmpty()) {
                val contextLabel = if (useDownloadedOnly) "Downloaded" else "Your Library"
                playbackController.playTracks(
                    tracks = playable,
                    startIndex = 0,
                    shuffle = true,
                    contextLabel = contextLabel,
                )
            }
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

    fun onCreatePlaylist(name: String, folderName: String? = null) {
        viewModelScope.launch {
            playlistRepository.createPlaylist(name = name, folderName = folderName)
        }
    }

    fun onMovePlaylistToFolder(playlistId: String, folderName: String?) {
        viewModelScope.launch {
            playlistRepository.movePlaylistToFolder(playlistId, folderName)
        }
    }

    fun onRenameFolder(oldFolderName: String, newFolderName: String) {
        val normalizedNew = PlaylistFolderCodec.normalizeFolderName(newFolderName) ?: return
        if (selectedPlaylistFolder.value?.equals(oldFolderName, ignoreCase = true) == true) {
            selectedPlaylistFolder.value = normalizedNew
        }
        viewModelScope.launch {
            playlistRepository.renameFolder(oldFolderName, normalizedNew)
        }
    }

    fun onUngroupFolder(folderName: String) {
        if (selectedPlaylistFolder.value?.equals(folderName, ignoreCase = true) == true) {
            selectedPlaylistFolder.value = null
        }
        viewModelScope.launch {
            playlistRepository.renameFolder(folderName, null)
        }
    }

    private fun sortTracks(tracks: List<Track>, sort: LibrarySort): List<Track> = when (sort) {
        LibrarySort.ALPHABETICAL -> tracks.sortedBy { it.title.lowercase() }
        LibrarySort.RECENTLY_ADDED -> tracks.sortedByDescending { it.lastPlayedAt ?: 0L }
    }
}
