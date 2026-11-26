// SPDX-License-Identifier: GPL-3.0-only
package com.oxigenplayer.app

import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI
import java.util.Locale

/** Runtime configuration for optional open translation services. */
data class TranslationProviderConfig(
    val libreTranslateBaseUrl: String = "",
    val mozhiBaseUrl: String = DEFAULT_MOZHI_BASE_URL,
    val mozhiEngine: String = DEFAULT_MOZHI_ENGINE,
    val libreTranslateApiKey: String = "",
    val deepLAuthKey: String = "",
    val ollamaBaseUrl: String = "",
    val ollamaModel: String = "",
    val ollamaBearerToken: String = "",
) {
    fun normalized(): TranslationProviderConfig = copy(
        libreTranslateBaseUrl = normalizeTranslationBaseUrl(libreTranslateBaseUrl).orEmpty(),
        mozhiBaseUrl = normalizeTranslationBaseUrl(
            mozhiBaseUrl,
            fallback = DEFAULT_MOZHI_BASE_URL,
        ) ?: DEFAULT_MOZHI_BASE_URL,
        mozhiEngine = normalizeMozhiEngine(mozhiEngine),
        libreTranslateApiKey = libreTranslateApiKey.trim().take(MAX_SECRET_LENGTH),
        deepLAuthKey = deepLAuthKey.trim().take(MAX_SECRET_LENGTH),
        ollamaBaseUrl = normalizeTranslationBaseUrl(ollamaBaseUrl).orEmpty(),
        ollamaModel = ollamaModel.trim().take(MAX_MODEL_NAME_LENGTH),
        ollamaBearerToken = ollamaBearerToken.trim().take(MAX_SECRET_LENGTH),
    )

    val isLibreTranslateConfigured: Boolean
        get() = libreTranslateBaseUrl.isNotBlank() &&
            (libreTranslateApiKey.isNotBlank() || !isOfficialLibreTranslateHost(libreTranslateBaseUrl))

    val isMozhiConfigured: Boolean
        get() = mozhiBaseUrl.isNotBlank() && mozhiEngine in MOZHI_ENGINES

    val isOllamaConfigured: Boolean
        get() = ollamaBaseUrl.isNotBlank() && ollamaModel.isNotBlank()

    companion object {
        const val DEFAULT_MOZHI_BASE_URL = "https://mozhi.aryak.me"

        /** Public Mozhi instances verified working; tried in order after the default fails. */
        val PUBLIC_MOZHI_BASE_URLS = listOf(
            DEFAULT_MOZHI_BASE_URL,
            "https://mozhi.ducks.party",
            "https://mozhi.adminforge.de",
            "https://mozhi.pussthecat.org",
        )
        const val DEFAULT_MOZHI_ENGINE = "google"
        val MOZHI_ENGINES = setOf("google", "duckduckgo", "mymemory", "libretranslate", "reverso", "yandex", "deepl")
        private const val MAX_MODEL_NAME_LENGTH = 160
        private const val MAX_SECRET_LENGTH = 512
    }
}

/**
 * Mozhi instances to try for [configuredBaseUrl]: a custom or self-hosted server is used alone,
 * while the default public instance falls back to the other public ones.
 */
internal fun mozhiInstanceOrder(configuredBaseUrl: String): List<String> {
    val normalized = normalizeTranslationBaseUrl(configuredBaseUrl) ?: return emptyList()
    val publicInstances = TranslationProviderConfig.PUBLIC_MOZHI_BASE_URLS
    return if (normalized in publicInstances) {
        listOf(normalized) + publicInstances.filterNot { it == normalized }
    } else {
        listOf(normalized)
    }
}

internal fun normalizeMozhiEngine(value: String): String {
    val normalized = value.trim().lowercase(Locale.ROOT)
    return normalized.takeIf { it in TranslationProviderConfig.MOZHI_ENGINES }
        ?: TranslationProviderConfig.DEFAULT_MOZHI_ENGINE
}

internal enum class TranslationEndpointScope { PUBLIC, LOCAL }

/**
 * Allows public HTTPS endpoints and cleartext only for explicit loopback/private/LAN targets.
 * User-info, query strings and fragments are rejected so credentials cannot be hidden in a URL.
 */
