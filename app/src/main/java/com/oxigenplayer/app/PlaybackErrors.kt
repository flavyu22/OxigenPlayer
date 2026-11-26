// SPDX-License-Identifier: GPL-3.0-only
package com.oxigenplayer.app

/**
 * Maps a Media3 playback error code to the string key of a message a viewer can act on.
 *
 * The numeric bands are part of the public `PlaybackException` contract (2xxx I/O, 3xxx
 * parsing, 4xxx decoding), so the mapping stays pure and testable without Android classes.
 * Returns null for codes that have no better explanation than the technical name.
 */
internal fun playbackErrorMessageKey(errorCode: Int): String? = when (errorCode) {
    2006 -> "error_no_permission" // ERROR_CODE_IO_NO_PERMISSION
    2005 -> "error_not_found" // ERROR_CODE_IO_FILE_NOT_FOUND
    2000, 2001, 2002, 2003, 2004, 2007, 2008 -> "error_network"
    in 3000..3999 -> "error_unsupported_format"
    in 4000..4999 -> "error_decoder"
    else -> null
}

/** Friendly sentence first, technical code name after it for support and bug reports. */
internal fun composePlaybackErrorMessage(
    friendlyMessage: String?,
    technicalMessage: String?,
    errorCodeName: String,
): String {
    val lead = friendlyMessage?.takeIf(String::isNotBlank)
        ?: technicalMessage?.takeIf(String::isNotBlank)
        ?: "Playback error"
    return "$lead ($errorCodeName)"
}
