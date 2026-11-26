// SPDX-License-Identifier: GPL-3.0-only
package com.oxigenplayer.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleSamiTtmlTimingTest {

    private val sami = """
        <SAMI>
        <HEAD><TITLE>Film</TITLE>
        <STYLE TYPE="text/css"><!-- P { font-size: 20pt; } .ROCC { Name: Romanian; lang: ro-RO; } --></STYLE>
        </HEAD>
        <BODY>
        <SYNC Start=1000><P Class=ROCC>Bună
        ziua<br>tuturor</P>
        <SYNC Start=3000><P Class=ROCC>&nbsp;</P>
        <SYNC Start="4500"><P Class=ROCC><font color="#ffff00">Al doilea</font></P>
        </BODY>
        </SAMI>
    """.trimIndent()

    @Test
    fun parsesRealSamiSyncCues() {
        val result = SubtitleParser.parse(sami.toByteArray(), fileName = "film.smi")
        assertTrue(result.toString(), result is SubtitleParseResult.Success)
        val cues = result.entries
        assertEquals(2, cues.size)

        // Source line breaks are HTML whitespace; <br> is the real line break.
        assertEquals("Bună ziua\ntuturor", cues[0].text)
        assertEquals(1_000L, cues[0].startTime)
        // The &nbsp; SYNC clears the screen, so it ends the first cue.
        assertEquals(3_000L, cues[0].endTime)

        assertEquals("Al doilea", cues[1].text)
        assertEquals(4_500L, cues[1].startTime)
        assertEquals(9_500L, cues[1].endTime)
    }

    @Test
    fun detectsSamiWithoutExtension() {
        assertEquals(SubtitleFormat.SMI, SubtitleParser.detectFormat(sami, fileName = null))
    }

    @Test
    fun parsesTtmlTickOffsets() {
        val ttml = "<?xml version=\"1.0\"?>" +
            "<tt xmlns=\"http://www.w3.org/ns/ttml\" " +
            "xmlns:ttp=\"http://www.w3.org/ns/ttml#parameter\" ttp:tickRate=\"10000000\">" +
            "<body><div>" +
            "<p begin=\"15000000t\" end=\"32500000t\">Primul</p>" +
            "</div></body></tt>"
        val cue = SubtitleParser.parse(ttml.toByteArray(), fileName = "film.dfxp").entries.single()
        assertEquals(1_500L, cue.startTime)
        assertEquals(3_250L, cue.endTime)
    }

    @Test
    fun parsesTtmlFrameClockAndFrameUnit() {
        val ttml = "<tt xmlns:ttp=\"http://www.w3.org/ns/ttml#parameter\" ttp:frameRate=\"25\">" +
            "<body><div>" +
            "<p begin=\"00:00:01:05\" end=\"75f\">Cadre</p>" +
            "</div></body></tt>"
        val cue = SubtitleParser.parse(ttml.toByteArray(), fileName = "film.ttml").entries.single()
        // 1 s + 5 frames at 25 fps = 1200 ms; 75 frames = 3000 ms.
        assertEquals(1_200L, cue.startTime)
        assertEquals(3_000L, cue.endTime)
    }
}
