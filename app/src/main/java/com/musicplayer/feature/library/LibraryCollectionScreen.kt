package com.musicplayer.feature.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.Favorite
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.musicplayer.R
import com.musicplayer.core.common.Formatters
import com.musicplayer.core.designsystem.component.EmptyState
import com.musicplayer.core.designsystem.component.FastScrollMath
import com.musicplayer.core.designsystem.component.LazyColumnFastScroller
import com.musicplayer.core.designsystem.component.OfflineStatusBanner
import com.musicplayer.core.designsystem.component.SwipeDownToDismissContainer
import com.musicplayer.core.designsystem.component.TrackRow
import com.musicplayer.core.designsystem.component.isAtTopOfList
import com.musicplayer.core.model.Track

@Composable
fun LibraryCollectionRoute(
    onBackClick: () -> Unit,
    onAlbumClick: (String) -> Unit,
    onGoToTrackArtist: (Track) -> Unit,
    onAddToPlaylist: (Track) -> Unit,
    onToggleTrackDownload: (Track) -> Unit,
    viewModel: LibraryCollectionViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LibraryCollectionScreen(
        state = state,
        onBackClick = onBackClick,
        onToggleSort = viewModel::onToggleSort,
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
fun LibraryCollectionScreen(
    state: LibraryCollectionUiState,
    onBackClick: () -> Unit,
    onToggleSort: () -> Unit,
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
    val isFavorites = state.section == LibraryCollectionSection.FAVORITES
    val titleRes = if (isFavorites) {
        R.string.library_collection_favorite_songs
    } else {
        R.string.library_collection_downloaded_songs
    }
    val icon = if (isFavorites) Icons.Rounded.Favorite else Icons.Rounded.DownloadDone

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
                        if (state.tracks.isNotEmpty()) {
                            IconButton(onClick = onToggleSort) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Rounded.Sort,
                                    contentDescription = stringResource(R.string.library_sort),
                                )
                            }
                        }
                    },
                )
            },
        ) { innerPadding ->
            if (state.tracks.isEmpty()) {
                EmptyState(
                    title = stringResource(
                        if (isFavorites) R.string.library_favorites_empty_title else R.string.library_downloaded_empty_title,
                    ),
                    subtitle = stringResource(
                        if (isFavorites) R.string.library_favorites_empty_subtitle else R.string.library_downloaded_empty_subtitle,
                    ),
                    icon = icon,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                )
            } else {
                val totalDurationMs = state.tracks.sumOf { it.durationMs }
                val sortLabel = stringResource(
                    when (state.sort) {
                        CollectionTrackSort.ALPHABETICAL -> R.string.library_sort_alphabetical
                        CollectionTrackSort.RECENTLY_PLAYED -> R.string.library_sort_recently_played
                    },
                )
                val metadata = listOfNotNull(
                    if (state.tracks.size == 1) "1 song" else "${state.tracks.size} songs",
                    totalDurationMs.takeIf { it > 0L }?.let(Formatters::formatDuration),
                    sortLabel,
                ).joinToString(" • ")

                LazyColumnFastScroller(
                    listState = listState,
                    itemCount = state.tracks.size,
                    headerItemCount = 2,
                    bubbleLabelForIndex = { idx ->
                        val track = state.tracks.getOrNull(idx) ?: return@LazyColumnFastScroller null
                        when (state.sort) {
                            CollectionTrackSort.ALPHABETICAL -> FastScrollMath.sectionLetter(track.title)
                            CollectionTrackSort.RECENTLY_PLAYED -> "#${idx + 1}"
                        }
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
                        item(key = "collection_header") {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 20.dp, vertical = 12.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(160.dp)
                                        .clip(MaterialTheme.shapes.large)
                                        .background(
                                            if (isFavorites) {
                                                MaterialTheme.colorScheme.primaryContainer
                                            } else {
                                                MaterialTheme.colorScheme.secondaryContainer
                                            },
                                        ),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        imageVector = icon,
                                        contentDescription = null,
                                        tint = if (isFavorites) {
                                            MaterialTheme.colorScheme.onPrimaryContainer
                                        } else {
                                            MaterialTheme.colorScheme.onSecondaryContainer
                                        },
                                        modifier = Modifier.size(64.dp),
                                    )
                                }
                                Spacer(modifier = Modifier.height(16.dp))
                                Text(
                                    text = stringResource(titleRes),
                                    style = MaterialTheme.typography.headlineMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    textAlign = TextAlign.Center,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Spacer(modifier = Modifier.height(4.dp))
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
                            key = { _, track -> "col_${state.section.routeKey}_${track.id}" },
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
