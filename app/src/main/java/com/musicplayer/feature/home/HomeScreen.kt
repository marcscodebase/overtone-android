package com.musicplayer.feature.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.musicplayer.R
import com.musicplayer.core.designsystem.component.AlbumCardShimmer
import com.musicplayer.core.designsystem.component.AlbumCarouselCard
import com.musicplayer.core.designsystem.component.EmptyState
import com.musicplayer.core.designsystem.component.ErrorState
import com.musicplayer.core.designsystem.component.PlaylistCarouselCard
import com.musicplayer.core.designsystem.component.SectionHeader
import com.musicplayer.core.designsystem.component.TrackRow
import com.musicplayer.core.model.Track

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeContent(
    state: HomeUiState,
    onRefresh: () -> Unit,
    onAlbumClick: (String) -> Unit,
    onPlaylistClick: (String) -> Unit,
    onTrackClick: (List<Track>, Int) -> Unit,
    onAddToQueue: (Track) -> Unit,
    onToggleFavorite: (Track) -> Unit,
    onAddToPlaylist: (Track) -> Unit,
    onToggleTrackDownload: (Track) -> Unit,
    onGoToArtist: (Track) -> Unit,
    modifier: Modifier = Modifier,
) {
    PullToRefreshBox(
        isRefreshing = state.isRefreshing,
        onRefresh = onRefresh,
        modifier = modifier.fillMaxSize(),
    ) {
        when {
            state.isEmpty && state.isRefreshing -> {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(top = 8.dp, bottom = 88.dp),
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
                    contentPadding = PaddingValues(top = 4.dp, bottom = 88.dp),
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
                                enabled = !state.isOffline || track.isDownloaded,
                                onClick = { onTrackClick(state.favoriteTracks, index) },
                                onAddToQueue = { onAddToQueue(track) },
                                onToggleFavorite = { onToggleFavorite(track) },
                                onAddToPlaylist = { onAddToPlaylist(track) },
                                onToggleDownload = { onToggleTrackDownload(track) },
                                onGoToAlbum = track.albumId?.let { id -> { onAlbumClick(id) } },
                                onGoToArtist = { onGoToArtist(track) },
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
                                enabled = !state.isOffline || track.isDownloaded,
                                onClick = { onTrackClick(state.recentlyPlayedTracks, index) },
                                onAddToQueue = { onAddToQueue(track) },
                                onToggleFavorite = { onToggleFavorite(track) },
                                onAddToPlaylist = { onAddToPlaylist(track) },
                                onToggleDownload = { onToggleTrackDownload(track) },
                                onGoToAlbum = track.albumId?.let { id -> { onAlbumClick(id) } },
                                onGoToArtist = { onGoToArtist(track) },
                            )
                        }
                    }
                }
            }
        }
    }
}
