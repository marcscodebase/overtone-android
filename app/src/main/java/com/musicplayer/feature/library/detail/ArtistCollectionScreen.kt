package com.musicplayer.feature.library.detail

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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material3.AssistChip
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
import com.musicplayer.core.designsystem.component.OfflineStatusBanner
import com.musicplayer.core.designsystem.component.SwipeDownToDismissContainer
import com.musicplayer.core.designsystem.component.TrackRow
import com.musicplayer.core.designsystem.component.isAtTopOfList
import com.musicplayer.core.model.Album
import com.musicplayer.core.model.Track

@Composable
fun ArtistCollectionRoute(
    onBackClick: () -> Unit,
    onAlbumClick: (String) -> Unit,
    onGoToTrackArtist: (Track) -> Unit,
    onAddToPlaylist: (Track) -> Unit,
    onToggleTrackDownload: (Track) -> Unit,
    viewModel: ArtistCollectionViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    ArtistCollectionScreen(
        state = state,
        onBackClick = onBackClick,
        onAlbumClick = onAlbumClick,
        onToggleChronologicalOrder = viewModel::onToggleChronologicalOrder,
        onPlayAll = viewModel::onPlayAll,
        onShuffleAll = viewModel::onShuffleAll,
        onTrackClick = viewModel::onTrackClick,
        onAddToQueue = viewModel::onAddToQueue,
        onGoToTrackArtist = onGoToTrackArtist,
        onAddToPlaylist = onAddToPlaylist,
        onToggleTrackDownload = onToggleTrackDownload,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArtistCollectionScreen(
    state: ArtistCollectionUiState,
    onBackClick: () -> Unit,
    onAlbumClick: (String) -> Unit,
    onToggleChronologicalOrder: () -> Unit,
    onPlayAll: () -> Unit,
    onShuffleAll: () -> Unit,
    onTrackClick: (Track) -> Unit,
    onAddToQueue: (Track) -> Unit,
    onGoToTrackArtist: (Track) -> Unit,
    onAddToPlaylist: (Track) -> Unit,
    onToggleTrackDownload: (Track) -> Unit,
    modifier: Modifier = Modifier,
) {
    val titleRes = when (state.section) {
        ArtistCollectionSection.FAVORITES -> R.string.artist_liked_songs
        ArtistCollectionSection.ALL_SONGS -> R.string.artist_all_songs
        ArtistCollectionSection.ALL_ALBUMS -> R.string.artist_all_albums
    }
    val supportsChronologicalSort = state.section != ArtistCollectionSection.FAVORITES
    val sortLabel = stringResource(
        if (state.chronologicalOrder == ChronologicalOrder.NEWEST_FIRST) {
            R.string.sort_newest_first
        } else {
            R.string.sort_oldest_first
        },
    )

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
                title = {
                    Column {
                        Text(
                            text = stringResource(titleRes),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        state.artist?.name?.takeIf { it.isNotBlank() }?.let { artistName ->
                            Text(
                                text = artistName,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
                actions = {
                    if (supportsChronologicalSort) {
                        IconButton(onClick = onToggleChronologicalOrder) {
                            Icon(
                                imageVector = Icons.Rounded.SwapVert,
                                contentDescription = stringResource(R.string.sort_toggle_chronological),
                            )
                        }
                    }
                },
            )
        },
    ) { innerPadding ->
        when (state.section) {
            ArtistCollectionSection.ALL_ALBUMS -> {
                if (state.albums.isEmpty()) {
                    EmptyState(
                        title = stringResource(R.string.library_empty_title),
                        subtitle = stringResource(R.string.detail_not_found_subtitle),
                        icon = Icons.Rounded.Album,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding),
                    )
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding),
                        contentPadding = PaddingValues(bottom = 24.dp),
                    ) {
                        item(key = "albums_offline_banner") {
                            OfflineStatusBanner(status = state.offlineStatus)
                        }
                        item(key = "albums_sort_bar") {
                            ChronologicalSummaryBar(
                                countLabel = if (state.albums.size == 1) "1 album" else "${state.albums.size} albums",
                                sortLabel = sortLabel,
                                onToggleSort = onToggleChronologicalOrder,
                            )
                        }
                        items(
                            items = state.albums,
                            key = { it.id },
                        ) { album ->
                            ArtistAlbumListRow(
                                album = album,
                                onClick = { onAlbumClick(album.id) },
                            )
                        }
                    }
                }
            }

            ArtistCollectionSection.FAVORITES,
            ArtistCollectionSection.ALL_SONGS,
            -> {
                if (state.tracks.isEmpty()) {
                    EmptyState(
                        title = stringResource(
                            if (state.section == ArtistCollectionSection.FAVORITES) {
                                R.string.artist_liked_songs_empty_title
                            } else {
                                R.string.library_empty_title
                            },
                        ),
                        subtitle = stringResource(
                            if (state.section == ArtistCollectionSection.FAVORITES) {
                                R.string.artist_liked_songs_empty_subtitle
                            } else {
                                R.string.detail_not_found_subtitle
                            },
                        ),
                        icon = if (state.section == ArtistCollectionSection.FAVORITES) {
                            Icons.Rounded.FavoriteBorder
                        } else {
                            Icons.Rounded.MusicNote
                        },
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding),
                    )
                } else {
                    val unknownYearLabel = stringResource(R.string.artist_unknown_year)
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding),
                        contentPadding = PaddingValues(bottom = 24.dp),
                    ) {
                        item(key = "tracks_offline_banner") {
                            OfflineStatusBanner(status = state.offlineStatus)
                        }
                        item(key = "tracks_action_header") {
                            TrackCollectionControls(
                                countLabel = if (state.tracks.size == 1) "1 song" else "${state.tracks.size} songs",
                                canPlay = state.playableTracks.isNotEmpty(),
                                sortLabel = if (supportsChronologicalSort) sortLabel else null,
                                onToggleSort = if (supportsChronologicalSort) onToggleChronologicalOrder else null,
                                onPlayAll = onPlayAll,
                                onShuffleAll = onShuffleAll,
                            )
                        }

                        if (state.section == ArtistCollectionSection.ALL_SONGS) {
                            var lastRenderedYearKey: String? = null
                            state.tracks.forEach { track ->
                                val year = state.trackYearsById[track.id]
                                val yearKey = year?.toString() ?: "unknown"
                                if (yearKey != lastRenderedYearKey) {
                                    lastRenderedYearKey = yearKey
                                    val headerLabel = year?.toString() ?: unknownYearLabel
                                    item(key = "year_header_$yearKey") {
                                        Text(
                                            text = headerLabel,
                                            style = MaterialTheme.typography.labelLarge,
                                            color = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 4.dp),
                                        )
                                    }
                                }
                                item(key = track.id) {
                                    TrackRow(
                                        track = track,
                                        isPlaying = track.id == state.activeTrackId,
                                        enabled = !state.isOffline || track.isDownloaded,
                                        subtitleOverride = track.albumName,
                                        onClick = { onTrackClick(track) },
                                        onAddToQueue = { onAddToQueue(track) },
                                        onAddToPlaylist = { onAddToPlaylist(track) },
                                        onToggleDownload = { onToggleTrackDownload(track) },
                                        onGoToAlbum = track.albumId?.let { id -> { onAlbumClick(id) } },
                                        onGoToArtist = if (track.resolvedArtists.size > 1) {
                                            { onGoToTrackArtist(track) }
                                        } else {
                                            null
                                        },
                                    )
                                }
                            }
                        } else {
                            items(
                                items = state.tracks,
                                key = { it.id },
                            ) { track ->
                                TrackRow(
                                    track = track,
                                    isPlaying = track.id == state.activeTrackId,
                                    enabled = !state.isOffline || track.isDownloaded,
                                    subtitleOverride = track.albumName,
                                    onClick = { onTrackClick(track) },
                                    onAddToQueue = { onAddToQueue(track) },
                                    onAddToPlaylist = { onAddToPlaylist(track) },
                                    onToggleDownload = { onToggleTrackDownload(track) },
                                    onGoToAlbum = track.albumId?.let { id -> { onAlbumClick(id) } },
                                    onGoToArtist = if (track.resolvedArtists.size > 1) {
                                        { onGoToTrackArtist(track) }
                                    } else {
                                        null
                                    },
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
private fun ChronologicalSummaryBar(
    countLabel: String,
    sortLabel: String,
    onToggleSort: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = countLabel,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        AssistChip(
            onClick = onToggleSort,
            label = { Text(sortLabel) },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Rounded.SwapVert,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
            },
        )
    }
}

@Composable
private fun TrackCollectionControls(
    countLabel: String,
    canPlay: Boolean,
    sortLabel: String?,
    onToggleSort: (() -> Unit)?,
    onPlayAll: () -> Unit,
    onShuffleAll: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = countLabel,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (sortLabel != null && onToggleSort != null) {
                AssistChip(
                    onClick = onToggleSort,
                    label = { Text(sortLabel) },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Rounded.SwapVert,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                    },
                )
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Button(
                onClick = onPlayAll,
                enabled = canPlay,
                modifier = Modifier
                    .weight(1f)
                    .height(44.dp),
            ) {
                Icon(Icons.Rounded.PlayArrow, contentDescription = null)
                Spacer(modifier = Modifier.width(6.dp))
                Text(stringResource(R.string.action_play))
            }
            FilledTonalButton(
                onClick = onShuffleAll,
                enabled = canPlay,
                modifier = Modifier
                    .weight(1f)
                    .height(44.dp),
            ) {
                Icon(Icons.Rounded.Shuffle, contentDescription = null)
                Spacer(modifier = Modifier.width(6.dp))
                Text(stringResource(R.string.action_shuffle))
            }
        }
    }
}

@Composable
private fun ArtistAlbumListRow(
    album: Album,
    onClick: () -> Unit,
) {
    val subtitle = listOfNotNull(
        album.year?.takeIf { it > 0 }?.toString(),
        album.trackCount.takeIf { it > 0 }?.let { count ->
            if (count == 1) "1 song" else "$count songs"
        },
    ).joinToString(" • ").ifBlank { album.artistName }

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
            fallbackIconSize = 26.dp,
            modifier = Modifier.size(64.dp),
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
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(
            imageVector = Icons.Rounded.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
