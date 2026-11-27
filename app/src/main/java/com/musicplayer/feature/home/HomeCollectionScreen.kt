package com.musicplayer.feature.home

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.musicplayer.R
import com.musicplayer.core.designsystem.component.ArtworkCard
import com.musicplayer.core.designsystem.component.EmptyState
import com.musicplayer.core.designsystem.component.LazyColumnFastScroller
import com.musicplayer.core.designsystem.component.OfflineStatusBanner
import com.musicplayer.core.designsystem.component.SwipeDownToDismissContainer
import com.musicplayer.core.designsystem.component.TrackRow
import com.musicplayer.core.designsystem.component.isAtTopOfList
import com.musicplayer.core.model.Album
import com.musicplayer.core.model.Track

@Composable
fun HomeCollectionRoute(
    onBackClick: () -> Unit,
    onAlbumClick: (String) -> Unit,
    onGoToTrackArtist: (Track) -> Unit,
    onAddToPlaylist: (Track) -> Unit,
    onToggleTrackDownload: (Track) -> Unit,
    viewModel: HomeCollectionViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    HomeCollectionScreen(
        state = state,
        onBackClick = onBackClick,
        onAlbumClick = onAlbumClick,
        onGoToTrackArtist = onGoToTrackArtist,
        onPlayAll = viewModel::onPlayAll,
        onShuffleAll = viewModel::onShuffleAll,
        onTrackClick = viewModel::onTrackClick,
        onAddToQueue = viewModel::onAddToQueue,
        onAddToPlaylist = onAddToPlaylist,
        onToggleTrackDownload = onToggleTrackDownload,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeCollectionScreen(
    state: HomeCollectionUiState,
    onBackClick: () -> Unit,
    onAlbumClick: (String) -> Unit,
    onGoToTrackArtist: (Track) -> Unit,
    onPlayAll: () -> Unit,
    onShuffleAll: () -> Unit,
    onTrackClick: (Int) -> Unit,
    onAddToQueue: (Track) -> Unit,
    onAddToPlaylist: (Track) -> Unit,
    onToggleTrackDownload: (Track) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val titleRes = when (state.section) {
        HomeCollectionSection.RECENTLY_ADDED -> R.string.home_recently_added
        HomeCollectionSection.RECENTLY_PLAYED -> R.string.home_recently_played
    }

    SwipeDownToDismissContainer(
        isAtTop = { listState.isAtTopOfList() },
        onDismiss = onBackClick,
        modifier = modifier,
    ) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            text = stringResource(titleRes),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = onBackClick) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                                contentDescription = stringResource(R.string.action_back),
                            )
                        }
                    },
                )
            },
        ) { innerPadding ->
            when (state.section) {
                HomeCollectionSection.RECENTLY_ADDED -> {
                    if (state.albums.isEmpty()) {
                        EmptyState(
                            title = stringResource(R.string.library_empty_title),
                            subtitle = stringResource(R.string.library_empty_subtitle),
                            icon = Icons.Rounded.Album,
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(innerPadding),
                        )
                    } else {
                        LazyColumnFastScroller(
                            listState = listState,
                            itemCount = state.albums.size,
                            headerItemCount = 1,
                            bubbleLabelForIndex = { idx ->
                                state.albums.getOrNull(idx)?.year?.toString() ?: "#${idx + 1}"
                            },
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(innerPadding),
                        ) {
                            LazyColumn(
                                state = listState,
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(bottom = 24.dp),
                            ) {
                                item(key = "offline_banner") {
                                    OfflineStatusBanner(status = state.offlineStatus)
                                }
                                items(
                                    items = state.albums,
                                    key = { it.id },
                                    contentType = { "album" },
                                ) { album ->
                                    RecentlyAddedAlbumRow(
                                        album = album,
                                        onClick = { onAlbumClick(album.id) },
                                    )
                                }
                            }
                        }
                    }
                }

                HomeCollectionSection.RECENTLY_PLAYED -> {
                    if (state.tracks.isEmpty()) {
                        EmptyState(
                            title = stringResource(R.string.home_recently_played_empty_title),
                            subtitle = stringResource(R.string.home_recently_played_empty_subtitle),
                            icon = Icons.Rounded.History,
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(innerPadding),
                        )
                    } else {
                        LazyColumnFastScroller(
                            listState = listState,
                            itemCount = state.tracks.size,
                            headerItemCount = 2,
                            bubbleLabelForIndex = { idx -> "#${idx + 1}" },
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(innerPadding),
                        ) {
                            LazyColumn(
                                state = listState,
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(bottom = 24.dp),
                            ) {
                                item(key = "offline_banner") {
                                    OfflineStatusBanner(status = state.offlineStatus)
                                }
                                item(key = "actions_bar") {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 16.dp, vertical = 10.dp),
                                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    ) {
                                        Button(
                                            onClick = onPlayAll,
                                            enabled = state.playableTracks.isNotEmpty(),
                                            modifier = Modifier
                                                .weight(1f)
                                                .height(46.dp),
                                        ) {
                                            Icon(Icons.Rounded.PlayArrow, contentDescription = null)
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text(stringResource(R.string.action_play))
                                        }
                                        FilledTonalButton(
                                            onClick = onShuffleAll,
                                            enabled = state.playableTracks.isNotEmpty(),
                                            modifier = Modifier
                                                .weight(1f)
                                                .height(46.dp),
                                        ) {
                                            Icon(Icons.Rounded.Shuffle, contentDescription = null)
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text(stringResource(R.string.action_shuffle))
                                        }
                                    }
                                }
                                itemsIndexed(
                                    items = state.tracks,
                                    key = { _, track -> "rp_${track.id}" },
                                    contentType = { _, _ -> "track" },
                                ) { index, track ->
                                    TrackRow(
                                        track = track,
                                        isPlaying = track.id == state.activeTrackId,
                                        enabled = !state.isOffline || track.isDownloaded,
                                        onClick = { onTrackClick(index) },
                                        onAddToQueue = { onAddToQueue(track) },
                                        onAddToPlaylist = { onAddToPlaylist(track) },
                                        onToggleDownload = { onToggleTrackDownload(track) },
                                        onGoToAlbum = track.albumId?.let { id -> { onAlbumClick(id) } },
                                        onGoToArtist = { onGoToTrackArtist(track) },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RecentlyAddedAlbumRow(
    album: Album,
    onClick: () -> Unit,
) {
    val subtitle = listOfNotNull(
        album.artistName,
        album.year?.toString(),
    ).joinToString(" • ")

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ArtworkCard(
            imageUrl = album.artworkUrl,
            contentDescription = album.title,
            shape = MaterialTheme.shapes.small,
            fallbackIcon = Icons.Rounded.Album,
            fallbackIconSize = 24.dp,
            modifier = Modifier.size(56.dp),
        )
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = album.title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle.isNotEmpty()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
