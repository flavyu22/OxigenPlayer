// SPDX-License-Identifier: GPL-3.0-only
package com.oxigenplayer.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateSecurityPolicyTest {
    @Test
    fun acceptsExpectedReleaseUrlAndMetadata() {
        val url =
            "https://github.com/flavyu22/OxigenPlayer/releases/download/v2.0/OxigenPlayer-v2.0.apk"

        assertTrue(UpdateSecurityPolicy.isAllowedDownloadUrl(url))
        assertTrue(
            UpdateSecurityPolicy.isValidMetadata(
                versionCode = 4,
                downloadUrl = url,
                sha256 = "a".repeat(64),
                sizeBytes = 25L * 1024L * 1024L,
                packageName = "com.oxigenplayer.app",
                expectedPackageName = "com.oxigenplayer.app"
            )
        )
    }

    @Test
    fun rejectsInsecureOrLookalikeReleaseUrls() {
        assertFalse(
            UpdateSecurityPolicy.isAllowedDownloadUrl(
                "http://github.com/flavyu22/OxigenPlayer/releases/download/v2/app.apk"
            )
        )
        assertFalse(
            UpdateSecurityPolicy.isAllowedDownloadUrl(
                "https://github.com.evil.example/flavyu22/OxigenPlayer/releases/download/v2/app.apk"
            )
        )
        assertFalse(
            UpdateSecurityPolicy.isAllowedDownloadUrl(
                "https://github.com/other/repository/releases/download/v2/app.apk"
            )
        )
        assertFalse(
            UpdateSecurityPolicy.isAllowedDownloadUrl(
                "https://user@github.com/flavyu22/OxigenPlayer/releases/download/v2/app.apk"
            )
        )
    }

    @Test
    fun validatesOnlyCanonicalSha256Values() {
        assertTrue(UpdateSecurityPolicy.isValidSha256("0123456789abcdef".repeat(4)))
        assertTrue(UpdateSecurityPolicy.isValidSha256("ABCDEF0123456789".repeat(4)))
        assertFalse(UpdateSecurityPolicy.isValidSha256("abc"))
        assertFalse(UpdateSecurityPolicy.isValidSha256("g".repeat(64)))
        assertFalse(UpdateSecurityPolicy.isValidSha256("a".repeat(65)))
    }

    @Test
    fun rejectsWrongPackageAndOversizedArtifact() {
        val url =
            "https://github.com/flavyu22/OxigenPlayer/releases/download/v2.0/OxigenPlayer-v2.0.apk"

        assertFalse(
            UpdateSecurityPolicy.isValidMetadata(
                versionCode = 4,
                downloadUrl = url,
                sha256 = "a".repeat(64),
                sizeBytes = 25L * 1024L * 1024L,
                packageName = "com.attacker.app",
                expectedPackageName = "com.oxigenplayer.app"
            )
        )
        assertFalse(
            UpdateSecurityPolicy.isValidMetadata(
                versionCode = 4,
                downloadUrl = url,
                sha256 = "a".repeat(64),
                sizeBytes = UpdateSecurityPolicy.MAX_APK_SIZE_BYTES + 1,
                packageName = "com.oxigenplayer.app",
                expectedPackageName = "com.oxigenplayer.app"
            )
        )
    }

    @Test
    fun restrictsMetadataAndRedirectHosts() {
        assertTrue(
            UpdateSecurityPolicy.isAllowedMetadataUrl(
                "https://raw.githubusercontent.com/flavyu22/OxigenPlayer/main/update.json"
            )
        )
        assertFalse(
            UpdateSecurityPolicy.isAllowedMetadataUrl(
                "https://raw.githubusercontent.com/attacker/OxigenPlayer/main/update.json"
            )
        )
        assertTrue(
            UpdateSecurityPolicy.isAllowedDownloadRedirect(
                "https://release-assets.githubusercontent.com/path/to/asset?token=opaque"
            )
        )
        assertFalse(
            UpdateSecurityPolicy.isAllowedDownloadRedirect(
                "https://release-assets.githubusercontent.com.evil.example/path/to/asset"
            )
        )
        assertFalse(
            UpdateSecurityPolicy.isAllowedDownloadRedirect(
                "https://user@release-assets.githubusercontent.com/path/to/asset"
            )
        )
        assertFalse(
            UpdateSecurityPolicy.isAllowedDownloadRedirect(
                "https://release-assets.githubusercontent.com:444/path/to/asset"
            )
        )
        assertFalse(
            UpdateSecurityPolicy.isAllowedDownloadRedirect(
                "http://release-assets.githubusercontent.com/path/to/asset"
            )
        )
    }

    @Test
    fun apkArchiveHeader_acceptsOnlyLocalFileSignature() {
        assertTrue(hasApkZipLocalFileHeader(byteArrayOf(0x50, 0x4B, 0x03, 0x04)))
        assertFalse(hasApkZipLocalFileHeader(byteArrayOf(0x50, 0x4B, 0x05, 0x06)))
        assertFalse(hasApkZipLocalFileHeader(byteArrayOf(0x50, 0x4B, 0x07, 0x08)))
        assertFalse(hasApkZipLocalFileHeader(byteArrayOf(0x50, 0x4B, 0x03)))
    }
}
