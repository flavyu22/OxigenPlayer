// SPDX-License-Identifier: GPL-3.0-only
package com.oxigenplayer.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MediaTitleParserInstrumentedTest {

    @Test
    fun bracketMetadataRegex_compilesOnAndroid() {
        assertEquals(
            "Example Movie 2024",
            MediaTitleParser.titleWithYear("Example.Movie.2024.[Release Group].{HDR}.mkv"),
        )
    }
}
