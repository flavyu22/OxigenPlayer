// SPDX-License-Identifier: GPL-3.0-only
package com.oxigenplayer.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AbRepeatTest {

    private fun inactive() = AbRepeatState()

    @Test
    fun firstPressSetsPointAAtCurrentPosition() {
        val next = abRepeatButtonTransition(inactive(), positionMs = 42_000L, durationMs = 120_000L)
        assertEquals(AbRepeatPhase.POINT_A_SET, next.phase)
        assertEquals(42_000L, next.pointAMs)
    }

    @Test
    fun immediatePressDoesNotSetPointB() {
        val withA = AbRepeatState(AbRepeatPhase.POINT_A_SET, pointAMs = 50_000L)
        val next = abRepeatButtonTransition(withA, positionMs = 50_200L, durationMs = 120_000L)
        assertEquals(AbRepeatPhase.POINT_A_SET, next.phase)
        assertNull(next.pointBMs.takeIf { it >= 0L })
    }

    @Test
    fun secondPressAfterTheStartSetsEndAndEnablesLooping() {
        val withA = AbRepeatState(AbRepeatPhase.POINT_A_SET, pointAMs = 50_000L)
        val next = abRepeatButtonTransition(withA, positionMs = 100_000L, durationMs = 120_000L)
        assertEquals(AbRepeatPhase.LOOPING, next.phase)
        assertEquals(100_000L, next.pointBMs)
    }

    @Test
    fun thirdPressClearsTheLoop() {
        val looping = AbRepeatState(
            phase = AbRepeatPhase.LOOPING,
            pointAMs = 50_000L,
            pointBMs = 100_000L,
        )
        assertEquals(AbRepeatState(), abRepeatButtonTransition(looping, 120_000L, 120_000L))
    }

    @Test
    fun positionsAreClampedIntoMediaBounds() {
        val below = abRepeatButtonTransition(inactive(), positionMs = -5_000L, durationMs = 60_000L)
        assertEquals(0L, below.pointAMs)
        val above = abRepeatButtonTransition(inactive(), positionMs = 90_000L, durationMs = 60_000L)
        assertEquals(60_000L, above.pointAMs)
    }

    @Test
    fun loopTargetIsNullWhileInactive() {
        assertNull(abRepeatSeekTarget(inactive(), positionMs = 42_000L))
    }

    @Test
    fun loopTargetIsNullAfterOnlyPointA() {
        val withA = AbRepeatState(AbRepeatPhase.POINT_A_SET, pointAMs = 10_000L)
        assertNull(abRepeatSeekTarget(withA, positionMs = 50_000L))
    }

    @Test
    fun loopTargetRewindsOncePastPointB() {
        val looping = AbRepeatState(
            phase = AbRepeatPhase.LOOPING,
            pointAMs = 10_000L,
            pointBMs = 20_000L,
        )
        assertNull(abRepeatSeekTarget(looping, positionMs = 19_000L))
        assertEquals(10_000L, abRepeatSeekTarget(looping, positionMs = 20_000L))
        assertEquals(10_000L, abRepeatSeekTarget(looping, positionMs = 25_000L))
    }

    @Test
    fun toleranceAllowsAPollIntervalPastB() {
        val looping = AbRepeatState(
            phase = AbRepeatPhase.LOOPING,
            pointAMs = 10_000L,
            pointBMs = 20_000L,
        )
        assertEquals(10_000L, abRepeatSeekTarget(looping, positionMs = 20_749L))
        assertNull(abRepeatSeekTarget(looping, positionMs = 19_249L))
    }

    @Test
    fun malformedLoopNeverSeeks() {
        val negativeA = AbRepeatState(
            phase = AbRepeatPhase.LOOPING,
            pointAMs = -1L,
            pointBMs = 20_000L,
        )
        val backwards = AbRepeatState(
            phase = AbRepeatPhase.LOOPING,
            pointAMs = 50_000L,
            pointBMs = 10_000L,
        )
        assertNull(abRepeatSeekTarget(negativeA, positionMs = 30_000L))
        assertNull(abRepeatSeekTarget(backwards, positionMs = 60_000L))
    }
}