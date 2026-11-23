package com.musicplayer.feature.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material.icons.rounded.Equalizer
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.musicplayer.R
import com.musicplayer.core.designsystem.component.ArtworkCard
import com.musicplayer.core.model.Track
import com.musicplayer.player.PlaybackState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QueueSheet(
    state: PlaybackState,
    onDismiss: () -> Unit,
    onSelectIndex: (Int) -> Unit,
    onRemoveIndex: (Int) -> Unit,
    onMoveItem: (fromIndex: Int, toIndex: Int) -> Unit,
    onClearManualQueue: () -> Unit,
    onClearUpNext: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val currentIndex = state.currentIndex
    val manualTracks = state.manualQueueTracks
    val contextTracks = state.contextUpNextTracks
    val hasAnyUpNext = manualTracks.isNotEmpty() || contextTracks.isNotEmpty()
    val allUpNextKeys = remember(manualTracks, contextTracks) {
        val seen = HashMap<String, Int>()
        (manualTracks + contextTracks).map { track ->
            val count = (seen[track.id] ?: 0) + 1
            seen[track.id] = count
            "${track.id}#$count"
        }
    }

    val density = LocalDensity.current
    val defaultRowHeightPx = with(density) { 64.dp.toPx() }
    var measuredRowHeightPx by remember { mutableFloatStateOf(defaultRowHeightPx) }
    var draggingQueueIndex by remember { mutableStateOf<Int?>(null) }
    var dragOffsetY by remember { mutableFloatStateOf(0f) }

    val latestQueueSize by rememberUpdatedState(state.queue.size)
    val latestCurrentIndex by rememberUpdatedState(currentIndex)
    val latestOnMoveItem by rememberUpdatedState(onMoveItem)
    val haptic = LocalHapticFeedback.current

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.queue_title),
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (hasAnyUpNext) {
                    TextButton(onClick = onClearUpNext) {
                        Text(stringResource(R.string.queue_clear_all))
                    }
                }
            }

            state.currentTrack?.let { active ->
                Text(
                    text = stringResource(R.string.queue_now_playing),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                )
                QueueRowContent(
                    track = active,
                    isActive = true,
                    isDragging = false,
                    onClick = {},
                    dragHandleModifier = Modifier,
                )
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            }

            if (!hasAnyUpNext) {
                Text(
                    text = stringResource(R.string.queue_up_next_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 24.dp),
                )
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(400.dp),
                    contentPadding = PaddingValues(bottom = 20.dp),
                ) {
                    if (manualTracks.isNotEmpty()) {
                        item(key = "header_next_in_queue") {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(start = 20.dp, end = 12.dp, top = 4.dp, bottom = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = stringResource(R.string.queue_next_in_queue),
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                TextButton(onClick = onClearManualQueue) {
                                    Text(stringResource(R.string.queue_clear_up_next))
                                }
                            }
                        }

                        itemsIndexed(
                            items = manualTracks,
                            key = { offset, _ -> allUpNextKeys[offset] },
                        ) { offset, track ->
                            val queueIndex = currentIndex + 1 + offset
                            DraggableQueueItem(
                                stableKey = allUpNextKeys[offset],
                                track = track,
                                isDragging = draggingQueueIndex == queueIndex,
                                dragOffsetY = if (draggingQueueIndex == queueIndex) dragOffsetY else 0f,
                                onRowHeightMeasured = { h -> if (h > 0f) measuredRowHeightPx = h },
                                onSelect = { onSelectIndex(queueIndex) },
                                onRemove = { onRemoveIndex(queueIndex) },
                                onDragStart = {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    draggingQueueIndex = queueIndex
                                    dragOffsetY = 0f
                                },
                                onDragDelta = { deltaY ->
                                    val activeDrag = draggingQueueIndex ?: return@DraggableQueueItem
                                    dragOffsetY += deltaY
                                    val step = measuredRowHeightPx.coerceAtLeast(1f)
                                    val threshold = step * 0.65f
                                    if (dragOffsetY > threshold && activeDrag < latestQueueSize - 1) {
                                        val target = activeDrag + 1
                                        latestOnMoveItem(activeDrag, target)
                                        draggingQueueIndex = target
                                        dragOffsetY -= step
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    } else if (dragOffsetY < -threshold && activeDrag > latestCurrentIndex + 1) {
                                        val target = activeDrag - 1
                                        latestOnMoveItem(activeDrag, target)
                                        draggingQueueIndex = target
                                        dragOffsetY += step
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    }
                                },
                                onDragEnd = {
                                    draggingQueueIndex = null
                                    dragOffsetY = 0f
                                },
                            )
                        }
                    }

                    if (contextTracks.isNotEmpty()) {
                        item(key = "header_next_from") {
                            val contextHeader = state.queueContextLabel?.takeIf { it.isNotBlank() }?.let {
                                stringResource(R.string.queue_next_from, it)
                            } ?: stringResource(R.string.queue_up_next)

                            Text(
                                text = contextHeader,
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(
                                    start = 20.dp,
                                    end = 20.dp,
                                    top = if (manualTracks.isNotEmpty()) 14.dp else 4.dp,
                                    bottom = 6.dp,
                                ),
                            )
                        }

                        val contextBaseIndex = currentIndex + 1 + manualTracks.size
                        itemsIndexed(
                            items = contextTracks,
                            key = { offset, _ -> allUpNextKeys[manualTracks.size + offset] },
                        ) { offset, track ->
                            val queueIndex = contextBaseIndex + offset
                            DraggableQueueItem(
                                stableKey = allUpNextKeys[manualTracks.size + offset],
                                track = track,
                                isDragging = draggingQueueIndex == queueIndex,
                                dragOffsetY = if (draggingQueueIndex == queueIndex) dragOffsetY else 0f,
                                onRowHeightMeasured = { h -> if (h > 0f) measuredRowHeightPx = h },
                                onSelect = { onSelectIndex(queueIndex) },
                                onRemove = { onRemoveIndex(queueIndex) },
                                onDragStart = {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    draggingQueueIndex = queueIndex
                                    dragOffsetY = 0f
                                },
                                onDragDelta = { deltaY ->
                                    val activeDrag = draggingQueueIndex ?: return@DraggableQueueItem
                                    dragOffsetY += deltaY
                                    val step = measuredRowHeightPx.coerceAtLeast(1f)
                                    val threshold = step * 0.65f
                                    if (dragOffsetY > threshold && activeDrag < latestQueueSize - 1) {
                                        val target = activeDrag + 1
                                        latestOnMoveItem(activeDrag, target)
                                        draggingQueueIndex = target
                                        dragOffsetY -= step
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    } else if (dragOffsetY < -threshold && activeDrag > latestCurrentIndex + 1) {
                                        val target = activeDrag - 1
                                        latestOnMoveItem(activeDrag, target)
                                        draggingQueueIndex = target
                                        dragOffsetY += step
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    }
                                },
                                onDragEnd = {
                                    draggingQueueIndex = null
                                    dragOffsetY = 0f
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DraggableQueueItem(
    stableKey: String,
    track: Track,
    isDragging: Boolean,
    dragOffsetY: Float,
    onRowHeightMeasured: (Float) -> Unit,
    onSelect: () -> Unit,
    onRemove: () -> Unit,
    onDragStart: () -> Unit,
    onDragDelta: (Float) -> Unit,
    onDragEnd: () -> Unit,
) {
    val latestOnDragStart by rememberUpdatedState(onDragStart)
    val latestOnDragDelta by rememberUpdatedState(onDragDelta)
    val latestOnDragEnd by rememberUpdatedState(onDragEnd)
    val latestOnRemove by rememberUpdatedState(onRemove)

    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (!isDragging && value == SwipeToDismissBoxValue.EndToStart) {
                latestOnRemove()
                true
            } else {
                false
            }
        },
    )

    val dragHandleModifier = Modifier.pointerInput(stableKey) {
        detectDragGestures(
            onDragStart = { latestOnDragStart() },
            onDragEnd = { latestOnDragEnd() },
            onDragCancel = { latestOnDragEnd() },
            onDrag = { change, dragAmount ->
                change.consume()
                latestOnDragDelta(dragAmount.y)
            },
        )
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .zIndex(if (isDragging) 2f else 0f)
            .onSizeChanged { size -> onRowHeightMeasured(size.height.toFloat()) }
            .graphicsLayer {
                translationY = if (isDragging) dragOffsetY else 0f
                scaleX = if (isDragging) 1.02f else 1f
                scaleY = if (isDragging) 1.02f else 1f
                shadowElevation = if (isDragging) 14f else 0f
            },
    ) {
        SwipeToDismissBox(
            state = dismissState,
            enableDismissFromStartToEnd = false,
            enableDismissFromEndToStart = !isDragging,
            backgroundContent = {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.errorContainer)
                        .padding(horizontal = 20.dp),
                    contentAlignment = Alignment.CenterEnd,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Delete,
                        contentDescription = stringResource(R.string.queue_remove),
                        tint = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            },
        ) {
            QueueRowContent(
                track = track,
                isActive = false,
                isDragging = isDragging,
                onClick = onSelect,
                dragHandleModifier = dragHandleModifier,
            )
        }
    }
}

@Composable
private fun QueueRowContent(
    track: Track,
    isActive: Boolean,
    isDragging: Boolean,
    onClick: () -> Unit,
    dragHandleModifier: Modifier,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (isDragging) {
                    MaterialTheme.colorScheme.surfaceContainerHigh
                } else {
                    MaterialTheme.colorScheme.surface
                },
            )
            .clickable(enabled = !isActive && !isDragging, onClick = onClick)
            .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
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
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (isActive) {
                    Icon(
                        imageVector = Icons.Rounded.Equalizer,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp),
                    )
                }
                Text(
                    text = track.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = if (isActive) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = track.artistName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (!isActive) {
            Box(
                modifier = dragHandleModifier
                    .size(44.dp),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Rounded.DragHandle,
                    contentDescription = stringResource(R.string.queue_drag_reorder),
                    tint = if (isDragging) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
    }
}
