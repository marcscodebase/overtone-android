package com.musicplayer.feature.library.detail

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.musicplayer.R
import com.musicplayer.core.common.Formatters
import com.musicplayer.core.designsystem.component.CollectionDetailHeader
import com.musicplayer.core.designsystem.component.EmptyState
import com.musicplayer.core.designsystem.component.OfflineStatusBanner
import com.musicplayer.core.designsystem.component.TrackRow
import com.musicplayer.core.model.Track

@Composable
fun AlbumDetailRoute(
    onBackClick: () -> Unit,
    onAddToPlaylist: (Track) -> Unit,
    onToggleTrackDownload: (Track) -> Unit,
    onDownloadAlbum: (albumId: String, title: String) -> Unit,
    onRemoveAlbumDownload: (albumId: String, title: String) -> Unit,
    viewModel: AlbumDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    AlbumDetailScreen(
        state = state,
        onBackClick = onBackClick,
        onPlayAll = viewModel::onPlayAll,
        onShuffleAll = viewModel::onShuffleAll,
        onTrackClick = viewModel::onTrackClick,
        onPlayNext = viewModel::onPlayNext,
        onAddToQueue = viewModel::onAddToQueue,
        onToggleAlbumFavorite = viewModel::onToggleAlbumFavorite,
        onToggleTrackFavorite = viewModel::onToggleTrackFavorite,
        onAddToPlaylist = onAddToPlaylist,
        onToggleTrackDownload = onToggleTrackDownload,
        onDownloadAlbum = onDownloadAlbum,
        onRemoveAlbumDownload = onRemoveAlbumDownload,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlbumDetailScreen(
    state: AlbumDetailUiState,
    onBackClick: () -> Unit,
    onPlayAll: () -> Unit,
    onShuffleAll: () -> Unit,
    onTrackClick: (Int) -> Unit,
    onPlayNext: (Track) -> Unit,
    onAddToQueue: (Track) -> Unit,
    onToggleAlbumFavorite: () -> Unit,
    onToggleTrackFavorite: (Track) -> Unit,
    onAddToPlaylist: (Track) -> Unit,
    onToggleTrackDownload: (Track) -> Unit,
    onDownloadAlbum: (albumId: String, title: String) -> Unit,
    onRemoveAlbumDownload: (albumId: String, title: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
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
                    state.album?.let { album ->
                        val allTracksDownloaded = state.tracks.isNotEmpty() && state.tracks.all { it.isDownloaded }
                        IconButton(
                            onClick = {
                                if (state.isAlbumPinnedDownloaded || allTracksDownloaded) {
                                    onRemoveAlbumDownload(album.id, album.title)
                                } else {
                                    onDownloadAlbum(album.id, album.title)
                                }
                            },
                        ) {
                            when {
                                state.isDownloadingAny -> {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(20.dp),
                                        strokeWidth = 2.dp,
                                    )
                                }

                                state.isAlbumPinnedDownloaded || allTracksDownloaded -> {
                                    Icon(
                                        imageVector = Icons.Rounded.DownloadDone,
                                        contentDescription = stringResource(R.string.download_remove_album),
                                        tint = MaterialTheme.colorScheme.primary,
                                    )
                                }

                                else -> {
                                    Icon(
                                        imageVector = Icons.Rounded.Download,
                                        contentDescription = stringResource(R.string.download_album),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                        IconButton(onClick = onToggleAlbumFavorite) {
                            Icon(
                                imageVector = if (album.isFavorite) {
                                    Icons.Rounded.Favorite
                                } else {
                                    Icons.Rounded.FavoriteBorder
                                },
                                contentDescription = stringResource(
                                    if (album.isFavorite) R.string.favorite_remove else R.string.favorite_add,
                                ),
                                tint = if (album.isFavorite) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        }
                    }
                },
            )
        },
    ) { innerPadding ->
        val album = state.album
        if (album == null && state.tracks.isEmpty()) {
            EmptyState(
                title = stringResource(R.string.detail_not_found_title),
                subtitle = stringResource(R.string.detail_not_found_subtitle),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            )
        } else {
            val totalDurationMs = state.tracks.sumOf { it.durationMs }
            val metadata = listOfNotNull(
                album?.year?.toString(),
                "${state.tracks.size} songs",
                totalDurationMs.takeIf { it > 0L }?.let(Formatters::formatDuration),
            ).joinToString(" • ")

            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentPadding = PaddingValues(bottom = 24.dp),
            ) {
                item(key = "album_offline_banner") {
                    OfflineStatusBanner(status = state.offlineStatus)
                }
                item(key = "album_header") {
                    CollectionDetailHeader(
                        title = album?.title ?: state.tracks.firstOrNull()?.albumName.orEmpty(),
                        subtitle = album?.artistName ?: state.tracks.firstOrNull()?.artistName.orEmpty(),
                        metadata = metadata,
                        artworkUrl = album?.artworkUrl ?: state.tracks.firstOrNull()?.artworkUrl,
                        isCircularArtwork = false,
                        canPlay = state.playableTracks.isNotEmpty(),
                        onPlayClick = onPlayAll,
                        onShuffleClick = onShuffleAll,
                    )
                }
                itemsIndexed(
                    items = state.tracks,
                    key = { _, track -> track.id },
                ) { index, track ->
                    TrackRow(
                        track = track,
                        isPlaying = track.id == state.activeTrackId,
                        enabled = !state.isOffline || track.isDownloaded,
                        showArtwork = false,
                        trackNumberOverride = track.trackNumber ?: (index + 1),
                        onClick = { onTrackClick(index) },
                        onPlayNext = { onPlayNext(track) },
                        onAddToQueue = { onAddToQueue(track) },
                        onToggleFavorite = { onToggleTrackFavorite(track) },
                        onAddToPlaylist = { onAddToPlaylist(track) },
                        onToggleDownload = { onToggleTrackDownload(track) },
                    )
                }
            }
        }
    }
}
