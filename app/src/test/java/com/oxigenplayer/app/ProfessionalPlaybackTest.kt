// SPDX-License-Identifier: GPL-3.0-only
package com.oxigenplayer.app

import org.junit.Assert.assertEquals
import org.junit.Test

class ProfessionalPlaybackTest {
    @Test
    fun qualityProfileFallsBackToAutoForUnknownValues() {
        assertEquals(VideoQualityProfile.AUTO, VideoQualityProfile.fromStored(null))
        assertEquals(VideoQualityProfile.AUTO, VideoQualityProfile.fromStored("future-mode"))
        assertEquals(VideoQualityProfile.FULL_HD, VideoQualityProfile.fromStored("FULL_HD"))
    }

    @Test
    fun sleepTimerUsesMonotonicDeadlineAndRoundsUpRemainingMinute() {
        assertEquals(0L, sleepTimerDeadline(1_000L, 0))
        assertEquals(901_000L, sleepTimerDeadline(1_000L, 15))
        assertEquals(43_201_000L, sleepTimerDeadline(1_000L, 24 * 60))
        assertEquals(15, sleepTimerRemainingMinutes(1_001L, 901_000L))
        assertEquals(1, sleepTimerRemainingMinutes(899_500L, 901_000L))
        assertEquals(0, sleepTimerRemainingMinutes(901_000L, 901_000L))
    }
}
