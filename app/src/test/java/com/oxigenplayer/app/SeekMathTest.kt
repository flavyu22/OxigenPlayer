// SPDX-License-Identifier: GPL-3.0-only
package com.oxigenplayer.app

import org.junit.Assert.assertEquals
import org.junit.Test

class SeekMathTest {
    @Test
    fun rightPressAdvancesExactlyTenSeconds() {
        assertEquals(40_000L, calculateSeekTarget(30_000L, -1L, false, 120_000L, TV_REMOTE_SEEK_STEP_MS))
    }

    @Test
    fun repeatedPressesAccumulateFromPendingPosition() {
        assertEquals(50_000L, calculateSeekTarget(30_000L, 40_000L, true, 120_000L, TV_REMOTE_SEEK_STEP_MS))
    }

    @Test
    fun leftPressClampsAtStart() {
        assertEquals(0L, calculateSeekTarget(3_000L, -1L, false, 120_000L, -TV_REMOTE_SEEK_STEP_MS))
    }

    @Test
    fun rightPressClampsAtDuration() {
        assertEquals(120_000L, calculateSeekTarget(117_000L, -1L, false, 120_000L, TV_REMOTE_SEEK_STEP_MS))
    }

    @Test
    fun extremeDeltasCannotOverflow() {
        assertEquals(Long.MAX_VALUE, calculateSeekTarget(Long.MAX_VALUE - 5L, -1L, false, 0L, Long.MAX_VALUE))
        assertEquals(0L, calculateSeekTarget(50_000L, -1L, false, 0L, Long.MIN_VALUE))
    }
}
