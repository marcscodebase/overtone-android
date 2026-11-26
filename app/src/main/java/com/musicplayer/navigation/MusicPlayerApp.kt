package com.musicplayer.navigation

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.core.content.ContextCompat
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.musicplayer.core.designsystem.component.LocalOnSaveTrack
import com.musicplayer.core.designsystem.component.LocalSavedTrackIds
import com.musicplayer.core.model.Track
import com.musicplayer.feature.auth.AuthRoute
import com.musicplayer.feature.download.DownloadActionsViewModel
import com.musicplayer.feature.download.DownloadQualitySheet
import com.musicplayer.feature.library.detail.AlbumDetailRoute
import com.musicplayer.feature.library.detail.ArtistCollectionRoute
import com.musicplayer.feature.library.detail.ArtistDetailRoute
import com.musicplayer.feature.library.detail.PlaylistDetailRoute
import com.musicplayer.feature.library.playlist.AddToPlaylistSheet
import com.musicplayer.feature.library.playlist.PlaylistPickerViewModel
import com.musicplayer.feature.player.AudioOutputSheet
import com.musicplayer.feature.player.LyricsSheet
import com.musicplayer.feature.player.MiniPlayer
import com.musicplayer.feature.player.NowPlayingSheet
import com.musicplayer.feature.player.PlayerViewModel
import com.musicplayer.feature.player.QueueSheet
import com.musicplayer.feature.player.TrackNavigationSheet
import com.musicplayer.feature.search.SearchRoute
import com.musicplayer.feature.settings.SettingsRoute
import com.musicplayer.feature.settings.StorageManagerRoute

private data class PendingTrackNavigation(
    val track: Track,
    val showAlbumOption: Boolean,
)

@Composable
fun MusicPlayerApp(
    sessionViewModel: AppSessionViewModel = hiltViewModel(),
) {
    val sessionState by sessionViewModel.sessionState.collectAsStateWithLifecycle()

    Surface(modifier = Modifier.fillMaxSize()) {
        when (sessionState) {
            AppSessionState.Loading -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            }

            AppSessionState.Unauthenticated -> {
                AuthRoute(onAuthComplete = {})
            }

            is AppSessionState.Authenticated -> {
                AuthenticatedShell()
            }
        }
    }
}

