package com.musicplayer.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.musicplayer.R
import com.musicplayer.feature.auth.AuthRoute
import com.musicplayer.feature.home.HomeRoute
import com.musicplayer.feature.library.LibraryRoute
import com.musicplayer.feature.library.detail.AlbumDetailRoute
import com.musicplayer.feature.library.detail.ArtistDetailRoute
import com.musicplayer.feature.library.detail.PlaylistDetailRoute
import com.musicplayer.feature.player.MiniPlayer
import com.musicplayer.feature.player.NowPlayingSheet
import com.musicplayer.feature.player.PlayerViewModel
import com.musicplayer.feature.player.QueueSheet
import com.musicplayer.feature.search.SearchRoute
import com.musicplayer.feature.settings.SettingsRoute

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

private data class TopLevelTab(
    val route: Any,
    val routeClass: kotlin.reflect.KClass<*>,
    val labelRes: Int,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
)

private val topLevelTabs = listOf(
    TopLevelTab(
        route = HomeDestination,
        routeClass = HomeDestination::class,
        labelRes = R.string.nav_home,
        selectedIcon = Icons.Rounded.Home,
        unselectedIcon = Icons.Outlined.Home,
    ),
    TopLevelTab(
        route = SearchDestination,
        routeClass = SearchDestination::class,
        labelRes = R.string.nav_search,
        selectedIcon = Icons.Rounded.Search,
        unselectedIcon = Icons.Outlined.Search,
    ),
    TopLevelTab(
        route = LibraryDestination,
        routeClass = LibraryDestination::class,
        labelRes = R.string.nav_library,
        selectedIcon = Icons.Rounded.LibraryMusic,
        unselectedIcon = Icons.Outlined.LibraryMusic,
    ),
)

@Composable
private fun AuthenticatedShell(
    playerViewModel: PlayerViewModel = hiltViewModel(),
) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination
    val playbackState by playerViewModel.playbackState.collectAsStateWithLifecycle()
    var showNowPlaying by rememberSaveable { mutableStateOf(false) }
    var showQueueSheet by rememberSaveable { mutableStateOf(false) }

    val showBottomBar = currentDestination == null ||
        currentDestination.hasRoute(HomeDestination::class) ||
        currentDestination.hasRoute(SearchDestination::class) ||
        currentDestination.hasRoute(LibraryDestination::class) ||
        currentDestination.hasRoute(AlbumDetailDestination::class) ||
        currentDestination.hasRoute(ArtistDetailDestination::class) ||
        currentDestination.hasRoute(PlaylistDetailDestination::class)

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            if (showBottomBar) {
                Column {
                    if (playbackState.currentTrack != null) {
                        MiniPlayer(
                            state = playbackState,
                            onExpand = { showNowPlaying = true },
                            onTogglePlayPause = playerViewModel::onTogglePlayPause,
                            onSkipNext = playerViewModel::onSkipNext,
                            onSkipPrevious = playerViewModel::onSkipPrevious,
                        )
                    }
                    NavigationBar {
                        topLevelTabs.forEach { tab ->
                            val selected = currentDestination?.hasRoute(tab.routeClass) == true
                            val label = stringResource(tab.labelRes)
                            NavigationBarItem(
                                selected = selected,
                                onClick = {
                                    navController.navigate(tab.route) {
                                        popUpTo(navController.graph.findStartDestination().id) {
                                            saveState = true
                                        }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                },
                                icon = {
                                    Icon(
                                        imageVector = if (selected) tab.selectedIcon else tab.unselectedIcon,
                                        contentDescription = label,
                                    )
                                },
                                label = { Text(text = label) },
                            )
                        }
                    }
                }
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = HomeDestination,
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = innerPadding.calculateBottomPadding()),
        ) {
            composable<HomeDestination> {
                HomeRoute(
                    onAlbumClick = { albumId -> navController.navigate(AlbumDetailDestination(albumId)) },
                    onPlaylistClick = { playlistId -> navController.navigate(PlaylistDetailDestination(playlistId)) },
                    onSettingsClick = { navController.navigate(SettingsDestination) },
                )
            }

            composable<SearchDestination> {
                SearchRoute(
                    onAlbumClick = { albumId -> navController.navigate(AlbumDetailDestination(albumId)) },
                    onArtistClick = { artistId -> navController.navigate(ArtistDetailDestination(artistId)) },
                    onPlaylistClick = { playlistId -> navController.navigate(PlaylistDetailDestination(playlistId)) },
                )
            }

            composable<LibraryDestination> {
                LibraryRoute(
                    onAlbumClick = { albumId -> navController.navigate(AlbumDetailDestination(albumId)) },
                    onArtistClick = { artistId -> navController.navigate(ArtistDetailDestination(artistId)) },
                    onPlaylistClick = { playlistId -> navController.navigate(PlaylistDetailDestination(playlistId)) },
                    onSettingsClick = { navController.navigate(SettingsDestination) },
                )
            }

            composable<AlbumDetailDestination> {
                AlbumDetailRoute(
                    onBackClick = { navController.popBackStack() },
                )
            }

            composable<ArtistDetailDestination> {
                ArtistDetailRoute(
                    onBackClick = { navController.popBackStack() },
                    onAlbumClick = { albumId -> navController.navigate(AlbumDetailDestination(albumId)) },
                )
            }

            composable<PlaylistDetailDestination> {
                PlaylistDetailRoute(
                    onBackClick = { navController.popBackStack() },
                )
            }

            composable<SettingsDestination> {
                SettingsRoute(
                    onBackClick = { navController.popBackStack() },
                )
            }
        }
    }

    if (showNowPlaying && playbackState.currentTrack != null) {
        NowPlayingSheet(
            state = playbackState,
            onDismiss = { showNowPlaying = false },
            onTogglePlayPause = playerViewModel::onTogglePlayPause,
            onSkipNext = playerViewModel::onSkipNext,
            onSkipPrevious = playerViewModel::onSkipPrevious,
            onSeekTo = playerViewModel::onSeekTo,
            onToggleShuffle = playerViewModel::onToggleShuffle,
            onCycleRepeatMode = playerViewModel::onCycleRepeatMode,
            onOpenQueue = { showQueueSheet = true },
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
}
