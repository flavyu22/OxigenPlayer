// SPDX-License-Identifier: GPL-3.0-only
package com.oxigenplayer.app

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleSafetyTest {
    @Test
    fun translationKeysDoNotUseHashCodeAsIdentity() {
        // This well-known Java String collision reproduced the previous wrong-translation bug.
        assertEquals("Aa".hashCode(), "BB".hashCode())

        assertNotEquals(
            SubtitleTranslationKey("ro", "Aa"),
            SubtitleTranslationKey("ro", "BB")
        )
        assertNotEquals(
            TranslationRequestCacheKey("en", "ro", TranslationSource.MLKIT, "Aa"),
            TranslationRequestCacheKey("en", "ro", TranslationSource.MLKIT, "BB")
        )
        assertNotEquals(
            TranslationRequestCacheKey("en", "ro", TranslationSource.MLKIT, "same"),
            TranslationRequestCacheKey("en", "ro", TranslationSource.MY_MEMORY, "same")
        )
    }

    @Test
    fun workIdentitySeparatesDocumentsLanguagesAndCueText() {
        val original = SubtitleWorkKey(1, 1, 3, "ro", "Text")
        assertNotEquals(original, original.copy(generation = 2))
        assertNotEquals(original, original.copy(presentationEpoch = 2))
        assertNotEquals(original, original.copy(targetLanguage = "de"))
        assertNotEquals(original, original.copy(text = "Alt text"))
    }

    @Test
    fun embeddedCueCannotPreemptAnActiveExternalDocument() {
        val externalMode = SubtitleConfig(useExternal = true)
        assertFalse(shouldProcessEmbeddedCue(externalMode, externalSubtitleCount = 20))
        assertTrue(shouldProcessEmbeddedCue(externalMode, externalSubtitleCount = 0))
        assertTrue(
            shouldProcessEmbeddedCue(
                externalMode.copy(useExternal = false),
                externalSubtitleCount = 20
            )
        )
    }

    @Test
    fun languageDetectionWaitsForConsensusThenLocksDominantLanguage() {
        assertFalse(
            decideDetectedLanguage(
                votes = mapOf("en" to 2, "fr" to 1),
                attempts = 3
            ).shouldLock
        )

        val consensus = decideDetectedLanguage(
            votes = mapOf("en" to 3, "fr" to 1),
            attempts = 4
        )
        assertTrue(consensus.shouldLock)
        assertEquals("en", consensus.language)
    }

    @Test
    fun languageDetectionLocksBestVoteWhenSampleBudgetIsExhausted() {
        val decision = decideDetectedLanguage(
            votes = mapOf("fr" to 3, "en" to 2, "de" to 2),
            attempts = 8
        )
        assertTrue(decision.shouldLock)
        assertEquals("fr", decision.language)
    }

    @Test
    fun autoIsExplicitlyClassifiedAsCloudCapable() {
        assertTrue(TranslationSource.AUTO.mayUseCloud)
        assertFalse(TranslationSource.MLKIT.mayUseCloud)
        assertFalse(TranslationSource.GEMINI_NANO.mayUseCloud)
    }

    @Test
    fun geminiNanoSelectionFailsClosedWhenTheDeviceCannotProvideIt() {
        val config = TranslationProviderConfig()
        assertEquals(
            TranslationSource.AUTO,
            ProviderAvailability.safeTranslationSource(
                source = TranslationSource.GEMINI_NANO,
                config = config,
                mlKitAllowed = true,
                geminiNanoAvailable = false,
            ),
        )
        assertEquals(
            TranslationSource.GEMINI_NANO,
            ProviderAvailability.safeTranslationSource(
                source = TranslationSource.GEMINI_NANO,
                config = config,
                mlKitAllowed = true,
                geminiNanoAvailable = true,
            ),
        )
    }

    @Test
    fun trustedUrlRequiresHttpsDefaultPortAndRealDomainBoundary() {
        val domains = setOf("subs.ro")
        assertTrue(isTrustedHttpsUrl("https://api.subs.ro/file/1", domains))
        assertTrue(isTrustedHttpsUrl("https://subs.ro/file/1", domains))

        assertFalse(isTrustedHttpsUrl("http://api.subs.ro/file/1", domains))
        assertFalse(isTrustedHttpsUrl("https://api.subs.ro:8443/file/1", domains))
        assertFalse(isTrustedHttpsUrl("https://api.subs.ro.evil.example/file/1", domains))
        assertFalse(isTrustedHttpsUrl("https://subs.ro@evil.example/file/1", domains))
    }

    @Test
    fun publicSubtitleDownloadPolicyRejectsCleartextAndPrivateNumericTargets() {
        assertTrue(isPublicDownloadUrl("https://downloads.opensubtitles.com/file.srt"))
        assertFalse(isPublicDownloadUrl("http://downloads.opensubtitles.com/file.srt"))
        assertFalse(isPublicDownloadUrl("https://127.0.0.1/file.srt"))
        assertFalse(isPublicDownloadUrl("https://192.168.1.10/file.srt"))
        assertFalse(isPublicDownloadUrl("https://user:pass@downloads.opensubtitles.com/file.srt"))
    }

    @Test
    fun redirectDomainPolicyCannotEscapeProviderBoundary() {
        val domains = setOf("opensubtitles.com", "opensubtitles.org")
        assertTrue(isTrustedHttpsUrl("https://dl.opensubtitles.com/subtitle.srt", domains))
        assertFalse(isTrustedHttpsUrl("https://opensubtitles.com.evil.example/subtitle.srt", domains))
        assertFalse(isTrustedHttpsUrl("https://cdn.example/subtitle.srt", domains))
    }

    @Test(expected = SubtitleDownloadException::class)
    fun boundedReaderRejectsOversizedUnknownLengthStream() {
        SubtitlePayloadCodec.readLimited(
            ByteArrayInputStream(ByteArray(1025)),
            maximumBytes = 1024,
            label = "test"
        )
    }

    @Test(expected = IOException::class)
    fun translationResponseReaderRejectsOversizedUnknownLengthStream() {
        TranslationResponseCodec.readUtf8(
            ByteArrayInputStream(ByteArray(1025)),
            maximumBytes = 1024
        )
    }

    @Test
    fun zipReaderExtractsAValidSrtWithoutWritingArchiveEntries() {
        val expected = "1\n00:00:01,000 --> 00:00:02,000\nSalut\n"
        val archive = zipOf("nested/movie.srt", expected.toByteArray())

        assertEquals(expected, SubtitlePayloadCodec.extractSrtFromZip(archive))
    }

    @Test(expected = SubtitleDownloadException::class)
    fun zipReaderRejectsAnOversizedExpandedEntry() {
        val archive = zipOf("bomb.srt", ByteArray(64 * 1024) { 'A'.code.toByte() })
        val limits = SubtitlePayloadLimits(
            maxDownloadBytes = 128 * 1024,
            maxSubtitleBytes = 8 * 1024,
            maxArchiveEntries = 8,
            maxArchiveExpandedBytes = 16 * 1024,
            maxCompressionRatio = 20
        )

        SubtitlePayloadCodec.extractSrtFromZip(archive, limits)
    }

    @Test(expected = SubtitleDownloadException::class)
    fun gzipReaderRejectsExcessiveExpansion() {
        val compressed = ByteArrayOutputStream().also { output ->
            GZIPOutputStream(output).use { gzip ->
                gzip.write(ByteArray(64 * 1024) { 'A'.code.toByte() })
            }
        }.toByteArray()
        val limits = SubtitlePayloadLimits(
            maxDownloadBytes = 128 * 1024,
            maxSubtitleBytes = 8 * 1024,
            maxArchiveEntries = 8,
            maxArchiveExpandedBytes = 16 * 1024,
            maxCompressionRatio = 20
        )

        SubtitlePayloadCodec.decodeSubtitle(compressed, limits)
    }

    private fun zipOf(name: String, content: ByteArray): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry(name))
            zip.write(content)
            zip.closeEntry()
        }
        return output.toByteArray()
    }
}
