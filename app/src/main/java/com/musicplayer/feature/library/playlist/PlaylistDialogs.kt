package com.musicplayer.feature.library.playlist

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.musicplayer.R
import com.musicplayer.core.designsystem.component.ArtworkCard
import com.musicplayer.core.model.Playlist
import com.musicplayer.core.model.PlaylistFolderCodec
import com.musicplayer.core.model.Track

@Composable
fun PlaylistNameDialog(
    title: String,
    confirmLabel: String,
    initialName: String = "",
    label: String = stringResource(R.string.playlist_name_label),
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by rememberSaveable(initialName) { mutableStateOf(initialName) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(label) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(name.trim()) },
                enabled = name.isNotBlank(),
            ) {
                Text(confirmLabel)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}

@Composable
fun CreatePlaylistDialog(
    existingFolders: List<String>,
    initialFolder: String? = null,
    onDismiss: () -> Unit,
    onConfirm: (name: String, folderName: String?) -> Unit,
) {
    var name by rememberSaveable { mutableStateOf("") }
    var folder by rememberSaveable(initialFolder) { mutableStateOf(initialFolder.orEmpty()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.playlist_create_new)) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.playlist_name_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = folder,
                    onValueChange = { folder = it },
                    label = { Text(stringResource(R.string.playlist_folder_label)) },
                    placeholder = { Text(stringResource(R.string.playlist_folder_placeholder)) },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Rounded.Folder,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                    },
                    trailingIcon = {
                        if (folder.isNotEmpty()) {
                            IconButton(onClick = { folder = "" }) {
                                Icon(
                                    imageVector = Icons.Rounded.Close,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (existingFolders.isNotEmpty()) {
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        items(items = existingFolders, key = { it }) { existing ->
                            val selected = folder.trim().equals(existing, ignoreCase = true)
                            FilterChip(
                                selected = selected,
                                onClick = { folder = if (selected) "" else existing },
                                label = { Text(existing) },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onConfirm(
                        name.trim(),
                        PlaylistFolderCodec.normalizeFolderName(folder),
                    )
                },
                enabled = name.isNotBlank(),
            ) {
                Text(stringResource(R.string.action_create))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}

@Composable
fun MovePlaylistToFolderDialog(
    playlistName: String,
    currentFolder: String?,
    existingFolders: List<String>,
    onDismiss: () -> Unit,
    onConfirm: (folderName: String?) -> Unit,
) {
    var folder by rememberSaveable(playlistName, currentFolder) {
        mutableStateOf(currentFolder.orEmpty())
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(R.string.playlist_move_to_folder_title, playlistName),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedTextField(
                    value = folder,
                    onValueChange = { folder = it },
                    label = { Text(stringResource(R.string.playlist_folder_new_name_label)) },
                    placeholder = { Text(stringResource(R.string.playlist_folder_placeholder)) },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Rounded.Folder,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                    },
                    trailingIcon = {
                        if (folder.isNotEmpty()) {
                            IconButton(onClick = { folder = "" }) {
                                Icon(
                                    imageVector = Icons.Rounded.Close,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    item(key = "no_folder_chip") {
                        FilterChip(
                            selected = folder.isBlank(),
                            onClick = { folder = "" },
                            label = { Text(stringResource(R.string.playlist_no_folder)) },
                        )
                    }
                    items(items = existingFolders, key = { it }) { existing ->
                        val selected = folder.trim().equals(existing, ignoreCase = true)
                        FilterChip(
                            selected = selected,
                            onClick = { folder = existing },
                            label = { Text(existing) },
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(PlaylistFolderCodec.normalizeFolderName(folder)) },
            ) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}

@Composable
fun DeletePlaylistDialog(
    playlistName: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.playlist_delete_title)) },
        text = { Text(stringResource(R.string.playlist_delete_message, playlistName)) },
        confirmButton = {
            Button(onClick = onConfirm) {
                Text(stringResource(R.string.action_delete))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}

/**
 * Spotify-style multi-select sheet for saving a track to Favorites and one or more Playlists.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddToPlaylistSheet(
    track: Track,
    initialIsFavorite: Boolean,
    playlists: List<Playlist>,
    initialPlaylistIds: Set<String>,
    showFavoriteRow: Boolean = true,
    onDismiss: () -> Unit,
    onConfirm: (isFavorite: Boolean, selectedPlaylistIds: Set<String>) -> Unit,
    onCreateNewWithTrack: (name: String, folderName: String?) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var showCreateDialog by rememberSaveable { mutableStateOf(false) }
    var favoriteSelected by remember(track.id, initialIsFavorite) { mutableStateOf(initialIsFavorite) }
    var selectedPlaylistIds by remember(track.id) { mutableStateOf(initialPlaylistIds) }
    var syncedInitialMembership by remember(track.id) { mutableStateOf(false) }
    val existingFolders = remember(playlists) { PlaylistFolderCodec.distinctFolderNames(playlists) }

    LaunchedEffect(track.id, initialPlaylistIds) {
        if (!syncedInitialMembership && initialPlaylistIds.isNotEmpty()) {
            selectedPlaylistIds = initialPlaylistIds
            syncedInitialMembership = true
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp),
        ) {
            Text(
                text = stringResource(
                    if (showFavoriteRow) R.string.playlist_save_to else R.string.playlist_add_to,
                ),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "${track.title} • ${track.artistName}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.height(14.dp))

            FilledTonalButton(
                onClick = { showCreateDialog = true },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Rounded.Add, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.playlist_create_new))
            }

            Spacer(modifier = Modifier.height(12.dp))

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(290.dp),
                contentPadding = PaddingValues(bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (showFavoriteRow) {
                    item(key = "favorites_row") {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(MaterialTheme.shapes.small)
                                .clickable { favoriteSelected = !favoriteSelected }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(44.dp)
                                    .clip(MaterialTheme.shapes.extraSmall)
                                    .background(MaterialTheme.colorScheme.primaryContainer),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Favorite,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.size(22.dp),
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = stringResource(R.string.home_favorites),
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                Text(
                                    text = stringResource(R.string.playlist_liked_songs_subtitle),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Icon(
                                imageVector = if (favoriteSelected) {
                                    Icons.Rounded.CheckCircle
                                } else {
                                    Icons.Rounded.RadioButtonUnchecked
                                },
                                contentDescription = null,
                                tint = if (favoriteSelected) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        }
                        HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))
                    }
                }

                items(items = playlists, key = { it.id }) { playlist ->
                    val isChecked = playlist.id in selectedPlaylistIds
                    val subtitle = listOfNotNull(
                        playlist.folderName,
                        "${playlist.trackCount} songs",
                    ).joinToString(" • ")

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(MaterialTheme.shapes.small)
                            .clickable {
                                selectedPlaylistIds = if (isChecked) {
                                    selectedPlaylistIds - playlist.id
                                } else {
                                    selectedPlaylistIds + playlist.id
                                }
                            }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ArtworkCard(
                            imageUrl = playlist.artworkUrl,
                            contentDescription = playlist.name,
                            fallbackIcon = Icons.AutoMirrored.Rounded.QueueMusic,
                            fallbackIconSize = 20.dp,
                            modifier = Modifier.size(44.dp),
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = playlist.name,
                                style = MaterialTheme.typography.titleSmall,
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
                        Icon(
                            imageVector = if (isChecked) {
                                Icons.Rounded.CheckCircle
                            } else {
                                Icons.Rounded.RadioButtonUnchecked
                            },
                            contentDescription = null,
                            tint = if (isChecked) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = { onConfirm(favoriteSelected, selectedPlaylistIds) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
            ) {
                Text(stringResource(R.string.action_done))
            }
            Spacer(modifier = Modifier.height(12.dp))
        }
    }

    if (showCreateDialog) {
        CreatePlaylistDialog(
            existingFolders = existingFolders,
            onDismiss = { showCreateDialog = false },
            onConfirm = { name, folderName ->
                showCreateDialog = false
                onCreateNewWithTrack(name, folderName)
            },
        )
    }
}
