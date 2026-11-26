// SPDX-License-Identifier: GPL-3.0-only
package com.oxigenplayer.app

import java.net.InetAddress

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslationProviderConfigTest {
    @Test
    fun endpointPolicyAllowsPublicHttpsAndLocalHttpOnly() {
        assertEquals("https://example.com/base", normalizeTranslationBaseUrl("https://example.com/base/"))
        assertEquals("http://127.0.0.1:11434", normalizeTranslationBaseUrl("http://127.0.0.1:11434"))
        assertEquals("http://server.local:5000", normalizeTranslationBaseUrl("http://server.local:5000/"))
        assertNull(normalizeTranslationBaseUrl("http://example.com"))
        assertNull(normalizeTranslationBaseUrl("https://user:pass@example.com"))
        assertNull(normalizeTranslationBaseUrl("https://example.com?token=secret"))
    }

    @Test
    fun localAddressPolicyRejectsPublicDnsAnswers() {
        assertTrue(isLocalNetworkAddress(InetAddress.getByName("127.0.0.1")))
        assertTrue(isLocalNetworkAddress(InetAddress.getByName("192.168.1.25")))
        assertTrue(isLocalNetworkAddress(InetAddress.getByName("fd00::1")))
        assertFalse(isLocalNetworkAddress(InetAddress.getByName("8.8.8.8")))
        assertFalse(isLocalNetworkAddress(InetAddress.getByName("2001:4860:4860::8888")))
    }

    @Test
    fun libreTranslateOfficialHostRequiresAKeyButSelfHostedDoesNot() {
        assertFalse(
            TranslationProviderConfig(
                libreTranslateBaseUrl = "https://libretranslate.com",
            ).normalized().isLibreTranslateConfigured
        )
        assertTrue(
            TranslationProviderConfig(
                libreTranslateBaseUrl = "https://libretranslate.com",
                libreTranslateApiKey = "key",
            ).normalized().isLibreTranslateConfigured
        )
        assertTrue(
            TranslationProviderConfig(
                libreTranslateBaseUrl = "http://192.168.1.20:5000",
            ).normalized().isLibreTranslateConfigured
        )
    }

    @Test
    fun utf8SplittingPreservesTextAndByteLimit() {
        val text = "Salut 👋 Привет lume!".repeat(80)
        val chunks = splitUtf8(text, 97)
        assertEquals(text, chunks.joinToString(separator = ""))
        assertTrue(chunks.all { it.toByteArray(Charsets.UTF_8).size <= 97 })
    }

    @Test
    fun mozhiDefaultInstanceFallsBackToOtherPublicInstances() {
        val order = mozhiInstanceOrder(TranslationProviderConfig.DEFAULT_MOZHI_BASE_URL)
        assertEquals(TranslationProviderConfig.DEFAULT_MOZHI_BASE_URL, order.first())
        assertEquals(TranslationProviderConfig.PUBLIC_MOZHI_BASE_URLS.toSet(), order.toSet())
        assertEquals(listOf("https://mozhi.example.org"), mozhiInstanceOrder("https://mozhi.example.org/"))
    }
    @Test
    fun mozhiConfigNormalizesSupportedEnginesAndFallsBackSafely() {
        val configured = TranslationProviderConfig(
            mozhiBaseUrl = "https://translate.example.org/",
            mozhiEngine = " DuckDuckGo ",
        ).normalized()
        assertEquals("https://translate.example.org", configured.mozhiBaseUrl)
        assertEquals("duckduckgo", configured.mozhiEngine)
        assertTrue(configured.isMozhiConfigured)

        val fallback = TranslationProviderConfig(mozhiEngine = "unknown-engine").normalized()
        assertEquals(TranslationProviderConfig.DEFAULT_MOZHI_ENGINE, fallback.mozhiEngine)
    }

}
