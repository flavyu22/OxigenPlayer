// SPDX-License-Identifier: GPL-3.0-only
package com.oxigenplayer.app

/** User-supplied public-client credentials. Values are stored only in encrypted preferences. */
data class SubtitleProviderConfig(
    val openSubtitlesApiKey: String = "",
    val subsRoApiKey: String = "",
    val subDlApiKey: String = "",
    val tmdbApiKey: String = "",
) {
    fun normalized(): SubtitleProviderConfig = copy(
        openSubtitlesApiKey = openSubtitlesApiKey.trim().take(MAX_KEY_LENGTH),
        subsRoApiKey = subsRoApiKey.trim().take(MAX_KEY_LENGTH),
        subDlApiKey = subDlApiKey.trim().take(MAX_KEY_LENGTH),
        tmdbApiKey = tmdbApiKey.trim().take(MAX_KEY_LENGTH),
    )

    companion object {
        private const val MAX_KEY_LENGTH = 512
    }
}
