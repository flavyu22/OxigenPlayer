// SPDX-License-Identifier: GPL-3.0-only
package com.oxigenplayer.app

import java.net.InetAddress
import java.net.URI
import java.util.Locale

private val ALLOWED_APPLICATION_MEDIA_TYPES = setOf(
    "application/x-mpegurl",
    "application/vnd.apple.mpegurl",
    "application/dash+xml",
)

private val REMOTE_PLAYBACK_SCHEMES = setOf("https", "rtsp", "rtsps")

internal fun isSupportedPlaybackMimeType(value: String?): Boolean {
    val mimeType = value
        ?.substringBefore(';')
        ?.trim()
        ?.lowercase(Locale.ROOT)
        ?.takeIf(String::isNotEmpty)
        ?: return false
    return mimeType.startsWith("video/") ||
        mimeType.startsWith("audio/") ||
        mimeType in ALLOWED_APPLICATION_MEDIA_TYPES
}

/**
 * Validates an address that the user explicitly chose to play.
 *
 * Playback is intentionally less restrictive than API/update traffic: local NAS hosts, `.local`
 * names, custom ports and RTSP/RTSPS are valid media sources. Cleartext HTTP is accepted only in
 * the explicit Direct/LAN build. Credentials embedded in the URL are rejected because Android
 * logs, histories and crash reports may expose them.
 */
internal fun isSupportedRemotePlaybackUri(
    rawUri: String,
    allowCleartextHttp: Boolean = BuildConfig.CLEARTEXT_LAN_ENABLED,
): Boolean {
    val uri = runCatching { URI(rawUri.trim()) }.getOrNull() ?: return false
    val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return false
    if (scheme !in REMOTE_PLAYBACK_SCHEMES && !(scheme == "http" && allowCleartextHttp)) return false
    if (uri.rawUserInfo != null || uri.rawFragment != null) return false
    if (uri.port !in -1..65535 || uri.port == 0) return false

    val rawHost = uri.host ?: return false
    if (rawHost.endsWith("..")) return false
    val host = rawHost.removePrefix("[").removeSuffix("]").removeSuffix(".")
    if (host.isBlank() || host.any(Char::isWhitespace)) return false

    // URI already validates bracketed IPv6. For DNS/IPv4 names, reject malformed labels but allow
    // single-label and mDNS names because they are common on home networks.
    if (':' !in host) {
        val labels = host.split('.')
        if (labels.any { label ->
                label.isEmpty() || label.length > 63 ||
                    !label.first().isLetterOrDigit() || !label.last().isLetterOrDigit() ||
                    label.any { !it.isLetterOrDigit() && it != '-' }
            }
        ) return false
    }
    if (scheme == "http" && !isExplicitLanHost(host)) return false
    return true
}

/** Cleartext media is limited to explicit LAN targets; public HTTP is never accepted. */
internal fun isExplicitLanHost(hostValue: String): Boolean {
    val host = hostValue.removePrefix("[").removeSuffix("]").removeSuffix(".").lowercase(Locale.ROOT)
    if (host == "localhost" || host.endsWith(".localhost") || host.endsWith(".local")) return true
    if ('.' !in host && ':' !in host && host.matches(Regex("^[a-z0-9-]{1,63}$"))) return true
    val address = parseStrictNumericAddress(host) ?: return false
    return isLocalNetworkAddress(address)
}

internal fun parseStrictNumericAddress(host: String): InetAddress? {
    if (':' in host) return runCatching { InetAddress.getByName(host) }.getOrNull()
    val parts = host.split('.')
    if (parts.size != 4 || parts.any { it.isEmpty() || (it.length > 1 && it.startsWith('0')) }) return null
    val bytes = parts.map { part ->
        part.toIntOrNull()?.takeIf { it in 0..255 } ?: return null
    }.map(Int::toByte).toByteArray()
    return InetAddress.getByAddress(bytes)
}

/**
 * True when [canonicalPath] is one of [roots] or lies below one of them. Compared per path
 * segment, so `/data/data/app` does not contain `/data/data/app2`.
 */
internal fun isPathInsideAny(canonicalPath: String, roots: List<String>): Boolean =
    roots.asSequence()
        .map { it.trimEnd('/') }
        .filter(String::isNotEmpty)
        .any { root -> canonicalPath == root || canonicalPath.startsWith("$root/") }
