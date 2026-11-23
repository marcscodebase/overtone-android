package com.musicplayer.feature.library

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.DriveFileMove
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderOff
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import com.musicplayer.core.model.Playlist
import com.musicplayer.core.model.PlaylistFolderGroup
import com.musicplayer.core.model.Track
import com.musicplayer.feature.library.playlist.MovePlaylistToFolderDialog
import com.musicplayer.feature.library.playlist.PlaylistNameDialog

@Composable
fun LibraryContent(
    state: LibraryUiState,
    existingFolders: List<String>,
    onFilterSelected: (LibraryFilter) -> Unit,
    onOpenPlaylistFolder: (String) -> Unit,
    onClosePlaylistFolder: () -> Unit,
    onMovePlaylistToFolder: (playlistId: String, folderName: String?) -> Unit,
    onRenameFolder: (oldFolderName: String, newFolderName: String) -> Unit,
    onUngroupFolder: (String) -> Unit,
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
    var playlistPendingFolderMove by remember { mutableStateOf<Playlist?>(null) }
    var folderPendingRename by remember { mutableStateOf<String?>(null) }

    BackHandler(
        enabled = state.selectedFilter == LibraryFilter.PLAYLISTS && state.activeFolderGroup != null,
        onBack = onClosePlaylistFolder,
    )

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
                    val activeFolder = state.activeFolderGroup
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = 140.dp),
                    ) {
                        if (activeFolder != null) {
                            item(key = "folder_header_${activeFolder.name}") {
                                PlaylistFolderHeaderRow(
                                    folder = activeFolder,
                                    onBack = onClosePlaylistFolder,
                                    onRename = { folderPendingRename = activeFolder.name },
                                    onUngroup = { onUngroupFolder(activeFolder.name) },
                                )
                                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                            }
                            items(
                                items = activeFolder.playlists,
                                key = { it.id },
                                contentType = { "playlist" },
                            ) { playlist ->
                                PlaylistLibraryRow(
                                    playlist = playlist,
                                    onClick = { onPlaylistClick(playlist.id) },
                                    onMoveToFolder = { playlistPendingFolderMove = playlist },
                                )
                            }
                        } else {
                            items(
                                items = state.playlistFolders,
                                key = { "folder_${it.name.lowercase()}" },
                                contentType = { "folder" },
                            ) { folder ->
                                PlaylistFolderRow(
                                    folder = folder,
                                    onClick = { onOpenPlaylistFolder(folder.name) },
                                    onRename = { folderPendingRename = folder.name },
                                    onUngroup = { onUngroupFolder(folder.name) },
                                )
                            }
                            items(
                                items = state.rootPlaylists,
                                key = { it.id },
                                contentType = { "playlist" },
                            ) { playlist ->
                                PlaylistLibraryRow(
                                    playlist = playlist,
                                    onClick = { onPlaylistClick(playlist.id) },
                                    onMoveToFolder = { playlistPendingFolderMove = playlist },
                                )
                            }
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
                                enabled = !state.isOffline || track.isDownloaded,
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

    playlistPendingFolderMove?.let { playlist ->
        MovePlaylistToFolderDialog(
            playlistName = playlist.name,
            currentFolder = playlist.folderName,
            existingFolders = existingFolders,
            onDismiss = { playlistPendingFolderMove = null },
            onConfirm = { newFolder ->
                onMovePlaylistToFolder(playlist.id, newFolder)
                playlistPendingFolderMove = null
            },
        )
    }

    folderPendingRename?.let { folderName ->
        PlaylistNameDialog(
            title = stringResource(R.string.playlist_folder_rename),
            confirmLabel = stringResource(R.string.action_save),
            initialName = folderName,
            label = stringResource(R.string.playlist_folder_new_name_label),
            onDismiss = { folderPendingRename = null },
            onConfirm = { newName ->
                onRenameFolder(folderName, newName)
                folderPendingRename = null
            },
        )
    }
}

@Composable
private fun PlaylistFolderHeaderRow(
    folder: PlaylistFolderGroup,
    onBack: () -> Unit,
    onRename: () -> Unit,
    onUngroup: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 4.dp, end = 4.dp, top = 4.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                contentDescription = stringResource(R.string.action_back),
            )
        }
        Icon(
            imageVector = Icons.Rounded.FolderOpen,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(22.dp),
        )
        Spacer(modifier = Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = folder.name,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val count = folder.playlists.size
            Text(
                text = "$count playlist${if (count == 1) "" else "s"} • ${folder.totalTrackCount} songs",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Box {
            IconButton(onClick = { menuExpanded = true }) {
                Icon(
                    imageVector = Icons.Rounded.MoreVert,
                    contentDescription = stringResource(R.string.playlist_folder_options),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            DropdownMenu(
                expanded = menuExpanded,
                onDismissRequest = { menuExpanded = false },
            ) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.playlist_folder_rename)) },
                    leadingIcon = { Icon(Icons.Rounded.Edit, contentDescription = null) },
                    onClick = {
                        menuExpanded = false
                        onRename()
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.playlist_folder_ungroup)) },
                    leadingIcon = { Icon(Icons.Rounded.FolderOff, contentDescription = null) },
                    onClick = {
                        menuExpanded = false
                        onUngroup()
                    },
                )
            }
        }
    }
}

@Composable
private fun PlaylistFolderRow(
    folder: PlaylistFolderGroup,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onUngroup: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val count = folder.playlists.size

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 16.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(MaterialTheme.shapes.small)
                .background(MaterialTheme.colorScheme.secondaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Rounded.Folder,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.size(28.dp),
            )
        }
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = folder.name,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "$count playlist${if (count == 1) "" else "s"} • ${folder.totalTrackCount} songs",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(
            imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Box {
            IconButton(onClick = { menuExpanded = true }) {
                Icon(
                    imageVector = Icons.Rounded.MoreVert,
                    contentDescription = stringResource(R.string.playlist_folder_options),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
            DropdownMenu(
                expanded = menuExpanded,
                onDismissRequest = { menuExpanded = false },
            ) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.playlist_folder_rename)) },
                    leadingIcon = { Icon(Icons.Rounded.Edit, contentDescription = null) },
                    onClick = {
                        menuExpanded = false
                        onRename()
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.playlist_folder_ungroup)) },
                    leadingIcon = { Icon(Icons.Rounded.FolderOff, contentDescription = null) },
                    onClick = {
                        menuExpanded = false
                        onUngroup()
                    },
                )
            }
        }
    }
}

@Composable
private fun PlaylistLibraryRow(
    playlist: Playlist,
    onClick: () -> Unit,
    onMoveToFolder: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 16.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ArtworkCard(
            imageUrl = playlist.artworkUrl,
            contentDescription = playlist.name,
            shape = MaterialTheme.shapes.small,
            fallbackIcon = Icons.AutoMirrored.Rounded.QueueMusic,
            fallbackIconSize = 24.dp,
            modifier = Modifier.size(56.dp),
        )
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = playlist.name,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "${playlist.trackCount} songs",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Box {
            IconButton(onClick = { menuExpanded = true }) {
                Icon(
                    imageVector = Icons.Rounded.MoreVert,
                    contentDescription = stringResource(R.string.playlist_options),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
            DropdownMenu(
                expanded = menuExpanded,
                onDismissRequest = { menuExpanded = false },
            ) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.playlist_move_to_folder)) },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.DriveFileMove,
                            contentDescription = null,
                        )
                    },
                    onClick = {
                        menuExpanded = false
                        onMoveToFolder()
                    },
                )
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
