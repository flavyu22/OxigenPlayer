// SPDX-License-Identifier: GPL-3.0-only
package com.oxigenplayer.app

import java.util.concurrent.ConcurrentHashMap

/**
 * Two-level memoisation (language tag -> resource id -> resolved text) for Android string
 * resources.
 *
 * `Resources.getString` is a native AssetManager lookup that allocates a fresh `String` on every
 * call. OxigenPlayer resolves its UI text through [OxigenStrings] from ~230 call sites that sit
 * inside Compose composition, so without memoisation every visible label re-resolves — and
 * re-allocates — its text on every recomposition. Dialogs such as Settings re-resolve dozens of
 * labels while the user moves focus with a D-pad, which is exactly the workload an entry-level
 * Android TV cannot afford.
 *
 * The language tag is part of the key, so runtime language switching (which recreates the
 * Activity) can never surface text resolved for a different locale. The cache is bounded by
 * `supportedLanguages` x the number of mapped keys — eight languages times roughly two hundred
 * short strings — and is dropped wholesale by [clear].
 *
 * Extracted from [OxigenStrings] so the memoisation invariants can be verified on the JVM without
 * an Android `Context`.
 */
internal class LocalizedStringCache {
    private val entries = ConcurrentHashMap<String, ConcurrentHashMap<Int, String>>()

    /** Text already resolved for [language]/[resourceId], or `null` when this pair is not cached. */
    operator fun get(language: String, resourceId: Int): String? =
        entries[language]?.get(resourceId)

    /**
     * Stores [value] and returns the text that is authoritative for the pair.
     *
     * Two threads can resolve the same pair concurrently; `putIfAbsent` keeps whichever value
     * landed first so every caller converges on one identical instance instead of flip-flopping
     * between two equal-but-distinct strings.
     */
    fun put(language: String, resourceId: Int, value: String): String =
        entries
            .getOrPut(language) { ConcurrentHashMap() }
            .putIfAbsent(resourceId, value)
            ?: value

    /** Drops every resolved string. Called when the localized contexts are rebuilt. */
    fun clear() {
        entries.clear()
    }
}
