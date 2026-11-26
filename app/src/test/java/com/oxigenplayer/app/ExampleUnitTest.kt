// SPDX-License-Identifier: GPL-3.0-only
package com.oxigenplayer.app

import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertArrayEquals
import java.io.ByteArrayInputStream
import java.io.IOException

class SubtitleParserTest {
    @Test
    fun parsesValidSrtAndRemovesMarkup() {
        val content = """
            1
            00:00:01,250 --> 00:00:03,500
            <i>Hello</i>

            2
            00:01:00.000 --> 00:01:02.100
            Second line
        """.trimIndent()

        val entries = SubtitleParser.parseSRTFromContent(content)

        assertEquals(2, entries.size)
        assertEquals(1_250L, entries[0].startTime)
        assertEquals(3_500L, entries[0].endTime)
        assertEquals("Hello", entries[0].text)
        assertEquals(60_000L, entries[1].startTime)
    }

    @Test
    fun parsesAdjacentCuesWithoutBlankSeparator() {
        val content = """
            00:00:01,000 --> 00:00:02,000
            First
            00:00:03,000 --> 00:00:04,000
            Second
        """.trimIndent()

        val entries = SubtitleParser.parseSRTFromContent(content)

        assertEquals(listOf("First", "Second"), entries.map { it.text })
    }

    @Test
    fun rejectsInvalidIntervalsAndOversizedCues() {
        val oversized = "x".repeat(SubtitleParser.MAX_CUE_TEXT_CHARS + 1)
        val content = """
            00:00:03,000 --> 00:00:02,000
            Invalid interval

            00:00:04,000 --> 00:00:05,000
            $oversized
        """.trimIndent()

        assertEquals(emptyList<SubtitleEntry>(), SubtitleParser.parseSRTFromContent(content))
    }

    @Test
    fun extractsReleaseYearSafely() {
        assertEquals("2024", MovieNameExtractor.extractYear("Movie.Name.(2024).1080p.mkv"))
        assertNull(MovieNameExtractor.extractYear("Movie.Name.No.Year.mkv"))
    }

    @Test
    fun boundedReaderAcceptsPayloadAtLimit() {
        val bytes = ByteArray(32) { it.toByte() }
        val result = FilePickerManager().readBounded(ByteArrayInputStream(bytes), bytes.size)
        assertArrayEquals(bytes, result)
    }

    @Test(expected = IOException::class)
    fun boundedReaderRejectsPayloadOverLimit() {
        val bytes = ByteArray(33)
        FilePickerManager().readBounded(ByteArrayInputStream(bytes), 32)
    }
}
