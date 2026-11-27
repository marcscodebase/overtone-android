package com.musicplayer.core.designsystem.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.Normalizer
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Global composition local reflecting the user's Left-Handed Mode preference (default: `false`,
 * optimized for right-handed one-handed use).
 */
val LocalLeftHandedMode = compositionLocalOf { false }

/**
 * Pure math & section-label helpers for [LazyColumnFastScroller].
 */
object FastScrollMath {

    fun sectionLetter(text: String?): String {
        val trimmed = text?.trim().orEmpty()
        if (trimmed.isEmpty()) return "#"
        val normalized = Normalizer.normalize(trimmed, Normalizer.Form.NFD)
        val firstBase = normalized.firstOrNull { ch ->
            Character.getType(ch) != Character.NON_SPACING_MARK.toInt()
        } ?: return "#"
        return if (firstBase.isLetter()) {
            firstBase.uppercaseChar().toString().uppercase(Locale.US)
        } else {
            "#"
        }
    }

    fun fractionToDataIndex(fraction: Float, itemCount: Int): Int {
        if (itemCount <= 1) return 0
        val clamped = fraction.coerceIn(0f, 1f)
        return (clamped * (itemCount - 1)).roundToInt().coerceIn(0, itemCount - 1)
    }

    fun scrollProgressFraction(
        firstVisibleIndex: Int,
        headerItemCount: Int,
        itemCount: Int,
        visibleItemCount: Int,
    ): Float {
        if (itemCount <= 1) return 0f
        val scrollableSpan = (itemCount - visibleItemCount.coerceAtLeast(1)).coerceAtLeast(1)
        val dataIndex = (firstVisibleIndex - headerItemCount).coerceIn(0, scrollableSpan)
        return (dataIndex.toFloat() / scrollableSpan.toFloat()).coerceIn(0f, 1f)
    }
}

/**
 * Wraps a `LazyColumn` with a draggable Pure Material You vertical fast-scroller & floating
 * section bubble (e.g. A–Z or release year). Automatically positions on the right edge by default
 * or the left edge when [LocalLeftHandedMode] is enabled.
 */
