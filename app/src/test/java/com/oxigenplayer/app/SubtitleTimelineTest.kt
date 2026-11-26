// SPDX-License-Identifier: GPL-3.0-only
package com.oxigenplayer.app

import org.junit.Assert.assertEquals
import org.junit.Test

class SubtitleTimelineTest {
    @Test
    fun overlappingCueRemainsDiscoverableAfterShorterCueEnds() {
        val subtitles = listOf(
            SubtitleEntry(0, 0, 10_000, "long"),
            SubtitleEntry(1, 1_000, 2_000, "short"),
            SubtitleEntry(2, 20_000, 21_000, "later"),
        )
        val prefix = buildSubtitlePrefixMaxEnd(subtitles)

        assertEquals(1, findActiveSubtitleIndex(subtitles, prefix, 1_500))
        assertEquals(0, findActiveSubtitleIndex(subtitles, prefix, 5_000))
        assertEquals(-1, findActiveSubtitleIndex(subtitles, prefix, 15_000))
        assertEquals(2, findActiveSubtitleIndex(subtitles, prefix, 20_500))
    }
}
