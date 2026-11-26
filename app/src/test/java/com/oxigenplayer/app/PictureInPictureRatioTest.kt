// SPDX-License-Identifier: GPL-3.0-only
package com.oxigenplayer.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PictureInPictureRatioTest {

    private fun snapshot(width: Int, height: Int, pixelRatio: Float = 1f) = PlaybackSnapshot(
        hasMedia = true,
        isPlaying = true,
        playWhenReady = true,
        phase = PlaybackPhase.READY,
        positionMs = 0L,
        durationMs = 1_000L,
        bufferedPositionMs = 0L,
        isLive = false,
        isSeekable = true,
        mediaUri = "content://media/1",
        videoWidth = width,
        videoHeight = height,
        pixelWidthHeightRatio = pixelRatio,
    )

    @Test
    fun aspectRatioIsUnknownUntilTheVideoSizeArrives() {
        assertNull(snapshot(0, 0).displayAspectRatio())
        assertNull(snapshot(1920, 0).displayAspectRatio())
        assertNull(snapshot(-1, 1080).displayAspectRatio())
    }

    @Test
    fun anamorphicPixelRatioIsApplied() {
        // 720x576 PAL widescreen stores square-ish samples with a 64/45 pixel aspect ratio.
        // The stored resolution alone reports 1.25:1 instead of the 16:9 the viewer sees.
        val ratio = requireNotNull(snapshot(720, 576, pixelRatio = 64f / 45f).displayAspectRatio())
        assertEquals(16f / 9f, ratio, 0.001f)
        // NTSC 720x480 widescreen uses 32/27 and must land on the same display ratio.
        val ntsc = requireNotNull(snapshot(720, 480, pixelRatio = 32f / 27f).displayAspectRatio())
        assertEquals(16f / 9f, ntsc, 0.001f)
        // Square-pixel 1080p is unaffected.
        assertEquals(16f / 9f, snapshot(1920, 1080).displayAspectRatio()!!, 0.001f)
    }

    @Test
    fun invalidPixelRatiosFallBackToSquarePixels() {
        assertEquals(16f / 9f, snapshot(1920, 1080, 0f).displayAspectRatio()!!, 0.0001f)
        assertEquals(16f / 9f, snapshot(1920, 1080, Float.NaN).displayAspectRatio()!!, 0.0001f)
        assertEquals(16f / 9f, snapshot(1920, 1080, -2f).displayAspectRatio()!!, 0.0001f)
    }

    @Test
    fun rationalStaysInsideThePlatformAcceptedRange() {
        val candidates = listOf(null, 0.0001f, 1f / 3f, 0.5f, 1.33f, 1.78f, 2.35f, 4f, 1000f)
        candidates.forEach { candidate ->
            val (numerator, denominator) = pictureInPictureRational(candidate)
            assertTrue("numerator must be positive for $candidate", numerator > 0)
            assertTrue("denominator must be positive for $candidate", denominator > 0)
            val ratio = numerator.toFloat() / denominator.toFloat()
            assertTrue(
                "ratio $ratio out of range for $candidate",
                ratio >= MIN_PIP_ASPECT_RATIO - 0.001f && ratio <= MAX_PIP_ASPECT_RATIO + 0.001f,
            )
        }
    }

    @Test
    fun commonRatiosAreReproducedFaithfully() {
        listOf(16f / 9f, 4f / 3f, 2.35f, 1f).forEach { expected ->
            val (numerator, denominator) = pictureInPictureRational(expected)
            assertEquals(expected, numerator.toFloat() / denominator.toFloat(), 0.002f)
        }
    }

    @Test
    fun missingAspectRatioFallsBackToSixteenNine() {
        val (numerator, denominator) = pictureInPictureRational(null)
        assertEquals(16f / 9f, numerator.toFloat() / denominator.toFloat(), 0.002f)
    }
}