@Composable
fun LazyColumnFastScroller(
    listState: LazyListState,
    itemCount: Int,
    modifier: Modifier = Modifier,
    headerItemCount: Int = 0,
    minItemsToShow: Int = 18,
    leftSide: Boolean = LocalLeftHandedMode.current,
    topPadding: Dp = 8.dp,
    bottomPadding: Dp = 16.dp,
    bubbleLabelForIndex: ((dataIndex: Int) -> String?)? = null,
    content: @Composable () -> Unit,
) {
    val coroutineScope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current

    val latestItemCount by rememberUpdatedState(itemCount)
    val latestHeaderCount by rememberUpdatedState(headerItemCount)
    val latestBubbleProvider by rememberUpdatedState(bubbleLabelForIndex)

    var isDragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }
    var activeBubbleLabel by remember { mutableStateOf<String?>(null) }
    var recentlyScrolled by remember { mutableStateOf(false) }

    val isScrollInProgress = listState.isScrollInProgress
    LaunchedEffect(isScrollInProgress, isDragging) {
        if (isScrollInProgress || isDragging) {
            recentlyScrolled = true
        } else {
            delay(1_400L)
            recentlyScrolled = false
        }
    }

    val scrollFraction by remember(listState, itemCount, headerItemCount) {
        derivedStateOf {
            val layout = listState.layoutInfo
            val visibleCount = layout.visibleItemsInfo.size
            FastScrollMath.scrollProgressFraction(
                firstVisibleIndex = listState.firstVisibleItemIndex,
                headerItemCount = headerItemCount,
                itemCount = itemCount,
                visibleItemCount = visibleCount,
            )
        }
    }

    val canScroll by remember(listState, itemCount, minItemsToShow) {
        derivedStateOf {
            itemCount >= minItemsToShow &&
                (listState.canScrollForward || listState.canScrollBackward)
        }
    }

    val displayedFraction = if (isDragging) dragFraction else scrollFraction
    val thumbAlpha by animateFloatAsState(
        targetValue = when {
            isDragging -> 1f
            recentlyScrolled -> 0.92f
            else -> 0.42f
        },
        label = "fast_scroll_alpha",
    )
    val thumbWidth by animateDpAsState(
        targetValue = if (isDragging) 8.dp else 5.dp,
        label = "fast_scroll_width",
    )

    Box(modifier = modifier.fillMaxSize()) {
        content()

        if (canScroll || isDragging) {
            BoxWithConstraints(
                modifier = Modifier
                    .align(if (leftSide) Alignment.CenterStart else Alignment.CenterEnd)
                    .fillMaxHeight()
                    .padding(top = topPadding, bottom = bottomPadding)
                    .width(34.dp),
            ) {
                val trackHeightPx = with(density) { maxHeight.toPx() }.coerceAtLeast(1f)
                val thumbHeightDp = 48.dp
                val thumbHeightPx = with(density) { thumbHeightDp.toPx() }
                val usableTrackPx = (trackHeightPx - thumbHeightPx).coerceAtLeast(1f)
                val thumbOffsetYPx = (displayedFraction * usableTrackPx).roundToInt()

                fun scrubToY(yPx: Float) {
                    val count = latestItemCount
                    if (count <= 0) return
                    val fraction = ((yPx - thumbHeightPx / 2f) / usableTrackPx).coerceIn(0f, 1f)
                    dragFraction = fraction
                    val dataIdx = FastScrollMath.fractionToDataIndex(fraction, count)
                    val newLabel = latestBubbleProvider?.invoke(dataIdx)?.takeIf { it.isNotBlank() }
                    if (newLabel != activeBubbleLabel && newLabel != null) {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    }
                    activeBubbleLabel = newLabel
                    coroutineScope.launch {
                        listState.scrollToItem((latestHeaderCount + dataIdx).coerceAtLeast(0))
                    }
                }

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(usableTrackPx, thumbHeightPx) {
                            detectTapGestures(
                                onPress = { offset ->
                                    isDragging = true
                                    scrubToY(offset.y)
                                    tryAwaitRelease()
                                    isDragging = false
                                    activeBubbleLabel = null
                                },
                            )
                        }
                        .pointerInput(usableTrackPx, thumbHeightPx) {
                            detectVerticalDragGestures(
                                onDragStart = { offset ->
                                    isDragging = true
                                    scrubToY(offset.y)
                                },
                                onDragEnd = {
                                    isDragging = false
                                    activeBubbleLabel = null
                                },
                                onDragCancel = {
                                    isDragging = false
                                    activeBubbleLabel = null
                                },
                                onVerticalDrag = { change, _ ->
                                    change.consume()
                                    scrubToY(change.position.y)
                                },
                            )
                        },
                ) {
                    // Subtle rail visible while dragging
                    if (isDragging) {
                        Box(
                            modifier = Modifier
                                .align(if (leftSide) Alignment.CenterStart else Alignment.CenterEnd)
                                .padding(horizontal = 5.dp)
                                .fillMaxHeight()
                                .width(3.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)),
                        )
                    }

                    // Draggable thumb pill
                    Box(
                        modifier = Modifier
                            .align(if (leftSide) Alignment.TopStart else Alignment.TopEnd)
                            .offset { IntOffset(x = 0, y = thumbOffsetYPx) }
                            .padding(horizontal = 4.dp)
                            .width(thumbWidth)
                            .height(thumbHeightDp)
                            .alpha(thumbAlpha)
                            .clip(CircleShape)
                            .background(
                                if (isDragging) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            ),
                    )
                }
            }

            // Floating Material You section bubble positioned opposite the thumb side
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = topPadding, bottom = bottomPadding),
            ) {
                val trackHeightPx = with(density) { maxHeight.toPx() }.coerceAtLeast(1f)
                val bubbleSizePx = with(density) { 54.dp.toPx() }
                val usableBubblePx = (trackHeightPx - bubbleSizePx).coerceAtLeast(1f)
                val bubbleOffsetYPx = (displayedFraction * usableBubblePx).roundToInt()

                AnimatedVisibility(
                    visible = isDragging && !activeBubbleLabel.isNullOrBlank(),
                    enter = fadeIn() + scaleIn(initialScale = 0.78f),
                    exit = fadeOut() + scaleOut(targetScale = 0.78f),
                    modifier = Modifier
                        .align(if (leftSide) Alignment.TopStart else Alignment.TopEnd)
                        .offset {
                            val horizontalOffsetPx = with(density) { 46.dp.roundToPx() }
                            IntOffset(
                                x = if (leftSide) horizontalOffsetPx else -horizontalOffsetPx,
                                y = bubbleOffsetYPx,
                            )
                        },
                ) {
                    Surface(
                        color = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                        shape = RoundedCornerShape(
                            topStart = 28.dp,
                            topEnd = 28.dp,
                            bottomStart = if (leftSide) 6.dp else 28.dp,
                            bottomEnd = if (leftSide) 28.dp else 6.dp,
                        ),
                        shadowElevation = 6.dp,
                        modifier = Modifier.sizeIn(minWidth = 54.dp, minHeight = 54.dp),
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        ) {
                            Text(
                                text = activeBubbleLabel.orEmpty(),
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                }
            }
        }
    }
}
