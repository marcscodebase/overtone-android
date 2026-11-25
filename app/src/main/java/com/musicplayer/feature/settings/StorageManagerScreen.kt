package com.musicplayer.feature.settings

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.core.content.ContextCompat
import androidx.compose.ui.platform.LocalContext
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
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
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
import com.musicplayer.core.designsystem.component.EmptyState
import com.musicplayer.core.model.DownloadQualityMode
import com.musicplayer.core.model.DownloadStatus
import com.musicplayer.core.model.TranscodeSource
import com.musicplayer.data.local.MediaStorePathBuilder
import com.musicplayer.data.repository.DownloadedTrackItem

@Composable
fun StorageManagerRoute(
    onBackClick: () -> Unit,
    viewModel: StorageManagerViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val audioReadPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.READ_MEDIA_AUDIO
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) {
        viewModel.onReconcile()
    }

    StorageManagerScreen(
        state = state,
        onBackClick = onBackClick,
        onReconcile = {
            val granted = ContextCompat.checkSelfPermission(
                context,
                audioReadPermission,
            ) == PackageManager.PERMISSION_GRANTED
            if (granted) {
                viewModel.onReconcile()
            } else {
                permissionLauncher.launch(audioReadPermission)
            }
        },
        onDeleteTrack = viewModel::onDeleteTrack,
        onRetryTrack = viewModel::onRetryTrack,
        onCancelTrack = viewModel::onCancelTrack,
        onClearAllDownloads = viewModel::onClearAllDownloads,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StorageManagerScreen(
    state: StorageManagerUiState,
    onBackClick: () -> Unit,
    onReconcile: () -> Unit,
    onDeleteTrack: (String) -> Unit,
    onRetryTrack: (String) -> Unit,
    onCancelTrack: (String) -> Unit,
    onClearAllDownloads: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showClearAllDialog by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_storage_manager)) },
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
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "storage_summary_card") {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = Formatters.formatBytes(state.summary.totalBytes),
                            style = MaterialTheme.typography.headlineMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            text = stringResource(
                                R.string.storage_breakdown,
                                state.summary.completedCount,
                                state.summary.losslessCount,
                                state.summary.mp3Count,
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Folder,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(16.dp),
                            )
                            Text(
                                text = "${MediaStorePathBuilder.ROOT_RELATIVE_PATH}/",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            FilledTonalButton(
                                onClick = onReconcile,
                                enabled = !state.isBusy,
                                modifier = Modifier.weight(1f),
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Sync,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(stringResource(R.string.storage_reconcile))
                            }
                            OutlinedButton(
                                onClick = { showClearAllDialog = true },
                                enabled = !state.isBusy && state.summary.items.isNotEmpty(),
                                modifier = Modifier.weight(1f),
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.DeleteSweep,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(stringResource(R.string.storage_clear_all))
                            }
                        }
                        state.statusMessage?.let { msg ->
                            Text(
                                text = msg,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            if (state.summary.items.isEmpty()) {
                item(key = "storage_empty") {
                    EmptyState(
                        title = stringResource(R.string.library_downloaded_empty_title),
                        subtitle = stringResource(R.string.library_downloaded_empty_subtitle),
                        icon = Icons.Rounded.DownloadDone,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 48.dp),
                    )
                }
            } else {
                items(
                    items = state.summary.items,
                    key = { it.track.id },
                ) { item ->
                    StorageTrackRow(
                        item = item,
                        onDelete = { onDeleteTrack(item.track.id) },
                        onRetry = { onRetryTrack(item.track.id) },
                        onCancel = { onCancelTrack(item.track.id) },
                    )
                }
            }
        }
    }

    if (showClearAllDialog) {
        AlertDialog(
            onDismissRequest = { showClearAllDialog = false },
            title = { Text(stringResource(R.string.storage_clear_all_title)) },
            text = { Text(stringResource(R.string.storage_clear_all_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showClearAllDialog = false
                        onClearAllDownloads()
                    },
                ) {
                    Text(stringResource(R.string.action_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearAllDialog = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

@Composable
private fun StorageTrackRow(
    item: DownloadedTrackItem,
    onDelete: () -> Unit,
    onRetry: () -> Unit,
    onCancel: () -> Unit,
) {
    val record = item.record
    val statusDetail = when (record.status) {
        DownloadStatus.QUEUED -> stringResource(R.string.download_status_queued)
        DownloadStatus.DOWNLOADING -> stringResource(R.string.download_status_downloading)
        DownloadStatus.TRANSCODING -> stringResource(R.string.download_status_transcoding)
        DownloadStatus.FAILED -> record.errorMessage ?: stringResource(R.string.download_status_failed)
        DownloadStatus.COMPLETED -> {
            val qualityText = when (record.qualityMode) {
                DownloadQualityMode.LOSSLESS -> (item.track.codec ?: item.track.container ?: "Lossless").uppercase()
                DownloadQualityMode.MP3 -> {
                    val sourceTag = when (record.transcodeSource) {
                        TranscodeSource.ON_DEVICE -> "On-device"
                        TranscodeSource.SERVER -> "Server"
                        TranscodeSource.ORIGINAL -> "Original"
                    }
                    "MP3 ${record.bitrateKbps ?: 320} kbps ($sourceTag)"
                }
            }
            "$qualityText • ${Formatters.formatBytes(record.fileSizeBytes)}"
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ArtworkCard(
            imageUrl = item.track.artworkUrl,
            contentDescription = item.track.albumName,
            shape = MaterialTheme.shapes.extraSmall,
            fallbackIconSize = 20.dp,
            modifier = Modifier.size(48.dp),
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.track.title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = item.track.artistName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = statusDetail,
                style = MaterialTheme.typography.labelSmall,
                color = if (record.status == DownloadStatus.FAILED) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.primary
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        when (record.status) {
            DownloadStatus.QUEUED,
            DownloadStatus.DOWNLOADING,
            DownloadStatus.TRANSCODING,
            -> {
                CircularProgressIndicator(
                    modifier = Modifier
                        .padding(horizontal = 6.dp)
                        .size(20.dp),
                    strokeWidth = 2.dp,
                )
                IconButton(onClick = onCancel) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = stringResource(R.string.action_cancel),
                    )
                }
            }

            DownloadStatus.FAILED -> {
                IconButton(onClick = onRetry) {
                    Icon(
                        imageVector = Icons.Rounded.Refresh,
                        contentDescription = stringResource(R.string.action_retry),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
                IconButton(onClick = onDelete) {
                    Icon(
                        imageVector = Icons.Rounded.Delete,
                        contentDescription = stringResource(R.string.action_delete),
                    )
                }
            }

            DownloadStatus.COMPLETED -> {
                IconButton(onClick = onDelete) {
                    Icon(
                        imageVector = Icons.Rounded.Delete,
                        contentDescription = stringResource(R.string.action_delete),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
