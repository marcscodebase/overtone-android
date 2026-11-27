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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CloudDone
import androidx.compose.material.icons.rounded.OfflineBolt
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.musicplayer.R
import com.musicplayer.core.common.Formatters
import com.musicplayer.core.designsystem.component.EmptyState
import com.musicplayer.core.designsystem.component.LazyColumnFastScroller
import com.musicplayer.core.designsystem.component.OfflineStatusBanner
import com.musicplayer.core.designsystem.component.SwipeDownToDismissContainer
import com.musicplayer.core.designsystem.component.TrackRow
import com.musicplayer.core.designsystem.component.isAtTopOfList
import com.musicplayer.core.model.Track

@Composable
fun SmartMixRoute(
    onBackClick: () -> Unit,
    onAlbumClick: (String) -> Unit,
    onGoToTrackArtist: (Track) -> Unit,
    onAddToPlaylist: (Track) -> Unit,
    onToggleTrackDownload: (Track) -> Unit,
    viewModel: SmartMixViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    SmartMixScreen(
        state = state,
        onBackClick = onBackClick,
        onRefresh = viewModel::onRefresh,
        onPlayAll = viewModel::onPlayAll,
        onShuffleAll = viewModel::onShuffleAll,
        onTrackClick = viewModel::onTrackClick,
        onAddToQueue = viewModel::onAddToQueue,
        onAddToPlaylist = onAddToPlaylist,
        onToggleTrackDownload = onToggleTrackDownload,
        onAlbumClick = onAlbumClick,
        onGoToTrackArtist = onGoToTrackArtist,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SmartMixScreen(
    state: SmartMixUiState,
    onBackClick: () -> Unit,
    onRefresh: () -> Unit,
    onPlayAll: () -> Unit,
    onShuffleAll: () -> Unit,
    onTrackClick: (Int) -> Unit,
    onAddToQueue: (Track) -> Unit,
    onAddToPlaylist: (Track) -> Unit,
    onToggleTrackDownload: (Track) -> Unit,
    onAlbumClick: (String) -> Unit,
    onGoToTrackArtist: (Track) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()

    SwipeDownToDismissContainer(
        isAtTop = { listState.isAtTopOfList() },
        onDismiss = onBackClick,
        modifier = modifier,
    ) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            topBar = {
                TopAppBar(
                    title = {},
                    navigationIcon = {
                        IconButton(onClick = onBackClick) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                                contentDescription = stringResource(R.string.action_back),
                            )
                        }
                    },
                    actions = {
                        if (state.isLoadingRemote) {
                            CircularProgressIndicator(
                                strokeWidth = 2.dp,
                                modifier = Modifier
                                    .padding(end = 12.dp)
                                    .size(20.dp),
                            )
                        } else if (state.canRefresh && state.tracks.isNotEmpty()) {
                            TextButton(onClick = onRefresh) {
                                Icon(
                                    imageVector = Icons.Rounded.Refresh,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(stringResource(R.string.daylist_refresh_now))
                            }
                        }
                    },
                )
            },
        ) { innerPadding ->
            if (state.tracks.isEmpty()) {
                EmptyState(
                    title = stringResource(R.string.home_mix_empty_title),
                    subtitle = stringResource(R.string.home_mix_empty_subtitle),
                    icon = Icons.Rounded.AutoAwesome,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                )
            } else {
                val totalDurationMs = state.tracks.sumOf { it.durationMs }
                val metadata = listOfNotNull(
                    if (state.tracks.size == 1) "1 song" else "${state.tracks.size} songs",
                    totalDurationMs.takeIf { it > 0L }?.let(Formatters::formatDuration),
                ).joinToString(" • ")

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
                        item(key = "mix_offline_banner") {
                            OfflineStatusBanner(status = state.offlineStatus)
                        }
                        item(key = "mix_header") {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 20.dp, vertical = 12.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                DaylistMosaicArtwork(
                                    artworkUrls = state.artworkUrls,
                                    fallbackIconSize = 64.dp,
                                    shape = MaterialTheme.shapes.large,
                                    modifier = Modifier.size(216.dp),
                                )
                                Spacer(modifier = Modifier.height(18.dp))
                                Text(
                                    text = state.title,
                                    style = MaterialTheme.typography.headlineMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    textAlign = TextAlign.Center,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Surface(
                                    color = if (state.fromJellyfinServer) {
                                        MaterialTheme.colorScheme.primaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.secondaryContainer
                                    },
                                    shape = CircleShape,
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                                    ) {
                                        Icon(
                                            imageVector = if (state.fromJellyfinServer) {
                                                Icons.Rounded.CloudDone
                                            } else {
                                                Icons.Rounded.OfflineBolt
                                            },
                                            contentDescription = null,
                                            tint = if (state.fromJellyfinServer) {
                                                MaterialTheme.colorScheme.onPrimaryContainer
                                            } else {
                                                MaterialTheme.colorScheme.onSecondaryContainer
                                            },
                                            modifier = Modifier.size(14.dp),
                                        )
                                        Text(
                                            text = state.subtitle,
                                            style = MaterialTheme.typography.labelMedium,
                                            color = if (state.fromJellyfinServer) {
                                                MaterialTheme.colorScheme.onPrimaryContainer
                                            } else {
                                                MaterialTheme.colorScheme.onSecondaryContainer
                                            },
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = metadata,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center,
                                )
                                Spacer(modifier = Modifier.height(18.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    Button(
                                        onClick = onPlayAll,
                                        enabled = state.playableTracks.isNotEmpty(),
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(48.dp),
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
                                            .height(48.dp),
                                    ) {
                                        Icon(Icons.Rounded.Shuffle, contentDescription = null)
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(stringResource(R.string.action_shuffle))
                                    }
                                }
                            }
                        }

                        itemsIndexed(
                            items = state.tracks,
                            key = { _, track -> "mix_${state.mixType.routeKey}_${track.id}" },
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
