// SPDX-License-Identifier: GPL-3.0-only
package com.oxigenplayer.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import com.oxigenplayer.app.ui.formatSubtitleSyncOffset
import org.junit.Test

/** Covers the subtitle sync math: stepping, clamping and the two-mark calibration. */
class SubtitleSyncTest {
    private val marking = AutoSyncState(phase = AutoSyncPhase.MARKING)

    @Test
    fun `nudging moves by the step and clamps at the limits`() {
        assertEquals(50L, nudgeSubtitleSyncOffset(0L, SUBTITLE_SYNC_FINE_STEP_MS))
        assertEquals(-500L, nudgeSubtitleSyncOffset(0L, -SUBTITLE_SYNC_COARSE_STEP_MS))
        assertEquals(SUBTITLE_SYNC_MAX_MS, nudgeSubtitleSyncOffset(9_990L, 500L))
        assertEquals(SUBTITLE_SYNC_MIN_MS, nudgeSubtitleSyncOffset(-9_990L, -500L))
        assertEquals(SUBTITLE_SYNC_MAX_MS, clampSubtitleSyncOffset(Long.MAX_VALUE))
    }

    @Test
    fun `late subtitles are pulled earlier`() {
        // Line heard at 60.0 s, its subtitle appeared at 61.2 s: subtitles are 1.2 s late.
        assertEquals(-1_200L, suggestSubtitleSyncOffset(0L, 60_000L, 61_200L))
    }

    @Test
    fun `early subtitles are pushed later`() {
        // Subtitle appeared at 59.3 s, the line was heard at 60.0 s: subtitles are 0.7 s early.
        assertEquals(700L, suggestSubtitleSyncOffset(0L, 60_000L, 59_300L))
    }

    @Test
    fun `suggestion builds on the offset already in effect`() {
        // With +500 ms already applied the subtitle is still 300 ms late -> +200 ms.
        assertEquals(200L, suggestSubtitleSyncOffset(500L, 60_000L, 60_300L))
        // Already in sync: nothing changes.
        assertEquals(-750L, suggestSubtitleSyncOffset(-750L, 60_000L, 60_000L))
    }

    @Test
    fun `suggestion is clamped to the supported range`() {
        assertEquals(SUBTITLE_SYNC_MIN_MS, suggestSubtitleSyncOffset(0L, 10_000L, 90_000L))
        assertEquals(SUBTITLE_SYNC_MAX_MS, suggestSubtitleSyncOffset(0L, 90_000L, 10_000L))
    }

    @Test
    fun `marks are accepted in either order`() {
        val audioFirst = autoSyncMarkTransition(marking, AutoSyncMark.AUDIO, 60_000L, 0L)
        assertEquals(AutoSyncPhase.MARKING, audioFirst.phase)
        assertTrue(audioFirst.hasAudioMark)
        assertFalse(audioFirst.hasSubtitleMark)
        val late = autoSyncMarkTransition(audioFirst, AutoSyncMark.SUBTITLE, 61_000L, 0L)
        assertEquals(AutoSyncPhase.SHOW_RESULT, late.phase)
        assertEquals(-1_000L, late.suggestedOffsetMs)

        val subtitleFirst = autoSyncMarkTransition(marking, AutoSyncMark.SUBTITLE, 59_000L, 0L)
        val early = autoSyncMarkTransition(subtitleFirst, AutoSyncMark.AUDIO, 60_000L, 0L)
        assertEquals(AutoSyncPhase.SHOW_RESULT, early.phase)
        assertEquals(1_000L, early.suggestedOffsetMs)
    }

    @Test
    fun `a mark can be corrected before the second one is set`() {
        val first = autoSyncMarkTransition(marking, AutoSyncMark.AUDIO, 60_000L, 0L)
        val corrected = autoSyncMarkTransition(first, AutoSyncMark.AUDIO, 60_400L, 0L)
        assertEquals(60_400L, corrected.audioMs)
        assertEquals(AutoSyncPhase.MARKING, corrected.phase)
    }

    @Test
    fun `marks are ignored outside the marking phase and for unknown positions`() {
        val idle = AutoSyncState()
        assertEquals(idle, autoSyncMarkTransition(idle, AutoSyncMark.AUDIO, 60_000L, 0L))
        assertEquals(marking, autoSyncMarkTransition(marking, AutoSyncMark.AUDIO, -1L, 0L))
        val done = AutoSyncState(AutoSyncPhase.SHOW_RESULT, 1L, 2L, -1L)
        assertEquals(done, autoSyncMarkTransition(done, AutoSyncMark.SUBTITLE, 99L, 0L))
    }

    @Test
    fun `offsets are formatted with an explicit sign and sensible units`() {
        assertEquals("0 ms", formatSubtitleSyncOffset(0L))
        assertEquals("+50 ms", formatSubtitleSyncOffset(50L))
        assertEquals("-150 ms", formatSubtitleSyncOffset(-150L))
        assertEquals("+1 s", formatSubtitleSyncOffset(1_000L))
        assertEquals("-3.141 s", formatSubtitleSyncOffset(-3_141L))
        assertEquals("+2.5 s", formatSubtitleSyncOffset(2_500L))
        assertEquals("-10 s", formatSubtitleSyncOffset(-10_000L))
    }
}
