package com.musicplayer.feature.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.VpnLock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
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
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.musicplayer.R
import com.musicplayer.core.common.Formatters
import com.musicplayer.core.model.CellularBitrateCap
import com.musicplayer.core.model.DownloadQuality
import com.musicplayer.core.model.DownloadQualityMode
import com.musicplayer.core.model.ReplayGainMode
import com.musicplayer.core.model.WireGuardMode
import com.musicplayer.core.model.WireGuardTunnelState
import com.musicplayer.feature.download.downloadQualityPresets
import com.musicplayer.feature.download.formatDownloadQualityLabel
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun SettingsRoute(
    onBackClick: () -> Unit,
    onOpenStorageManager: () -> Unit,
    onOpenWireGuard: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    SettingsScreen(
        state = state,
        onBackClick = onBackClick,
        onOpenStorageManager = onOpenStorageManager,
        onOpenWireGuard = onOpenWireGuard,
        onToggleOfflineMode = viewModel::onToggleOfflineMode,
        onSelectCellularBitrateCap = viewModel::onSelectCellularBitrateCap,
        onToggleAutoMatchOutputQuality = viewModel::onToggleAutoMatchOutputQuality,
        onSelectDefaultDownloadQuality = viewModel::onSelectDefaultDownloadQuality,
        onToggleWifiOnlyDownloads = viewModel::onToggleWifiOnlyDownloads,
        onToggleAskDownloadQuality = viewModel::onToggleAskDownloadQuality,
        onSelectReplayGainMode = viewModel::onSelectReplayGainMode,
        onSetReplayGainPreampDb = viewModel::onSetReplayGainPreampDb,
        onFlushPendingSync = viewModel::onFlushPendingSync,
        onSyncLibrary = viewModel::onSyncLibrary,
        onSignOut = viewModel::onSignOut,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    state: SettingsUiState,
    onBackClick: () -> Unit,
    onOpenStorageManager: () -> Unit,
    onOpenWireGuard: () -> Unit,
    onToggleOfflineMode: (Boolean) -> Unit,
    onSelectCellularBitrateCap: (CellularBitrateCap) -> Unit,
    onToggleAutoMatchOutputQuality: (Boolean) -> Unit,
    onSelectDefaultDownloadQuality: (DownloadQuality) -> Unit,
    onToggleWifiOnlyDownloads: (Boolean) -> Unit,
    onToggleAskDownloadQuality: (Boolean) -> Unit,
    onSelectReplayGainMode: (ReplayGainMode) -> Unit,
    onSetReplayGainPreampDb: (Float) -> Unit,
    onFlushPendingSync: () -> Unit,
    onSyncLibrary: () -> Unit,
    onSignOut: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showCellularDialog by rememberSaveable { mutableStateOf(false) }
    var showDownloadQualityDialog by rememberSaveable { mutableStateOf(false) }
    var showReplayGainDialog by rememberSaveable { mutableStateOf(false) }
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.action_settings)) },
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            state.session?.let { session ->
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
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Rounded.Dns,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = session.serverName,
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                        Text(
                            text = session.serverUrl,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = stringResource(R.string.settings_signed_in_as, session.username),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }

            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onOpenWireGuard)
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.VpnLock,
                        contentDescription = null,
                        tint = if (state.wireGuardStatus.state == WireGuardTunnelState.CONNECTED) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.wireguard_title),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        val wgSubtitle = when {
                            state.wireGuardConfig == null ->
                                stringResource(R.string.wireguard_settings_subtitle_unconfigured)

                            state.wireGuardStatus.state == WireGuardTunnelState.CONNECTED ->
                                "${stringResource(R.string.wireguard_state_connected)} • ${formatWireGuardModeLabel(state.preferences.wireGuardMode)}"

                            state.wireGuardStatus.state == WireGuardTunnelState.DIRECT_LOCAL ->
                                "${stringResource(R.string.wireguard_state_direct_local)} • ${formatWireGuardModeLabel(state.preferences.wireGuardMode)}"

                            state.wireGuardStatus.state == WireGuardTunnelState.CONNECTING ->
                                stringResource(R.string.wireguard_state_connecting)

                            state.wireGuardStatus.state == WireGuardTunnelState.ERROR ->
                                state.wireGuardStatus.errorMessage ?: stringResource(R.string.wireguard_state_error)

                            else ->
                                "${state.wireGuardConfig.profileName} • ${formatWireGuardModeLabel(state.preferences.wireGuardMode)}"
                        }
                        Text(
                            text = wgSubtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = when (state.wireGuardStatus.state) {
                                WireGuardTunnelState.CONNECTED,
                                WireGuardTunnelState.DIRECT_LOCAL,
                                -> MaterialTheme.colorScheme.primary

                                WireGuardTunnelState.ERROR -> MaterialTheme.colorScheme.error
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.settings_wifi_quality),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = stringResource(R.string.settings_wifi_quality_value),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    HorizontalDivider()
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showCellularDialog = true }
                            .padding(16.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.settings_cellular_quality),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = formatCellularCapLabel(state.preferences.cellularBitrateCap),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    HorizontalDivider()
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.settings_auto_match_output_quality),
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = stringResource(R.string.settings_auto_match_output_quality_subtitle),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Switch(
                            checked = state.preferences.autoMatchOutputQuality,
                            onCheckedChange = onToggleAutoMatchOutputQuality,
                        )
                    }
                    HorizontalDivider()
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showReplayGainDialog = true }
                            .padding(16.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.settings_replay_gain),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = formatReplayGainModeLabel(state.preferences.replayGainMode),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    if (state.preferences.replayGainMode != ReplayGainMode.OFF) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(
                                    text = stringResource(R.string.settings_replay_gain_preamp),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(
                                    text = String.format(Locale.US, "%+.1f dB", state.preferences.replayGainPreampDb),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                            Slider(
                                value = state.preferences.replayGainPreampDb.coerceIn(-6f, 6f),
                                onValueChange = { raw ->
                                    val snapped = (raw * 2f).roundToInt() / 2f
                                    onSetReplayGainPreampDb(snapped)
                                },
                                valueRange = -6f..6f,
                                steps = 23,
                            )
                        }
                    }
                }
            }

            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showDownloadQualityDialog = true }
                            .padding(16.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.settings_download_quality),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = formatDownloadQualityLabel(state.preferences.defaultDownloadQuality),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    HorizontalDivider()
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.settings_wifi_only_downloads),
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = stringResource(R.string.settings_wifi_only_downloads_subtitle),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = state.preferences.wifiOnlyDownloads,
                            onCheckedChange = onToggleWifiOnlyDownloads,
                        )
                    }
                    HorizontalDivider()
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.settings_ask_download_quality),
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = stringResource(R.string.settings_ask_download_quality_subtitle),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = state.preferences.askDownloadQualityEachTime,
                            onCheckedChange = onToggleAskDownloadQuality,
                        )
                    }
                    HorizontalDivider()
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(onClick = onOpenStorageManager)
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.settings_storage_manager),
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = if (state.downloadedCount > 0) {
                                    "${state.downloadedCount} songs • ${Formatters.formatBytes(state.downloadedBytes)}"
                                } else {
                                    stringResource(R.string.settings_storage_manager_subtitle)
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = if (state.downloadedCount > 0) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        }
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.settings_offline_mode),
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = stringResource(R.string.settings_offline_mode_subtitle),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = state.preferences.offlineModeEnabled,
                            onCheckedChange = onToggleOfflineMode,
                        )
                    }
                    if (state.pendingSyncCount > 0) {
                        HorizontalDivider()
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                text = stringResource(R.string.offline_sync_pending, state.pendingSyncCount),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(
                                onClick = onFlushPendingSync,
                                enabled = !state.preferences.offlineModeEnabled,
                            ) {
                                Text(stringResource(R.string.offline_sync_now))
                            }
                        }
                    }
                }
            }

            FilledTonalButton(
                onClick = onSyncLibrary,
                enabled = !state.isSyncing,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
            ) {
                if (state.isSyncing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                    )
                } else {
                    Icon(Icons.Rounded.Sync, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.action_refresh))
                }
            }

            state.statusMessage?.let { status ->
                Text(
                    text = status,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            OutlinedButton(
                onClick = onSignOut,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
            ) {
                Icon(Icons.AutoMirrored.Rounded.Logout, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.action_sign_out))
            }
        }
    }

    if (showCellularDialog) {
        AlertDialog(
            onDismissRequest = { showCellularDialog = false },
            title = { Text(stringResource(R.string.settings_cellular_quality)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    CellularBitrateCap.entries.forEach { cap ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onSelectCellularBitrateCap(cap)
                                    showCellularDialog = false
                                }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = state.preferences.cellularBitrateCap == cap,
                                onClick = {
                                    onSelectCellularBitrateCap(cap)
                                    showCellularDialog = false
                                },
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = formatCellularCapLabel(cap),
                                style = MaterialTheme.typography.bodyLarge,
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showCellularDialog = false }) {
                    Text(stringResource(R.string.action_close))
                }
            },
        )
    }
    if (showDownloadQualityDialog) {
        val current = state.preferences.defaultDownloadQuality
        AlertDialog(
            onDismissRequest = { showDownloadQualityDialog = false },
            title = { Text(stringResource(R.string.settings_download_quality)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    downloadQualityPresets.forEach { preset ->
                        val selected = current.mode == preset.mode &&
                            (preset.mode == DownloadQualityMode.LOSSLESS || current.mp3Bitrate == preset.mp3Bitrate)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onSelectDefaultDownloadQuality(preset)
                                    showDownloadQualityDialog = false
                                }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = selected,
                                onClick = {
                                    onSelectDefaultDownloadQuality(preset)
                                    showDownloadQualityDialog = false
                                },
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = formatDownloadQualityLabel(preset),
                                style = MaterialTheme.typography.bodyLarge,
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showDownloadQualityDialog = false }) {
                    Text(stringResource(R.string.action_close))
                }
            },
        )
    }
    if (showReplayGainDialog) {
        AlertDialog(
            onDismissRequest = { showReplayGainDialog = false },
            title = { Text(stringResource(R.string.settings_replay_gain)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    ReplayGainMode.entries.forEach { mode ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onSelectReplayGainMode(mode)
                                    showReplayGainDialog = false
                                }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = state.preferences.replayGainMode == mode,
                                onClick = {
                                    onSelectReplayGainMode(mode)
                                    showReplayGainDialog = false
                                },
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = formatReplayGainModeLabel(mode),
                                style = MaterialTheme.typography.bodyLarge,
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showReplayGainDialog = false }) {
                    Text(stringResource(R.string.action_close))
                }
            },
        )
    }
}

@Composable
private fun formatCellularCapLabel(cap: CellularBitrateCap): String = when (cap) {
    CellularBitrateCap.ORIGINAL -> stringResource(R.string.settings_quality_original)
    else -> "${cap.kbps} kbps (MP3)"
}

@Composable
private fun formatReplayGainModeLabel(mode: ReplayGainMode): String = when (mode) {
    ReplayGainMode.OFF -> stringResource(R.string.settings_replay_gain_off)
    ReplayGainMode.TRACK -> stringResource(R.string.settings_replay_gain_track)
    ReplayGainMode.ALBUM -> stringResource(R.string.settings_replay_gain_album)
}

@Composable
private fun formatWireGuardModeLabel(mode: WireGuardMode): String = when (mode) {
    WireGuardMode.OFF -> stringResource(R.string.wireguard_mode_off)
    WireGuardMode.AUTO -> stringResource(R.string.wireguard_mode_auto)
    WireGuardMode.ALWAYS -> stringResource(R.string.wireguard_mode_always)
}
