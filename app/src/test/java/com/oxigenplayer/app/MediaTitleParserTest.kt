// SPDX-License-Identifier: GPL-3.0-only
package com.oxigenplayer.app

import org.junit.Assert.assertEquals
import org.junit.Test

class MediaTitleParserTest {

    @Test
    fun plainVideoName_isSafeToParse() {
        assertEquals("oxigen test", MediaTitleParser.titleWithYear("oxigen_test.mp4"))
    }

    @Test
    fun bracketedReleaseMetadata_isRemoved() {
        assertEquals(
            "Example Movie 2024",
            MediaTitleParser.titleWithYear("Example.Movie.2024.[Release Group].{HDR}.mkv"),
        )
    }

    @Test
    fun sceneRelease_dropsEverythingAfterTheYear() {
        assertEquals(
            "The Matrix (1999)",
            MediaTitleParser.displayTitle("The.Matrix.1999.1080p.BluRay.x264-SPARKS.mkv"),
        )
        assertEquals(
            "Spider-Man No Way Home (2021)",
            MediaTitleParser.displayTitle("Spider-Man.No.Way.Home.2021.2160p.WEB-DL.DDP5.1.Atmos.HEVC-GRP.mkv"),
        )
    }

    @Test
    fun numbersInTitle_areKept() {
        assertEquals("Blade Runner 2049 (2017)", MediaTitleParser.displayTitle("Blade.Runner.2049.2017.1080p.mkv"))
        assertEquals("1917 (2019)", MediaTitleParser.displayTitle("1917.2019.720p.mkv"))
        assertEquals("Movie Name (2019)", MediaTitleParser.displayTitle("Movie Name (2019) [1080p].mp4"))
    }

    @Test
    fun episodes_keepTheEpisodeTag() {
        assertEquals("Show Name S01E02", MediaTitleParser.displayTitle("Show.Name.S01E02.720p.HDTV.x264-GRP.mkv"))
    }

    @Test
    fun downloadSitePrefix_isRemoved() {
        assertEquals(
            "Ranabaali (2026)",
            MediaTitleParser.displayTitle("www.1TamilMV.io - Ranabaali (2026) Telugu HQ HDRip - 1080p - x264.mkv"),
        )
        assertEquals("Ranabaali (2026)", MediaTitleParser.displayTitle("www 1TamilMV io Ranabaali (2026).mkv"))
        assertEquals("Some Movie (2020)", MediaTitleParser.displayTitle("www.Site.com.Some.Movie.2020.720p.mkv"))
        assertEquals("Some Movie (2020)", MediaTitleParser.displayTitle("1TamilBlasters.io - Some Movie 2020 720p.mkv"))
        assertEquals("Love In Paris (2019)", MediaTitleParser.displayTitle("Love.In.Paris.2019.1080p.mkv"))
    }

    @Test
    fun noYear_cutsAtMetadataAndGroup() {
        assertEquals("Some Movie", MediaTitleParser.displayTitle("Some.Movie.1080p.WEBRip.x265-RARBG.mp4"))
        assertEquals("Some Movie 2019", MediaTitleParser.titleWithName("Some_Movie_2019_720p.mkv"))
    }
}
