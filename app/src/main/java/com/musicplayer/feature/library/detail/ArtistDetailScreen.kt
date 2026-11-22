package com.musicplayer.feature.library.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import com.musicplayer.core.designsystem.component.AlbumCarouselCard
import com.musicplayer.core.designsystem.component.CollectionDetailHeader
import com.musicplayer.core.designsystem.component.EmptyState
import com.musicplayer.core.designsystem.component.SectionHeader
import com.musicplayer.core.designsystem.component.TrackRow

@Composable
fun ArtistDetailRoute(
    onBackClick: () -> Unit,
    onAlbumClick: (String) -> Unit,
    viewModel: ArtistDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    ArtistDetailScreen(
        state = state,
        onBackClick = onBackClick,
        onAlbumClick = onAlbumClick,
        onPlayAll = viewModel::onPlayAll,
        onShuffleAll = viewModel::onShuffleAll,
        onTrackClick = viewModel::onTrackClick,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArtistDetailScreen(
    state: ArtistDetailUiState,
    onBackClick: () -> Unit,
    onAlbumClick: (String) -> Unit,
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
        val artist = state.artist
        if (artist == null && state.albums.isEmpty() && state.tracks.isEmpty()) {
            EmptyState(
                title = stringResource(R.string.detail_not_found_title),
                subtitle = stringResource(R.string.detail_not_found_subtitle),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentPadding = PaddingValues(bottom = 24.dp),
            ) {
                item(key = "artist_header") {
                    CollectionDetailHeader(
                        title = artist?.name ?: state.tracks.firstOrNull()?.artistName.orEmpty(),
                        subtitle = stringResource(R.string.library_filter_artists),
                        metadata = "${state.albums.size} albums • ${state.tracks.size} songs",
                        artworkUrl = artist?.artworkUrl,
                        isCircularArtwork = true,
                        canPlay = state.tracks.isNotEmpty(),
                        onPlayClick = onPlayAll,
                        onShuffleClick = onShuffleAll,
                    )
                }

                if (state.albums.isNotEmpty()) {
                    item(key = "artist_albums_header") {
                        SectionHeader(title = stringResource(R.string.library_filter_albums))
                    }
                    item(key = "artist_albums_row") {
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            items(items = state.albums, key = { it.id }) { album ->
                                AlbumCarouselCard(
                                    album = album,
                                    onClick = { onAlbumClick(album.id) },
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                    }
                }

                if (state.tracks.isNotEmpty()) {
                    item(key = "artist_tracks_header") {
                        SectionHeader(title = stringResource(R.string.library_filter_songs))
                    }
                    itemsIndexed(
                        items = state.tracks,
                        key = { _, track -> track.id },
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
}
