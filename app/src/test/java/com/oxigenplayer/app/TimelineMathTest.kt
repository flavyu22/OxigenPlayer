// SPDX-License-Identifier: GPL-3.0-only
package com.oxigenplayer.app

import org.junit.Assert.assertEquals
import org.junit.Test

class TimelineMathTest {
    @Test
    fun pixelMappingClampsToTimeline() {
        assertEquals(0L, timelinePositionFromPixels(-20f, 100f, 10_000L))
        assertEquals(5_000L, timelinePositionFromPixels(50f, 100f, 10_000L))
        assertEquals(10_000L, timelinePositionFromPixels(120f, 100f, 10_000L))
    }

    @Test
    fun invalidTimelineIsSafe() {
        assertEquals(0L, timelinePositionFromPixels(50f, 0f, 10_000L))
        assertEquals(0L, timelinePositionFromPixels(50f, 100f, 0L))
        assertEquals(0f, timelineFraction(5_000L, 0L))
    }

    @Test
    fun remainingAndBufferedValuesAreClamped() {
        assertEquals(7_000L, timelineRemainingMs(3_000L, 10_000L))
        assertEquals(0L, timelineRemainingMs(15_000L, 10_000L))
        assertEquals(100, timelineBufferedPercent(15_000L, 10_000L))
        assertEquals(0, timelineBufferedPercent(-1L, 10_000L))
    }
}
