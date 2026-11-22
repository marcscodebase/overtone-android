package com.musicplayer.feature.player

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.musicplayer.R
import com.musicplayer.core.designsystem.component.EmptyState
import com.musicplayer.core.model.LyricsParser
import com.musicplayer.core.model.LyricsSource
import com.musicplayer.core.model.TrackLyrics
import com.musicplayer.player.PlaybackState

data class LyricsUiState(
    val trackId: String? = null,
    val isLoading: Boolean = false,
    val lyrics: TrackLyrics? = null,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LyricsSheet(
    playbackState: PlaybackState,
    lyricsState: LyricsUiState,
    onDismiss: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onSeekTo: (Long) -> Unit,
) {
    val track = playbackState.currentTrack ?: return
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val listState = rememberLazyListState()
    val haptic = LocalHapticFeedback.current

    val lyrics = lyricsState.lyrics
    val activeLineIndex = remember(lyrics, playbackState.positionMs) {
        if (lyrics != null && lyrics.isSynced) {
            LyricsParser.findActiveLineIndex(lyrics.lines, playbackState.positionMs)
        } else {
            -1
        }
    }

    LaunchedEffect(activeLineIndex) {
        if (activeLineIndex >= 0 && lyrics != null && lyrics.isSynced) {
            val target = (activeLineIndex - 2).coerceAtLeast(0)
            listState.animateScrollToItem(target)
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        dragHandle = null,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxSize(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = track.title,
                        style = MaterialTheme.typography.titleMedium,
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
                lyrics?.let { loaded ->
                    val badgeLabel = when {
                        loaded.isSynced && loaded.source == LyricsSource.JELLYFIN ->
                            stringResource(R.string.lyrics_badge_synced_jellyfin)
                        loaded.isSynced -> stringResource(R.string.lyrics_badge_synced_local)
                        loaded.source == LyricsSource.EMBEDDED ->
                            stringResource(R.string.lyrics_badge_plain_local)
                        else -> stringResource(R.string.lyrics_badge_plain)
                    }
                    AssistChip(
                        onClick = {},
                        label = { Text(badgeLabel, style = MaterialTheme.typography.labelSmall) },
                    )
                }
                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = stringResource(R.string.action_close),
                    )
                }
            }

            HorizontalDivider()

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                when {
                    lyricsState.isLoading -> {
                        CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                    }

                    lyrics == null || lyrics.lines.isEmpty() -> {
                        EmptyState(
                            title = stringResource(R.string.lyrics_empty_title),
                            subtitle = stringResource(R.string.lyrics_empty_subtitle),
                            icon = Icons.Rounded.Lyrics,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }

                    else -> {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(horizontal = 24.dp, vertical = 28.dp),
                            verticalArrangement = Arrangement.spacedBy(18.dp),
                        ) {
                            itemsIndexed(
                                items = lyrics.lines,
                                key = { index, line -> "${index}_${line.startTimeMs ?: -1L}" },
                            ) { index, line ->
                                val isCurrent = lyrics.isSynced && index == activeLineIndex
                                val isPast = lyrics.isSynced && activeLineIndex >= 0 && index < activeLineIndex
                                val targetColor = when {
                                    isCurrent -> MaterialTheme.colorScheme.primary
                                    isPast -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
                                    lyrics.isSynced -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f)
                                    else -> MaterialTheme.colorScheme.onSurface
                                }
                                val animatedColor by animateColorAsState(
                                    targetValue = targetColor,
                                    label = "lyricLineColor",
                                )

                                Text(
                                    text = line.text,
                                    style = if (isCurrent) {
                                        MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold)
                                    } else {
                                        MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold)
                                    },
                                    color = animatedColor,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(MaterialTheme.shapes.medium)
                                        .then(
                                            if (line.startTimeMs != null) {
                                                Modifier.clickable {
                                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                    onSeekTo(line.startTimeMs)
                                                }
                                            } else {
                                                Modifier
                                            },
                                        )
                                        .padding(vertical = 4.dp),
                                )
                            }
                        }
                    }
                }
            }

            LinearProgressIndicator(
                progress = { playbackState.progressFraction },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilledIconButton(
                    onClick = onTogglePlayPause,
                    shape = CircleShape,
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    ),
                    modifier = Modifier.size(52.dp),
                ) {
                    Icon(
                        imageVector = if (playbackState.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        contentDescription = stringResource(R.string.player_play_pause),
                        modifier = Modifier.size(28.dp),
                    )
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
        }
    }
}
