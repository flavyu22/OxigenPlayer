// SPDX-License-Identifier: GPL-3.0-only
package com.oxigenplayer.app

import androidx.media3.common.PlaybackException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Covers the mapping from Media3 error codes to viewer-facing messages. */
class PlaybackErrorsTest {
    @Test
    fun `io errors map to the cause a viewer can fix`() {
        assertEquals("error_no_permission", playbackErrorMessageKey(PlaybackException.ERROR_CODE_IO_NO_PERMISSION))
        assertEquals("error_not_found", playbackErrorMessageKey(PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND))
        assertEquals("error_network", playbackErrorMessageKey(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED))
        assertEquals("error_network", playbackErrorMessageKey(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS))
    }

    @Test
    fun `parsing and decoder families are recognised by band`() {
        assertEquals(
            "error_unsupported_format",
            playbackErrorMessageKey(PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED),
        )
        assertEquals("error_decoder", playbackErrorMessageKey(PlaybackException.ERROR_CODE_DECODER_INIT_FAILED))
        assertEquals("error_decoder", playbackErrorMessageKey(PlaybackException.ERROR_CODE_DECODING_FAILED))
    }

    @Test
    fun `unknown codes keep the technical message`() {
        assertNull(playbackErrorMessageKey(PlaybackException.ERROR_CODE_UNSPECIFIED))
        assertEquals("Source error (X)", composePlaybackErrorMessage(null, "Source error", "X"))
        assertEquals("Playback error (X)", composePlaybackErrorMessage(null, " ", "X"))
    }

    @Test
    fun `friendly message leads and the code name is kept for support`() {
        assertEquals(
            "Network problem. (ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)",
            composePlaybackErrorMessage(
                "Network problem.",
                "Source error",
                "ERROR_CODE_IO_NETWORK_CONNECTION_FAILED",
            ),
        )
    }
}
