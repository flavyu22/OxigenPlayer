// SPDX-License-Identifier: GPL-3.0-only
package com.oxigenplayer.app

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/** Covers the lip-sync math that shifts the audio-driven media clock. */
class AudioDelayTest {
    private val unset = Long.MIN_VALUE

    @After
    fun resetSharedState() {
        AudioDelayState.delayMs = 0
    }

    @Test
    fun `zero delay leaves the clock untouched`() {
        assertEquals(0L, audioDelayClockOffsetUs(0))
        assertEquals(1_234_000L, applyAudioDelayToClockUs(1_234_000L, 0, unset))
    }

    @Test
    fun `positive delay moves the clock ahead of the audible position`() {
        assertEquals(250_000L, audioDelayClockOffsetUs(250))
        assertEquals(10_250_000L, applyAudioDelayToClockUs(10_000_000L, 250, unset))
    }

    @Test
    fun `negative delay moves the clock behind the audible position`() {
        assertEquals(-150_000L, audioDelayClockOffsetUs(-150))
        assertEquals(9_850_000L, applyAudioDelayToClockUs(10_000_000L, -150, unset))
    }

    @Test
    fun `out of range values are clamped`() {
        assertEquals(AUDIO_DELAY_MAX_MS, clampAudioDelayMs(60_000))
        assertEquals(AUDIO_DELAY_MIN_MS, clampAudioDelayMs(-60_000))
        assertEquals(5_000_000L, audioDelayClockOffsetUs(Int.MAX_VALUE))
        assertEquals(-5_000_000L, audioDelayClockOffsetUs(Int.MIN_VALUE))
    }

    @Test
    fun `unset position sentinel is never shifted`() {
        assertEquals(unset, applyAudioDelayToClockUs(unset, 500, unset))
        assertEquals(unset, applyAudioDelayToClockUs(unset, -500, unset))
    }

    @Test
    fun `shared state clamps what it stores`() {
        AudioDelayState.delayMs = 99_999
        assertEquals(AUDIO_DELAY_MAX_MS, AudioDelayState.delayMs)
        AudioDelayState.delayMs = -99_999
        assertEquals(AUDIO_DELAY_MIN_MS, AudioDelayState.delayMs)
        AudioDelayState.delayMs = 300
        assertEquals(300, AudioDelayState.delayMs)
    }
}
