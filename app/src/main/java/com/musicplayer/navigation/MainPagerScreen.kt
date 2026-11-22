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
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.musicplayer.R
import com.musicplayer.core.model.Track
import com.musicplayer.feature.home.HomeContent
import com.musicplayer.feature.home.HomeViewModel
import com.musicplayer.feature.library.LibraryContent
import com.musicplayer.feature.library.LibraryViewModel
import com.musicplayer.feature.library.playlist.PlaylistNameDialog
import kotlinx.coroutines.launch

@Composable
fun MainPagerRoute(
    onSearchClick: () -> Unit,
    onAlbumClick: (String) -> Unit,
    onArtistClick: (String) -> Unit,
    onPlaylistClick: (String) -> Unit,
    onAddToPlaylist: (Track) -> Unit,
    onSettingsClick: () -> Unit,
    homeViewModel: HomeViewModel = hiltViewModel(),
    libraryViewModel: LibraryViewModel = hiltViewModel(),
) {
    val pagerState = rememberPagerState(initialPage = 0, pageCount = { 2 })
    val coroutineScope = rememberCoroutineScope()
    val currentPage by remember { derivedStateOf { pagerState.currentPage } }
    var showCreatePlaylistDialog by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            CompactTopTabBar(
                selectedPage = currentPage,
                onSelectPage = { page ->
                    coroutineScope.launch { pagerState.animateScrollToPage(page) }
                },
                onRefreshClick = homeViewModel::refreshLibrary,
                onToggleSortClick = libraryViewModel::onToggleSort,
                onSettingsClick = onSettingsClick,
            )
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
                    onAlbumClick = onAlbumClick,
                    onPlaylistClick = onPlaylistClick,
                    onAddToPlaylist = onAddToPlaylist,
                )

                1 -> LibraryPagerPage(
                    viewModel = libraryViewModel,
                    onAlbumClick = onAlbumClick,
                    onArtistClick = onArtistClick,
                    onPlaylistClick = onPlaylistClick,
                    onAddToPlaylist = onAddToPlaylist,
                )
            }
        }
    }

    if (showCreatePlaylistDialog) {
        PlaylistNameDialog(
            title = stringResource(R.string.playlist_create_new),
            confirmLabel = stringResource(R.string.action_create),
            onDismiss = { showCreatePlaylistDialog = false },
            onConfirm = { name ->
                showCreatePlaylistDialog = false
                libraryViewModel.onCreatePlaylist(name)
            },
        )
    }
}

@Composable
private fun HomePagerPage(
    viewModel: HomeViewModel,
    onAlbumClick: (String) -> Unit,
    onPlaylistClick: (String) -> Unit,
    onAddToPlaylist: (Track) -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    HomeContent(
        state = state,
        onRefresh = viewModel::refreshLibrary,
        onAlbumClick = onAlbumClick,
        onPlaylistClick = onPlaylistClick,
        onTrackClick = viewModel::onTrackClick,
        onPlayNext = viewModel::onPlayNext,
        onAddToQueue = viewModel::onAddToQueue,
        onToggleFavorite = viewModel::onToggleFavorite,
        onAddToPlaylist = onAddToPlaylist,
    )
}

@Composable
private fun LibraryPagerPage(
    viewModel: LibraryViewModel,
    onAlbumClick: (String) -> Unit,
    onArtistClick: (String) -> Unit,
    onPlaylistClick: (String) -> Unit,
    onAddToPlaylist: (Track) -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LibraryContent(
        state = state,
        onFilterSelected = viewModel::onFilterSelected,
        onAlbumClick = onAlbumClick,
        onArtistClick = onArtistClick,
        onPlaylistClick = onPlaylistClick,
        onTrackClick = viewModel::onPlayTracks,
        onPlayNext = viewModel::onPlayNext,
        onAddToQueue = viewModel::onAddToQueue,
        onToggleFavorite = viewModel::onToggleFavorite,
        onAddToPlaylist = onAddToPlaylist,
    )
}

@Composable
private fun CompactTopTabBar(
    selectedPage: Int,
    onSelectPage: (Int) -> Unit,
    onRefreshClick: () -> Unit,
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
