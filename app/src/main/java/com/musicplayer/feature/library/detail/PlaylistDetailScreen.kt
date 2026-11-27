package com.musicplayer.feature.library.detail

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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.DriveFileMove
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.musicplayer.R
import com.musicplayer.core.common.Formatters
import com.musicplayer.core.designsystem.component.ArtworkCard
import com.musicplayer.core.designsystem.component.CollectionDetailHeader
import com.musicplayer.core.designsystem.component.EmptyState
import com.musicplayer.core.designsystem.component.LazyColumnFastScroller
import com.musicplayer.core.designsystem.component.OfflineStatusBanner
import com.musicplayer.core.designsystem.component.SwipeDownToDismissContainer
import com.musicplayer.core.designsystem.component.TrackRow
import com.musicplayer.core.designsystem.component.isAtTopOfList
import com.musicplayer.core.model.PlaylistTrackEntry
import com.musicplayer.core.model.Track
import com.musicplayer.feature.library.playlist.DeletePlaylistDialog
import com.musicplayer.feature.library.playlist.MovePlaylistToFolderDialog
import com.musicplayer.feature.library.playlist.PlaylistNameDialog

@Composable
fun PlaylistDetailRoute(
    onBackClick: () -> Unit,
    onAlbumClick: (String) -> Unit,
    onGoToTrackArtist: (Track) -> Unit,
    onAddToPlaylist: (Track) -> Unit,
    onToggleTrackDownload: (Track) -> Unit,
    onDownloadPlaylist: (playlistId: String, name: String) -> Unit,
    onRemovePlaylistDownload: (playlistId: String, name: String) -> Unit,
    viewModel: PlaylistDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(state.isDeleted) {
        if (state.isDeleted) {
            onBackClick()
        }
    }

    PlaylistDetailScreen(
        state = state,
        onBackClick = onBackClick,
        onPlayAll = viewModel::onPlayAll,
        onShuffleAll = viewModel::onShuffleAll,
        onTrackClick = viewModel::onTrackClick,
        onAddToQueue = viewModel::onAddToQueue,
        onAlbumClick = onAlbumClick,
        onGoToTrackArtist = onGoToTrackArtist,
        onAddToPlaylist = onAddToPlaylist,
        onToggleTrackDownload = onToggleTrackDownload,
        onDownloadPlaylist = onDownloadPlaylist,
        onRemovePlaylistDownload = onRemovePlaylistDownload,
        onToggleReorderMode = viewModel::onToggleReorderMode,
        onMoveTrack = viewModel::onMoveTrack,
        onRemoveTrack = viewModel::onRemoveTrack,
        onToggleAutoSyncDownloads = viewModel::onToggleAutoSyncDownloads,
        onSyncDownloadsNow = viewModel::onSyncDownloadsNow,
        onRenamePlaylist = viewModel::onRenamePlaylist,
        onMoveToFolder = viewModel::onMoveToFolder,
        onDeletePlaylist = viewModel::onDeletePlaylist,
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
    onAddToQueue: (Track) -> Unit,
    onAlbumClick: (String) -> Unit,
    onGoToTrackArtist: (Track) -> Unit,
    onAddToPlaylist: (Track) -> Unit,
    onToggleTrackDownload: (Track) -> Unit,
    onDownloadPlaylist: (playlistId: String, name: String) -> Unit,
    onRemovePlaylistDownload: (playlistId: String, name: String) -> Unit,
    onToggleReorderMode: () -> Unit,
    onMoveTrack: (Int, Int) -> Unit,
    onRemoveTrack: (String) -> Unit,
    onToggleAutoSyncDownloads: (Boolean) -> Unit,
    onSyncDownloadsNow: () -> Unit,
    onRenamePlaylist: (String) -> Unit,
    onMoveToFolder: (String?) -> Unit,
    onDeletePlaylist: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var menuExpanded by rememberSaveable { mutableStateOf(false) }
    var showRenameDialog by rememberSaveable { mutableStateOf(false) }
    var showMoveFolderDialog by rememberSaveable { mutableStateOf(false) }
    var showDeleteDialog by rememberSaveable { mutableStateOf(false) }

    val listState = rememberLazyListState()
    SwipeDownToDismissContainer(
        isAtTop = { listState.isAtTopOfList() && !state.isReordering },
        onDismiss = onBackClick,
        modifier = modifier,
    ) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
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
                    state.playlist?.let { playlist ->
                        if (state.entries.isNotEmpty()) {
                            val allTracksDownloaded = state.entries.all { it.track.isDownloaded }
                            IconButton(
                                onClick = {
                                    if (state.isPlaylistPinnedDownloaded || allTracksDownloaded) {
                                        onRemovePlaylistDownload(playlist.id, playlist.name)
                                    } else {
                                        onDownloadPlaylist(playlist.id, playlist.name)
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

                                    state.isPlaylistPinnedDownloaded || allTracksDownloaded -> {
                                        Icon(
                                            imageVector = Icons.Rounded.DownloadDone,
                                            contentDescription = stringResource(R.string.download_remove_playlist),
                                            tint = MaterialTheme.colorScheme.primary,
                                        )
                                    }

                                    else -> {
                                        Icon(
                                            imageVector = Icons.Rounded.Download,
                                            contentDescription = stringResource(R.string.download_playlist),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        }
                    }
                    if (state.entries.size > 1) {
                        IconButton(onClick = onToggleReorderMode) {
                            Icon(
                                imageVector = if (state.isReordering) Icons.Rounded.Check else Icons.Rounded.SwapVert,
                                contentDescription = stringResource(R.string.playlist_reorder),
                                tint = if (state.isReordering) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        }
                    }
                    Box {
                        IconButton(onClick = { menuExpanded = true }) {
                            Icon(
                                imageVector = Icons.Rounded.MoreVert,
                                contentDescription = stringResource(R.string.track_more_options),
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
                                    showMoveFolderDialog = true
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.playlist_rename)) },
                                leadingIcon = { Icon(Icons.Rounded.Edit, contentDescription = null) },
                                onClick = {
                                    menuExpanded = false
                                    showRenameDialog = true
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.playlist_delete)) },
                                leadingIcon = { Icon(Icons.Rounded.Delete, contentDescription = null) },
                                onClick = {
                                    menuExpanded = false
                                    showDeleteDialog = true
                                },
                            )
                        }
                    }
                },
            )
        },
    ) { innerPadding ->
        val totalDurationMs = state.entries.sumOf { it.track.durationMs }
        val metadata = listOfNotNull(
            "${state.entries.size} songs",
            totalDurationMs.takeIf { it > 0L }?.let(Formatters::formatDuration),
        ).joinToString(" • ")

        LazyColumnFastScroller(
            listState = listState,
            itemCount = if (state.isReordering) 0 else state.entries.size,
            headerItemCount = 3,
            bubbleLabelForIndex = { idx -> "#${idx + 1}" },
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 24.dp),
            ) {
            item(key = "playlist_offline_banner") {
                OfflineStatusBanner(status = state.offlineStatus)
            }
            if (state.isSyncing) {
                item(key = "playlist_syncing") {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
            item(key = "playlist_header") {
                val baseLabel = stringResource(R.string.playlist_label)
                val subtitle = state.playlist?.folderName?.let { "$baseLabel • $it" } ?: baseLabel
                CollectionDetailHeader(
                    title = state.playlist?.name ?: stringResource(R.string.library_filter_playlists),
                    subtitle = subtitle,
                    metadata = metadata,
                    artworkUrl = state.playlist?.artworkUrl,
                    isCircularArtwork = false,
                    canPlay = state.playableTracks.isNotEmpty(),
                    onPlayClick = onPlayAll,
                    onShuffleClick = onShuffleAll,
                )
            }
            state.playlist?.let { playlist ->
                item(key = "playlist_auto_sync_card") {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Sync,
                                    contentDescription = null,
                                    tint = if (playlist.isAutoSyncDownloaded) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                    modifier = Modifier.size(20.dp),
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = stringResource(R.string.playlist_auto_sync),
                                        style = MaterialTheme.typography.titleSmall,
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                    Text(
                                        text = stringResource(R.string.playlist_auto_sync_subtitle),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                                Switch(
                                    checked = playlist.isAutoSyncDownloaded,
                                    onCheckedChange = onToggleAutoSyncDownloads,
                                )
                            }
                            if (playlist.isAutoSyncDownloaded && !state.isOffline) {
                                TextButton(
                                    onClick = onSyncDownloadsNow,
                                    modifier = Modifier.align(Alignment.End),
                                ) {
                                    Text(stringResource(R.string.playlist_sync_downloads_now))
                                }
                            }
                        }
                    }
                }
            }
            if (!state.isSyncing && state.entries.isEmpty()) {
                item(key = "playlist_empty") {
                    EmptyState(
                        title = stringResource(R.string.playlist_empty_title),
                        subtitle = stringResource(R.string.playlist_empty_subtitle),
                    )
                }
            } else {
                itemsIndexed(
                    items = state.entries,
                    key = { _, entry -> entry.playlistItemId },
                ) { index, entry ->
                    if (state.isReordering) {
                        PlaylistReorderRow(
                            entry = entry,
                            canMoveUp = index > 0,
                            canMoveDown = index < state.entries.lastIndex,
                            onMoveUp = { onMoveTrack(index, index - 1) },
                            onMoveDown = { onMoveTrack(index, index + 1) },
                            onRemove = { onRemoveTrack(entry.playlistItemId) },
                        )
                    } else {
                        TrackRow(
                            track = entry.track,
                            isPlaying = entry.track.id == state.activeTrackId,
                            enabled = !state.isOffline || entry.track.isDownloaded,
                            onClick = { onTrackClick(index) },
                            onAddToQueue = { onAddToQueue(entry.track) },
                            onAddToPlaylist = { onAddToPlaylist(entry.track) },
                            onToggleDownload = { onToggleTrackDownload(entry.track) },
                            onGoToAlbum = entry.track.albumId?.let { id -> { onAlbumClick(id) } },
                            onGoToArtist = { onGoToTrackArtist(entry.track) },
                            extraMenuActionLabel = stringResource(R.string.playlist_remove_track),
                            onExtraMenuAction = { onRemoveTrack(entry.playlistItemId) },
                        )
                    }
                }
            }
        }
        }
    }
    }

    if (showRenameDialog) {
        PlaylistNameDialog(
            title = stringResource(R.string.playlist_rename),
            confirmLabel = stringResource(R.string.action_save),
            initialName = state.playlist?.name.orEmpty(),
            onDismiss = { showRenameDialog = false },
            onConfirm = { newName ->
                showRenameDialog = false
                onRenamePlaylist(newName)
            },
        )
    }

    if (showMoveFolderDialog) {
        MovePlaylistToFolderDialog(
            playlistName = state.playlist?.name.orEmpty(),
            currentFolder = state.playlist?.folderName,
            existingFolders = state.existingFolders,
            onDismiss = { showMoveFolderDialog = false },
            onConfirm = { newFolder ->
                showMoveFolderDialog = false
                onMoveToFolder(newFolder)
            },
        )
    }

    if (showDeleteDialog) {
        DeletePlaylistDialog(
            playlistName = state.playlist?.name.orEmpty(),
            onDismiss = { showDeleteDialog = false },
            onConfirm = {
                showDeleteDialog = false
                onDeletePlaylist()
            },
        )
    }
}

@Composable
private fun PlaylistReorderRow(
    entry: PlaylistTrackEntry,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ArtworkCard(
            imageUrl = entry.track.artworkUrl,
            contentDescription = entry.track.albumName,
            shape = MaterialTheme.shapes.extraSmall,
            fallbackIconSize = 20.dp,
            modifier = Modifier.size(44.dp),
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.track.title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = entry.track.artistName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onMoveUp, enabled = canMoveUp) {
            Icon(
                imageVector = Icons.Rounded.ArrowUpward,
                contentDescription = stringResource(R.string.queue_move_up),
                modifier = Modifier.size(18.dp),
            )
        }
        IconButton(onClick = onMoveDown, enabled = canMoveDown) {
            Icon(
                imageVector = Icons.Rounded.ArrowDownward,
                contentDescription = stringResource(R.string.queue_move_down),
                modifier = Modifier.size(18.dp),
            )
        }
        IconButton(onClick = onRemove) {
            Icon(
                imageVector = Icons.Rounded.Delete,
                contentDescription = stringResource(R.string.playlist_remove_track),
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}
