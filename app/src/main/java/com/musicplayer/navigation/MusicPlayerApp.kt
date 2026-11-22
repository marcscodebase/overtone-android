package com.musicplayer.navigation

import androidx.compose.foundation.layout.Box
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.musicplayer.core.model.Track
import com.musicplayer.feature.auth.AuthRoute
import com.musicplayer.feature.download.DownloadActionsViewModel
import com.musicplayer.feature.download.DownloadQualitySheet
import com.musicplayer.feature.library.detail.AlbumDetailRoute
import com.musicplayer.feature.library.detail.ArtistDetailRoute
import com.musicplayer.feature.library.detail.PlaylistDetailRoute
import com.musicplayer.feature.library.playlist.AddToPlaylistSheet
import com.musicplayer.feature.library.playlist.PlaylistPickerViewModel
import com.musicplayer.feature.player.LyricsSheet
import com.musicplayer.feature.player.MiniPlayer
import com.musicplayer.feature.player.NowPlayingSheet
import com.musicplayer.feature.player.PlayerViewModel
import com.musicplayer.feature.player.QueueSheet
import com.musicplayer.feature.search.SearchRoute
import com.musicplayer.feature.settings.SettingsRoute
import com.musicplayer.feature.settings.StorageManagerRoute

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
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination
    val playbackState by playerViewModel.playbackState.collectAsStateWithLifecycle()
    val lyricsState by playerViewModel.lyricsState.collectAsStateWithLifecycle()
    val allPlaylists by playlistPickerViewModel.playlists.collectAsStateWithLifecycle()
    val downloadRecords by downloadActionsViewModel.downloadRecords.collectAsStateWithLifecycle()
    val downloadPrefs by downloadActionsViewModel.preferences.collectAsStateWithLifecycle()
    val pendingDownload by downloadActionsViewModel.pendingRequest.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var showNowPlaying by rememberSaveable { mutableStateOf(false) }
    var showQueueSheet by rememberSaveable { mutableStateOf(false) }
    var showLyricsSheet by rememberSaveable { mutableStateOf(false) }
    var trackForPlaylistPicker by remember { mutableStateOf<Track?>(null) }

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

    val showMiniPlayer = playbackState.currentTrack != null &&
        currentDestination?.hasRoute(SettingsDestination::class) != true &&
        currentDestination?.hasRoute(StorageManagerDestination::class) != true

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
                        onExpand = { showNowPlaying = true },
                        onTogglePlayPause = playerViewModel::onTogglePlayPause,
                        onToggleFavorite = playerViewModel::onToggleCurrentTrackFavorite,
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
                    onAlbumClick = { albumId -> navController.navigate(AlbumDetailDestination(albumId)) },
                    onArtistClick = { artistId -> navController.navigate(ArtistDetailDestination(artistId)) },
                    onPlaylistClick = { playlistId -> navController.navigate(PlaylistDetailDestination(playlistId)) },
                    onAddToPlaylist = { track -> trackForPlaylistPicker = track },
                    onToggleTrackDownload = downloadActionsViewModel::onToggleTrackDownload,
                    onSettingsClick = { navController.navigate(SettingsDestination) },
                )
            }

            composable<SearchDestination> {
                SearchRoute(
                    onBackClick = { navController.popBackStack() },
                    onAlbumClick = { albumId -> navController.navigate(AlbumDetailDestination(albumId)) },
                    onArtistClick = { artistId -> navController.navigate(ArtistDetailDestination(artistId)) },
                    onPlaylistClick = { playlistId -> navController.navigate(PlaylistDetailDestination(playlistId)) },
                    onAddToPlaylist = { track -> trackForPlaylistPicker = track },
                    onToggleTrackDownload = downloadActionsViewModel::onToggleTrackDownload,
                )
            }

            composable<AlbumDetailDestination> {
                AlbumDetailRoute(
                    onBackClick = { navController.popBackStack() },
                    onAddToPlaylist = { track -> trackForPlaylistPicker = track },
                    onToggleTrackDownload = downloadActionsViewModel::onToggleTrackDownload,
                    onDownloadAlbum = downloadActionsViewModel::onDownloadAlbum,
                    onRemoveAlbumDownload = downloadActionsViewModel::onRemoveAlbumDownload,
                )
            }

            composable<ArtistDetailDestination> {
                ArtistDetailRoute(
                    onBackClick = { navController.popBackStack() },
                    onAlbumClick = { albumId -> navController.navigate(AlbumDetailDestination(albumId)) },
                    onAddToPlaylist = { track -> trackForPlaylistPicker = track },
                    onToggleTrackDownload = downloadActionsViewModel::onToggleTrackDownload,
                )
            }

            composable<PlaylistDetailDestination> {
                PlaylistDetailRoute(
                    onBackClick = { navController.popBackStack() },
                    onAddToPlaylist = { track -> trackForPlaylistPicker = track },
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

    val activeTrack = playbackState.currentTrack
    if (showNowPlaying && activeTrack != null) {
        NowPlayingSheet(
            state = playbackState,
            downloadStatus = downloadRecords[activeTrack.id]?.status,
            onDismiss = { showNowPlaying = false },
            onTogglePlayPause = playerViewModel::onTogglePlayPause,
            onToggleFavorite = playerViewModel::onToggleCurrentTrackFavorite,
            onToggleDownload = { downloadActionsViewModel.onToggleTrackDownload(activeTrack) },
            onSkipNext = playerViewModel::onSkipNext,
            onSkipPrevious = playerViewModel::onSkipPrevious,
            onSeekTo = playerViewModel::onSeekTo,
            onToggleShuffle = playerViewModel::onToggleShuffle,
            onCycleRepeatMode = playerViewModel::onCycleRepeatMode,
            onOpenQueue = { showQueueSheet = true },
            onOpenLyrics = { showLyricsSheet = true },
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

    if (showQueueSheet && playbackState.queue.isNotEmpty()) {
        QueueSheet(
            state = playbackState,
            onDismiss = { showQueueSheet = false },
            onSelectIndex = playerViewModel::onSelectQueueIndex,
            onRemoveIndex = playerViewModel::onRemoveQueueIndex,
            onMoveItem = playerViewModel::onMoveQueueItem,
            onClearUpNext = playerViewModel::onClearUpNext,
        )
    }

    trackForPlaylistPicker?.let { selectedTrack ->
        AddToPlaylistSheet(
            track = selectedTrack,
            playlists = allPlaylists,
            onDismiss = { trackForPlaylistPicker = null },
            onSelectPlaylist = { playlist ->
                playlistPickerViewModel.addTrackToPlaylist(playlist.id, selectedTrack)
                trackForPlaylistPicker = null
            },
            onCreateNewWithTrack = { newName ->
                playlistPickerViewModel.createPlaylistWithTrack(newName, selectedTrack)
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
