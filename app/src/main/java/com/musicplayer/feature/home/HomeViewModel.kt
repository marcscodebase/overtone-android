package com.musicplayer.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.musicplayer.core.common.AppResult
import com.musicplayer.core.datastore.UserPreferencesDataStore
import com.musicplayer.core.model.Album
import com.musicplayer.core.model.Artist
import com.musicplayer.core.model.Playlist
import com.musicplayer.core.model.Track
import com.musicplayer.core.network.OfflineStateProvider
import com.musicplayer.core.network.OfflineStatus
import com.musicplayer.data.repository.DownloadRepository
import com.musicplayer.data.repository.LibraryRepository
import com.musicplayer.data.repository.SyncRepository
import com.musicplayer.feature.library.LibraryCollectionSection
import com.musicplayer.player.PlaybackController
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class HomeUiState(
    val quickAccessTiles: List<QuickAccessTile> = emptyList(),
    val daylist: Daylist? = null,
    val smartMixes: List<SmartMixPreview> = emptyList(),
    val spotlight: AlbumSpotlight? = null,
    val hasMultipleSpotlights: Boolean = false,
    val exploreTags: List<ExploreTag> = emptyList(),
    val recentlyAddedAlbums: List<Album> = emptyList(),
    val moreFromArtistsYouLove: List<Album> = emptyList(),
    val rediscoverAlbums: List<RediscoverAlbumItem> = emptyList(),
    val unplayedGems: List<Album> = emptyList(),
    val playlists: List<Playlist> = emptyList(),
    val recentlyPlayedTracks: List<Track> = emptyList(),
    val activeTrackId: String? = null,
    val offlineStatus: OfflineStatus = OfflineStatus(),
    val isRefreshing: Boolean = false,
    val errorMessage: String? = null,
) {
    val isOffline: Boolean get() = offlineStatus.isOffline
    val isEmpty: Boolean
        get() = quickAccessTiles.isEmpty() &&
            smartMixes.isEmpty() &&
            spotlight == null &&
            recentlyAddedAlbums.isEmpty() &&
            moreFromArtistsYouLove.isEmpty() &&
            rediscoverAlbums.isEmpty() &&
            unplayedGems.isEmpty() &&
            playlists.isEmpty() &&
            recentlyPlayedTracks.isEmpty()
}

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val libraryRepository: LibraryRepository,
    private val downloadRepository: DownloadRepository,
    private val syncRepository: SyncRepository,
    private val preferencesDataStore: UserPreferencesDataStore,
    private val playbackController: PlaybackController,
    private val offlineStateProvider: OfflineStateProvider,
) : ViewModel() {

    private val isRefreshing = MutableStateFlow(false)
    private val errorMessage = MutableStateFlow<String?>(null)
    private val daylistSlot = MutableStateFlow(DaylistSlot.current())
    private val spotlightOffset = MutableStateFlow(0)

    private data class RemoteMixCache(
        val seedId: String? = null,
        val tracks: List<Track> = emptyList(),
        val fromServer: Boolean = false,
    )

    private val remoteInstantMix = MutableStateFlow(RemoteMixCache())
    private var lastFetchedSeedId: String? = null

    private val activeTrackId = playbackController.playbackState
        .map { it.currentTrack?.id }
        .distinctUntilChanged()

    private val prefsBundle = preferencesDataStore.userPreferences
        .map { prefs ->
            HomePrefsBundle(
                daylistSlotKey = prefs.daylistRefreshSlotKey,
                daylistNonce = prefs.daylistRefreshNonce,
                pinnedKeys = prefs.pinnedLibraryKeys,
            )
        }
        .distinctUntilChanged()

    private val libraryBundle = combine(
        libraryRepository.observeAllTracks(),
        libraryRepository.observeAllAlbums(),
        libraryRepository.observeAllArtists(),
        libraryRepository.observeAllPlaylists(),
    ) { tracks, albums, artists, playlists ->
        HomeLibraryBundle(tracks, albums, artists, playlists)
    }

    private val statusBundle = combine(
        activeTrackId,
        offlineStateProvider.offlineStatus,
        isRefreshing,
        errorMessage,
    ) { activeId, offline, refreshing, error ->
        HomeStatusBundle(activeId, offline, refreshing, error)
    }

    private val controlsBundle = combine(
        daylistSlot,
        prefsBundle,
        spotlightOffset,
        remoteInstantMix,
    ) { slot, prefs, offset, remoteMix ->
        HomeControlsBundle(slot, prefs, offset, remoteMix)
    }

    val uiState: StateFlow<HomeUiState> = combine(
        libraryBundle,
        controlsBundle,
        statusBundle,
    ) { library, controls, status ->
        val effectiveNonce = if (controls.prefs.daylistSlotKey == controls.slot.slotKey) {
            controls.prefs.daylistNonce
        } else {
            0L
        }
        val feed = HomeDiscoveryEngine.buildHomeFeed(
            allTracks = library.tracks,
            allAlbums = library.albums,
            allArtists = library.artists,
            playlists = library.playlists,
            pinnedKeys = controls.prefs.pinnedKeys,
            slot = controls.slot,
            daylistRefreshNonce = effectiveNonce,
            isOffline = status.offlineStatus.isOffline,
            jellyfinInstantMixTracks = controls.remoteMix.tracks,
            jellyfinInstantMixFromServer = controls.remoteMix.fromServer,
        )

        val seed = feed.instantMixSeed
        if (seed != null && !status.offlineStatus.isOffline && seed.id != lastFetchedSeedId) {
            lastFetchedSeedId = seed.id
            fetchRemoteInstantMix(seed)
        }

        val candidates = feed.spotlightCandidates
        val currentSpotlight = if (candidates.isNotEmpty()) {
            candidates[controls.spotlightOffset.mod(candidates.size)]
        } else {
            null
        }

        HomeUiState(
            quickAccessTiles = feed.quickAccessTiles,
            daylist = feed.daylist,
            smartMixes = feed.smartMixes,
            spotlight = currentSpotlight,
            hasMultipleSpotlights = candidates.size > 1,
            exploreTags = feed.exploreTags,
            recentlyAddedAlbums = feed.recentlyAddedAlbums,
            moreFromArtistsYouLove = feed.moreFromArtistsYouLove,
            rediscoverAlbums = feed.rediscoverAlbums,
            unplayedGems = feed.unplayedGems,
            playlists = feed.playlists,
            recentlyPlayedTracks = feed.recentlyPlayedTracks,
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

    private data class HomePrefsBundle(
        val daylistSlotKey: String,
        val daylistNonce: Long,
        val pinnedKeys: List<String>,
    )

    private data class HomeLibraryBundle(
        val tracks: List<Track>,
        val albums: List<Album>,
        val artists: List<Artist>,
        val playlists: List<Playlist>,
    )

    private data class HomeControlsBundle(
        val slot: DaylistSlot,
        val prefs: HomePrefsBundle,
        val spotlightOffset: Int,
        val remoteMix: RemoteMixCache,
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

    private fun fetchRemoteInstantMix(seed: InstantMixSeed) {
        viewModelScope.launch {
            val result = libraryRepository.getInstantMix(
                seedKind = seed.kind,
                seedId = seed.id,
                limit = 40,
            )
            if (result.tracks.isNotEmpty() && result.fromJellyfinServer) {
                remoteInstantMix.value = RemoteMixCache(
                    seedId = seed.id,
                    tracks = result.tracks,
                    fromServer = true,
                )
            }
        }
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
            lastFetchedSeedId = null
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

    fun onNextSpotlight() {
        spotlightOffset.value += 1
    }

    fun onPlayQuickAccess(tile: QuickAccessTile) {
        val isOffline = uiState.value.isOffline
        viewModelScope.launch {
            val rawTracks: List<Track> = when (val target = tile.target) {
                is QuickAccessTarget.AlbumTarget ->
                    libraryRepository.observeAlbumTracks(target.albumId).first()
                is QuickAccessTarget.PlaylistTarget -> {
                    val cached = libraryRepository.observePlaylistTracks(target.playlistId).first()
                    if (cached.isNotEmpty() || isOffline) {
                        cached
                    } else {
                        libraryRepository.syncPlaylistTracks(target.playlistId)
                        libraryRepository.observePlaylistTracks(target.playlistId).first()
                    }
                }
                is QuickAccessTarget.ArtistTarget ->
                    libraryRepository.observeArtistTracks(target.artistId).first()
                is QuickAccessTarget.CollectionTarget -> when (target.section) {
                    LibraryCollectionSection.FAVORITES ->
                        libraryRepository.observeFavoriteTracks().first()
                    LibraryCollectionSection.DOWNLOADED ->
                        libraryRepository.observeDownloadedTracks().first()
                }
            }
            val playable = if (isOffline) rawTracks.filter { it.isDownloaded } else rawTracks
            if (playable.isNotEmpty()) {
                playbackController.playTracks(
                    tracks = playable,
                    startIndex = 0,
                    shuffle = false,
                    contextLabel = tile.title,
                )
            }
        }
    }

    fun onPlaySmartMix(mix: SmartMixPreview) {
        val isOffline = uiState.value.isOffline
        val playable = if (isOffline) mix.tracks.filter { it.isDownloaded } else mix.tracks
        if (playable.isNotEmpty()) {
            playbackController.playTracks(
                tracks = playable,
                startIndex = 0,
                shuffle = false,
                contextLabel = mix.title,
            )
        }
    }

    fun onPlaySpotlightAlbum(album: Album) {
        val isOffline = uiState.value.isOffline
        viewModelScope.launch {
            val tracks = libraryRepository.observeAlbumTracks(album.id).first()
            val playable = if (isOffline) tracks.filter { it.isDownloaded } else tracks
            if (playable.isNotEmpty()) {
                playbackController.playTracks(
                    tracks = playable,
                    startIndex = 0,
                    shuffle = false,
                    contextLabel = album.title,
                )
            }
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

    fun onAddToQueue(track: Track) = playbackController.addToQueue(track)

    fun onToggleFavorite(track: Track) {
        val target = !track.isFavorite
        playbackController.updateTrackFavorite(track.id, target)
        viewModelScope.launch {
            syncRepository.setTrackFavorite(track.id, target)
        }
    }
}
