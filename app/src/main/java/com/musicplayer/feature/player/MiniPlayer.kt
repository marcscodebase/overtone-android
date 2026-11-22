package com.musicplayer.feature.player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.musicplayer.R
import com.musicplayer.core.designsystem.component.ArtworkCard
import com.musicplayer.player.PlaybackState

@Composable
fun MiniPlayer(
    state: PlaybackState,
    onExpand: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onSkipNext: () -> Unit,
    onSkipPrevious: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val track = state.currentTrack ?: return
    var dragDelta by remember { mutableFloatStateOf(0f) }

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 4.dp,
        shape = MaterialTheme.shapes.medium,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .pointerInput(track.id) {
                detectHorizontalDragGestures(
                    onDragStart = { dragDelta = 0f },
                    onHorizontalDrag = { _, dragAmount -> dragDelta += dragAmount },
                    onDragEnd = {
                        when {
                            dragDelta < -SWIPE_THRESHOLD_PX -> onSkipNext()
                            dragDelta > SWIPE_THRESHOLD_PX -> onSkipPrevious()
                        }
                        dragDelta = 0f
                    },
                )
            }
            .clickable(onClick = onExpand),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 10.dp, end = 6.dp, top = 8.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ArtworkCard(
                    imageUrl = track.artworkUrl,
                    contentDescription = track.albumName,
                    shape = MaterialTheme.shapes.extraSmall,
                    fallbackIconSize = 20.dp,
                    modifier = Modifier.size(44.dp),
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = track.title,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = track.artistName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(onClick = onTogglePlayPause) {
                    if (state.isBuffering) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(22.dp),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Icon(
                            imageVector = if (state.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                            contentDescription = stringResource(R.string.player_play_pause),
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
            LinearProgressIndicator(
                progress = { state.progressFraction },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp),
                trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            )
        }
    }
}

private const val SWIPE_THRESHOLD_PX = 96f
