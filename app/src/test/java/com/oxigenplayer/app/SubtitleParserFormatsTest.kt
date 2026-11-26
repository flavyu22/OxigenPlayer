// SPDX-License-Identifier: GPL-3.0-only
package com.oxigenplayer.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.nio.charset.Charset

class SubtitleParserFormatsTest {
    private val parser = SubtitleParser

    @Test
    fun parsesWebVtt() {
        val result = parser.parse(
            "WEBVTT\n\n00:00:01.000 --> 00:00:02.500\nSalut\n".toByteArray(),
            mimeType = "text/vtt",
            fileName = "movie.vtt",
        )
        assertTrue(result is SubtitleParseResult.Success)
        val cues = (result as SubtitleParseResult.Success).entries
        assertEquals("Salut", cues.single().text)
        assertEquals(1_000L, cues.single().startTime)
    }

    @Test
    fun parsesSmiUsingSrtTimingSyntax() {
        val smi = "Sync=Start, Language: Romanian\r\n" +
            "00:00:01,000 --> 00:00:03,000\r\n" +
            "<font color=\"#ffff00\">Buna</font>\r\n"
        val result = parser.parse(smi.toByteArray(), fileName = "movie.smi")
        assertTrue(result.toString(), result is SubtitleParseResult.Success)
        val cues = (result as SubtitleParseResult.Success).entries
        assertEquals(1_000L, cues.single().startTime)
        assertEquals(3_000L, cues.single().endTime)
        // The <font> wrapper must not leak into the rendered cue.
        assertEquals("Buna", cues.single().text)
    }

    @Test
    fun detectsSmiAndTtmlByContentWhenExtensionIsUnknown() {
        val smi = "Sync=Start\n00:00:01,000 --> 00:00:02,000\nText\n"
        assertEquals(SubtitleFormat.SMI, SubtitleParser.detectFormat(smi, fileName = null))
        assertEquals(SubtitleFormat.SMI, SubtitleParser.detectFormat(smi, mimeType = "application/x-smil"))

        val ttml = "<?xml version=\"1.0\"?><tt><body><div>" +
            "<p begin=\"00:00:01.000\" end=\"00:00:02.000\">Salut</p></div></body></tt>"
        assertEquals(SubtitleFormat.TTML, SubtitleParser.detectFormat(ttml, fileName = null))
        assertEquals(SubtitleFormat.TTML, SubtitleParser.detectFormat(ttml, fileName = "movie.ttml"))
        assertEquals(SubtitleFormat.TTML, SubtitleParser.detectFormat(ttml, fileName = "movie.dfxp"))
    }

    @Test
    fun parsesTtmlClockAndUnitOffsets() {
        val ttml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
            "<tt xmlns=\"http://www.w3.org/ns/ttml\"><body><div>\n" +
            "<p begin=\"00:00:01.500\" end=\"00:00:03.250\">Primul</p>\n" +
            "<p begin=\"00:01:00.000\" end=\"62000ms\">Al doilea</p>\n" +
            "<p begin=\"00:00:04.000\" end=\"00:00:05.000\"><span>Al treilea</span></p>\n" +
            "</div></body></tt>"
        val result = parser.parse(ttml.toByteArray(), fileName = "movie.ttml")
        assertTrue(result.toString(), result is SubtitleParseResult.Success)
        // Cues come back sorted by startTime, so address them by content rather than by source order.
        val cues = (result as SubtitleParseResult.Success).entries
        assertEquals(3, cues.size)
        val byText = cues.associateBy { it.text }

        // Fractional seconds must survive: 00:00:01.500 -> 1500 ms, not 1000 and not rejected.
        val first = byText.getValue("Primul")
        assertEquals(1_500L, first.startTime)
        assertEquals(3_250L, first.endTime)

        // `hh:mm:ss.fff` on the left, a `ms` unit on the right: 00:01:00.000 = 60 000 ms.
        val second = byText.getValue("Al doilea")
        assertEquals(60_000L, second.startTime)
        assertEquals(62_000L, second.endTime)

        // Nested <span> markup is flattened to its text, not dropped.
        val third = byText.getValue("Al treilea")
        assertEquals(4_000L, third.startTime)
        assertEquals(5_000L, third.endTime)
    }

