// SPDX-License-Identifier: GPL-3.0-only
package com.oxigenplayer.app

import org.junit.Assert.assertEquals
import org.junit.Test

class VideoResizeModeTest {

    private val wideVideo = 16f / 9f      // 1.777…
    private val cinemaVideo = 2.39f       // anamorphic widescreen film
    private val tallVideo = 4f / 3f       // 1.333…
    private val tvContainer = 16f / 9f    // 4K / Full-HD television

    // The Compose PlayerSurface stretches the decoded frame to fill the container, so the
    // scale factors below undo that stretch to restore the original proportions.

    @Test
    fun fitLetterboxesWideFilmOnTelevision() {
        // A 2.39:1 film on a 16:9 screen is stretched too tall; squash the height back so the
        // whole frame stays visible at its original size and the black background forms the bars.
        val (sx, sy) = videoResizeScale(VideoResizeMode.FIT, cinemaVideo, 3_840f, 2_160f)
        assertEquals(1f, sx, 0.0001f)
        assertEquals(tvContainer / cinemaVideo, sy, 0.0001f)
    }

    @Test
    fun fitPillarboxesTallVideoOnTelevision() {
        // A 4:3 picture on a 16:9 screen is stretched too wide; shrink the width instead.
        val (sx, sy) = videoResizeScale(VideoResizeMode.FIT, tallVideo, 1_920f, 1_080f)
        assertEquals(tallVideo / tvContainer, sx, 0.0001f)
        assertEquals(1f, sy, 0.0001f)
    }

    @Test
    fun fillCoversWideFilmWithoutDistortion() {
        // FILL keeps the film undistorted and grows the width until the frame covers the screen;
        // the parent clips the overflow (center-crop).
        val (sx, sy) = videoResizeScale(VideoResizeMode.FILL, cinemaVideo, 3_840f, 2_160f)
        assertEquals(cinemaVideo / tvContainer, sx, 0.0001f)
        assertEquals(1f, sy, 0.0001f)
    }

    @Test
    fun fillCoversTallVideoWithoutDistortion() {
        val (sx, sy) = videoResizeScale(VideoResizeMode.FILL, tallVideo, 1_920f, 1_080f)
        assertEquals(1f, sx, 0.0001f)
        assertEquals(tvContainer / tallVideo, sy, 0.0001f)
    }

    @Test
    fun stretchFillsTheContainerAsIs() {
        // The surface already stretches to fill, so STRETCH must not scale anything.
        assertEquals(1f to 1f, videoResizeScale(VideoResizeMode.STRETCH, cinemaVideo, 3_840f, 2_160f))
        assertEquals(1f to 1f, videoResizeScale(VideoResizeMode.STRETCH, tallVideo, 1_920f, 1_080f))
    }

    @Test
    fun matchingAspectRatioNeedsNoScaling() {
        // 16:9 content on a 16:9 screen already fills correctly in every mode.
        assertEquals(1f to 1f, videoResizeScale(VideoResizeMode.FIT, wideVideo, 3_840f, 2_160f))
        assertEquals(1f to 1f, videoResizeScale(VideoResizeMode.FILL, wideVideo, 1_920f, 1_080f))
        assertEquals(1f to 1f, videoResizeScale(VideoResizeMode.STRETCH, wideVideo, 1_920f, 1_080f))
    }

    @Test
    fun unknownGeometryFallsBackToIdentity() {
        assertEquals(1f to 1f, videoResizeScale(VideoResizeMode.FIT, null, 100f, 100f))
        assertEquals(1f to 1f, videoResizeScale(VideoResizeMode.FILL, Float.NaN, 100f, 100f))
        assertEquals(1f to 1f, videoResizeScale(VideoResizeMode.FIT, cinemaVideo, 0f, 100f))
        assertEquals(1f to 1f, videoResizeScale(VideoResizeMode.FILL, cinemaVideo, 100f, -1f))
    }

    @Test
    fun containerAspectRatioIsDerivedFromGeometry() {
        // Absolute container size must not matter, only the ratio: a 2.39:1 film letterboxed on
        // any 16:9 screen produces the same factors.
        val small = videoResizeScale(VideoResizeMode.FIT, cinemaVideo, 960f, 540f)
        val large = videoResizeScale(VideoResizeMode.FIT, cinemaVideo, 3_840f, 2_160f)
        assertEquals(small, large)
        // On a narrow portrait phone the same film is limited by width, so only height shrinks.
        val phone = videoResizeScale(VideoResizeMode.FIT, cinemaVideo, 540f, 960f)
        assertEquals(1f, phone.first, 0.0001f)
        assertEquals((540f / 960f) / cinemaVideo, phone.second, 0.0001f)
    }

    @Test
    fun aspectRatioOverrideForcesTargetRatio() {
        // A 2.39:1 film forced to 16:9 on a 16:9 screen now matches the container: no scaling.
        assertEquals(
            1f to 1f,
            videoResizeScale(VideoResizeMode.FIT, cinemaVideo, 3_840f, 2_160f, VideoAspectRatioOverride.RATIO_16_9),
        )
        // Forcing 4:3 on the same screen pillarboxes: only the width shrinks.
        val (sx, sy) = videoResizeScale(VideoResizeMode.FIT, cinemaVideo, 3_840f, 2_160f, VideoAspectRatioOverride.RATIO_4_3)
        assertEquals((4f / 3f) / tvContainer, sx, 0.0001f)
        assertEquals(1f, sy, 0.0001f)
        // ORIGINAL is identical to passing no override at all.
        assertEquals(
            videoResizeScale(VideoResizeMode.FIT, cinemaVideo, 3_840f, 2_160f),
            videoResizeScale(VideoResizeMode.FIT, cinemaVideo, 3_840f, 2_160f, VideoAspectRatioOverride.ORIGINAL),
        )
    }
}