package com.musicplayer.feature.library

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.DriveFileMove
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderOff
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.musicplayer.R
import com.musicplayer.core.designsystem.component.ArtworkCard
import com.musicplayer.core.designsystem.component.EmptyState
import com.musicplayer.core.model.Playlist
import com.musicplayer.core.model.PlaylistFolderGroup
import com.musicplayer.feature.library.playlist.MovePlaylistToFolderDialog
import com.musicplayer.feature.library.playlist.PlaylistNameDialog

@Composable
fun LibraryContent(
    state: LibraryUiState,
    existingFolders: List<String>,
    onFilterSelected: (LibraryFilter) -> Unit,
    onClearFilter: () -> Unit,
    onSortSelected: (LibrarySort) -> Unit,
    onTogglePin: (String) -> Unit,
    onOpenCollection: (LibraryCollectionSection) -> Unit,
    onOpenPlaylistFolder: (String) -> Unit,
    onClosePlaylistFolder: () -> Unit,
    onMovePlaylistToFolder: (playlistId: String, folderName: String?) -> Unit,
    onRenameFolder: (oldFolderName: String, newFolderName: String) -> Unit,
    onUngroupFolder: (String) -> Unit,
    onAlbumClick: (String) -> Unit,
    onArtistClick: (String) -> Unit,
    onPlaylistClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var playlistPendingFolderMove by remember { mutableStateOf<Playlist?>(null) }
    var folderPendingRename by remember { mutableStateOf<String?>(null) }

    val activeFolder = state.activeFolderGroup
    BackHandler(
        enabled = activeFolder != null,
        onBack = onClosePlaylistFolder,
    )

    Column(modifier = modifier.fillMaxSize()) {
        // Spotify-style toggleable filter chips
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (state.selectedFilter != null) {
                item(key = "clear_filter") {
                    Surface(
                        onClick = onClearFilter,
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        modifier = Modifier.size(32.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Rounded.Close,
                                contentDescription = stringResource(R.string.library_clear_filter),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                }
            }
            items(
                items = LibraryFilter.entries,
                key = { it.name },
            ) { filter ->
                val labelRes = when (filter) {
                    LibraryFilter.PLAYLISTS -> R.string.library_filter_playlists
                    LibraryFilter.ALBUMS -> R.string.library_filter_albums
                    LibraryFilter.ARTISTS -> R.string.library_filter_artists
                    LibraryFilter.DOWNLOADED -> R.string.library_filter_downloaded
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

        if (activeFolder != null) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 210.dp),
            ) {
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
                        isPinned = false,
                        showPinOption = false,
                        onTogglePin = {},
                        onClick = { onPlaylistClick(playlist.id) },
                        onMoveToFolder = { playlistPendingFolderMove = playlist },
                    )
                }
            }
        } else {
            LibrarySortBar(
                selectedSort = state.selectedSort,
                itemCount = state.items.size,
                onSortSelected = onSortSelected,
            )

            if (state.items.isEmpty()) {
                EmptyState(
                    title = stringResource(R.string.library_curated_empty_title),
                    subtitle = stringResource(R.string.library_curated_empty_subtitle),
                    icon = Icons.AutoMirrored.Rounded.QueueMusic,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 210.dp),
                ) {
                    items(
                        items = state.items,
                        key = { it.pinKey },
                        contentType = { item ->
                            when (item) {
                                is LibraryListItem.BuiltInCollection -> "collection"
                                is LibraryListItem.PlaylistFolderItem -> "folder"
                                is LibraryListItem.PlaylistItem -> "playlist"
                                is LibraryListItem.AlbumItem -> "album"
                                is LibraryListItem.ArtistItem -> "artist"
                            }
                        },
                    ) { item ->
                        when (item) {
                            is LibraryListItem.BuiltInCollection -> {
                                BuiltInCollectionRow(
                                    item = item,
                                    onClick = { onOpenCollection(item.section) },
                                    onTogglePin = { onTogglePin(item.pinKey) },
                                )
                            }

                            is LibraryListItem.PlaylistFolderItem -> {
                                PlaylistFolderRow(
                                    folder = item.folder,
                                    isPinned = item.isPinned,
                                    onClick = { onOpenPlaylistFolder(item.folder.name) },
                                    onTogglePin = { onTogglePin(item.pinKey) },
                                    onRename = { folderPendingRename = item.folder.name },
                                    onUngroup = { onUngroupFolder(item.folder.name) },
                                )
                            }

                            is LibraryListItem.PlaylistItem -> {
                                PlaylistLibraryRow(
                                    playlist = item.playlist,
                                    isPinned = item.isPinned,
                                    showPinOption = true,
                                    onTogglePin = { onTogglePin(item.pinKey) },
                                    onClick = { onPlaylistClick(item.playlist.id) },
                                    onMoveToFolder = { playlistPendingFolderMove = item.playlist },
                                )
                            }

                            is LibraryListItem.AlbumItem -> {
                                val subtitle = listOfNotNull(
                                    stringResource(R.string.library_type_album),
                                    item.album.artistName.takeIf { it.isNotBlank() },
                                ).joinToString(" • ")
                                CuratedCollectionRow(
                                    title = item.album.title,
                                    subtitle = subtitle,
                                    imageUrl = item.album.artworkUrl,
                                    isCircular = false,
                                    isPinned = item.isPinned,
                                    hasDownloaded = item.hasDownloadedContent,
                                    fallbackIcon = Icons.Rounded.Album,
                                    onClick = { onAlbumClick(item.album.id) },
                                    onTogglePin = { onTogglePin(item.pinKey) },
                                )
                            }

                            is LibraryListItem.ArtistItem -> {
                                val subtitle = listOfNotNull(
                                    stringResource(R.string.library_type_artist),
                                    item.artist.albumCount.takeIf { it > 0 }?.let { "$it albums" },
                                ).joinToString(" • ")
                                CuratedCollectionRow(
                                    title = item.artist.name,
                                    subtitle = subtitle,
                                    imageUrl = item.artist.artworkUrl,
                                    isCircular = true,
                                    isPinned = item.isPinned,
                                    hasDownloaded = false,
                                    fallbackIcon = Icons.Rounded.Person,
                                    onClick = { onArtistClick(item.artist.id) },
                                    onTogglePin = { onTogglePin(item.pinKey) },
                                )
                            }
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
private fun LibrarySortBar(
    selectedSort: LibrarySort,
    itemCount: Int,
    onSortSelected: (LibrarySort) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val sortLabel = stringResource(
        when (selectedSort) {
            LibrarySort.ALPHABETICAL -> R.string.library_sort_alphabetical
            LibrarySort.RECENTLY_ADDED -> R.string.library_sort_recently_added
            LibrarySort.CREATOR -> R.string.library_sort_creator
        },
    )

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
                    text = sortLabel,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
            ) {
                LibrarySort.entries.forEach { option ->
                    val optionLabel = stringResource(
                        when (option) {
                            LibrarySort.ALPHABETICAL -> R.string.library_sort_alphabetical
                            LibrarySort.RECENTLY_ADDED -> R.string.library_sort_recently_added
                            LibrarySort.CREATOR -> R.string.library_sort_creator
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(optionLabel) },
                        trailingIcon = if (option == selectedSort) {
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
        if (itemCount > 0) {
            Text(
                text = if (itemCount == 1) "1 item" else "$itemCount items",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BuiltInCollectionRow(
    item: LibraryListItem.BuiltInCollection,
    onClick: () -> Unit,
    onTogglePin: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current
    val isFavorites = item.section == LibraryCollectionSection.FAVORITES
    val icon = if (isFavorites) Icons.Rounded.Favorite else Icons.Rounded.DownloadDone
    val title = stringResource(
        if (isFavorites) R.string.library_filter_favorites else R.string.library_filter_downloaded,
    )
    val countText = if (item.trackCount == 1) "1 song" else "${item.trackCount} songs"

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    menuExpanded = true
                },
            )
            .padding(start = 16.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(MaterialTheme.shapes.small)
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
                modifier = Modifier.size(26.dp),
            )
        }
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            PinAwareSubtitle(
                subtitle = "${stringResource(R.string.library_type_collection)} • $countText",
                isPinned = item.isPinned,
                hasDownloaded = !isFavorites && item.trackCount > 0,
            )
        }
        Box {
            IconButton(onClick = { menuExpanded = true }) {
                Icon(
                    imageVector = Icons.Rounded.MoreVert,
                    contentDescription = stringResource(R.string.library_item_options),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
            DropdownMenu(
                expanded = menuExpanded,
                onDismissRequest = { menuExpanded = false },
            ) {
                PinDropdownItem(
                    isPinned = item.isPinned,
                    onClick = {
                        menuExpanded = false
                        onTogglePin()
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CuratedCollectionRow(
    title: String,
    subtitle: String,
    imageUrl: String?,
    isCircular: Boolean,
    isPinned: Boolean,
    hasDownloaded: Boolean,
    fallbackIcon: ImageVector,
    onClick: () -> Unit,
    onTogglePin: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    menuExpanded = true
                },
            )
            .padding(start = 16.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
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
            PinAwareSubtitle(
                subtitle = subtitle,
                isPinned = isPinned,
                hasDownloaded = hasDownloaded,
            )
        }
        Box {
            IconButton(onClick = { menuExpanded = true }) {
                Icon(
                    imageVector = Icons.Rounded.MoreVert,
                    contentDescription = stringResource(R.string.library_item_options),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
            DropdownMenu(
                expanded = menuExpanded,
                onDismissRequest = { menuExpanded = false },
            ) {
                PinDropdownItem(
                    isPinned = isPinned,
                    onClick = {
                        menuExpanded = false
                        onTogglePin()
                    },
                )
            }
        }
    }
}

@Composable
private fun PinAwareSubtitle(
    subtitle: String,
    isPinned: Boolean,
    hasDownloaded: Boolean,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        if (isPinned) {
            Icon(
                imageVector = Icons.Rounded.PushPin,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(13.dp),
            )
        }
        if (hasDownloaded) {
            Icon(
                imageVector = Icons.Rounded.DownloadDone,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(13.dp),
            )
        }
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun PinDropdownItem(
    isPinned: Boolean,
    onClick: () -> Unit,
) {
    DropdownMenuItem(
        text = {
            Text(
                stringResource(
                    if (isPinned) R.string.library_unpin else R.string.library_pin_to_top,
                ),
            )
        },
        leadingIcon = {
            Icon(
                imageVector = Icons.Rounded.PushPin,
                contentDescription = null,
                tint = if (isPinned) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        },
        onClick = onClick,
    )
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PlaylistFolderRow(
    folder: PlaylistFolderGroup,
    isPinned: Boolean,
    onClick: () -> Unit,
    onTogglePin: () -> Unit,
    onRename: () -> Unit,
    onUngroup: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current
    val count = folder.playlists.size

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    menuExpanded = true
                },
            )
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
            PinAwareSubtitle(
                subtitle = "$count playlist${if (count == 1) "" else "s"} • ${folder.totalTrackCount} songs",
                isPinned = isPinned,
                hasDownloaded = folder.playlists.any { it.isAutoSyncDownloaded },
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
                PinDropdownItem(
                    isPinned = isPinned,
                    onClick = {
                        menuExpanded = false
                        onTogglePin()
                    },
                )
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PlaylistLibraryRow(
    playlist: Playlist,
    isPinned: Boolean,
    showPinOption: Boolean,
    onTogglePin: () -> Unit,
    onClick: () -> Unit,
    onMoveToFolder: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    menuExpanded = true
                },
            )
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
            val countText = if (playlist.trackCount == 1) "1 song" else "${playlist.trackCount} songs"
            PinAwareSubtitle(
                subtitle = "${stringResource(R.string.library_type_playlist)} • $countText",
                isPinned = isPinned,
                hasDownloaded = playlist.isAutoSyncDownloaded,
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
                if (showPinOption) {
                    PinDropdownItem(
                        isPinned = isPinned,
                        onClick = {
                            menuExpanded = false
                            onTogglePin()
                        },
                    )
                }
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
