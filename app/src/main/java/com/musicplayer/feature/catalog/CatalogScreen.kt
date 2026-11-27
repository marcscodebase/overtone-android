package com.musicplayer.feature.catalog

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.musicplayer.R
import com.musicplayer.core.designsystem.component.ArtworkCard
import com.musicplayer.core.designsystem.component.EmptyState
import com.musicplayer.core.designsystem.component.TrackRow
import com.musicplayer.core.model.Track

@Composable
fun CatalogContent(
    state: CatalogUiState,
    onTabSelected: (CatalogTab) -> Unit,
    onSortSelected: (CatalogSort) -> Unit,
    onAlbumClick: (String) -> Unit,
    onArtistClick: (String) -> Unit,
    onTrackClick: (List<Track>, Int) -> Unit,
    onAddToQueue: (Track) -> Unit,
    onAddToPlaylist: (Track) -> Unit,
    onToggleTrackDownload: (Track) -> Unit,
    onGoToTrackArtist: (Track) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            items(
                items = CatalogTab.entries,
                key = { it.name },
            ) { tab ->
                val labelRes = when (tab) {
                    CatalogTab.ALBUMS -> R.string.library_filter_albums
                    CatalogTab.ARTISTS -> R.string.library_filter_artists
                    CatalogTab.SONGS -> R.string.library_filter_songs
                }
                FilterChip(
                    selected = state.selectedTab == tab,
                    onClick = { onTabSelected(tab) },
                    label = { Text(stringResource(labelRes)) },
                )
            }
        }

        val countLabel = when (state.selectedTab) {
            CatalogTab.ALBUMS -> {
                val n = state.albums.size
                if (n == 1) "1 album" else "$n albums"
            }

            CatalogTab.ARTISTS -> {
                val n = state.artists.size
                if (n == 1) "1 artist" else "$n artists"
            }

            CatalogTab.SONGS -> {
                val n = state.tracks.size
                if (n == 1) "1 song" else "$n songs"
            }
        }

        CatalogSortBar(
            selectedTab = state.selectedTab,
            selectedSort = state.selectedSort,
            countLabel = countLabel,
            onSortSelected = onSortSelected,
        )

        when (state.selectedTab) {
            CatalogTab.ALBUMS -> {
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
                        contentPadding = PaddingValues(bottom = 160.dp),
                    ) {
                        items(
                            items = state.albums,
                            key = { it.id },
                            contentType = { "album" },
                        ) { album ->
                            val subtitle = listOfNotNull(
                                album.artistName,
                                album.year?.toString(),
                            ).joinToString(" • ")
                            CatalogCollectionRow(
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

            CatalogTab.ARTISTS -> {
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
                        contentPadding = PaddingValues(bottom = 160.dp),
                    ) {
                        items(
                            items = state.artists,
                            key = { it.id },
                            contentType = { "artist" },
                        ) { artist ->
                            val countText = if (artist.albumCount == 1) "1 album" else "${artist.albumCount} albums"
                            CatalogCollectionRow(
                                title = artist.name,
                                subtitle = countText,
                                imageUrl = artist.artworkUrl,
                                isCircular = true,
                                onClick = { onArtistClick(artist.id) },
                            )
                        }
                    }
                }
            }

            CatalogTab.SONGS -> {
                if (state.tracks.isEmpty()) {
                    EmptyState(
                        title = stringResource(R.string.library_empty_title),
                        subtitle = stringResource(R.string.library_empty_subtitle),
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = 160.dp),
                    ) {
                        itemsIndexed(
                            items = state.tracks,
                            key = { _, track -> "cat_${track.id}" },
                            contentType = { _, _ -> "track" },
                        ) { index, track ->
                            TrackRow(
                                track = track,
                                isPlaying = track.id == state.activeTrackId,
                                enabled = !state.isOffline || track.isDownloaded,
                                // Keep horizontal right-swipe free for smoothly swiping back to Library
                                enableSwipeToQueue = false,
                                onClick = { onTrackClick(state.tracks, index) },
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

@Composable
private fun CatalogSortBar(
    selectedTab: CatalogTab,
    selectedSort: CatalogSort,
    countLabel: String,
    onSortSelected: (CatalogSort) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val options = when (selectedTab) {
        CatalogTab.ALBUMS -> listOf(CatalogSort.ALPHABETICAL, CatalogSort.RECENTLY_ADDED, CatalogSort.CREATOR)
        CatalogTab.ARTISTS -> listOf(CatalogSort.ALPHABETICAL, CatalogSort.RECENTLY_ADDED)
        CatalogTab.SONGS -> listOf(CatalogSort.ALPHABETICAL, CatalogSort.RECENTLY_ADDED, CatalogSort.CREATOR)
    }
    val effectiveSort = if (selectedSort in options) selectedSort else CatalogSort.ALPHABETICAL

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Box {
            Row(
                modifier = Modifier
                    .clip(MaterialTheme.shapes.small)
                    .clickable { expanded = true }
                    .padding(vertical = 6.dp, horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.SwapVert,
                    contentDescription = stringResource(R.string.library_sort),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    text = catalogSortLabel(selectedTab, effectiveSort),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
            ) {
                options.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(catalogSortLabel(selectedTab, option)) },
                        trailingIcon = if (option == effectiveSort) {
                            {
                                Icon(
                                    imageVector = Icons.Rounded.Check,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            }
                        } else {
                            null
                        },
                        onClick = {
                            expanded = false
                            onSortSelected(option)
                        },
                    )
                }
            }
        }
        Text(
            text = countLabel,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun catalogSortLabel(tab: CatalogTab, sort: CatalogSort): String = stringResource(
    when (tab) {
        CatalogTab.ALBUMS -> when (sort) {
            CatalogSort.ALPHABETICAL -> R.string.library_sort_alphabetical
            CatalogSort.RECENTLY_ADDED -> R.string.library_sort_recently_added
            CatalogSort.CREATOR -> R.string.catalog_sort_artist
        }

        CatalogTab.ARTISTS -> when (sort) {
            CatalogSort.RECENTLY_ADDED -> R.string.catalog_sort_most_albums
            CatalogSort.ALPHABETICAL,
            CatalogSort.CREATOR,
            -> R.string.library_sort_alphabetical
        }

        CatalogTab.SONGS -> when (sort) {
            CatalogSort.ALPHABETICAL -> R.string.library_sort_alphabetical
            CatalogSort.RECENTLY_ADDED -> R.string.library_sort_recently_played
            CatalogSort.CREATOR -> R.string.catalog_sort_artist
        }
    },
)

@Composable
private fun CatalogCollectionRow(
    title: String,
    subtitle: String,
    imageUrl: String?,
    isCircular: Boolean,
    fallbackIcon: ImageVector = if (isCircular) Icons.Rounded.Person else Icons.Rounded.Album,
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
            fallbackIcon = fallbackIcon,
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
