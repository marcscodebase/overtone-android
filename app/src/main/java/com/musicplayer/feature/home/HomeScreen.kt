package com.musicplayer.feature.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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
    onDaylistClick: () -> Unit,
    onPlayDaylist: () -> Unit,
    onShuffleDaylist: () -> Unit,
    onShowAllRecentlyAdded: () -> Unit,
    onShowAllPlaylists: () -> Unit,
    onShowAllFavorites: () -> Unit,
    onShowAllRecentlyPlayed: () -> Unit,
    onAlbumClick: (String) -> Unit,
    onPlaylistClick: (String) -> Unit,
    onTrackClick: (List<Track>, Int) -> Unit,
    onAddToQueue: (Track) -> Unit,
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
                    state.daylist?.takeIf { it.tracks.isNotEmpty() }?.let { daylist ->
                        item(key = "section_daylist") {
                            DaylistHomeCard(
                                daylist = daylist,
                                canPlay = !state.isOffline || daylist.tracks.any { it.isDownloaded },
                                onClick = onDaylistClick,
                                onPlayClick = onPlayDaylist,
                                onShuffleClick = onShuffleDaylist,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                        }
                    }

                    if (state.recentlyAddedAlbums.isNotEmpty()) {
                        item(key = "section_recent_albums") {
                            SectionHeader(
                                title = stringResource(R.string.home_recently_added),
                                actionLabel = stringResource(R.string.action_show_all),
                                onActionClick = onShowAllRecentlyAdded,
                            )
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
                            SectionHeader(
                                title = stringResource(R.string.home_playlists),
                                actionLabel = stringResource(R.string.action_show_all),
                                onActionClick = onShowAllPlaylists,
                            )
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
                                onAddToPlaylist = { onAddToPlaylist(track) },
                                onToggleDownload = { onToggleTrackDownload(track) },
                                onGoToAlbum = track.albumId?.let { id -> { onAlbumClick(id) } },
                                onGoToArtist = { onGoToArtist(track) },
                            )
                        }
                        item(key = "show_all_favorites") {
                            ShowAllListFooterButton(onClick = onShowAllFavorites)
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
                                onAddToPlaylist = { onAddToPlaylist(track) },
                                onToggleDownload = { onToggleTrackDownload(track) },
                                onGoToAlbum = track.albumId?.let { id -> { onAlbumClick(id) } },
                                onGoToArtist = { onGoToArtist(track) },
                            )
                        }
                        item(key = "show_all_recently_played") {
                            ShowAllListFooterButton(onClick = onShowAllRecentlyPlayed)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ShowAllListFooterButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.Center,
    ) {
        TextButton(onClick = onClick) {
            Text(
                text = stringResource(R.string.action_show_all),
                style = MaterialTheme.typography.labelLarge,
            )
            Spacer(modifier = Modifier.width(2.dp))
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun DaylistHomeCard(
    daylist: Daylist,
    canPlay: Boolean,
    onClick: () -> Unit,
    onPlayClick: () -> Unit,
    onShuffleClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val periodLabel = daylistPeriodLabel(daylist.slot.period)
    val countLabel = if (daylist.tracks.size == 1) "1 song" else "${daylist.tracks.size} songs"
    val updatesLabel = stringResource(R.string.daylist_updates_at, daylist.slot.nextRefreshTimeLabel)

    Card(
        onClick = onClick,
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.72f),
        ),
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DaylistMosaicArtwork(
                artworkUrls = daylist.artworkUrls,
                shape = MaterialTheme.shapes.medium,
                fallbackIconSize = 30.dp,
                modifier = Modifier.size(72.dp),
            )
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.AutoAwesome,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(15.dp),
                    )
                    Text(
                        text = periodLabel,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = stringResource(R.string.daylist_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "$countLabel • $updatesLabel",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilledTonalIconButton(
                    onClick = onShuffleClick,
                    enabled = canPlay,
                    shape = CircleShape,
                    modifier = Modifier.size(40.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Shuffle,
                        contentDescription = stringResource(R.string.action_shuffle),
                        modifier = Modifier.size(20.dp),
                    )
                }
                FilledIconButton(
                    onClick = onPlayClick,
                    enabled = canPlay,
                    shape = CircleShape,
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    ),
                    modifier = Modifier.size(42.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.PlayArrow,
                        contentDescription = stringResource(R.string.action_play),
                        modifier = Modifier.size(24.dp),
                    )
                }
            }
        }
    }
}