    @Test
    fun rejectsTtmlCuesWithUnparseableOrInvertedTiming() {
        val ttml = "<tt><body><div>" +
            // No unit and not a clock time: TTML does not define it, so it must not be guessed.
            "<p begin=\"1500\" end=\"3000\">Fara unitate</p>" +
            // end before begin: invalid cue.
            "<p begin=\"00:00:05.000\" end=\"00:00:01.000\">Inversate</p>" +
            "<p begin=\"00:00:06.000\" end=\"00:00:07.000\">Valid</p>" +
            "</div></body></tt>"
        val result = parser.parse(ttml.toByteArray(), fileName = "movie.ttml")
        assertTrue(result is SubtitleParseResult.Partial)
        val partial = result as SubtitleParseResult.Partial
        assertEquals(listOf("Valid"), partial.entries.map { it.text })
        assertEquals(2, partial.rejectedCues)
    }

    @Test
    fun parsesAssAndRemovesOverrideTags() {
        val ass = """
            [Script Info]
            Title: Test
            [Events]
            Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
            Dialogue: 0,0:00:01.00,0:00:03.00,Default,,0,0,0,,{\\i1}Hello{\\i0}\\NWorld
        """.trimIndent()
        val result = parser.parse(ass.toByteArray(), fileName = "movie.ass")
        assertTrue(result is SubtitleParseResult.Success)
        assertEquals("Hello\nWorld", (result as SubtitleParseResult.Success).entries.single().text)
    }

    @Test
    fun decodesWindows1250RomanianDiacritics() {
        val content = "1\r\n00:00:01,000 --> 00:00:02,000\r\nŞtiinţă şi ţară\r\n"
        val bytes = content.toByteArray(Charset.forName("windows-1250"))
        val result = parser.parse(bytes, fileName = "movie.srt")
        assertTrue(result is SubtitleParseResult.Success)
        assertEquals("Știință și țară", (result as SubtitleParseResult.Success).entries.single().text)
    }

    @Test
    fun parsesSrtWithoutBlankLineBetweenCues() {
        val content = """1
00:00:01,000 --> 00:00:02,000
First line
2
00:00:03,000 --> 00:00:04,000
Second line
""".trimIndent()

        val entries = SubtitleParser.parseContent(content, fileName = "compact.srt").entries
        assertEquals(2, entries.size)
        assertEquals("First line", entries[0].text)
        assertEquals("Second line", entries[1].text)
    }

    @Test
    fun webVttPreservesBreakTagsAndDecodesNestedEntities() {
        val content = """WEBVTT

00:00:01.000 --> 00:00:02.000
Tom &amp;amp; Jerry<br/>Salut &#x0218;i
""".trimIndent()

        val entries = SubtitleParser.parseContent(content, fileName = "movie.vtt").entries
        assertEquals(1, entries.size)
        assertEquals("Tom & Jerry\nSalut Și", entries.single().text)
    }
    @Test
    fun rejectsOversizedInputStreamsAndByteArrays() {
        val oversized = ByteArray(SubtitleParser.MAX_DOCUMENT_BYTES + 1)
        val streamResult = parser.parse(ByteArrayInputStream(oversized), fileName = "large.srt")
        val byteArrayResult = parser.parse(oversized, fileName = "large.srt")

        assertEquals(
            SubtitleParseFailure.TOO_LARGE,
            (streamResult as SubtitleParseResult.Failure).reason,
        )
        assertEquals(
            SubtitleParseFailure.TOO_LARGE,
            (byteArrayResult as SubtitleParseResult.Failure).reason,
        )
    }

}
