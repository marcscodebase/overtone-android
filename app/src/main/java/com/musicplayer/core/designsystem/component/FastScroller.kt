package com.musicplayer.core.designsystem.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Global composition local reflecting the user's Left-Handed Mode preference (default: `false`,
 * optimized for right-handed one-handed use).
 */
val LocalLeftHandedMode = compositionLocalOf { false }

/**
 * Represents a contiguous section on the opposite-side visual index rail (e.g., `"A"`, `"★"`, `"2024"`).
 */
data class FastScrollSection(
    val label: String,
    val firstIndex: Int,
    val lastIndex: Int,
)

/**
 * Pure math & section-label helpers for [LazyColumnFastScroller].
 */
object FastScrollMath {

    fun sectionLetter(text: String?): String {
        val trimmed = text?.trim().orEmpty()
        if (trimmed.isEmpty()) return "#"
        val first = trimmed[0]
        if (first in 'A'..'Z') return first.toString()
        if (first in 'a'..'z') return first.uppercaseChar().toString()
        if (first in '0'..'9') return "#"
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

    /**
     * Groups contiguous items with identical non-blank labels into ordered [FastScrollSection]s,
     * evenly downsampling if there are more runs than [maxSections] while preserving the first
     * and last section.
     */
    fun buildSections(
        itemCount: Int,
        maxSections: Int = 28,
        labelForIndex: (Int) -> String?,
    ): List<FastScrollSection> {
        if (itemCount <= 0 || maxSections <= 0) return emptyList()
        val runs = ArrayList<FastScrollSection>(32)
        var currentLabel: String? = null
        var currentStart = 0
        var currentEnd = 0
        for (idx in 0 until itemCount) {
            val label = labelForIndex(idx)?.trim()?.takeIf { it.isNotEmpty() } ?: continue
            if (currentLabel == null) {
                currentLabel = label
                currentStart = idx
                currentEnd = idx
            } else if (label == currentLabel) {
                currentEnd = idx
            } else {
                runs.add(FastScrollSection(currentLabel, currentStart, currentEnd))
                currentLabel = label
                currentStart = idx
                currentEnd = idx
            }
        }
        if (currentLabel != null) {
            runs.add(FastScrollSection(currentLabel, currentStart, currentEnd))
        }
        if (runs.size <= maxSections) return runs
        if (maxSections == 1) {
            return listOf(
                FastScrollSection(
                    label = runs.first().label,
                    firstIndex = runs.first().firstIndex,
                    lastIndex = runs.last().lastIndex,
                ),
            )
        }
        val pickedIndices = (0 until maxSections).map { slot ->
            ((slot.toFloat() / (maxSections - 1).toFloat()) * runs.lastIndex)
                .roundToInt()
                .coerceIn(0, runs.lastIndex)
        }.distinct()
        return pickedIndices.mapIndexed { pos, runIdx ->
            val run = runs[runIdx]
            val nextFirst = pickedIndices.getOrNull(pos + 1)?.let { runs[it].firstIndex }
            val coveredLast = if (nextFirst != null) {
                (nextFirst - 1).coerceAtLeast(run.lastIndex)
            } else {
                runs.last().lastIndex
            }
            FastScrollSection(
                label = run.label,
                firstIndex = run.firstIndex,
                lastIndex = coveredLast,
            )
        }
    }

    /**
     * Resolves the index inside [sections] corresponding to [dataIndex].
     */
    fun activeSectionIndex(sections: List<FastScrollSection>, dataIndex: Int): Int {
        if (sections.isEmpty()) return -1
        var resolved = 0
        for (i in sections.indices) {
            if (sections[i].firstIndex <= dataIndex) {
                resolved = i
            } else {
                break
            }
        }
        return resolved
    }

    /**
     * Normalized vertical center `(0..1)` of [sectionIndex] inside a uniformly spaced rail.
     */
    fun sectionCenterFraction(sectionIndex: Int, sectionCount: Int): Float {
        if (sectionCount <= 1) return 0.5f
        val clamped = sectionIndex.coerceIn(0, sectionCount - 1)
        return (clamped + 0.5f) / sectionCount.toFloat()
    }
}

/**
 * Wraps a `LazyColumn` with a draggable Pure Material You vertical fast-scroller.
 *
 * - Positioned on the **right** edge by default, or the **left** edge when [LocalLeftHandedMode]
 *   is enabled.
 * - While scrubbing, displays an opposite-side visual section index rail (`★`, `#`, `A`..`Z`,
 *   years, etc.) with the active section highlighted and magnified right beside it so the user's
 *   thumb never obscures the current letter.
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
    var activeDataIndex by remember { mutableIntStateOf(0) }
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
            // Draggable edge thumb & gesture track
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
                    activeDataIndex = dataIdx
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
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                down.consume()
                                isDragging = true
                                scrubToY(down.position.y)
                                while (true) {
                                    val event = awaitPointerEvent()
                                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                    if (!change.pressed) {
                                        change.consume()
                                        break
                                    }
                                    if (change.positionChanged()) {
                                        change.consume()
                                        scrubToY(change.position.y)
                                    }
                                }
                                isDragging = false
                                activeBubbleLabel = null
                            }
                        },
                ) {
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

            // Opposite-side visual section index rail & magnified active letter badge
            if (bubbleLabelForIndex != null) {
                OppositeSideIndexOverlay(
                    isDragging = isDragging,
                    indexOnLeft = !leftSide,
                    itemCount = itemCount,
                    activeDataIndex = activeDataIndex,
                    activeBubbleLabel = activeBubbleLabel,
                    displayedFraction = displayedFraction,
                    topPadding = topPadding,
                    bottomPadding = 20.dp,
                    labelForIndex = latestBubbleProvider,
                )
            }
        }
    }
}

@Composable
private fun OppositeSideIndexOverlay(
    isDragging: Boolean,
    indexOnLeft: Boolean,
    itemCount: Int,
    activeDataIndex: Int,
    activeBubbleLabel: String?,
    displayedFraction: Float,
    topPadding: Dp,
    bottomPadding: Dp,
    labelForIndex: ((Int) -> String?)?,
) {
    val density = LocalDensity.current

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = topPadding, bottom = bottomPadding),
    ) {
        val availableHeightDp = maxHeight
        val availableHeightPx = with(density) { availableHeightDp.toPx() }.coerceAtLeast(1f)
        val maxSectionsForHeight = (availableHeightDp.value / 17f).toInt().coerceIn(8, 28)

        // Build ordered sections lazily only when actively scrubbing
        val sections = remember(isDragging, itemCount, maxSectionsForHeight) {
            if (!isDragging || labelForIndex == null || itemCount <= 0) {
                emptyList()
            } else {
                val raw = FastScrollMath.buildSections(
                    itemCount = itemCount,
                    maxSections = maxSectionsForHeight,
                    labelForIndex = labelForIndex,
                )
                val hasLongLabels = raw.any { it.label.length > 2 }
                if (hasLongLabels && raw.size > 16) {
                    FastScrollMath.buildSections(
                        itemCount = itemCount,
                        maxSections = (availableHeightDp.value / 24f).toInt().coerceIn(8, 16),
                        labelForIndex = labelForIndex,
                    )
                } else {
                    raw
                }
            }
        }

        val showRail = isDragging && sections.size >= 2
        val showFallbackBadgeOnly = isDragging && sections.size < 2 && !activeBubbleLabel.isNullOrBlank()
        val activeSectionIdx = remember(sections, activeDataIndex) {
            FastScrollMath.activeSectionIndex(sections, activeDataIndex)
        }
        val activeDisplayLabel = activeBubbleLabel
            ?: sections.getOrNull(activeSectionIdx)?.label

        val hasMultiCharLabels = remember(sections) { sections.any { it.label.length > 1 } }
        val railWidthDp = if (hasMultiCharLabels) 46.dp else 30.dp
        val railOuterMarginDp = 10.dp
        val railVerticalPaddingDp = 8.dp
        val desiredRowHeightDp = if (hasMultiCharLabels) 26.dp else 22.dp
        val railHeightDp = if (sections.isEmpty()) {
            0.dp
        } else {
            (desiredRowHeightDp * sections.size + railVerticalPaddingDp * 2)
                .coerceAtMost(availableHeightDp)
        }
        val railHeightPx = with(density) { railHeightDp.toPx() }
        val railTopPx = ((availableHeightPx - railHeightPx) / 2f).coerceAtLeast(0f)
        val railInnerPaddingPx = with(density) { railVerticalPaddingDp.toPx() }
        val railInnerHeightPx = (railHeightPx - railInnerPaddingPx * 2f).coerceAtLeast(1f)

        val targetCenterFraction = if (sections.size >= 2 && activeSectionIdx >= 0) {
            FastScrollMath.sectionCenterFraction(activeSectionIdx, sections.size)
        } else {
            displayedFraction
        }
        val animatedCenterFraction by animateFloatAsState(
            targetValue = targetCenterFraction,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioNoBouncy,
                stiffness = Spring.StiffnessHigh,
            ),
            label = "index_rail_active_fraction",
        )

        // 1) Opposite-side vertical index rail (e.g. A..Z on the left for right-handers, right for left-handers)
        AnimatedVisibility(
            visible = showRail,
            enter = fadeIn(tween(130)) + slideInHorizontally(tween(150)) {
                if (indexOnLeft) -it / 2 else it / 2
            },
            exit = fadeOut(tween(180)) + slideOutHorizontally(tween(180)) {
                if (indexOnLeft) -it / 2 else it / 2
            },
            modifier = Modifier
                .align(if (indexOnLeft) Alignment.CenterStart else Alignment.CenterEnd)
                .padding(horizontal = railOuterMarginDp),
        ) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.94f),
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                shape = RoundedCornerShape(20.dp),
                tonalElevation = 4.dp,
                shadowElevation = 6.dp,
                modifier = Modifier
                    .width(railWidthDp)
                    .height(railHeightDp),
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.SpaceEvenly,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(vertical = railVerticalPaddingDp, horizontal = 2.dp),
                ) {
                    sections.forEachIndexed { idx, section ->
                        val distance = if (activeSectionIdx >= 0) abs(idx - activeSectionIdx) else 99
                        val isActive = distance == 0
                        val isNeighbor = distance == 1
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .weight(1f)
                                .clip(CircleShape)
                                .background(
                                    if (isActive) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.surface.copy(alpha = 0f)
                                    },
                                )
                                .padding(horizontal = 4.dp),
                        ) {
                            Text(
                                text = section.label,
                                fontSize = when {
                                    isActive -> if (hasMultiCharLabels) 11.sp else 12.sp
                                    isNeighbor -> if (hasMultiCharLabels) 10.sp else 11.sp
                                    else -> if (hasMultiCharLabels) 9.sp else 10.sp
                                },
                                lineHeight = 12.sp,
                                fontWeight = when {
                                    isActive -> FontWeight.ExtraBold
                                    isNeighbor -> FontWeight.SemiBold
                                    else -> FontWeight.Medium
                                },
                                color = when {
                                    isActive -> MaterialTheme.colorScheme.onPrimary
                                    isNeighbor -> MaterialTheme.colorScheme.onSurface
                                    else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.78f)
                                },
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Clip,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                }
            }
        }

        // 2) Magnified active letter callout positioned directly beside the active letter on the opposite rail
        val badgeSizeDp = 58.dp
        val badgeSizePx = with(density) { badgeSizeDp.toPx() }
        val badgeHorizontalOffsetPx = with(density) {
            val baseOffset = if (showRail) {
                railOuterMarginDp + railWidthDp + 10.dp
            } else {
                16.dp
            }
            baseOffset.roundToPx()
        }
        val badgeCenterYPx = if (showRail) {
            railTopPx + railInnerPaddingPx + animatedCenterFraction * railInnerHeightPx
        } else {
            displayedFraction * (availableHeightPx - badgeSizePx) + badgeSizePx / 2f
        }
        val badgeOffsetYPx = (badgeCenterYPx - badgeSizePx / 2f)
            .roundToInt()
            .coerceIn(0, (availableHeightPx - badgeSizePx).roundToInt().coerceAtLeast(0))

        AnimatedVisibility(
            visible = (showRail || showFallbackBadgeOnly) && !activeDisplayLabel.isNullOrBlank(),
            enter = fadeIn(tween(120)) + scaleIn(initialScale = 0.78f, animationSpec = tween(140)),
            exit = fadeOut(tween(150)) + scaleOut(targetScale = 0.78f, animationSpec = tween(150)),
            modifier = Modifier
                .align(if (indexOnLeft) Alignment.TopStart else Alignment.TopEnd)
                .offset {
                    IntOffset(
                        x = if (indexOnLeft) badgeHorizontalOffsetPx else -badgeHorizontalOffsetPx,
                        y = badgeOffsetYPx,
                    )
                },
        ) {
            Surface(
                color = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                shape = RoundedCornerShape(
                    topStart = if (indexOnLeft) 12.dp else 28.dp,
                    topEnd = if (indexOnLeft) 28.dp else 12.dp,
                    bottomStart = 28.dp,
                    bottomEnd = 28.dp,
                ),
                shadowElevation = 8.dp,
                modifier = Modifier.sizeIn(minWidth = badgeSizeDp, minHeight = badgeSizeDp),
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                ) {
                    Text(
                        text = activeDisplayLabel.orEmpty(),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}
