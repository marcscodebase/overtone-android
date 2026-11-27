package com.musicplayer.feature.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.musicplayer.core.datastore.UserPreferencesDataStore
import com.musicplayer.core.model.Album
import com.musicplayer.core.model.Artist
import com.musicplayer.core.model.Playlist
import com.musicplayer.core.model.PlaylistFolderCodec
import com.musicplayer.core.model.PlaylistFolderGroup
import com.musicplayer.core.network.OfflineStateProvider
import com.musicplayer.core.network.OfflineStatus
import com.musicplayer.data.repository.LibraryRepository
import com.musicplayer.data.repository.PlaylistRepository
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

data class LibraryUiState(
    val selectedFilter: LibraryFilter? = null,
    val selectedSort: LibrarySort = LibrarySort.ALPHABETICAL,
    val selectedPlaylistFolder: String? = null,
    val items: List<LibraryListItem> = emptyList(),
    val playlistFolders: List<PlaylistFolderGroup> = emptyList(),
    val offlineStatus: OfflineStatus = OfflineStatus(),
) {
    val isOffline: Boolean get() = offlineStatus.isOffline

    val activeFolderGroup: PlaylistFolderGroup?
        get() = selectedPlaylistFolder?.let { name ->
            playlistFolders.firstOrNull { it.name.equals(name, ignoreCase = true) }
        }
}

@HiltViewModel
class LibraryViewModel @Inject constructor(
    libraryRepository: LibraryRepository,
    private val playlistRepository: PlaylistRepository,
    private val preferencesDataStore: UserPreferencesDataStore,
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

    private val selectedFilter = MutableStateFlow<LibraryFilter?>(null)
    private val selectedSort = MutableStateFlow(LibrarySort.ALPHABETICAL)
    private val selectedPlaylistFolder = MutableStateFlow<String?>(null)

    private val pinnedKeysFlow = preferencesDataStore.userPreferences
        .map { it.pinnedLibraryKeys }
        .distinctUntilChanged()

    private val collectionCountsFlow = combine(
        libraryRepository.observeFavoriteTracks().map { it.size }.distinctUntilChanged(),
        libraryRepository.observeDownloadedTracks().map { tracks ->
            tracks.size to tracks.mapNotNull { it.albumId }.toSet()
        }.distinctUntilChanged(),
    ) { favCount, (dlCount, dlAlbumIds) ->
        Triple(favCount, dlCount, dlAlbumIds)
    }

    private val curatedSourcesFlow = combine(
        collectionCountsFlow,
        libraryRepository.observeAllPlaylists(),
        libraryRepository.observeAllAlbums(),
        libraryRepository.observeAllArtists(),
    ) { counts, playlists, albums, artists ->
        CuratedSources(
            favoriteTrackCount = counts.first,
            downloadedTrackCount = counts.second,
            downloadedAlbumIds = counts.third,
            playlists = playlists,
            albums = albums,
            artists = artists,
        )
    }

    private val controlsFlow = combine(
        selectedFilter,
        selectedSort,
        selectedPlaylistFolder,
        pinnedKeysFlow,
        offlineStateProvider.offlineStatus,
    ) { filter, sort, folder, pinnedKeys, offline ->
        LibraryControls(
            filter = filter,
            sort = sort,
            folder = folder,
            pinnedKeys = pinnedKeys,
            offlineStatus = offline,
        )
    }

    val uiState: StateFlow<LibraryUiState> = combine(
        curatedSourcesFlow,
        controlsFlow,
    ) { sources, controls ->
        val (folders, rootPlaylists) = PlaylistFolderCodec.groupPlaylists(sources.playlists)
        val builtItems = LibraryListBuilder.build(
            favoriteTrackCount = sources.favoriteTrackCount,
            downloadedTrackCount = sources.downloadedTrackCount,
            downloadedAlbumIds = sources.downloadedAlbumIds,
            playlistFolders = folders,
            rootPlaylists = rootPlaylists,
            allAlbums = sources.albums,
            allArtists = sources.artists,
            activeFilter = controls.filter,
            sort = controls.sort,
            pinnedKeys = controls.pinnedKeys,
        )
        val resolvedFolder = controls.folder?.takeIf { target ->
            (controls.filter == null || controls.filter == LibraryFilter.PLAYLISTS) &&
                folders.any { it.name.equals(target, ignoreCase = true) }
        }
        LibraryUiState(
            selectedFilter = controls.filter,
            selectedSort = controls.sort,
            selectedPlaylistFolder = resolvedFolder,
            items = builtItems,
            playlistFolders = folders,
            offlineStatus = controls.offlineStatus,
        )
    }
        .flowOn(Dispatchers.Default)
        .distinctUntilChanged()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = LibraryUiState(),
        )

    private data class CuratedSources(
        val favoriteTrackCount: Int,
        val downloadedTrackCount: Int,
        val downloadedAlbumIds: Set<String>,
        val playlists: List<Playlist>,
        val albums: List<Album>,
        val artists: List<Artist>,
    )

    private data class LibraryControls(
        val filter: LibraryFilter?,
        val sort: LibrarySort,
        val folder: String?,
        val pinnedKeys: List<String>,
        val offlineStatus: OfflineStatus,
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

    /**
     * Spotify-style toggleable filter chip: tapping an active filter clears it back to the
     * unified list. Passing `forceSelect = true` (e.g., from Home -> Show all playlists) always
     * selects the chip cleanly without toggling it off.
     */
    fun onFilterSelected(filter: LibraryFilter?, forceSelect: Boolean = false) {
        val next = if (!forceSelect && filter != null && selectedFilter.value == filter) {
            null
        } else {
            filter
        }
        if (next != null && next != LibraryFilter.PLAYLISTS) {
            selectedPlaylistFolder.value = null
        }
        selectedFilter.value = next
    }

    fun onClearFilter() {
        selectedFilter.value = null
        selectedPlaylistFolder.value = null
    }

    fun onSortSelected(sort: LibrarySort) {
        selectedSort.value = sort
    }

    fun onTogglePin(pinKey: String) {
        viewModelScope.launch {
            preferencesDataStore.toggleLibraryPin(pinKey)
        }
    }

    fun onOpenPlaylistFolder(folderName: String) {
        selectedPlaylistFolder.value = folderName
    }

    fun onClosePlaylistFolder() {
        selectedPlaylistFolder.value = null
    }

    fun onShuffleLibrary() {
        val state = uiState.value
        val useDownloadedOnly = state.isOffline || state.selectedFilter == LibraryFilter.DOWNLOADED
        playbackController.playLibraryShuffle(downloadedOnly = useDownloadedOnly)
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
}
