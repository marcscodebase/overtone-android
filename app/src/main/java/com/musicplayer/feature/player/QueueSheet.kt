package com.musicplayer.feature.player

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material.icons.rounded.Equalizer
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.musicplayer.R
import com.musicplayer.core.designsystem.component.ArtworkCard
import com.musicplayer.core.designsystem.component.EmptyState
import com.musicplayer.core.designsystem.component.LocalLeftHandedMode
import com.musicplayer.core.designsystem.component.TrackRow
import com.musicplayer.core.model.Track
import com.musicplayer.player.PlaybackState
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QueueSheet(
    state: PlaybackState,
    recentlyPlayedTracks: List<Track>,
    isOffline: Boolean,
    onDismiss: () -> Unit,
    onSelectIndex: (Int) -> Unit,
    onRemoveIndex: (Int) -> Unit,
    onMoveItem: (fromIndex: Int, toIndex: Int) -> Unit,
    onClearManualQueue: () -> Unit,
    onClearUpNext: () -> Unit,
    onReshuffleQueue: () -> Unit,
    onPlayRecentTrack: (List<Track>, Int) -> Unit,
    onAddToQueue: (Track) -> Unit,
    onAddToPlaylist: (Track) -> Unit,
    onGoToAlbum: (String) -> Unit,
    onGoToArtist: (Track) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val pagerState = rememberPagerState(initialPage = 0, pageCount = { 2 })
    val queueListState = rememberLazyListState()
    val lastPlayedListState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    val currentPage by remember { derivedStateOf { pagerState.currentPage } }

    // Reset the inactive tab off-screen (including after items are prepended to Next in Queue)
    // so Compose's key-based scroll anchoring never hides newly added tracks above the fold.
    LaunchedEffect(pagerState.settledPage, state.manualQueueCount, state.queue.size) {
        if (pagerState.settledPage == 1) {
            queueListState.scrollToItem(0)
        } else {
            lastPlayedListState.scrollToItem(0)
        }
    }
    LaunchedEffect(pagerState.currentPage) {
        if (pagerState.currentPage == 0) {
            queueListState.scrollToItem(0)
        } else {
            lastPlayedListState.scrollToItem(0)
        }
    }

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
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                QueueTabPill(
                    label = stringResource(R.string.queue_title),
                    selected = currentPage == 0,
                    onClick = {
                        coroutineScope.launch { pagerState.animateScrollToPage(0) }
                    },
                )
                QueueTabPill(
                    label = stringResource(R.string.queue_last_played),
                    selected = currentPage == 1,
                    onClick = {
                        coroutineScope.launch { pagerState.animateScrollToPage(1) }
                    },
                )
                Spacer(modifier = Modifier.weight(1f))
                if (currentPage == 0 && state.shuffleEnabled && (manualTracks.size + contextTracks.size) >= 2) {
                    FilledTonalIconButton(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onReshuffleQueue()
                        },
                        colors = IconButtonDefaults.filledTonalIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        ),
                        modifier = Modifier.size(38.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Shuffle,
                            contentDescription = stringResource(R.string.queue_reshuffle),
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
                if (currentPage == 0 && hasAnyUpNext) {
                    TextButton(onClick = onClearUpNext) {
                        Text(stringResource(R.string.queue_clear_all))
                    }
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            HorizontalPager(
                state = pagerState,
                beyondViewportPageCount = 1,
                flingBehavior = PagerDefaults.flingBehavior(
                    state = pagerState,
                    snapPositionalThreshold = 0.22f,
                    snapAnimationSpec = spring(stiffness = Spring.StiffnessMedium),
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(480.dp),
            ) { page ->
                when (page) {
                    0 -> {
                        Column(modifier = Modifier.fillMaxSize()) {
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
                                    onRemove = null,
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
                                    state = queueListState,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .weight(1f),
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

                    1 -> {
                        if (recentlyPlayedTracks.isEmpty()) {
                            EmptyState(
                                title = stringResource(R.string.queue_last_played_empty_title),
                                subtitle = stringResource(R.string.queue_last_played_empty_subtitle),
                                icon = Icons.Rounded.History,
                                modifier = Modifier.fillMaxSize(),
                            )
                        } else {
                            LazyColumn(
                                state = lastPlayedListState,
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(bottom = 20.dp),
                            ) {
                                itemsIndexed(
                                    items = recentlyPlayedTracks,
                                    key = { _, track -> "history_${track.id}" },
                                ) { index, track ->
                                    TrackRow(
                                        track = track,
                                        isPlaying = track.id == state.currentTrack?.id,
                                        enabled = !isOffline || track.isDownloaded,
                                        enableSwipeToQueue = false,
                                        onClick = { onPlayRecentTrack(recentlyPlayedTracks, index) },
                                        onAddToQueue = { onAddToQueue(track) },
                                        onAddToPlaylist = { onAddToPlaylist(track) },
                                        onGoToAlbum = track.albumId?.let { id ->
                                            {
                                                onDismiss()
                                                onGoToAlbum(id)
                                            }
                                        },
                                        onGoToArtist = {
                                            onDismiss()
                                            onGoToArtist(track)
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun QueueTabPill(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        color = if (selected) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            Color.Transparent
        },
        shape = CircleShape,
        modifier = Modifier
            .clip(CircleShape)
            .clickable(onClick = onClick),
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                color = if (selected) {
                    MaterialTheme.colorScheme.onSecondaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
    }
}

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
        QueueRowContent(
            track = track,
            isActive = false,
            isDragging = isDragging,
            onClick = onSelect,
            onRemove = onRemove,
            dragHandleModifier = dragHandleModifier,
        )
    }
}

@Composable
private fun QueueRowContent(
    track: Track,
    isActive: Boolean,
    isDragging: Boolean,
    onClick: () -> Unit,
    onRemove: (() -> Unit)?,
    dragHandleModifier: Modifier,
) {
    val isLeftHanded = LocalLeftHandedMode.current
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
            .padding(
                start = if (isLeftHanded && !isActive) 8.dp else 16.dp,
                end = if (isLeftHanded && !isActive) 12.dp else 8.dp,
                top = 8.dp,
                bottom = 8.dp,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (!isActive && isLeftHanded) {
            Box(
                modifier = dragHandleModifier.size(44.dp),
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
            if (onRemove != null) {
                IconButton(
                    onClick = onRemove,
                    modifier = Modifier.size(38.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = stringResource(R.string.queue_remove),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            if (!isLeftHanded) {
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
}
