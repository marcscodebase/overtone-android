package com.musicplayer.core.designsystem.component

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SwipeDownDismissMathTest {

    @Test
    fun `dismisses when dragged past distance threshold even with zero fling velocity`() {
        assertTrue(
            SwipeDownDismissMath.shouldDismiss(
                dragOffsetPx = 300f,
                velocityYPxPerSec = 0f,
                dismissThresholdPx = 280f,
                flingMinOffsetPx = 120f,
                flingVelocityThresholdPxPerSec = 2000f,
            ),
        )
    }

    @Test
    fun `dismisses on brisk downward fling past minimum drag offset`() {
        assertTrue(
            SwipeDownDismissMath.shouldDismiss(
                dragOffsetPx = 150f,
                velocityYPxPerSec = 2400f,
                dismissThresholdPx = 280f,
                flingMinOffsetPx = 120f,
                flingVelocityThresholdPxPerSec = 2000f,
            ),
        )
    }

    @Test
    fun `does not dismiss on short accidental pull or upward release`() {
        assertFalse(
            SwipeDownDismissMath.shouldDismiss(
                dragOffsetPx = 90f,
                velocityYPxPerSec = 3000f,
                dismissThresholdPx = 280f,
                flingMinOffsetPx = 120f,
                flingVelocityThresholdPxPerSec = 2000f,
            ),
        )
        assertFalse(
            SwipeDownDismissMath.shouldDismiss(
                dragOffsetPx = 180f,
                velocityYPxPerSec = -500f,
                dismissThresholdPx = 280f,
                flingMinOffsetPx = 120f,
                flingVelocityThresholdPxPerSec = 2000f,
            ),
        )
    }
}
