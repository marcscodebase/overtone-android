package com.musicplayer.feature.auth

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.VpnLock
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.musicplayer.R

@Composable
fun AuthRoute(
    onAuthComplete: () -> Unit,
    onOpenWireGuardSetup: () -> Unit = {},
    viewModel: AuthViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(uiState.isCompleted) {
        if (uiState.isCompleted) {
            onAuthComplete()
        }
    }

    AuthScreen(
        state = uiState,
        onServerUrlChanged = viewModel::onServerUrlChanged,
        onConnectToServer = viewModel::onConnectToServer,
        onOpenWireGuardSetup = onOpenWireGuardSetup,
        onBackToServerStep = viewModel::onBackToServerStep,
        onLoginTabSelected = viewModel::onLoginTabSelected,
        onUsernameChanged = viewModel::onUsernameChanged,
        onPasswordChanged = viewModel::onPasswordChanged,
        onLoginWithPassword = viewModel::onLoginWithPassword,
        onRetryQuickConnect = viewModel::startQuickConnect,
        onToggleLibrary = viewModel::onToggleLibrary,
        onConfirmLibraries = viewModel::onConfirmLibraries,
    )
}

@Composable
fun AuthScreen(
    state: AuthUiState,
    onServerUrlChanged: (String) -> Unit,
    onConnectToServer: () -> Unit,
    onOpenWireGuardSetup: () -> Unit = {},
    onBackToServerStep: () -> Unit,
    onLoginTabSelected: (LoginTab) -> Unit,
    onUsernameChanged: (String) -> Unit,
    onPasswordChanged: (String) -> Unit,
    onLoginWithPassword: () -> Unit,
    onRetryQuickConnect: () -> Unit,
    onToggleLibrary: (String) -> Unit,
    onConfirmLibraries: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(modifier = modifier.fillMaxSize()) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(modifier = Modifier.height(16.dp))
            Surface(
                shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(72.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.MusicNote,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.padding(18.dp),
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.auth_tagline),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )

            Spacer(modifier = Modifier.height(32.dp))

            when (state.step) {
                AuthStep.SERVER_URL -> ServerUrlStep(
                    serverUrl = state.serverUrlInput,
                    isLoading = state.isLoading,
                    onServerUrlChanged = onServerUrlChanged,
                    onConnect = onConnectToServer,
                    onOpenWireGuardSetup = onOpenWireGuardSetup,
                )

                AuthStep.CREDENTIALS -> CredentialsStep(
                    state = state,
                    onBack = onBackToServerStep,
                    onTabSelected = onLoginTabSelected,
                    onUsernameChanged = onUsernameChanged,
                    onPasswordChanged = onPasswordChanged,
                    onLoginWithPassword = onLoginWithPassword,
                    onRetryQuickConnect = onRetryQuickConnect,
                )

                AuthStep.LIBRARY_PICKER -> LibraryPickerStep(
                    state = state,
                    onToggleLibrary = onToggleLibrary,
                    onConfirm = onConfirmLibraries,
                )
            }

            if (state.errorMessage != null) {
                Spacer(modifier = Modifier.height(16.dp))
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = state.errorMessage,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun ServerUrlStep(
    serverUrl: String,
    isLoading: Boolean,
    onServerUrlChanged: (String) -> Unit,
    onConnect: () -> Unit,
    onOpenWireGuardSetup: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = stringResource(R.string.auth_server_title),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        OutlinedTextField(
            value = serverUrl,
            onValueChange = onServerUrlChanged,
            label = { Text(stringResource(R.string.auth_server_url_label)) },
            placeholder = { Text(stringResource(R.string.auth_server_url_placeholder)) },
            leadingIcon = { Icon(Icons.Rounded.Dns, contentDescription = null) },
            singleLine = true,
            enabled = !isLoading,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Uri,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(onDone = { onConnect() }),
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            text = stringResource(R.string.auth_server_url_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Button(
            onClick = onConnect,
            enabled = !isLoading && serverUrl.isNotBlank(),
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp),
        ) {
            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            } else {
                Text(stringResource(R.string.auth_connect))
            }
        }
        FilledTonalButton(
            onClick = onOpenWireGuardSetup,
            enabled = !isLoading,
            modifier = Modifier
                .fillMaxWidth()
                .height(46.dp),
        ) {
            Icon(Icons.Rounded.VpnLock, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text(stringResource(R.string.wireguard_auth_button))
        }
    }
}

@Composable
private fun CredentialsStep(
    state: AuthUiState,
    onBack: () -> Unit,
    onTabSelected: (LoginTab) -> Unit,
    onUsernameChanged: (String) -> Unit,
    onPasswordChanged: (String) -> Unit,
    onLoginWithPassword: () -> Unit,
    onRetryQuickConnect: () -> Unit,
) {
    val server = state.serverInfo ?: return
    var passwordVisible by rememberSaveable { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            ),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = stringResource(R.string.action_back),
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = server.serverName,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = server.serverUrl,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        if (server.quickConnectEnabled) {
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                SegmentedButton(
                    selected = state.loginTab == LoginTab.PASSWORD,
                    onClick = { onTabSelected(LoginTab.PASSWORD) },
                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                ) {
                    Text(stringResource(R.string.auth_tab_password))
                }
                SegmentedButton(
                    selected = state.loginTab == LoginTab.QUICK_CONNECT,
                    onClick = { onTabSelected(LoginTab.QUICK_CONNECT) },
                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                ) {
                    Text(stringResource(R.string.auth_tab_quick_connect))
                }
            }
        }

        when (state.loginTab) {
            LoginTab.PASSWORD -> {
                OutlinedTextField(
                    value = state.username,
                    onValueChange = onUsernameChanged,
                    label = { Text(stringResource(R.string.auth_username_label)) },
                    singleLine = true,
                    enabled = !state.isLoading,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = state.password,
                    onValueChange = onPasswordChanged,
                    label = { Text(stringResource(R.string.auth_password_label)) },
                    singleLine = true,
                    enabled = !state.isLoading,
                    visualTransformation = if (passwordVisible) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    trailingIcon = {
                        IconButton(onClick = { passwordVisible = !passwordVisible }) {
                            Icon(
                                imageVector = if (passwordVisible) {
                                    Icons.Rounded.VisibilityOff
                                } else {
                                    Icons.Rounded.Visibility
                                },
                                contentDescription = null,
                            )
                        }
                    },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(onDone = { onLoginWithPassword() }),
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    onClick = onLoginWithPassword,
                    enabled = !state.isLoading && state.username.isNotBlank(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp),
                ) {
                    if (state.isLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                    } else {
                        Text(stringResource(R.string.auth_sign_in))
                    }
                }
            }

            LoginTab.QUICK_CONNECT -> {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.auth_quick_connect_instructions),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            textAlign = TextAlign.Center,
                        )
                        if (state.isLoading && state.quickConnectSession == null) {
                            CircularProgressIndicator()
                        } else if (state.quickConnectSession != null) {
                            Text(
                                text = state.quickConnectSession.code,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                fontSize = 36.sp,
                                letterSpacing = 6.sp,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                            if (state.isPollingQuickConnect) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        strokeWidth = 2.dp,
                                    )
                                    Text(
                                        text = stringResource(R.string.auth_quick_connect_waiting),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                                    )
                                }
                            }
                        }
                        FilledTonalButton(onClick = onRetryQuickConnect) {
                            Text(stringResource(R.string.auth_quick_connect_new_code))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LibraryPickerStep(
    state: AuthUiState,
    onToggleLibrary: (String) -> Unit,
    onConfirm: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = stringResource(R.string.auth_select_libraries_title),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = stringResource(R.string.auth_select_libraries_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        state.availableLibraries.forEach { library ->
            val checked = library.id in state.selectedLibraryIds
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onToggleLibrary(library.id) },
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.LibraryMusic,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = library.name,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    Checkbox(
                        checked = checked,
                        onCheckedChange = { onToggleLibrary(library.id) },
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Button(
            onClick = onConfirm,
            enabled = state.selectedLibraryIds.isNotEmpty(),
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp),
        ) {
            Text(stringResource(R.string.auth_continue))
        }
    }
}
