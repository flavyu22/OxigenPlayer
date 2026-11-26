// SPDX-License-Identifier: GPL-3.0-only
package com.oxigenplayer.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalizedStringCacheTest {
    @Test
    fun unresolvedPairIsReportedAsAMiss() {
        val cache = LocalizedStringCache()
        assertNull(cache["en", 1])
    }

    @Test
    fun resolvedPairIsReturnedFromCache() {
        val cache = LocalizedStringCache()
        val stored = "Settings"
        assertEquals(stored, cache.put("en", 1, stored))
        assertSame(stored, cache["en", 1])
    }

    /**
     * The language tag is part of the key. If it were not, a television that switches from
     * Romanian to English (or back) without restarting the process could keep showing text
     * resolved for the previous locale.
     */
    @Test
    fun languagesNeverLeakIntoEachOther() {
        val cache = LocalizedStringCache()
        cache.put("ro", 1, "Setari")
        cache.put("en", 1, "Settings")
        cache.put("ru", 1, "Nastroiki")

        assertEquals("Setari", cache["ro", 1])
        assertEquals("Settings", cache["en", 1])
        assertEquals("Nastroiki", cache["ru", 1])
        assertNull(cache["de", 1])
    }

    @Test
    fun resourceIdsNeverLeakAcrossKeysWithinALanguage() {
        val cache = LocalizedStringCache()
        cache.put("en", 1, "Settings")
        cache.put("en", 2, "Tracks")

        assertEquals("Settings", cache["en", 1])
        assertEquals("Tracks", cache["en", 2])
        assertNull(cache["en", 3])
    }

    /**
     * Concurrent resolution of the same pair must converge on one instance. Two equal-but-distinct
     * strings would make `put` return a different reference on each call and defeat the identity
     * comparison Compose uses to skip unchanged `Text` nodes.
     */
    @Test
    fun firstStoredValueWinsForTheSamePair() {
        val cache = LocalizedStringCache()
        val first = "Settings"
        val second = String("Settings".toCharArray())

        assertEquals(first, cache.put("en", 1, first))
        assertSame(first, cache.put("en", 1, second))
        assertSame(first, cache["en", 1])
    }

    @Test
    fun clearDropsEveryLanguage() {
        val cache = LocalizedStringCache()
        OxigenStrings.supportedLanguages.forEachIndexed { index, language ->
            cache.put(language, index, "text-$language")
        }
        assertTrue(OxigenStrings.supportedLanguages.isNotEmpty())

        cache.clear()

        OxigenStrings.supportedLanguages.forEachIndexed { index, language ->
            assertNull(language, cache[language, index])
        }
    }
}
