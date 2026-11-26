package com.musicplayer.navigation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CloudDone
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.FilledTonalIconToggleButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.musicplayer.R
import com.musicplayer.core.designsystem.component.OfflineStatusBanner
import com.musicplayer.core.model.Track
import com.musicplayer.feature.home.HomeContent
import com.musicplayer.feature.home.HomeViewModel
import com.musicplayer.feature.library.LibraryContent
import com.musicplayer.feature.library.LibraryViewModel
import com.musicplayer.feature.library.playlist.CreatePlaylistDialog
import kotlinx.coroutines.launch

@Composable
fun MainPagerRoute(
    onSearchClick: () -> Unit,
    onDaylistClick: () -> Unit,
    onAlbumClick: (String) -> Unit,
    onArtistClick: (String) -> Unit,
    onGoToTrackArtist: (Track) -> Unit,
    onPlaylistClick: (String) -> Unit,
    onAddToPlaylist: (Track) -> Unit,
    onToggleTrackDownload: (Track) -> Unit,
    onSettingsClick: () -> Unit,
    homeViewModel: HomeViewModel = hiltViewModel(),
    libraryViewModel: LibraryViewModel = hiltViewModel(),
) {
    val pagerState = rememberPagerState(initialPage = 0, pageCount = { 2 })
    val coroutineScope = rememberCoroutineScope()
    val currentPage by remember { derivedStateOf { pagerState.currentPage } }
    val offlineStatus by libraryViewModel.offlineStatus.collectAsStateWithLifecycle()
    val existingPlaylistFolders by libraryViewModel.existingPlaylistFolders.collectAsStateWithLifecycle()
    val libraryUiState by libraryViewModel.uiState.collectAsStateWithLifecycle()
    var showCreatePlaylistDialog by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            Column(modifier = Modifier.fillMaxWidth()) {
                CompactTopTabBar(
                    selectedPage = currentPage,
                    manualOfflineMode = offlineStatus.manualOfflineMode,
                    onSelectPage = { page ->
                        coroutineScope.launch { pagerState.animateScrollToPage(page) }
                    },
                    onRefreshClick = homeViewModel::refreshLibrary,
                    onToggleOfflineMode = libraryViewModel::onToggleOfflineMode,
                    onToggleSortClick = libraryViewModel::onToggleSort,
                    onSettingsClick = onSettingsClick,
                )
                OfflineStatusBanner(
                    status = offlineStatus,
                    onGoOnlineClick = libraryViewModel::onDisableManualOfflineMode,
                )
            }
        },
        floatingActionButton = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                AnimatedVisibility(
                    visible = currentPage == 1,
                    enter = fadeIn() + scaleIn(initialScale = 0.8f) + expandVertically(expandFrom = Alignment.Bottom),
                    exit = fadeOut() + scaleOut(targetScale = 0.8f) + shrinkVertically(shrinkTowards = Alignment.Bottom),
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        SmallFloatingActionButton(
                            onClick = { showCreatePlaylistDialog = true },
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Add,
                                contentDescription = stringResource(R.string.playlist_create_new),
                            )
                        }
                        FloatingActionButton(onClick = libraryViewModel::onShuffleLibrary) {
                            Icon(
                                imageVector = Icons.Rounded.Shuffle,
                                contentDescription = stringResource(R.string.library_shuffle_all),
                            )
                        }
                    }
                }
                FloatingActionButton(onClick = onSearchClick) {
                    Icon(
                        imageVector = Icons.Rounded.Search,
                        contentDescription = stringResource(R.string.nav_search),
                    )
                }
            }
        },
    ) { innerPadding ->
        HorizontalPager(
            state = pagerState,
            beyondViewportPageCount = 1,
            flingBehavior = PagerDefaults.flingBehavior(
                state = pagerState,
                snapPositionalThreshold = 0.22f,
                snapAnimationSpec = spring(stiffness = Spring.StiffnessMedium),
            ),
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) { page ->
            when (page) {
                0 -> HomePagerPage(
                    viewModel = homeViewModel,
                    onDaylistClick = onDaylistClick,
                    onAlbumClick = onAlbumClick,
                    onGoToTrackArtist = onGoToTrackArtist,
                    onPlaylistClick = onPlaylistClick,
                    onAddToPlaylist = onAddToPlaylist,
                    onToggleTrackDownload = onToggleTrackDownload,
                )

                1 -> LibraryPagerPage(
                    viewModel = libraryViewModel,
                    onAlbumClick = onAlbumClick,
                    onArtistClick = onArtistClick,
                    onGoToTrackArtist = onGoToTrackArtist,
                    onPlaylistClick = onPlaylistClick,
                    onAddToPlaylist = onAddToPlaylist,
                    onToggleTrackDownload = onToggleTrackDownload,
                )
            }
        }
    }

    if (showCreatePlaylistDialog) {
        CreatePlaylistDialog(
            existingFolders = existingPlaylistFolders,
            initialFolder = libraryUiState.selectedPlaylistFolder,
            onDismiss = { showCreatePlaylistDialog = false },
            onConfirm = { name, folderName ->
                showCreatePlaylistDialog = false
                libraryViewModel.onCreatePlaylist(name = name, folderName = folderName)
            },
        )
    }
}

