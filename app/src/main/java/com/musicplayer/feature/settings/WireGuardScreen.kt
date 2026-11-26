package com.musicplayer.feature.settings

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.FileOpen
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.NetworkCheck
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.VpnLock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.musicplayer.R
import com.musicplayer.core.common.Formatters
import com.musicplayer.core.model.WireGuardMode
import com.musicplayer.core.model.WireGuardTunnelState
import kotlinx.coroutines.delay

@Composable
fun WireGuardRoute(
    onBackClick: () -> Unit,
    viewModel: WireGuardViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(state.runtimeStatus.isRoutingThroughTunnel) {
        if (!state.runtimeStatus.isRoutingThroughTunnel) return@LaunchedEffect
        while (true) {
            viewModel.onRefreshStats()
            delay(3_000L)
        }
    }

    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri != null) {
            val imported = readConfigFromUri(context, uri)
            if (imported != null) {
                viewModel.onImportRawConfig(
                    rawText = imported.second,
                    profileName = imported.first,
                )
            } else {
                viewModel.onSetFeedback("Could not read selected .conf file")
            }
        }
    }

    WireGuardScreen(
        state = state,
        onBackClick = onBackClick,
        onPickConfigFile = { filePicker.launch(arrayOf("*/*")) },
        onImportPastedConfig = viewModel::onImportRawConfig,
        onSelectMode = viewModel::onSelectMode,
        onTestConnection = viewModel::onTestConnection,
        onDeleteConfig = viewModel::onDeleteConfig,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WireGuardScreen(
    state: WireGuardUiState,
    onBackClick: () -> Unit,
    onPickConfigFile: () -> Unit,
    onImportPastedConfig: (rawText: String, profileName: String) -> Boolean,
    onSelectMode: (WireGuardMode) -> Unit,
    onTestConnection: () -> Unit,
    onDeleteConfig: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showPasteDialog by rememberSaveable { mutableStateOf(false) }
    var pastedConfigText by rememberSaveable { mutableStateOf("") }
    var pastedProfileName by rememberSaveable { mutableStateOf("Home Network") }
    var showDeleteConfirm by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.wireguard_title)) },
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
            WireGuardStatusCard(state = state)

            if (!state.isNativeAvailable) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = stringResource(R.string.wireguard_native_unavailable),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }

            if (state.config != null) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.wireguard_mode_title),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        val modes = WireGuardMode.entries
                        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                            modes.forEachIndexed { index, mode ->
                                SegmentedButton(
                                    selected = state.mode == mode,
                                    onClick = { onSelectMode(mode) },
                                    shape = SegmentedButtonDefaults.itemShape(index = index, count = modes.size),
                                ) {
                                    Text(
                                        text = when (mode) {
                                            WireGuardMode.OFF -> stringResource(R.string.wireguard_mode_off)
                                            WireGuardMode.AUTO -> stringResource(R.string.wireguard_mode_auto)
                                            WireGuardMode.ALWAYS -> stringResource(R.string.wireguard_mode_always)
                                        },
                                        maxLines = 1,
                                    )
                                }
                            }
                        }
                        Text(
                            text = when (state.mode) {
                                WireGuardMode.OFF -> stringResource(R.string.wireguard_mode_off_desc)
                                WireGuardMode.AUTO -> stringResource(R.string.wireguard_mode_auto_desc)
                                WireGuardMode.ALWAYS -> stringResource(R.string.wireguard_mode_always_desc)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                val cfg = state.config
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
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                text = cfg.profileName,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            ConfigInfoRow(
                                label = stringResource(R.string.wireguard_field_endpoint),
                                value = cfg.endpoint,
                            )
                            ConfigInfoRow(
                                label = stringResource(R.string.wireguard_field_address),
                                value = cfg.addressesDisplay,
                            )
                            if (cfg.dnsServers.isNotEmpty()) {
                                ConfigInfoRow(
                                    label = stringResource(R.string.wireguard_field_dns),
                                    value = cfg.dnsDisplay,
                                )
                            }
                            ConfigInfoRow(
                                label = stringResource(R.string.wireguard_field_allowed_ips),
                                value = cfg.allowedIpsDisplay,
                            )
                            ConfigInfoRow(
                                label = stringResource(R.string.wireguard_field_peer_key),
                                value = cfg.peerPublicKeyPreview,
                            )
                        }
                        HorizontalDivider()
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Button(
                                onClick = onTestConnection,
                                enabled = !state.isTesting && state.isNativeAvailable,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp),
                            ) {
                                if (state.isTesting) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(18.dp),
                                        strokeWidth = 2.dp,
                                        color = MaterialTheme.colorScheme.onPrimary,
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(stringResource(R.string.wireguard_testing))
                                } else {
                                    Icon(Icons.Rounded.NetworkCheck, contentDescription = null)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(stringResource(R.string.wireguard_test_connection))
                                }
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                FilledTonalButton(
                                    onClick = onPickConfigFile,
                                    modifier = Modifier.weight(1f),
                                ) {
                                    Icon(Icons.Rounded.FileOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(stringResource(R.string.wireguard_import_file))
                                }
                                FilledTonalButton(
                                    onClick = {
                                        pastedProfileName = cfg.profileName
                                        pastedConfigText = ""
                                        showPasteDialog = true
                                    },
                                    modifier = Modifier.weight(1f),
                                ) {
                                    Icon(Icons.Rounded.ContentPaste, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(stringResource(R.string.wireguard_paste_config))
                                }
                            }
                            OutlinedButton(
                                onClick = { showDeleteConfirm = true },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Icon(Icons.Rounded.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(stringResource(R.string.wireguard_delete_profile))
                            }
                        }
                    }
                }
            } else {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.wireguard_setup_title),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = stringResource(R.string.wireguard_setup_description),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Button(
                            onClick = onPickConfigFile,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp),
                        ) {
                            Icon(Icons.Rounded.FileOpen, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.wireguard_import_conf_button))
                        }
                        FilledTonalButton(
                            onClick = {
                                pastedProfileName = "Home Network"
                                pastedConfigText = ""
                                showPasteDialog = true
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp),
                        ) {
                            Icon(Icons.Rounded.ContentPaste, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.wireguard_paste_conf_button))
                        }
                    }
                }
            }

            state.testResult?.let { result ->
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = if (result.success) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.errorContainer
                        },
                    ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = if (result.success) Icons.Rounded.CheckCircle else Icons.Rounded.ErrorOutline,
                            contentDescription = null,
                            tint = if (result.success) {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            } else {
                                MaterialTheme.colorScheme.onErrorContainer
                            },
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = result.message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (result.success) {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            } else {
                                MaterialTheme.colorScheme.onErrorContainer
                            },
                        )
                    }
                }
            }

            state.feedbackMessage?.let { msg ->
                Text(
                    text = msg,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    if (showPasteDialog) {
        AlertDialog(
            onDismissRequest = { showPasteDialog = false },
            title = { Text(stringResource(R.string.wireguard_paste_conf_button)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = pastedProfileName,
                        onValueChange = { pastedProfileName = it },
                        label = { Text(stringResource(R.string.wireguard_profile_name_label)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = pastedConfigText,
                        onValueChange = { pastedConfigText = it },
                        label = { Text(stringResource(R.string.wireguard_conf_text_label)) },
                        placeholder = {
                            Text(
                                text = "[Interface]\nPrivateKey = ...\nAddress = 10.0.0.2/32\n\n[Peer]\nPublicKey = ...\nEndpoint = home.example:51820\nAllowedIPs = 192.168.178.0/24",
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            )
                        },
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        minLines = 6,
                        maxLines = 12,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (onImportPastedConfig(pastedConfigText, pastedProfileName)) {
                            showPasteDialog = false
                        }
                    },
                    enabled = pastedConfigText.isNotBlank(),
                ) {
                    Text(stringResource(R.string.action_save))
                }
            },
            dismissButton = {
                TextButton(onClick = { showPasteDialog = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text(stringResource(R.string.wireguard_delete_confirm_title)) },
            text = { Text(stringResource(R.string.wireguard_delete_confirm_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteConfirm = false
                        onDeleteConfig()
                    },
                ) {
                    Text(stringResource(R.string.action_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

@Composable
private fun WireGuardStatusCard(state: WireGuardUiState) {
    val runtime = state.runtimeStatus
    val (containerColor, contentColor, icon, titleText) = when (runtime.state) {
        WireGuardTunnelState.CONNECTED -> Quad(
            MaterialTheme.colorScheme.primaryContainer,
            MaterialTheme.colorScheme.onPrimaryContainer,
            Icons.Rounded.VpnLock,
            stringResource(R.string.wireguard_state_connected),
        )

        WireGuardTunnelState.DIRECT_LOCAL -> Quad(
            MaterialTheme.colorScheme.secondaryContainer,
            MaterialTheme.colorScheme.onSecondaryContainer,
            Icons.Rounded.Home,
            stringResource(R.string.wireguard_state_direct_local),
        )

        WireGuardTunnelState.CONNECTING -> Quad(
            MaterialTheme.colorScheme.surfaceContainerHigh,
            MaterialTheme.colorScheme.onSurface,
            Icons.Rounded.Shield,
            stringResource(R.string.wireguard_state_connecting),
        )

        WireGuardTunnelState.ERROR -> Quad(
            MaterialTheme.colorScheme.errorContainer,
            MaterialTheme.colorScheme.onErrorContainer,
            Icons.Rounded.ErrorOutline,
            stringResource(R.string.wireguard_state_error),
        )

        WireGuardTunnelState.DISCONNECTED -> Quad(
            MaterialTheme.colorScheme.surfaceContainerLow,
            MaterialTheme.colorScheme.onSurface,
            Icons.Rounded.Shield,
            stringResource(R.string.wireguard_state_disconnected),
        )
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = containerColor),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = contentColor,
                    modifier = Modifier.size(24.dp),
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = titleText,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = contentColor,
                    )
                    Text(
                        text = stringResource(R.string.wireguard_app_only_badge),
                        style = MaterialTheme.typography.labelSmall,
                        color = contentColor.copy(alpha = 0.8f),
                    )
                }
            }

            if (runtime.state == WireGuardTunnelState.CONNECTED) {
                val handshakeText = formatHandshakeAge(runtime.lastHandshakeEpochSec)
                val transferText = "RX ${Formatters.formatBytes(runtime.rxBytes)} • TX ${Formatters.formatBytes(runtime.txBytes)}"
                Text(
                    text = "$handshakeText • $transferText",
                    style = MaterialTheme.typography.bodySmall,
                    color = contentColor.copy(alpha = 0.88f),
                )
            } else if (runtime.state == WireGuardTunnelState.DIRECT_LOCAL) {
                Text(
                    text = stringResource(R.string.wireguard_direct_local_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = contentColor.copy(alpha = 0.88f),
                )
            } else if (!runtime.errorMessage.isNullOrBlank()) {
                Text(
                    text = runtime.errorMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = contentColor,
                )
            }
        }
    }
}

@Composable
private fun ConfigInfoRow(
    label: String,
    value: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private data class Quad<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)

private fun formatHandshakeAge(epochSec: Long): String {
    if (epochSec <= 0L) return "Awaiting handshake"
    val ageSec = (System.currentTimeMillis() / 1000L - epochSec).coerceAtLeast(0L)
    return when {
        ageSec < 10L -> "Handshake just now"
        ageSec < 60L -> "Handshake ${ageSec}s ago"
        ageSec < 3600L -> "Handshake ${ageSec / 60}m ago"
        else -> "Handshake ${ageSec / 3600}h ago"
    }
}

private fun readConfigFromUri(context: Context, uri: Uri): Pair<String, String>? = runCatching {
    val content = context.contentResolver.openInputStream(uri)?.use { stream ->
        stream.bufferedReader(Charsets.UTF_8).readText()
    } ?: return null

    var displayName: String? = null
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) {
            val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0) {
                displayName = cursor.getString(idx)
            }
        }
    }
    val profileName = displayName
        ?.substringBeforeLast('.')
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?: "Home Network"
    profileName to content
}.getOrNull()
