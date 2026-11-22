package com.musicplayer.feature.library.detail

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import com.musicplayer.core.designsystem.component.TrackRow

@Composable
fun PlaylistDetailRoute(
    onBackClick: () -> Unit,
    viewModel: PlaylistDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    PlaylistDetailScreen(
        state = state,
        onBackClick = onBackClick,
        onPlayAll = viewModel::onPlayAll,
        onShuffleAll = viewModel::onShuffleAll,
        onTrackClick = viewModel::onTrackClick,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistDetailScreen(
    state: PlaylistDetailUiState,
    onBackClick: () -> Unit,
    onPlayAll: () -> Unit,
    onShuffleAll: () -> Unit,
    onTrackClick: (Int) -> Unit,
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
            )
        },
    ) { innerPadding ->
        val totalDurationMs = state.tracks.sumOf { it.durationMs }
        val metadata = listOfNotNull(
            "${state.tracks.size} songs",
            totalDurationMs.takeIf { it > 0L }?.let(Formatters::formatDuration),
        ).joinToString(" • ")

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            if (state.isSyncing) {
                item(key = "playlist_syncing") {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
            item(key = "playlist_header") {
                CollectionDetailHeader(
                    title = state.playlist?.name ?: stringResource(R.string.library_filter_playlists),
                    subtitle = stringResource(R.string.playlist_label),
                    metadata = metadata,
                    artworkUrl = state.playlist?.artworkUrl,
                    isCircularArtwork = false,
                    canPlay = state.tracks.isNotEmpty(),
                    onPlayClick = onPlayAll,
                    onShuffleClick = onShuffleAll,
                )
            }
            if (!state.isSyncing && state.tracks.isEmpty()) {
                item(key = "playlist_empty") {
                    EmptyState(
                        title = stringResource(R.string.playlist_empty_title),
                        subtitle = stringResource(R.string.playlist_empty_subtitle),
                    )
                }
            } else {
                itemsIndexed(
                    items = state.tracks,
                    key = { index, track -> "${track.id}_$index" },
                ) { index, track ->
                    TrackRow(
                        track = track,
                        isPlaying = track.id == state.activeTrackId,
                        onClick = { onTrackClick(index) },
                    )
                }
            }
        }
    }
}
