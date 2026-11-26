// SPDX-License-Identifier: GPL-3.0-only
package com.oxigenplayer.app

import androidx.media3.common.PlaybackException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the decoder-failure classification that drives the software-decoder rebuild.
 *
 * The two real-world failures this guards are the ones seen on car head units: E-AC3 reported by
 * `MediaCodecList` but rejected at `configure()`, and HEVC Main 10 accepted at init then failing
 * with `ERROR_CODE_DECODING_FAILED`.
 */
class SoftwareFallbackPolicyTest {

    @Test
    fun `audio decoder init failure triggers software fallback`() {
        assertTrue(
            isSoftwareFallbackErrorCode(
                PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
                "ERROR_CODE_DECODER_INIT_FAILED",
            ),
        )
    }

    @Test
    fun `hevc decoding failure triggers software fallback`() {
        assertTrue(
            isSoftwareFallbackErrorCode(
                PlaybackException.ERROR_CODE_DECODING_FAILED,
                "ERROR_CODE_DECODING_FAILED",
            ),
        )
    }

    @Test
    fun `format support failures trigger software fallback`() {
        assertTrue(
            isSoftwareFallbackErrorCode(
                PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
                "ERROR_CODE_DECODING_FORMAT_UNSUPPORTED",
            ),
        )
        assertTrue(
            isSoftwareFallbackErrorCode(
                PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
                "ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES",
            ),
        )
    }

    @Test
    fun `decoder query failure triggers software fallback`() {
        assertTrue(
            isSoftwareFallbackErrorCode(
                PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
                "ERROR_CODE_DECODER_QUERY_FAILED",
            ),
        )
    }

    @Test
    fun `renamed decoder init code is still recognised by name`() {
        // Builds disagree on the symbol for this failure; the name keeps recovery working
        // regardless of which numbering the linked Media3 exposes.
        assertTrue(
            isSoftwareFallbackErrorCode(
                -50001,
                "ERROR_CODE_DECODER_INIT_UNSUPPORTED_SUBTYPE",
            ),
        )
    }

    @Test
    fun `resources reclaimed is excluded because Media3 recovers it internally`() {
        assertFalse(
            isSoftwareFallbackErrorCode(
                PlaybackException.ERROR_CODE_DECODING_RESOURCES_RECLAIMED,
                "ERROR_CODE_DECODING_RESOURCES_RECLAIMED",
            ),
        )
    }

    @Test
    fun `transient source errors never trigger software fallback`() {
        assertFalse(
            isSoftwareFallbackErrorCode(
                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
                "ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT",
            ),
        )
        assertFalse(
            isSoftwareFallbackErrorCode(
                PlaybackException.ERROR_CODE_TIMEOUT,
                "ERROR_CODE_TIMEOUT",
            ),
        )
    }

    @Test
    fun `container parsing errors never trigger software fallback`() {
        assertFalse(
            isSoftwareFallbackErrorCode(
                PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
                "ERROR_CODE_PARSING_CONTAINER_MALFORMED",
            ),
        )
        assertFalse(
            isSoftwareFallbackErrorCode(
                PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
                "ERROR_CODE_PARSING_MANIFEST_MALFORMED",
            ),
        )
    }

    @Test
    fun `unexpected runtime errors never trigger software fallback`() {
        assertFalse(
            isSoftwareFallbackErrorCode(
                PlaybackException.ERROR_CODE_FAILED_RUNTIME_CHECK,
                "ERROR_CODE_FAILED_RUNTIME_CHECK",
            ),
        )
    }

    @Test
    fun `retry budget is bounded to re-prepare plus one software rebuild`() {
        assertEquals(2, MAX_SOFTWARE_FALLBACK_ATTEMPTS)
    }
}