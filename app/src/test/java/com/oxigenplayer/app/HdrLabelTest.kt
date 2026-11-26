// SPDX-License-Identifier: GPL-3.0-only
package com.oxigenplayer.app

import androidx.media3.common.C
import androidx.media3.common.ColorInfo
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import org.junit.Assert.assertEquals
import org.junit.Test

@androidx.annotation.OptIn(UnstableApi::class)
class HdrLabelTest {

    @Test
    fun detectsDolbyVisionByCodecPrefix() {
        assertEquals("Dolby Vision", Format.Builder().setCodecs("dvhe.05.01").build().hdrLabel())
        assertEquals("Dolby Vision", Format.Builder().setCodecs("dvh1.08.04").build().hdrLabel())
    }

    @Test
    fun detectsHlgAndHdr10ByColorTransfer() {
        val hlg = ColorInfo.Builder().setColorTransfer(C.COLOR_TRANSFER_HLG).build()
        assertEquals("HLG", Format.Builder().setColorInfo(hlg).build().hdrLabel())
        val pq = ColorInfo.Builder().setColorTransfer(C.COLOR_TRANSFER_ST2084).build()
        assertEquals("HDR10", Format.Builder().setColorInfo(pq).build().hdrLabel())
    }

    @Test
    fun sdrAndUnknownColorInfoFallBackToSdr() {
        val sdr = ColorInfo.Builder().setColorTransfer(C.COLOR_TRANSFER_SDR).build()
        assertEquals("SDR", Format.Builder().setColorInfo(sdr).build().hdrLabel())
        // A null colorInfo is Media3's assumed SDR default.
        assertEquals("SDR", Format.Builder().build().hdrLabel())
    }
}