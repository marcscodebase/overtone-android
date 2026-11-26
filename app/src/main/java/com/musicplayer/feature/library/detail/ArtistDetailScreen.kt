package com.musicplayer.feature.library.detail

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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.MusicNote
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.musicplayer.R
import com.musicplayer.core.designsystem.component.AlbumCarouselCard
import com.musicplayer.core.designsystem.component.CollectionDetailHeader
import com.musicplayer.core.designsystem.component.EmptyState
import com.musicplayer.core.designsystem.component.OfflineStatusBanner
import com.musicplayer.core.designsystem.component.SectionHeader
import com.musicplayer.core.designsystem.component.TrackRow
import com.musicplayer.core.model.Track

@Composable
fun ArtistDetailRoute(
    onBackClick: () -> Unit,
    onAlbumClick: (String) -> Unit,
    onOpenArtistCollection: (artistId: String, section: ArtistCollectionSection) -> Unit,
    onGoToTrackArtist: (Track) -> Unit,
    onAddToPlaylist: (Track) -> Unit,
    onToggleTrackDownload: (Track) -> Unit,
    viewModel: ArtistDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    ArtistDetailScreen(
        state = state,
        onBackClick = onBackClick,
        onAlbumClick = onAlbumClick,
        onViewFavorites = {
            onOpenArtistCollection(state.artistId, ArtistCollectionSection.FAVORITES)
        },
        onViewAllSongs = {
            onOpenArtistCollection(state.artistId, ArtistCollectionSection.ALL_SONGS)
        },
        onViewAllAlbums = {
            onOpenArtistCollection(state.artistId, ArtistCollectionSection.ALL_ALBUMS)
        },
        onPlayAll = viewModel::onPlayAll,
        onShuffleAll = viewModel::onShuffleAll,
        onTopTrackClick = viewModel::onTopTrackClick,
        onToggleTopTracksExpanded = viewModel::onToggleTopTracksExpanded,
        onAddToQueue = viewModel::onAddToQueue,
        onToggleArtistFavorite = viewModel::onToggleArtistFavorite,
        onGoToTrackArtist = onGoToTrackArtist,
        onAddToPlaylist = onAddToPlaylist,
        onToggleTrackDownload = onToggleTrackDownload,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArtistDetailScreen(
    state: ArtistDetailUiState,
    onBackClick: () -> Unit,
    onAlbumClick: (String) -> Unit,
    onViewFavorites: () -> Unit,
    onViewAllSongs: () -> Unit,
    onViewAllAlbums: () -> Unit,
    onPlayAll: () -> Unit,
    onShuffleAll: () -> Unit,
    onTopTrackClick: (Track) -> Unit,
    onToggleTopTracksExpanded: () -> Unit,
    onAddToQueue: (Track) -> Unit,
    onToggleArtistFavorite: () -> Unit,
    onGoToTrackArtist: (Track) -> Unit,
    onAddToPlaylist: (Track) -> Unit,
    onToggleTrackDownload: (Track) -> Unit,
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
                    state.artist?.let { artist ->
                        IconButton(onClick = onToggleArtistFavorite) {
                            Icon(
                                imageVector = if (artist.isFavorite) {
                                    Icons.Rounded.Favorite
                                } else {
                                    Icons.Rounded.FavoriteBorder
                                },
                                contentDescription = stringResource(
                                    if (artist.isFavorite) R.string.favorite_remove else R.string.favorite_add,
                                ),
                                tint = if (artist.isFavorite) {
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
                item(key = "artist_offline_banner") {
                    OfflineStatusBanner(status = state.offlineStatus)
                }
                item(key = "artist_header") {
                    CollectionDetailHeader(
                        title = artist?.name ?: state.tracks.firstOrNull()?.artistName.orEmpty(),
                        subtitle = stringResource(R.string.library_filter_artists),
                        metadata = "${state.albums.size} albums • ${state.tracks.size} songs",
                        artworkUrl = artist?.artworkUrl,
                        isCircularArtwork = true,
                        canPlay = state.playableTracks.isNotEmpty(),
                        onPlayClick = onPlayAll,
                        onShuffleClick = onShuffleAll,
                    )
                }

                if (state.favoriteTracks.isNotEmpty()) {
                    item(key = "artist_liked_songs_row") {
                        ArtistLikedSongsRow(
                            likedCount = state.favoriteTracks.size,
                            onClick = onViewFavorites,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                        )
                    }
                }

                if (state.displayedTopTracks.isNotEmpty()) {
                    item(key = "artist_top_tracks_header") {
                        Spacer(modifier = Modifier.height(6.dp))
                        SectionHeader(title = stringResource(R.string.artist_most_played))
                    }
                    itemsIndexed(
                        items = state.displayedTopTracks,
                        key = { _, track -> "top_${track.id}" },
                    ) { index, track ->
                        val subtitle = if (track.playCount > 0) {
                            val playLabel = if (track.playCount == 1) "1 play" else "${track.playCount} plays"
                            "$playLabel • ${track.albumName}"
                        } else {
                            track.albumName
                        }
                        TrackRow(
                            track = track,
                            isPlaying = track.id == state.activeTrackId,
                            enabled = !state.isOffline || track.isDownloaded,
                            leadingRank = index + 1,
                            subtitleOverride = subtitle,
                            onClick = { onTopTrackClick(track) },
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

                    if (state.canExpandTopTracks) {
                        item(key = "artist_top_tracks_expand") {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 2.dp),
                                contentAlignment = Alignment.CenterStart,
                            ) {
                                TextButton(onClick = onToggleTopTracksExpanded) {
                                    Icon(
                                        imageVector = if (state.isTopTracksExpanded) {
                                            Icons.Rounded.ExpandLess
                                        } else {
                                            Icons.Rounded.ExpandMore
                                        },
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp),
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = stringResource(
                                            if (state.isTopTracksExpanded) {
                                                R.string.artist_show_less_songs
                                            } else {
                                                R.string.artist_show_more_songs
                                            },
                                        ),
                                    )
                                }
                            }
                        }
                    }

                    item(key = "artist_view_all_songs") {
                        Spacer(modifier = Modifier.height(6.dp))
                        FilledTonalButton(
                            onClick = onViewAllSongs,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.MusicNote,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "${stringResource(R.string.artist_view_all_songs)} (${state.tracks.size})",
                                modifier = Modifier.weight(1f),
                            )
                            Icon(
                                imageVector = Icons.Rounded.ChevronRight,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                        Spacer(modifier = Modifier.height(14.dp))
                    }
                }

                if (state.albums.isNotEmpty()) {
                    item(key = "artist_albums_header") {
                        SectionHeader(
                            title = stringResource(R.string.library_filter_albums),
                            actionLabel = stringResource(R.string.action_view_all),
                            onActionClick = onViewAllAlbums,
                        )
                    }
                    item(key = "artist_albums_row") {
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            items(items = state.albums, key = { it.id }) { album ->
                                val albumSubtitle = listOfNotNull(
                                    album.year?.takeIf { it > 0 }?.toString(),
                                    album.trackCount.takeIf { it > 0 }?.let { count ->
                                        if (count == 1) "1 song" else "$count songs"
                                    },
                                ).joinToString(" • ").ifBlank { album.artistName }

                                AlbumCarouselCard(
                                    album = album,
                                    subtitleOverride = albumSubtitle,
                                    onClick = { onAlbumClick(album.id) },
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun ArtistLikedSongsRow(
    likedCount: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(MaterialTheme.shapes.small)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Rounded.Favorite,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp),
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.artist_liked_songs),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = if (likedCount == 1) "1 song" else "$likedCount songs",
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
}
