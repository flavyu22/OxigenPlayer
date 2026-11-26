// SPDX-License-Identifier: GPL-3.0-only
package com.oxigenplayer.app

import java.net.URI
import java.util.Locale

/** Pure update URL and metadata validation, intentionally independent of Android APIs. */
internal object UpdateSecurityPolicy {
    const val MAX_METADATA_BYTES = 64 * 1024
    const val MAX_APK_SIZE_BYTES = 350L * 1024L * 1024L

    private const val METADATA_HOST = "raw.githubusercontent.com"
    private const val DOWNLOAD_HOST = "github.com"
    private const val RELEASE_PATH_PREFIX = "/flavyu22/OxigenPlayer/releases/download/"
    private val assetHosts = setOf(
        DOWNLOAD_HOST,
        "objects.githubusercontent.com",
        "release-assets.githubusercontent.com",
        "github-releases.githubusercontent.com",
    )
    private val sha256Pattern = Regex("^[0-9a-fA-F]{64}$")
    private val releasePathPattern = Regex(
        "^${Regex.escape(RELEASE_PATH_PREFIX)}" +
            "[0-9A-Za-z][0-9A-Za-z._+-]{0,127}/" +
            "[0-9A-Za-z][0-9A-Za-z._+-]{0,191}\\.apk$",
        RegexOption.IGNORE_CASE,
    )

    fun isValidSha256(value: String): Boolean = sha256Pattern.matches(value)

    fun isAllowedMetadataUrl(rawUrl: String): Boolean =
        parseHttpsUri(rawUrl)?.let { uri ->
            uri.host.equals(METADATA_HOST, ignoreCase = true) &&
                uri.path == "/flavyu22/OxigenPlayer/main/update.json" &&
                uri.rawQuery == null
        } == true

    fun isAllowedDownloadUrl(rawUrl: String): Boolean =
        parseHttpsUri(rawUrl)?.let { uri ->
            uri.host.equals(DOWNLOAD_HOST, ignoreCase = true) &&
                // Match the raw path as one tag and one asset segment. Besides making release URLs
                // canonical, this rejects literal/encoded dot segments, slashes and backslashes
                // before GitHub or an intermediary gets a chance to normalize them.
                uri.rawPath?.let(releasePathPattern::matches) == true &&
                uri.rawQuery == null
        } == true

    fun isAllowedDownloadRedirect(rawUrl: String): Boolean =
        parseHttpsUri(rawUrl)?.host?.lowercase(Locale.US)?.let(assetHosts::contains) == true

    fun isValidMetadata(
        versionCode: Int,
        downloadUrl: String,
        sha256: String,
        sizeBytes: Long,
        packageName: String,
        expectedPackageName: String,
    ): Boolean =
        versionCode > 0 &&
            packageName == expectedPackageName &&
            isAllowedDownloadUrl(downloadUrl) &&
            isValidSha256(sha256) &&
            sizeBytes in 1..MAX_APK_SIZE_BYTES

    private fun parseHttpsUri(rawUrl: String): URI? = try {
        URI(rawUrl).takeIf { uri ->
            uri.isAbsolute &&
                uri.scheme.equals("https", ignoreCase = true) &&
                !uri.host.isNullOrBlank() &&
                uri.userInfo == null &&
                uri.fragment == null &&
                (uri.port == -1 || uri.port == 443)
        }
    } catch (_: Exception) {
        null
    }
}

/** Pure retention rules for private update-cache artifacts. */
internal object UpdateCachePolicy {
    const val INSTALLER_HANDOFF_RETENTION_MILLIS = 24L * 60L * 60L * 1_000L

    private val artifactNamePattern =
        Regex("^update_[0-9]+(?:_[0-9]+)?\\.apk(?:\\.part)?$")

    fun isManagedArtifact(fileName: String): Boolean = artifactNamePattern.matches(fileName)

    fun shouldDelete(fileName: String, lastModifiedMillis: Long, nowMillis: Long): Boolean {
        if (!isManagedArtifact(fileName)) return false
        if (fileName.endsWith(".part")) return true
        if (lastModifiedMillis <= 0L) return true
        if (lastModifiedMillis > nowMillis) return false
        return nowMillis - lastModifiedMillis >= INSTALLER_HANDOFF_RETENTION_MILLIS
    }
}


/** APK files must start with the ZIP local-file header PK\x03\x04. */
internal fun hasApkZipLocalFileHeader(signature: ByteArray): Boolean =
    signature.size >= 4 &&
        signature[0] == 0x50.toByte() &&
        signature[1] == 0x4B.toByte() &&
        signature[2] == 0x03.toByte() &&
        signature[3] == 0x04.toByte()
