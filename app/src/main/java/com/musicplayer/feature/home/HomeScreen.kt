package com.musicplayer.feature.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.musicplayer.R
import com.musicplayer.core.designsystem.component.AlbumCardShimmer
import com.musicplayer.core.designsystem.component.AlbumCarouselCard
import com.musicplayer.core.designsystem.component.EmptyState
import com.musicplayer.core.designsystem.component.ErrorState
import com.musicplayer.core.designsystem.component.PlaylistCarouselCard
import com.musicplayer.core.designsystem.component.SectionHeader
import com.musicplayer.core.designsystem.component.TrackRow
import com.musicplayer.core.model.Track

@Composable
fun HomeRoute(
    onAlbumClick: (String) -> Unit,
    onPlaylistClick: (String) -> Unit,
    onAddToPlaylist: (Track) -> Unit,
    onSettingsClick: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    HomeScreen(
        state = state,
        onRefresh = viewModel::refreshLibrary,
        onAlbumClick = onAlbumClick,
        onPlaylistClick = onPlaylistClick,
        onTrackClick = viewModel::onTrackClick,
        onPlayNext = viewModel::onPlayNext,
        onAddToQueue = viewModel::onAddToQueue,
        onToggleFavorite = viewModel::onToggleFavorite,
        onAddToPlaylist = onAddToPlaylist,
        onSettingsClick = onSettingsClick,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    state: HomeUiState,
    onRefresh: () -> Unit,
    onAlbumClick: (String) -> Unit,
    onPlaylistClick: (String) -> Unit,
    onTrackClick: (List<Track>, Int) -> Unit,
    onPlayNext: (Track) -> Unit,
    onAddToQueue: (Track) -> Unit,
    onToggleFavorite: (Track) -> Unit,
    onAddToPlaylist: (Track) -> Unit,
    onSettingsClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.nav_home),
                        style = MaterialTheme.typography.headlineMedium,
                    )
                },
                actions = {
                    IconButton(onClick = onRefresh) {
                        Icon(
                            imageVector = Icons.Rounded.Refresh,
                            contentDescription = stringResource(R.string.action_refresh),
                        )
                    }
                    IconButton(onClick = onSettingsClick) {
                        Icon(
                            imageVector = Icons.Rounded.Settings,
                            contentDescription = stringResource(R.string.action_settings),
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        PullToRefreshBox(
            isRefreshing = state.isRefreshing,
            onRefresh = onRefresh,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            when {
                state.isEmpty && state.isRefreshing -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(vertical = 16.dp),
                    ) {
                        item(key = "shimmer_header") {
                            SectionHeader(title = stringResource(R.string.home_recently_added))
                        }
                        item(key = "shimmer_row") {
                            LazyRow(
                                contentPadding = PaddingValues(horizontal = 16.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                items(4) { AlbumCardShimmer() }
                            }
                        }
                    }
                }

                state.isEmpty && state.errorMessage != null -> {
                    ErrorState(
                        title = stringResource(R.string.home_sync_failed),
                        message = state.errorMessage,
                        retryLabel = stringResource(R.string.action_retry),
                        onRetry = onRefresh,
                        modifier = Modifier.fillMaxSize(),
                    )
                }

                state.isEmpty -> {
                    EmptyState(
                        title = stringResource(R.string.home_empty_title),
                        subtitle = stringResource(R.string.home_empty_subtitle),
                        actionLabel = stringResource(R.string.action_refresh),
                        onAction = onRefresh,
                        modifier = Modifier.fillMaxSize(),
                    )
                }

                else -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = 24.dp),
                    ) {
                        if (state.recentlyAddedAlbums.isNotEmpty()) {
                            item(key = "section_recent_albums") {
                                SectionHeader(title = stringResource(R.string.home_recently_added))
                            }
                            item(key = "row_recent_albums") {
                                LazyRow(
                                    contentPadding = PaddingValues(horizontal = 16.dp),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    items(
                                        items = state.recentlyAddedAlbums,
                                        key = { it.id },
                                    ) { album ->
                                        AlbumCarouselCard(
                                            album = album,
                                            onClick = { onAlbumClick(album.id) },
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.height(16.dp))
                            }
                        }

                        if (state.playlists.isNotEmpty()) {
                            item(key = "section_playlists") {
                                SectionHeader(title = stringResource(R.string.home_playlists))
                            }
                            item(key = "row_playlists") {
                                LazyRow(
                                    contentPadding = PaddingValues(horizontal = 16.dp),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    items(
                                        items = state.playlists,
                                        key = { it.id },
                                    ) { playlist ->
                                        PlaylistCarouselCard(
                                            playlist = playlist,
                                            onClick = { onPlaylistClick(playlist.id) },
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.height(16.dp))
                            }
                        }

                        if (state.favoriteTracks.isNotEmpty()) {
                            item(key = "section_favorite_tracks") {
                                SectionHeader(title = stringResource(R.string.home_favorites))
                            }
                            itemsIndexed(
                                items = state.favoriteTracks,
                                key = { _, track -> "fav_${track.id}" },
                            ) { index, track ->
                                TrackRow(
                                    track = track,
                                    isPlaying = track.id == state.activeTrackId,
                                    onClick = { onTrackClick(state.favoriteTracks, index) },
                                    onPlayNext = { onPlayNext(track) },
                                    onAddToQueue = { onAddToQueue(track) },
                                    onToggleFavorite = { onToggleFavorite(track) },
                                    onAddToPlaylist = { onAddToPlaylist(track) },
                                )
                            }
                            item(key = "spacer_favorites") {
                                Spacer(modifier = Modifier.height(12.dp))
                            }
                        }

                        if (state.recentlyPlayedTracks.isNotEmpty()) {
                            item(key = "section_recent_tracks") {
                                SectionHeader(title = stringResource(R.string.home_recently_played))
                            }
                            itemsIndexed(
                                items = state.recentlyPlayedTracks,
                                key = { _, track -> "recent_${track.id}" },
                            ) { index, track ->
                                TrackRow(
                                    track = track,
                                    isPlaying = track.id == state.activeTrackId,
                                    onClick = { onTrackClick(state.recentlyPlayedTracks, index) },
                                    onPlayNext = { onPlayNext(track) },
                                    onAddToQueue = { onAddToQueue(track) },
                                    onToggleFavorite = { onToggleFavorite(track) },
                                    onAddToPlaylist = { onAddToPlaylist(track) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
