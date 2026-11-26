// SPDX-License-Identifier: GPL-3.0-only
package com.oxigenplayer.app

import androidx.media3.common.PlaybackException

/**
 * Decoder failures that a software-decoder retry can plausibly fix.
 *
 * Many Android TV boxes and car head units advertise codecs through `MediaCodecList` that they
 * cannot actually run: the codec reports support, wins renderer selection, and then fails with
 * `ERROR_CODE_DECODER_INIT_FAILED` (E-AC3 declared but `configure()` rejected) or
 * `ERROR_CODE_DECODING_FAILED` (HEVC Main 10 accepted at init, then dies on the first 10-bit
 * frame). `setEnableDecoderFallback(true)` does not help here because it only cycles between
 * `MediaCodec` decoders of the same MIME type; the FFmpeg renderer stays unused because renderer
 * choice happens once, at track selection. Rebuilding the player software-first is the only way
 * to reach those decoders.
 *
 * The numeric range check deliberately covers the whole decoder family rather than a hand-picked
 * list: builds disagree on names for these codes (`ERROR_CODE_DECODER_INIT_UNSUPPORTED_SUBTYPE`
 * vs `ERROR_CODE_DECODER_INIT_FAILED`), while the 4000-4099 band is stable across them.
 * `ERROR_CODE_DECODING_RESOURCES_RECLAIMED` is excluded because Media3 already recovers from it
 * internally by re-selecting tracks.
 */
internal fun isSoftwareFallbackErrorCode(errorCode: Int, errorCodeName: String): Boolean {
    if (errorCode == PlaybackException.ERROR_CODE_DECODING_RESOURCES_RECLAIMED) return false
    if (errorCode in PLAYBACK_ERROR_CODE_DECODER_MIN..PLAYBACK_ERROR_CODE_DECODER_MAX) return true
    // Older/renamed codes: fall back to the symbolic name so recovery is not tied to numbering.
    val normalized = errorCodeName.lowercase()
    return normalized.contains("decoder_init") || normalized.contains("decoding_failed")
}

/**
 * Bounded recovery budget per media item: first a re-prepare with the failing platform decoder
 * type hidden ([PlatformDecoderBlocklist]), then, if that also fails, a full FFmpeg-only
 * rebuild. Retrying forever would loop on genuinely undecodable content.
 */
internal const val MAX_SOFTWARE_FALLBACK_ATTEMPTS = 2

private const val PLAYBACK_ERROR_CODE_DECODER_MIN = 4000
private const val PLAYBACK_ERROR_CODE_DECODER_MAX = 4099