package com.musicplayer.feature.home

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.musicplayer.core.model.InstantMixSeedKind
import com.musicplayer.core.model.Track
import com.musicplayer.core.network.OfflineStateProvider
import com.musicplayer.core.network.OfflineStatus
import com.musicplayer.data.repository.LibraryRepository
import com.musicplayer.navigation.SmartMixDestination
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

data class SmartMixUiState(
    val mixType: SmartMixType = SmartMixType.ON_REPEAT,
    val title: String = "",
    val subtitle: String = "",
    val tracks: List<Track> = emptyList(),
    val artworkUrls: List<String> = emptyList(),
    val fromJellyfinServer: Boolean = false,
    val isLoadingRemote: Boolean = false,
    val canRefresh: Boolean = false,
    val activeTrackId: String? = null,
    val offlineStatus: OfflineStatus = OfflineStatus(),
) {
    val isOffline: Boolean get() = offlineStatus.isOffline
    val playableTracks: List<Track>
        get() = if (isOffline) tracks.filter { it.isDownloaded } else tracks
}

@HiltViewModel
class SmartMixViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val libraryRepository: LibraryRepository,
    private val playbackController: PlaybackController,
    private val offlineStateProvider: OfflineStateProvider,
) : ViewModel() {

    private val destination = savedStateHandle.toRoute<SmartMixDestination>()
    private val mixType = SmartMixType.fromRouteKey(destination.mixType)
    private val seedId = destination.seedId
    private val seedName = destination.seedName

    private val refreshNonce = MutableStateFlow(0L)
    private val remoteTracks = MutableStateFlow<List<Track>?>(null)
    private val remoteFromServer = MutableStateFlow(false)
    private val isLoadingRemote = MutableStateFlow(false)

    private val supportsJellyfinInstantMix: Boolean =
        mixType == SmartMixType.JELLYFIN_INSTANT_MIX || mixType == SmartMixType.GENRE_MIX

    private val supportsManualRefresh: Boolean =
        mixType != SmartMixType.ON_REPEAT

    private val remoteBundle = combine(
        remoteTracks,
        remoteFromServer,
        isLoadingRemote,
    ) { tracks, fromServer, loading -> Triple(tracks, fromServer, loading) }

    val uiState: StateFlow<SmartMixUiState> = combine(
        libraryRepository.observeAllTracks(),
        libraryRepository.observeAllArtists(),
        refreshNonce,
        remoteBundle,
        combine(
            playbackController.playbackState.map { it.currentTrack?.id }.distinctUntilChanged(),
            offlineStateProvider.offlineStatus,
        ) { activeId, offline -> activeId to offline },
    ) { allTracks, allArtists, nonce, (fetchedTracks, fromServer, loading), (activeId, offline) ->
        val pool = if (offline.isOffline) {
            allTracks.filter { it.isDownloaded }.ifEmpty { allTracks }
        } else {
            allTracks
        }
        val lovedArtistIds = buildSet {
            allArtists.filter { it.isFavorite }.forEach { add(it.id) }
            allTracks.filter { it.isFavorite || it.playCount >= 3 }
                .mapNotNull { it.artistId }
                .forEach { add(it) }
        }

        val localTracks = when (mixType) {
            SmartMixType.ON_REPEAT -> HomeDiscoveryEngine.buildOnRepeatTracks(pool)
            SmartMixType.TIME_CAPSULE -> HomeDiscoveryEngine.buildTimeCapsuleTracks(
                tracks = pool,
                refreshNonce = nonce,
            )
            SmartMixType.DEEP_CUTS -> HomeDiscoveryEngine.buildDeepCutsTracks(
                tracks = pool,
                lovedArtistIds = lovedArtistIds,
                refreshNonce = nonce,
            )
            SmartMixType.GENRE_MIX -> HomeDiscoveryEngine.buildGenreTracksLocal(
                tracks = pool,
                genreName = seedName.orEmpty(),
                seedSalt = "genre_$nonce",
            )
            SmartMixType.DECADE_MIX -> HomeDiscoveryEngine.buildDecadeTracks(
                tracks = pool,
                decadeStartYear = seedId?.toIntOrNull() ?: 2010,
                seedSalt = "decade_$nonce",
            )
            SmartMixType.JELLYFIN_INSTANT_MIX,
            SmartMixType.DAYLIST,
            -> {
                val targetId = seedId
                if (!targetId.isNullOrBlank()) {
                    HomeDiscoveryEngine.buildHomeFeed(
                        allTracks = pool,
                        allAlbums = emptyList(),
                        allArtists = allArtists,
                        playlists = emptyList(),
                        pinnedKeys = emptyList(),
                        slot = DaylistSlot.current(),
                        daylistRefreshNonce = nonce,
                        isOffline = offline.isOffline,
                    ).smartMixes.firstOrNull { it.type == SmartMixType.JELLYFIN_INSTANT_MIX }?.tracks
                        ?: pool.shuffled().take(40)
                } else {
                    pool.shuffled().take(40)
                }
            }
        }

        val useRemote = !offline.isOffline && !fetchedTracks.isNullOrEmpty()
        val effectiveTracks = if (useRemote) fetchedTracks else localTracks

        val resolvedTitle = when (mixType) {
            SmartMixType.ON_REPEAT -> "On Repeat"
            SmartMixType.TIME_CAPSULE -> "Time Capsule"
            SmartMixType.DEEP_CUTS -> "Deep Cuts"
            SmartMixType.JELLYFIN_INSTANT_MIX ->
                seedName?.takeIf { it.isNotBlank() }?.let { "$it Mix" } ?: "Instant Mix"
            SmartMixType.GENRE_MIX ->
                seedName?.takeIf { it.isNotBlank() }?.let { "$it Mix" } ?: "Genre Mix"
            SmartMixType.DECADE_MIX ->
                seedName?.takeIf { it.isNotBlank() }?.let { "$it Mix" } ?: "Decade Mix"
            SmartMixType.DAYLIST -> "Daylist"
        }

        val resolvedSubtitle = when (mixType) {
            SmartMixType.ON_REPEAT -> "Songs you've had on heavy rotation"
            SmartMixType.TIME_CAPSULE -> "Favorites & played tracks you haven't heard in a while"
            SmartMixType.DEEP_CUTS -> "Unplayed cuts waiting in your catalog"
            SmartMixType.JELLYFIN_INSTANT_MIX ->
                if (useRemote && fromServer) {
                    "Generated live by Jellyfin Instant Mix"
                } else {
                    "Smart mix from your local library"
                }
            SmartMixType.GENRE_MIX ->
                if (useRemote && fromServer) {
                    "Jellyfin genre radio • ${seedName.orEmpty()}"
                } else {
                    "Local library mix • ${seedName.orEmpty()}"
                }
            SmartMixType.DECADE_MIX -> "Curated tracks from the ${seedName.orEmpty()}"
            SmartMixType.DAYLIST -> "Mixed for right now"
        }

        SmartMixUiState(
            mixType = mixType,
            title = resolvedTitle,
            subtitle = resolvedSubtitle,
            tracks = effectiveTracks,
            artworkUrls = HomeDiscoveryEngine.extractMosaic(effectiveTracks),
            fromJellyfinServer = useRemote && fromServer,
            isLoadingRemote = loading,
            canRefresh = supportsManualRefresh,
            activeTrackId = activeId,
            offlineStatus = offline,
        )
    }
        .flowOn(Dispatchers.Default)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = SmartMixUiState(mixType = mixType, canRefresh = supportsManualRefresh),
        )

    init {
        if (supportsJellyfinInstantMix) {
            fetchJellyfinMix()
        }
    }

    fun onRefresh() {
        refreshNonce.value += 1L
        if (supportsJellyfinInstantMix) {
            fetchJellyfinMix()
        }
    }

    private fun fetchJellyfinMix() {
        if (offlineStateProvider.offlineStatus.value.isOffline) return
        viewModelScope.launch {
            isLoadingRemote.value = true
            val result = when (mixType) {
                SmartMixType.GENRE_MIX -> libraryRepository.getInstantMix(
                    seedKind = InstantMixSeedKind.GENRE,
                    genreName = seedName,
                    limit = 50,
                )

                else -> libraryRepository.getInstantMix(
                    seedKind = InstantMixSeedKind.ARTIST,
                    seedId = seedId,
                    limit = 50,
                )
            }
            if (result.tracks.isNotEmpty()) {
                remoteTracks.value = result.tracks
                remoteFromServer.value = result.fromJellyfinServer
            }
            isLoadingRemote.value = false
        }
    }

    fun onPlayAll() {
        val state = uiState.value
        val playable = state.playableTracks
        if (playable.isNotEmpty()) {
            playbackController.playTracks(
                tracks = playable,
                startIndex = 0,
                shuffle = false,
                contextLabel = state.title.ifBlank { "Smart Mix" },
            )
        }
    }

    fun onShuffleAll() {
        val state = uiState.value
        val playable = state.playableTracks
        if (playable.isNotEmpty()) {
            playbackController.playTracks(
                tracks = playable,
                startIndex = 0,
                shuffle = true,
                contextLabel = state.title.ifBlank { "Smart Mix" },
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
                contextLabel = state.title.ifBlank { "Smart Mix" },
            )
        }
    }

    fun onAddToQueue(track: Track) = playbackController.addToQueue(track)
}
