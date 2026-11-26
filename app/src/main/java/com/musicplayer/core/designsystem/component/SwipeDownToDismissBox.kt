package com.musicplayer.core.designsystem.component

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

object SwipeDownDismissMath {

    fun shouldDismiss(
        dragOffsetPx: Float,
        velocityYPxPerSec: Float,
        dismissThresholdPx: Float,
        flingMinOffsetPx: Float,
        flingVelocityThresholdPxPerSec: Float,
    ): Boolean {
        if (dragOffsetPx >= dismissThresholdPx) return true
        return dragOffsetPx >= flingMinOffsetPx && velocityYPxPerSec >= flingVelocityThresholdPxPerSec
    }

    fun applyResistance(rawDeltaY: Float): Float = rawDeltaY * 0.68f
}

fun LazyListState.isAtTopOfList(): Boolean =
    firstVisibleItemIndex == 0 && firstVisibleItemScrollOffset == 0

/**
 * Wraps a detail screen (Album, Artist, Playlist, Daylist, Artist Collection) so pulling down
 * from the top of the list — or dragging down on non-scrollable chrome such as the TopAppBar —
 * smoothly dismisses the screen back to the previous view without interfering with normal scrolling.
 */
@Composable
fun SwipeDownToDismissContainer(
    isAtTop: () -> Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()

    val dismissThresholdPx = with(density) { 108.dp.toPx() }
    val flingMinOffsetPx = with(density) { 44.dp.toPx() }
    val flingVelocityThresholdPx = with(density) { 900.dp.toPx() }
    val maxPullPx = with(density) { 260.dp.toPx() }

    val offsetY = remember { Animatable(0f) }
    var hasDismissed by remember { mutableStateOf(false) }

    val latestIsAtTop by rememberUpdatedState(isAtTop)
    val latestOnDismiss by rememberUpdatedState(onDismiss)

    fun settleDrag(velocityY: Float) {
        if (hasDismissed) return
        val current = offsetY.value
        if (current <= 0f) return
        val dismiss = SwipeDownDismissMath.shouldDismiss(
            dragOffsetPx = current,
            velocityYPxPerSec = velocityY,
            dismissThresholdPx = dismissThresholdPx,
            flingMinOffsetPx = flingMinOffsetPx,
            flingVelocityThresholdPxPerSec = flingVelocityThresholdPx,
        )
        if (dismiss) {
            hasDismissed = true
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            latestOnDismiss()
        } else {
            scope.launch {
                offsetY.animateTo(
                    targetValue = 0f,
                    animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
                )
            }
        }
    }

    val nestedScrollConnection = remember(dismissThresholdPx, maxPullPx) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source != NestedScrollSource.UserInput || hasDismissed) return Offset.Zero
                val current = offsetY.value
                // If already pulling down and user drags back up, retract the pull offset first
                if (current > 0f && available.y < 0f) {
                    val newOffset = (current + available.y).coerceAtLeast(0f)
                    val consumedY = newOffset - current
                    scope.launch { offsetY.snapTo(newOffset) }
                    return Offset(0f, consumedY)
                }
                // Intercept downward pull when scrolled to the very top (before Android 12+ stretch overscroll)
                if (available.y > 0f && latestIsAtTop()) {
                    val damped = SwipeDownDismissMath.applyResistance(available.y)
                    val newOffset = (current + damped).coerceIn(0f, maxPullPx)
                    scope.launch { offsetY.snapTo(newOffset) }
                    return Offset(0f, available.y)
                }
                return Offset.Zero
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                if (offsetY.value > 0f) {
                    settleDrag(available.y)
                    return available
                }
                return Velocity.Zero
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .nestedScroll(nestedScrollConnection)
            .pointerInput(dismissThresholdPx, maxPullPx) {
                detectVerticalDragGestures(
                    onVerticalDrag = { _, dragAmount ->
                        if (hasDismissed) return@detectVerticalDragGestures
                        val current = offsetY.value
                        if (dragAmount > 0f && (current > 0f || latestIsAtTop())) {
                            val next = (current + SwipeDownDismissMath.applyResistance(dragAmount))
                                .coerceIn(0f, maxPullPx)
                            scope.launch { offsetY.snapTo(next) }
                        } else if (dragAmount < 0f && current > 0f) {
                            val next = (current + dragAmount).coerceAtLeast(0f)
                            scope.launch { offsetY.snapTo(next) }
                        }
                    },
                    onDragEnd = { settleDrag(0f) },
                    onDragCancel = { settleDrag(0f) },
                )
            }
            .graphicsLayer {
                val current = offsetY.value
                translationY = current
                val progress = (current / maxPullPx).coerceIn(0f, 1f)
                val scale = 1f - progress * 0.04f
                scaleX = scale
                scaleY = scale
                alpha = 1f - progress * 0.16f
            },
    ) {
        content()
    }
}