@Composable
private fun HomePagerPage(
    viewModel: HomeViewModel,
    onDaylistClick: () -> Unit,
    onAlbumClick: (String) -> Unit,
    onGoToTrackArtist: (Track) -> Unit,
    onPlaylistClick: (String) -> Unit,
    onAddToPlaylist: (Track) -> Unit,
    onToggleTrackDownload: (Track) -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.checkDaylistSlot()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    HomeContent(
        state = state,
        onRefresh = viewModel::refreshLibrary,
        onDaylistClick = onDaylistClick,
        onPlayDaylist = viewModel::onPlayDaylist,
        onShuffleDaylist = viewModel::onShuffleDaylist,
        onAlbumClick = onAlbumClick,
        onPlaylistClick = onPlaylistClick,
        onTrackClick = viewModel::onTrackClick,
        onAddToQueue = viewModel::onAddToQueue,
        onAddToPlaylist = onAddToPlaylist,
        onToggleTrackDownload = onToggleTrackDownload,
        onGoToArtist = onGoToTrackArtist,
    )
}

@Composable
private fun LibraryPagerPage(
    viewModel: LibraryViewModel,
    onAlbumClick: (String) -> Unit,
    onArtistClick: (String) -> Unit,
    onGoToTrackArtist: (Track) -> Unit,
    onPlaylistClick: (String) -> Unit,
    onAddToPlaylist: (Track) -> Unit,
    onToggleTrackDownload: (Track) -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val existingFolders by viewModel.existingPlaylistFolders.collectAsStateWithLifecycle()
    LibraryContent(
        state = state,
        existingFolders = existingFolders,
        onFilterSelected = viewModel::onFilterSelected,
        onOpenPlaylistFolder = viewModel::onOpenPlaylistFolder,
        onClosePlaylistFolder = viewModel::onClosePlaylistFolder,
        onMovePlaylistToFolder = viewModel::onMovePlaylistToFolder,
        onRenameFolder = viewModel::onRenameFolder,
        onUngroupFolder = viewModel::onUngroupFolder,
        onAlbumClick = onAlbumClick,
        onArtistClick = onArtistClick,
        onPlaylistClick = onPlaylistClick,
        onTrackClick = viewModel::onPlayTracks,
        onAddToQueue = viewModel::onAddToQueue,
        onAddToPlaylist = onAddToPlaylist,
        onToggleTrackDownload = onToggleTrackDownload,
        onGoToTrackArtist = onGoToTrackArtist,
    )
}

@Composable
private fun CompactTopTabBar(
    selectedPage: Int,
    manualOfflineMode: Boolean,
    onSelectPage: (Int) -> Unit,
    onRefreshClick: () -> Unit,
    onToggleOfflineMode: () -> Unit,
    onToggleSortClick: () -> Unit,
    onSettingsClick: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            TopTabPill(
                label = stringResource(R.string.nav_home),
                selected = selectedPage == 0,
                onClick = { onSelectPage(0) },
            )
            TopTabPill(
                label = stringResource(R.string.nav_library),
                selected = selectedPage == 1,
                onClick = { onSelectPage(1) },
            )
            Spacer(modifier = Modifier.weight(1f))
            if (selectedPage == 0) {
                IconButton(onClick = onRefreshClick) {
                    Icon(
                        imageVector = Icons.Rounded.Refresh,
                        contentDescription = stringResource(R.string.action_refresh),
                    )
                }
            } else {
                FilledTonalIconToggleButton(
                    checked = manualOfflineMode,
                    onCheckedChange = { onToggleOfflineMode() },
                ) {
                    Icon(
                        imageVector = if (manualOfflineMode) Icons.Rounded.CloudOff else Icons.Rounded.CloudDone,
                        contentDescription = stringResource(R.string.offline_toggle_desc),
                    )
                }
                IconButton(onClick = onToggleSortClick) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.Sort,
                        contentDescription = stringResource(R.string.library_sort),
                    )
                }
            }
            IconButton(onClick = onSettingsClick) {
                Icon(
                    imageVector = Icons.Rounded.Settings,
                    contentDescription = stringResource(R.string.action_settings),
                )
            }
        }
    }
}

@Composable
private fun TopTabPill(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        color = if (selected) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            Color.Transparent
        },
        shape = CircleShape,
        modifier = Modifier
            .clip(CircleShape)
            .clickable(onClick = onClick),
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                color = if (selected) {
                    MaterialTheme.colorScheme.onSecondaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
    }
}
