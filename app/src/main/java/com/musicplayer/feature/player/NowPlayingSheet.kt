package com.musicplayer.feature.player

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.AddCircleOutline
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DataSaverOn
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.HighQuality
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.musicplayer.R
import com.musicplayer.core.common.Formatters
import com.musicplayer.core.database.entity.DownloadRecordEntity
import com.musicplayer.core.designsystem.component.ArtworkCard
import com.musicplayer.core.model.AudioOutputQualityPolicy
import com.musicplayer.core.model.AudioOutputState
import com.musicplayer.core.model.DownloadQualityMode
import com.musicplayer.core.model.DownloadStatus
import com.musicplayer.core.model.LyricsParser
import com.musicplayer.core.model.StreamQualitySelection
import com.musicplayer.player.PlaybackState
import com.musicplayer.player.RepeatMode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NowPlayingSheet(
    state: PlaybackState,
    lyricsState: LyricsUiState,
    audioOutputState: AudioOutputState,
    hiResBluetoothDevices: Set<String>,
    downloadRecord: DownloadRecordEntity?,
    isTrackSaved: Boolean,
    lowQualityBitrateKbps: Int,
    isOffline: Boolean,
    onDismiss: () -> Unit,
    onPlayingFromAlbumClick: (String) -> Unit,
    onTrackTitleOrArtistClick: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onSaveOrAddToPlaylist: () -> Unit,
    onToggleDownload: () -> Unit,
    onSelectStreamQuality: (StreamQualitySelection) -> Unit,
    onSkipNext: () -> Unit,
    onSkipPrevious: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onToggleShuffle: () -> Unit,
    onCycleRepeatMode: () -> Unit,
    onOpenAudioOutput: () -> Unit,
    onOpenQueue: () -> Unit,
    onOpenLyrics: () -> Unit,
) {
    val track = state.currentTrack ?: return
    val downloadStatus = downloadRecord?.status
    val isTrackDownloaded = downloadStatus == DownloadStatus.COMPLETED &&
        !downloadRecord.mediaStoreUri.isNullOrBlank()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var qualityMenuExpanded by remember(track.id) { mutableStateOf(false) }

    val loadedLyrics = lyricsState.lyrics?.takeIf { lyricsState.trackId == track.id && it.lines.isNotEmpty() }
    val hasLyrics = loadedLyrics != null || track.hasLyrics
    val syncedLyrics = loadedLyrics?.takeIf { it.isSynced }

    val activeOutput = audioOutputState.activeDevice
    val outputCaps = remember(activeOutput, hiResBluetoothDevices) {
        AudioOutputQualityPolicy.evaluate(activeOutput, hiResBluetoothDevices)
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        dragHandle = null,
        containerColor = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxSize(),
    ) {
        var isDraggingSlider by remember(track.id) { mutableStateOf(false) }
        var sliderPosition by remember(track.id) { mutableFloatStateOf(0f) }

        val durationMs = state.durationMs.coerceAtLeast(1L)
        val displayedPositionMs = if (isDraggingSlider) {
            (sliderPosition * durationMs).toLong()
        } else {
            state.positionMs
        }
        val currentFraction = if (isDraggingSlider) {
            sliderPosition
        } else {
            state.progressFraction
        }

        val highFormatLabel = Formatters.formatAudioQualityBadge(
            container = track.container,
            codec = track.codec,
            bitrate = track.bitrate,
            sampleRate = track.sampleRate,
            bitDepth = track.bitDepth,
            isLocal = false,
        )
        val localFormatLabel = when {
            downloadRecord?.qualityMode == DownloadQualityMode.MP3 ->
                "MP3 • ${downloadRecord.bitrateKbps ?: 320} kbps • Local"
            else -> "$highFormatLabel • Local"
        }

        val qualityBadge = when {
            state.isLocalPlayback && state.transcodedBitrateKbps != null -> {
                "MP3 • ${state.transcodedBitrateKbps} kbps • Local"
            }

            state.transcodedBitrateKbps != null && !state.isLocalPlayback -> {
                "MP3 • ${state.transcodedBitrateKbps} kbps"
            }

            else -> {
                Formatters.formatAudioQualityBadge(
                    container = track.container,
                    codec = track.codec,
                    bitrate = track.bitrate,
                    sampleRate = track.sampleRate,
                    bitDepth = track.bitDepth,
                    isLocal = state.isLocalPlayback,
                )
            }
        }

        val qualityMenu = remember(
            state.isLocalPlayback,
            state.transcodedBitrateKbps,
            isTrackDownloaded,
            isOffline,
            highFormatLabel,
            lowQualityBitrateKbps,
            localFormatLabel,
        ) {
            QualitySwitcherMenuBuilder.build(
                isLocalPlayback = state.isLocalPlayback,
                transcodedBitrateKbps = state.transcodedBitrateKbps,
                isTrackDownloaded = isTrackDownloaded,
                isOffline = isOffline,
                highQualitySubtitle = highFormatLabel,
                lowQualityBitrateKbps = lowQualityBitrateKbps,
                localQualitySubtitle = localFormatLabel,
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.Rounded.KeyboardArrowDown,
                        contentDescription = stringResource(R.string.player_dismiss),
                        modifier = Modifier.size(30.dp),
                    )
                }
                val albumId = track.albumId
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clip(CircleShape)
                        .let { mod ->
                            if (!albumId.isNullOrBlank()) {
                                mod.clickable { onPlayingFromAlbumClick(albumId) }
                            } else {
                                mod
                            }
                        }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = stringResource(R.string.player_playing_from, track.albumName),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                    )
                }
                Spacer(modifier = Modifier.size(48.dp))
            }

            Spacer(modifier = Modifier.weight(0.45f))

            ArtworkCard(
                imageUrl = track.artworkUrl,
                contentDescription = track.albumName,
                shape = MaterialTheme.shapes.large,
                fallbackIconSize = 72.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
            )

            if (syncedLyrics != null) {
                val activeIndex = LyricsParser.findActiveLineIndex(syncedLyrics.lines, displayedPositionMs)
                val activeLineText = syncedLyrics.lines.getOrNull(activeIndex)?.text
                    ?: syncedLyrics.lines.firstOrNull()?.text.orEmpty()

                Spacer(modifier = Modifier.height(12.dp))
                Surface(
                    onClick = onOpenLyrics,
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        AnimatedContent(
                            targetState = activeLineText,
                            transitionSpec = {
                                (fadeIn() + slideInVertically { it / 3 }) togetherWith
                                    (fadeOut() + slideOutVertically { -it / 3 })
                            },
                            label = "live_lyric_line",
                        ) { line ->
                            Text(
                                text = line,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                textAlign = TextAlign.Center,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Box {
                AssistChip(
                    onClick = { qualityMenuExpanded = true },
                    label = {
                        Text(
                            text = qualityBadge,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    },
                    trailingIcon = {
                        Icon(
                            imageVector = Icons.Rounded.KeyboardArrowDown,
                            contentDescription = stringResource(R.string.player_switch_quality),
                            modifier = Modifier.size(16.dp),
                        )
                    },
                )
                DropdownMenu(
                    expanded = qualityMenuExpanded,
                    onDismissRequest = { qualityMenuExpanded = false },
                ) {
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(
                                    text = qualityMenu.active.title,
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                Text(
                                    text = qualityMenu.active.subtitle,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Rounded.Check,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        },
                        onClick = { qualityMenuExpanded = false },
                    )
                    if (qualityMenu.alternatives.isNotEmpty()) {
                        HorizontalDivider()
                        qualityMenu.alternatives.forEach { option ->
                            val icon = when (option.selection) {
                                StreamQualitySelection.HIGH -> Icons.Rounded.HighQuality
                                StreamQualitySelection.LOW -> Icons.Rounded.DataSaverOn
                                StreamQualitySelection.LOCAL -> Icons.Rounded.DownloadDone
                                StreamQualitySelection.AUTO -> Icons.Rounded.HighQuality
                            }
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text(
                                            text = option.title,
                                            style = MaterialTheme.typography.bodyLarge,
                                        )
                                        Text(
                                            text = option.subtitle,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                },
                                leadingIcon = {
                                    Icon(imageVector = icon, contentDescription = null)
                                },
                                onClick = {
                                    qualityMenuExpanded = false
                                    onSelectStreamQuality(option.selection)
                                },
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.weight(0.45f))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val hasTrackLinks = !track.albumId.isNullOrBlank() || track.resolvedArtists.isNotEmpty()
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clip(MaterialTheme.shapes.small)
                        .let { mod ->
                            if (hasTrackLinks) mod.clickable(onClick = onTrackTitleOrArtistClick) else mod
                        }
                        .padding(vertical = 2.dp),
                ) {
                    Text(
                        text = track.title,
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = track.artistName,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(onClick = onToggleDownload) {
                    when (downloadStatus) {
                        DownloadStatus.QUEUED,
                        DownloadStatus.DOWNLOADING,
                        DownloadStatus.TRANSCODING,
                        -> {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                            )
                        }

                        DownloadStatus.COMPLETED -> {
                            Icon(
                                imageVector = Icons.Rounded.DownloadDone,
                                contentDescription = stringResource(R.string.download_remove),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }

                        DownloadStatus.FAILED -> {
                            Icon(
                                imageVector = Icons.Rounded.ErrorOutline,
                                contentDescription = stringResource(R.string.action_retry),
                                tint = MaterialTheme.colorScheme.error,
                            )
                        }

                        null -> {
                            Icon(
                                imageVector = Icons.Rounded.Download,
                                contentDescription = stringResource(R.string.action_download),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                IconButton(onClick = onSaveOrAddToPlaylist) {
                    Icon(
                        imageVector = if (isTrackSaved) {
                            Icons.Rounded.CheckCircle
                        } else {
                            Icons.Rounded.AddCircleOutline
                        },
                        contentDescription = stringResource(R.string.playlist_save_to),
                        tint = if (isTrackSaved) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.size(26.dp),
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Slider(
                value = currentFraction,
                onValueChange = { fraction ->
                    isDraggingSlider = true
                    sliderPosition = fraction
                },
                onValueChangeFinished = {
                    val targetMs = (sliderPosition * durationMs).toLong()
                    isDraggingSlider = false
                    onSeekTo(targetMs)
                },
                modifier = Modifier.fillMaxWidth(),
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = Formatters.formatDuration(displayedPositionMs),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = Formatters.formatDuration(state.durationMs),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TransportToggleButton(
                    icon = Icons.Rounded.Shuffle,
                    contentDescription = stringResource(R.string.player_shuffle),
                    active = state.shuffleEnabled,
                    onClick = onToggleShuffle,
                )

                IconButton(
                    onClick = onSkipPrevious,
                    modifier = Modifier.size(52.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.SkipPrevious,
                        contentDescription = stringResource(R.string.player_previous),
                        modifier = Modifier.size(36.dp),
                    )
                }

                FilledIconButton(
                    onClick = onTogglePlayPause,
                    shape = CircleShape,
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    ),
                    modifier = Modifier.size(68.dp),
                ) {
                    if (state.isBuffering) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(28.dp),
                            strokeWidth = 3.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                    } else {
                        Icon(
                            imageVector = if (state.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                            contentDescription = stringResource(R.string.player_play_pause),
                            modifier = Modifier.size(36.dp),
                        )
                    }
                }

                IconButton(
                    onClick = onSkipNext,
                    modifier = Modifier.size(52.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.SkipNext,
                        contentDescription = stringResource(R.string.player_next),
                        modifier = Modifier.size(36.dp),
                    )
                }

                val repeatIcon = if (state.repeatMode == RepeatMode.ONE) {
                    Icons.Rounded.RepeatOne
                } else {
                    Icons.Rounded.Repeat
                }
                TransportToggleButton(
                    icon = repeatIcon,
                    contentDescription = stringResource(R.string.player_repeat),
                    active = state.repeatMode != RepeatMode.OFF,
                    onClick = onCycleRepeatMode,
                )
            }

            state.errorMessage?.let { err ->
                Spacer(modifier = Modifier.height(10.dp))
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = err,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(12.dp),
                    )
                }
            }

            Spacer(modifier = Modifier.weight(0.35f))

            // Spotify-style bottom utility bar: Output device on far left, Lyrics (when available) + Queue on far right
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .clip(MaterialTheme.shapes.small)
                        .clickable(onClick = onOpenAudioOutput)
                        .padding(horizontal = 6.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = audioOutputIcon(activeOutput.type),
                        contentDescription = stringResource(R.string.audio_output_title),
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = activeOutput.name,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = "${outputCaps.codecLabel} • ${outputCaps.maxQualityLabel}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                if (hasLyrics) {
                    IconButton(onClick = onOpenLyrics) {
                        Icon(
                            imageVector = Icons.Rounded.Lyrics,
                            contentDescription = stringResource(R.string.lyrics_title),
                            tint = if (syncedLyrics != null) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                }

                IconButton(onClick = onOpenQueue) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.QueueMusic,
                        contentDescription = stringResource(R.string.queue_title),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun TransportToggleButton(
    icon: ImageVector,
    contentDescription: String,
    active: Boolean,
    onClick: () -> Unit,
) {
    val haptic = LocalHapticFeedback.current

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        FilledTonalIconButton(
            onClick = {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onClick()
            },
            shape = CircleShape,
            colors = IconButtonDefaults.filledTonalIconButtonColors(
                containerColor = if (active) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    Color.Transparent
                },
                contentColor = if (active) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
                },
            ),
            modifier = Modifier.size(44.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
            )
        }
        Box(
            modifier = Modifier
                .size(5.dp)
                .clip(CircleShape)
                .background(
                    if (active) MaterialTheme.colorScheme.primary else Color.Transparent,
                ),
        )
    }
}
