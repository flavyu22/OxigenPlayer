// SPDX-License-Identifier: GPL-3.0-only
package com.oxigenplayer.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleProviderConfigTest {
    @Test
    fun providerKeysAreTrimmedAndLengthLimited() {
        val normalized = SubtitleProviderConfig(
            openSubtitlesApiKey = "  open-key  ",
            subsRoApiKey = "s".repeat(700),
            subDlApiKey = "  subdl  ",
            tmdbApiKey = "  tmdb  ",
        ).normalized()

        assertEquals("open-key", normalized.openSubtitlesApiKey)
        assertEquals(512, normalized.subsRoApiKey.length)
        assertEquals("subdl", normalized.subDlApiKey)
        assertEquals("tmdb", normalized.tmdbApiKey)
    }

    @Test
    fun userDeepLKeyEnablesProviderWithoutBuildConfigSecret() {
        val config = TranslationProviderConfig(deepLAuthKey = " user-key ").normalized()
        assertEquals("user-key", config.deepLAuthKey)
        assertTrue(ProviderAvailability.supports(TranslationSource.DEEPL, config))
    }

    @Test
    fun mozhiProviderHasAFreeDefaultInstance() {
        val config = TranslationProviderConfig().normalized()
        assertTrue(config.isMozhiConfigured)
        assertTrue(ProviderAvailability.supports(TranslationSource.MOZHI, config))
        assertEquals(
            TranslationSource.MOZHI,
            ProviderAvailability.safeTranslationSource(TranslationSource.MOZHI, config),
        )
    }

    @Test
    fun autoProviderRemainsAvailableWithoutSecrets() {
        assertTrue(ProviderAvailability.supports(TranslationSource.AUTO, TranslationProviderConfig()))
    }
}
