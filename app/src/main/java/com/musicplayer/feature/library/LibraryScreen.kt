package com.musicplayer.feature.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.musicplayer.R
import com.musicplayer.core.designsystem.component.ArtworkCard
import com.musicplayer.core.designsystem.component.EmptyState
import com.musicplayer.core.designsystem.component.TrackRow
import com.musicplayer.core.model.Track

@Composable
fun LibraryContent(
    state: LibraryUiState,
    onFilterSelected: (LibraryFilter) -> Unit,
    onAlbumClick: (String) -> Unit,
    onArtistClick: (String) -> Unit,
    onPlaylistClick: (String) -> Unit,
    onTrackClick: (List<Track>, Int) -> Unit,
    onPlayNext: (Track) -> Unit,
    onAddToQueue: (Track) -> Unit,
    onToggleFavorite: (Track) -> Unit,
    onAddToPlaylist: (Track) -> Unit,
    onToggleTrackDownload: (Track) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            items(
                items = LibraryFilter.entries,
                key = { it.name },
            ) { filter ->
                val labelRes = when (filter) {
                    LibraryFilter.DOWNLOADED -> R.string.library_filter_downloaded
                    LibraryFilter.PLAYLISTS -> R.string.library_filter_playlists
                    LibraryFilter.ALBUMS -> R.string.library_filter_albums
                    LibraryFilter.ARTISTS -> R.string.library_filter_artists
                    LibraryFilter.SONGS -> R.string.library_filter_songs
                }
                FilterChip(
                    selected = state.selectedFilter == filter,
                    onClick = { onFilterSelected(filter) },
                    label = { Text(stringResource(labelRes)) },
                    leadingIcon = if (filter == LibraryFilter.DOWNLOADED) {
                        {
                            Icon(
                                imageVector = Icons.Rounded.DownloadDone,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    } else {
                        null
                    },
                )
            }
        }

        when (state.selectedFilter) {
            LibraryFilter.DOWNLOADED -> {
                if (state.downloadedTracks.isEmpty()) {
                    EmptyState(
                        title = stringResource(R.string.library_downloaded_empty_title),
                        subtitle = stringResource(R.string.library_downloaded_empty_subtitle),
                        icon = Icons.Rounded.DownloadDone,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = 140.dp),
                    ) {
                        itemsIndexed(
                            items = state.downloadedTracks,
                            key = { _, track -> "dl_${track.id}" },
                            contentType = { _, _ -> "track" },
                        ) { index, track ->
                            TrackRow(
                                track = track,
                                isPlaying = track.id == state.activeTrackId,
                                onClick = { onTrackClick(state.downloadedTracks, index) },
                                onPlayNext = { onPlayNext(track) },
                                onAddToQueue = { onAddToQueue(track) },
                                onToggleFavorite = { onToggleFavorite(track) },
                                onAddToPlaylist = { onAddToPlaylist(track) },
                                onToggleDownload = { onToggleTrackDownload(track) },
                            )
                        }
                    }
                }
            }

            LibraryFilter.PLAYLISTS -> {
                if (state.playlists.isEmpty()) {
                    EmptyState(
                        title = stringResource(R.string.library_empty_title),
                        subtitle = stringResource(R.string.library_playlists_empty_subtitle),
                        icon = Icons.AutoMirrored.Rounded.QueueMusic,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = 140.dp),
                    ) {
                        items(
                            items = state.playlists,
                            key = { it.id },
                            contentType = { "collection" },
                        ) { playlist ->
                            LibraryCollectionRow(
                                title = playlist.name,
                                subtitle = "${playlist.trackCount} songs",
                                imageUrl = playlist.artworkUrl,
                                isCircular = false,
                                onClick = { onPlaylistClick(playlist.id) },
                            )
                        }
                    }
                }
            }

            LibraryFilter.ALBUMS -> {
                if (state.albums.isEmpty()) {
                    EmptyState(
                        title = stringResource(R.string.library_empty_title),
                        subtitle = stringResource(R.string.library_empty_subtitle),
                        icon = Icons.Rounded.Album,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = 140.dp),
                    ) {
                        items(
                            items = state.albums,
                            key = { it.id },
                            contentType = { "collection" },
                        ) { album ->
                            val subtitle = listOfNotNull(
                                album.artistName,
                                album.year?.toString(),
                            ).joinToString(" • ")
                            LibraryCollectionRow(
                                title = album.title,
                                subtitle = subtitle,
                                imageUrl = album.artworkUrl,
                                isCircular = false,
                                onClick = { onAlbumClick(album.id) },
                            )
                        }
                    }
                }
            }

            LibraryFilter.ARTISTS -> {
                if (state.artists.isEmpty()) {
                    EmptyState(
                        title = stringResource(R.string.library_empty_title),
                        subtitle = stringResource(R.string.library_empty_subtitle),
                        icon = Icons.Rounded.Person,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = 140.dp),
                    ) {
                        items(
                            items = state.artists,
                            key = { it.id },
                            contentType = { "artist" },
                        ) { artist ->
                            LibraryCollectionRow(
                                title = artist.name,
                                subtitle = "${artist.albumCount} albums",
                                imageUrl = artist.artworkUrl,
                                isCircular = true,
                                onClick = { onArtistClick(artist.id) },
                            )
                        }
                    }
                }
            }

            LibraryFilter.SONGS -> {
                if (state.tracks.isEmpty()) {
                    EmptyState(
                        title = stringResource(R.string.library_empty_title),
                        subtitle = stringResource(R.string.library_empty_subtitle),
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = 140.dp),
                    ) {
                        itemsIndexed(
                            items = state.tracks,
                            key = { _, track -> track.id },
                            contentType = { _, _ -> "track" },
                        ) { index, track ->
                            TrackRow(
                                track = track,
                                isPlaying = track.id == state.activeTrackId,
                                onClick = { onTrackClick(state.tracks, index) },
                                onPlayNext = { onPlayNext(track) },
                                onAddToQueue = { onAddToQueue(track) },
                                onToggleFavorite = { onToggleFavorite(track) },
                                onAddToPlaylist = { onAddToPlaylist(track) },
                                onToggleDownload = { onToggleTrackDownload(track) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LibraryCollectionRow(
    title: String,
    subtitle: String,
    imageUrl: String?,
    isCircular: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ArtworkCard(
            imageUrl = imageUrl,
            contentDescription = title,
            shape = if (isCircular) CircleShape else MaterialTheme.shapes.small,
            fallbackIcon = if (isCircular) Icons.Rounded.Person else Icons.Rounded.Album,
            fallbackIconSize = 24.dp,
            modifier = Modifier.size(56.dp),
        )
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
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
