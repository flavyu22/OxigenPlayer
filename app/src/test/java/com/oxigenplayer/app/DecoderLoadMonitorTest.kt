// SPDX-License-Identifier: GPL-3.0-only
package com.oxigenplayer.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DecoderLoadMonitorTest {

    @Test
    fun `smooth playback is not overloaded`() {
        assertFalse(isDecoderOverloaded(renderedDelta = 120, droppedDelta = 0))
        assertFalse(isDecoderOverloaded(renderedDelta = 118, droppedDelta = 2))
    }

    @Test
    fun `heavy dropping is overloaded`() {
        assertTrue(isDecoderOverloaded(renderedDelta = 70, droppedDelta = 50))
    }

    @Test
    fun `exactly the threshold ratio is not overloaded`() {
        assertFalse(isDecoderOverloaded(renderedDelta = 80, droppedDelta = 20))
        assertTrue(isDecoderOverloaded(renderedDelta = 79, droppedDelta = 21))
    }

    @Test
    fun `too few frames are never trusted`() {
        // A paused or just-resumed window must not trigger the warning.
        assertFalse(isDecoderOverloaded(renderedDelta = 5, droppedDelta = 20))
    }
}