@Composable
private fun AuthenticatedShell(
    playerViewModel: PlayerViewModel = hiltViewModel(),
    playlistPickerViewModel: PlaylistPickerViewModel = hiltViewModel(),
    downloadActionsViewModel: DownloadActionsViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination
    val playbackState by playerViewModel.playbackState.collectAsStateWithLifecycle()
    val recentlyPlayedTracks by playerViewModel.recentlyPlayedTracks.collectAsStateWithLifecycle()
    val lyricsState by playerViewModel.lyricsState.collectAsStateWithLifecycle()
    val audioOutputState by playerViewModel.audioOutputState.collectAsStateWithLifecycle()
    val hiResBluetoothDevices by playerViewModel.hiResBluetoothDevices.collectAsStateWithLifecycle()
    val isOffline by playerViewModel.isOffline.collectAsStateWithLifecycle()
    val allPlaylists by playlistPickerViewModel.playlists.collectAsStateWithLifecycle()
    val trackIdsInPlaylists by playlistPickerViewModel.trackIdsInAnyPlaylist.collectAsStateWithLifecycle()
    val downloadRecords by downloadActionsViewModel.downloadRecords.collectAsStateWithLifecycle()
    val downloadPrefs by downloadActionsViewModel.preferences.collectAsStateWithLifecycle()
    val pendingDownload by downloadActionsViewModel.pendingRequest.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var showNowPlaying by rememberSaveable { mutableStateOf(false) }
    var showQueueSheet by rememberSaveable { mutableStateOf(false) }
    var showLyricsSheet by rememberSaveable { mutableStateOf(false) }
    var showAudioOutputSheet by rememberSaveable { mutableStateOf(false) }
    var pendingTrackNav by remember { mutableStateOf<PendingTrackNavigation?>(null) }
    var trackForPlaylistPicker by remember { mutableStateOf<Track?>(null) }
    var playlistPickerInitialFavorite by remember { mutableStateOf(false) }

    val audioReadPermission = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
    }
    var hasRequestedAudioPermission by rememberSaveable { mutableStateOf(false) }
    val audioPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            downloadActionsViewModel.onReconcileDownloads()
        }
    }

    LaunchedEffect(audioReadPermission) {
        val alreadyGranted = ContextCompat.checkSelfPermission(
            context,
            audioReadPermission,
        ) == PackageManager.PERMISSION_GRANTED
        if (alreadyGranted) {
            downloadActionsViewModel.onReconcileDownloads()
        } else if (!hasRequestedAudioPermission) {
            hasRequestedAudioPermission = true
            audioPermissionLauncher.launch(audioReadPermission)
        }
    }

    LaunchedEffect(playerViewModel) {
        playerViewModel.feedbackMessages.collect { message ->
            snackbarHostState.currentSnackbarData?.dismiss()
            snackbarHostState.showSnackbar(
                message = message,
                duration = SnackbarDuration.Short,
            )
        }
    }

    LaunchedEffect(downloadActionsViewModel) {
        downloadActionsViewModel.feedbackMessages.collect { message ->
            snackbarHostState.currentSnackbarData?.dismiss()
            snackbarHostState.showSnackbar(
                message = message,
                duration = SnackbarDuration.Short,
            )
        }
    }

    LaunchedEffect(playlistPickerViewModel) {
        playlistPickerViewModel.feedbackMessages.collect { message ->
            snackbarHostState.currentSnackbarData?.dismiss()
            snackbarHostState.showSnackbar(
                message = message,
                duration = SnackbarDuration.Short,
            )
        }
    }

    val navigateToAlbum: (String) -> Unit = { albumId ->
        showNowPlaying = false
        pendingTrackNav = null
        navController.navigate(AlbumDetailDestination(albumId))
    }

    val navigateToArtist: (String) -> Unit = { artistId ->
        showNowPlaying = false
        pendingTrackNav = null
        navController.navigate(ArtistDetailDestination(artistId))
    }

    val onGoToTrackArtist: (Track) -> Unit = { track ->
        val artists = track.resolvedArtists
        when (artists.size) {
            0 -> Unit
            1 -> navigateToArtist(artists.first().id)
            else -> pendingTrackNav = PendingTrackNavigation(track = track, showAlbumOption = false)
        }
    }

    val activeTrack = playbackState.currentTrack
    val isActiveTrackSaved = activeTrack != null &&
        (activeTrack.isFavorite || activeTrack.id in trackIdsInPlaylists)

    val onSaveTrack: (Track) -> Unit = { track ->
        if (!track.isFavorite) {
            playlistPickerViewModel.autoFavoriteTrack(track)
            playlistPickerInitialFavorite = true
            trackForPlaylistPicker = track.copy(isFavorite = true)
        } else {
            playlistPickerInitialFavorite = true
            trackForPlaylistPicker = track
        }
    }

    val onSaveCurrentTrack: () -> Unit = {
        playbackState.currentTrack?.let(onSaveTrack)
    }

    val showMiniPlayer = activeTrack != null &&
        currentDestination?.hasRoute(SettingsDestination::class) != true &&
        currentDestination?.hasRoute(StorageManagerDestination::class) != true

    CompositionLocalProvider(
        LocalSavedTrackIds provides trackIdsInPlaylists,
        LocalOnSaveTrack provides onSaveTrack,
    ) {
        Scaffold(
        modifier = Modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        bottomBar = {
            if (showMiniPlayer) {
                Box(
                    modifier = Modifier
                        .navigationBarsPadding()
                        .padding(bottom = 4.dp),
                ) {
                    MiniPlayer(
                        state = playbackState,
                        isTrackSaved = isActiveTrackSaved,
                        onExpand = { showNowPlaying = true },
                        onTogglePlayPause = playerViewModel::onTogglePlayPause,
                        onSaveOrAddToPlaylist = onSaveCurrentTrack,
                        onSkipNext = playerViewModel::onSkipNext,
                        onSkipPrevious = playerViewModel::onSkipPrevious,
                    )
                }
            } else {
                Box(modifier = Modifier.navigationBarsPadding())
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = MainDestination,
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = innerPadding.calculateBottomPadding()),
        ) {
            composable<MainDestination> {
                MainPagerRoute(
                    onSearchClick = { navController.navigate(SearchDestination) },
                    onAlbumClick = navigateToAlbum,
                    onArtistClick = navigateToArtist,
                    onGoToTrackArtist = onGoToTrackArtist,
                    onPlaylistClick = { playlistId -> navController.navigate(PlaylistDetailDestination(playlistId)) },
                    onAddToPlaylist = { track ->
                        playlistPickerInitialFavorite = track.isFavorite
                        trackForPlaylistPicker = track
                    },
                    onToggleTrackDownload = downloadActionsViewModel::onToggleTrackDownload,
                    onSettingsClick = { navController.navigate(SettingsDestination) },
                )
            }

            composable<SearchDestination> {
                SearchRoute(
                    onBackClick = { navController.popBackStack() },
                    onAlbumClick = navigateToAlbum,
                    onArtistClick = navigateToArtist,
                    onGoToTrackArtist = onGoToTrackArtist,
                    onPlaylistClick = { playlistId -> navController.navigate(PlaylistDetailDestination(playlistId)) },
                    onAddToPlaylist = { track ->
                        playlistPickerInitialFavorite = track.isFavorite
                        trackForPlaylistPicker = track
                    },
                    onToggleTrackDownload = downloadActionsViewModel::onToggleTrackDownload,
                )
            }

            composable<AlbumDetailDestination> {
                AlbumDetailRoute(
                    onBackClick = { navController.popBackStack() },
                    onArtistClick = navigateToArtist,
                    onGoToTrackArtist = onGoToTrackArtist,
                    onAddToPlaylist = { track ->
                        playlistPickerInitialFavorite = track.isFavorite
                        trackForPlaylistPicker = track
                    },
                    onToggleTrackDownload = downloadActionsViewModel::onToggleTrackDownload,
                    onDownloadAlbum = downloadActionsViewModel::onDownloadAlbum,
                    onRemoveAlbumDownload = downloadActionsViewModel::onRemoveAlbumDownload,
                )
            }

            composable<ArtistDetailDestination> {
                ArtistDetailRoute(
                    onBackClick = { navController.popBackStack() },
                    onAlbumClick = navigateToAlbum,
                    onOpenArtistCollection = { artistId, section ->
                        navController.navigate(
                            ArtistCollectionDestination(
                                artistId = artistId,
                                section = section.routeKey,
                            ),
                        )
                    },
                    onGoToTrackArtist = onGoToTrackArtist,
                    onAddToPlaylist = { track ->
                        playlistPickerInitialFavorite = track.isFavorite
                        trackForPlaylistPicker = track
                    },
                    onToggleTrackDownload = downloadActionsViewModel::onToggleTrackDownload,
                )
            }

            composable<ArtistCollectionDestination> {
                ArtistCollectionRoute(
                    onBackClick = { navController.popBackStack() },
                    onAlbumClick = navigateToAlbum,
                    onGoToTrackArtist = onGoToTrackArtist,
                    onAddToPlaylist = { track ->
                        playlistPickerInitialFavorite = track.isFavorite
                        trackForPlaylistPicker = track
                    },
                    onToggleTrackDownload = downloadActionsViewModel::onToggleTrackDownload,
                )
            }

            composable<PlaylistDetailDestination> {
                PlaylistDetailRoute(
                    onBackClick = { navController.popBackStack() },
                    onAlbumClick = navigateToAlbum,
                    onGoToTrackArtist = onGoToTrackArtist,
                    onAddToPlaylist = { track ->
                        playlistPickerInitialFavorite = track.isFavorite
                        trackForPlaylistPicker = track
                    },
                    onToggleTrackDownload = downloadActionsViewModel::onToggleTrackDownload,
                    onDownloadPlaylist = downloadActionsViewModel::onDownloadPlaylist,
                    onRemovePlaylistDownload = downloadActionsViewModel::onRemovePlaylistDownload,
                )
            }

            composable<SettingsDestination> {
                SettingsRoute(
                    onBackClick = { navController.popBackStack() },
                    onOpenStorageManager = { navController.navigate(StorageManagerDestination) },
                )
            }

            composable<StorageManagerDestination> {
                StorageManagerRoute(
                    onBackClick = { navController.popBackStack() },
                )
            }
        }
    }

    if (showNowPlaying && activeTrack != null) {
        NowPlayingSheet(
            state = playbackState,
            lyricsState = lyricsState,
            audioOutputState = audioOutputState,
            hiResBluetoothDevices = hiResBluetoothDevices,
            downloadRecord = downloadRecords[activeTrack.id],
            isTrackSaved = isActiveTrackSaved,
            isOffline = isOffline,
            onDismiss = { showNowPlaying = false },
            onPlayingFromAlbumClick = navigateToAlbum,
            onTrackTitleOrArtistClick = {
                pendingTrackNav = PendingTrackNavigation(
                    track = activeTrack,
                    showAlbumOption = true,
                )
            },
            onTogglePlayPause = playerViewModel::onTogglePlayPause,
            onSaveOrAddToPlaylist = onSaveCurrentTrack,
            onToggleDownload = { downloadActionsViewModel.onToggleTrackDownload(activeTrack) },
            onSelectStreamQuality = playerViewModel::onSelectStreamQuality,
            onSkipNext = playerViewModel::onSkipNext,
            onSkipPrevious = playerViewModel::onSkipPrevious,
            onSeekTo = playerViewModel::onSeekTo,
            onToggleShuffle = playerViewModel::onToggleShuffle,
            onCycleRepeatMode = playerViewModel::onCycleRepeatMode,
            onOpenAudioOutput = {
                playerViewModel.onRefreshAudioOutputs()
                showAudioOutputSheet = true
            },
            onOpenQueue = { showQueueSheet = true },
            onOpenLyrics = { showLyricsSheet = true },
        )
    }

    if (showAudioOutputSheet) {
        AudioOutputSheet(
            outputState = audioOutputState,
            hiResBluetoothDevices = hiResBluetoothDevices,
            onToggleBluetoothHiRes = playerViewModel::onSetBluetoothDeviceHiRes,
            onOpenSystemOutputSwitcher = { playerViewModel.onOpenSystemOutputSwitcher(context) },
            onDismiss = { showAudioOutputSheet = false },
        )
    }

    pendingTrackNav?.let { navRequest ->
        TrackNavigationSheet(
            track = navRequest.track,
            showAlbumOption = navRequest.showAlbumOption,
            onDismiss = { pendingTrackNav = null },
            onGoToAlbum = navigateToAlbum,
            onGoToArtist = navigateToArtist,
        )
    }

    if (showLyricsSheet && activeTrack != null) {
        LyricsSheet(
            playbackState = playbackState,
            lyricsState = lyricsState,
            onDismiss = { showLyricsSheet = false },
            onTogglePlayPause = playerViewModel::onTogglePlayPause,
            onSeekTo = playerViewModel::onSeekTo,
        )
    }

    if (showQueueSheet) {
        QueueSheet(
            state = playbackState,
            recentlyPlayedTracks = recentlyPlayedTracks,
            isOffline = isOffline,
            onDismiss = { showQueueSheet = false },
            onSelectIndex = playerViewModel::onSelectQueueIndex,
            onRemoveIndex = playerViewModel::onRemoveQueueIndex,
            onMoveItem = playerViewModel::onMoveQueueItem,
            onClearManualQueue = playerViewModel::onClearManualQueue,
            onClearUpNext = playerViewModel::onClearUpNext,
            onReshuffleQueue = playerViewModel::onReshuffleQueue,
            onPlayRecentTrack = playerViewModel::onPlayRecentTrack,
            onAddToQueue = playerViewModel::onAddToQueue,
            onAddToPlaylist = { track ->
                playlistPickerInitialFavorite = track.isFavorite
                trackForPlaylistPicker = track
            },
            onGoToAlbum = navigateToAlbum,
            onGoToArtist = onGoToTrackArtist,
        )
    }

    trackForPlaylistPicker?.let { selectedTrack ->
        val membershipFlow = remember(selectedTrack.id) {
            playlistPickerViewModel.observeTrackPlaylistMembership(selectedTrack.id)
        }
        val membership by membershipFlow.collectAsStateWithLifecycle(initialValue = emptyMap())

        AddToPlaylistSheet(
            track = selectedTrack,
            initialIsFavorite = playlistPickerInitialFavorite,
            playlists = allPlaylists,
            initialPlaylistIds = membership.keys,
            onDismiss = { trackForPlaylistPicker = null },
            onConfirm = { targetFavorite, targetPlaylistIds ->
                playlistPickerViewModel.applySaveSelection(
                    track = selectedTrack,
                    initialFavorite = playlistPickerInitialFavorite,
                    targetFavorite = targetFavorite,
                    initialMembership = membership,
                    targetPlaylistIds = targetPlaylistIds,
                )
                trackForPlaylistPicker = null
            },
            onCreateNewWithTrack = { newName, folderName ->
                playlistPickerViewModel.createPlaylistWithTrack(newName, folderName, selectedTrack)
                trackForPlaylistPicker = null
            },
        )
    }

    pendingDownload?.let { request ->
        DownloadQualitySheet(
            title = request.displayTitle,
            defaultQuality = downloadPrefs.defaultDownloadQuality,
            onDismiss = downloadActionsViewModel::onDismissPendingDownload,
            onConfirm = downloadActionsViewModel::onConfirmPendingDownload,
        )
    }
    }
}
