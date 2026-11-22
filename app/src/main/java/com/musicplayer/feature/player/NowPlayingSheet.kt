package com.musicplayer.feature.player

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.KeyboardArrowDown
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.musicplayer.R
import com.musicplayer.core.common.Formatters
import com.musicplayer.core.designsystem.component.ArtworkCard
import com.musicplayer.player.PlaybackState
import com.musicplayer.player.RepeatMode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NowPlayingSheet(
    state: PlaybackState,
    onDismiss: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onToggleFavorite: () -> Unit,
    onSkipNext: () -> Unit,
    onSkipPrevious: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onToggleShuffle: () -> Unit,
    onCycleRepeatMode: () -> Unit,
    onOpenQueue: () -> Unit,
) {
    val track = state.currentTrack ?: return
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

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

        val qualityBadge = if (state.transcodedBitrateKbps != null && !state.isLocalPlayback) {
            "MP3 • ${state.transcodedBitrateKbps} kbps • Mobile Data"
        } else {
            Formatters.formatAudioQualityBadge(
                container = track.container,
                codec = track.codec,
                bitrate = track.bitrate,
                sampleRate = track.sampleRate,
                bitDepth = track.bitDepth,
                isLocal = state.isLocalPlayback,
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp, vertical = 12.dp),
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
                Column(
                    modifier = Modifier.weight(1f),
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
                IconButton(onClick = onOpenQueue) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.QueueMusic,
                        contentDescription = stringResource(R.string.queue_title),
                    )
                }
            }

            Spacer(modifier = Modifier.weight(0.6f))

            ArtworkCard(
                imageUrl = track.artworkUrl,
                contentDescription = track.albumName,
                shape = MaterialTheme.shapes.large,
                fallbackIconSize = 72.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp),
            )

            Spacer(modifier = Modifier.height(20.dp))

            AssistChip(
                onClick = {},
                label = {
                    Text(
                        text = qualityBadge,
                        style = MaterialTheme.typography.labelSmall,
                    )
                },
            )

            Spacer(modifier = Modifier.weight(0.5f))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
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
                IconButton(onClick = onToggleFavorite) {
                    Icon(
                        imageVector = if (track.isFavorite) {
                            Icons.Rounded.Favorite
                        } else {
                            Icons.Rounded.FavoriteBorder
                        },
                        contentDescription = stringResource(
                            if (track.isFavorite) R.string.favorite_remove else R.string.favorite_add,
                        ),
                        tint = if (track.isFavorite) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

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

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onToggleShuffle) {
                    Icon(
                        imageVector = Icons.Rounded.Shuffle,
                        contentDescription = stringResource(R.string.player_shuffle),
                        tint = if (state.shuffleEnabled) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }

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

                IconButton(onClick = onCycleRepeatMode) {
                    val repeatIcon = if (state.repeatMode == RepeatMode.ONE) {
                        Icons.Rounded.RepeatOne
                    } else {
                        Icons.Rounded.Repeat
                    }
                    Icon(
                        imageVector = repeatIcon,
                        contentDescription = stringResource(R.string.player_repeat),
                        tint = if (state.repeatMode != RepeatMode.OFF) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }

            state.errorMessage?.let { err ->
                Spacer(modifier = Modifier.height(12.dp))
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

            Spacer(modifier = Modifier.weight(0.5f))
        }
    }
}