internal fun normalizeTranslationBaseUrl(raw: String, fallback: String? = null): String? {
    val candidate = raw.trim().ifEmpty { fallback?.trim().orEmpty() }
    if (candidate.isEmpty() || candidate.length > 2_048) return null

    val uri = runCatching { URI(candidate) }.getOrNull() ?: return null
    val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return null
    val host = uri.host?.lowercase(Locale.ROOT)?.trimEnd('.') ?: return null
    if (uri.userInfo != null || uri.rawQuery != null || uri.rawFragment != null) return null
    if (host.isBlank() || uri.port !in -1..65_535) return null

    val local = isExplicitLocalHost(host)
    if (scheme != "https" && !(scheme == "http" && local)) return null

    val normalizedPath = uri.rawPath.orEmpty()
        .replace(Regex("/{2,}"), "/")
        .trimEnd('/')
    return runCatching {
        URI(scheme, null, host, uri.port, normalizedPath.ifEmpty { null }, null, null).toASCIIString()
    }.getOrNull()
}

internal fun translationEndpointScope(baseUrl: String): TranslationEndpointScope? {
    val normalized = normalizeTranslationBaseUrl(baseUrl) ?: return null
    val host = URI(normalized).host ?: return null
    return if (isExplicitLocalHost(host)) TranslationEndpointScope.LOCAL else TranslationEndpointScope.PUBLIC
}

internal fun translationEndpoint(baseUrl: String, endpointPath: String): String? {
    val normalized = normalizeTranslationBaseUrl(baseUrl) ?: return null
    val suffix = endpointPath.trim().let { if (it.startsWith('/')) it else "/$it" }
    return "$normalized$suffix"
}

internal fun isOfficialLibreTranslateHost(baseUrl: String): Boolean {
    val host = runCatching { URI(normalizeTranslationBaseUrl(baseUrl) ?: return false).host }.getOrNull()
        ?.lowercase(Locale.ROOT)
        ?: return false
    return host == "libretranslate.com" || host.endsWith(".libretranslate.com")
}

/** Returns true only for loopback, link-local, private IPv4 or IPv6 unique-local addresses. */
internal fun isLocalNetworkAddress(address: InetAddress): Boolean {
    if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
        address.isSiteLocalAddress
    ) return true

    if (address is Inet6Address) {
        val firstByte = address.address.firstOrNull()?.toInt()?.and(0xff) ?: return false
        if (firstByte and 0xfe == 0xfc) return true // Unique-local fc00::/7.
    }
    return false
}

internal fun isExplicitLocalHost(hostValue: String): Boolean {
    val host = hostValue.lowercase(Locale.ROOT).trim().trim('[', ']').trimEnd('.')
    if (host == "localhost" || host.endsWith(".localhost") || host.endsWith(".local")) return true

    val looksNumeric = host.all { it.isDigit() || it == '.' } || ':' in host
    if (!looksNumeric) return false
    val address = runCatching { InetAddress.getByName(host) }.getOrNull() ?: return false
    return isLocalNetworkAddress(address)
}

/** Splits without breaking UTF-8 code points; concatenating the chunks recreates the input. */
internal fun splitUtf8(text: String, maximumBytes: Int): List<String> {
    require(maximumBytes > 0)
    if (text.toByteArray(Charsets.UTF_8).size <= maximumBytes) return listOf(text)

    val chunks = mutableListOf<String>()
    val current = StringBuilder()
    var currentBytes = 0
    var index = 0
    while (index < text.length) {
        val codePoint = text.codePointAt(index)
        val part = String(Character.toChars(codePoint))
        val partBytes = part.toByteArray(Charsets.UTF_8).size
        require(partBytes <= maximumBytes) { "maximumBytes is smaller than one UTF-8 code point" }
        if (currentBytes + partBytes > maximumBytes && current.isNotEmpty()) {
            chunks += current.toString()
            current.setLength(0)
            currentBytes = 0
        }
        current.append(part)
        currentBytes += partBytes
        index += Character.charCount(codePoint)
    }
    if (current.isNotEmpty()) chunks += current.toString()
    return chunks
}
