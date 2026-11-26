package com.musicplayer.feature.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material.icons.rounded.AutoAwesome
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.musicplayer.R
import com.musicplayer.core.common.Formatters
import com.musicplayer.core.designsystem.component.ArtworkCard
import com.musicplayer.core.designsystem.component.EmptyState
import com.musicplayer.core.designsystem.component.OfflineStatusBanner
import com.musicplayer.core.designsystem.component.SwipeDownToDismissContainer
import com.musicplayer.core.designsystem.component.TrackRow
import com.musicplayer.core.designsystem.component.isAtTopOfList
import com.musicplayer.core.model.Track

@Composable
fun DaylistRoute(
    onBackClick: () -> Unit,
    onAlbumClick: (String) -> Unit,
    onGoToTrackArtist: (Track) -> Unit,
    onAddToPlaylist: (Track) -> Unit,
    onToggleTrackDownload: (Track) -> Unit,
    viewModel: DaylistViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.checkSlotRefresh()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    DaylistScreen(
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
fun DaylistScreen(
    state: DaylistUiState,
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
            )
        },
    ) { innerPadding ->
        val daylist = state.daylist
        if (daylist == null || daylist.tracks.isEmpty()) {
            EmptyState(
                title = stringResource(R.string.home_empty_title),
                subtitle = stringResource(R.string.home_empty_subtitle),
                icon = Icons.Rounded.AutoAwesome,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            )
        } else {
            val periodLabel = stringResource(
                if (daylist.slot.period == DaylistPeriod.MORNING) {
                    R.string.daylist_period_morning
                } else {
                    R.string.daylist_period_evening
                },
            )
            val totalDurationMs = daylist.tracks.sumOf { it.durationMs }
            val metadata = listOfNotNull(
                if (daylist.tracks.size == 1) "1 song" else "${daylist.tracks.size} songs",
                stringResource(R.string.daylist_updates_at, daylist.slot.nextRefreshTimeLabel),
                totalDurationMs.takeIf { it > 0L }?.let(Formatters::formatDuration),
            ).joinToString(" • ")

            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentPadding = PaddingValues(bottom = 24.dp),
            ) {
                item(key = "daylist_offline_banner") {
                    OfflineStatusBanner(status = state.offlineStatus)
                }
                item(key = "daylist_header") {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        DaylistMosaicArtwork(
                            artworkUrls = daylist.artworkUrls,
                            fallbackIconSize = 64.dp,
                            shape = MaterialTheme.shapes.large,
                            modifier = Modifier.size(216.dp),
                        )
                        Spacer(modifier = Modifier.height(20.dp))
                        Text(
                            text = stringResource(R.string.daylist_title),
                            style = MaterialTheme.typography.headlineMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = periodLabel,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary,
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
                        Spacer(modifier = Modifier.height(20.dp))
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
                    items = daylist.tracks,
                    key = { _, track -> "daylist_${track.id}" },
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

@Composable
fun DaylistMosaicArtwork(
    artworkUrls: List<String>,
    modifier: Modifier = Modifier,
    shape: Shape = MaterialTheme.shapes.medium,
    fallbackIconSize: Dp = 32.dp,
) {
    if (artworkUrls.size >= 4) {
        Column(
            modifier = modifier
                .aspectRatio(1f)
                .clip(shape)
                .background(MaterialTheme.colorScheme.primaryContainer),
        ) {
            Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                ArtworkCard(
                    imageUrl = artworkUrls[0],
                    contentDescription = null,
                    shape = RectangleShape,
                    fallbackIcon = Icons.Rounded.AutoAwesome,
                    fallbackIconSize = fallbackIconSize / 2,
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                )
                ArtworkCard(
                    imageUrl = artworkUrls[1],
                    contentDescription = null,
                    shape = RectangleShape,
                    fallbackIcon = Icons.Rounded.AutoAwesome,
                    fallbackIconSize = fallbackIconSize / 2,
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                )
            }
            Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                ArtworkCard(
                    imageUrl = artworkUrls[2],
                    contentDescription = null,
                    shape = RectangleShape,
                    fallbackIcon = Icons.Rounded.AutoAwesome,
                    fallbackIconSize = fallbackIconSize / 2,
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                )
                ArtworkCard(
                    imageUrl = artworkUrls[3],
                    contentDescription = null,
                    shape = RectangleShape,
                    fallbackIcon = Icons.Rounded.AutoAwesome,
                    fallbackIconSize = fallbackIconSize / 2,
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                )
            }
        }
    } else if (artworkUrls.isNotEmpty()) {
        ArtworkCard(
            imageUrl = artworkUrls.first(),
            contentDescription = stringResource(R.string.daylist_title),
            shape = shape,
            fallbackIcon = Icons.Rounded.AutoAwesome,
            fallbackIconSize = fallbackIconSize,
            modifier = modifier,
        )
    } else {
        Box(
            modifier = modifier
                .aspectRatio(1f)
                .clip(shape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Rounded.AutoAwesome,
                contentDescription = stringResource(R.string.daylist_title),
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(fallbackIconSize),
            )
        }
    }
}
