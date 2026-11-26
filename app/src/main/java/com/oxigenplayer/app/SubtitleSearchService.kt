// SPDX-License-Identifier: GPL-3.0-only
package com.oxigenplayer.app

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl
import okhttp3.Request
import okhttp3.ResponseBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONException
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.IOException
import java.net.URI
import java.net.SocketTimeoutException
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern
import java.util.zip.GZIPInputStream
import java.util.zip.ZipInputStream

// Precompiled once: these run per search result / per language entry.
private val RELEASE_TAG_REGEX = Regex("""\b(1080p|720p|480p|2160p|4k|bluray|web-dl|webrip|x264|x265|hevc|hdrip|mkv|mp4|avi|aac|dts|dd5|ac3|truehd)\b""", RegexOption.IGNORE_CASE)
private val YEAR_REGEX = Regex("""\b(19|20)\d{2}\b""")
private val COMBINING_MARKS_REGEX = Regex("""\p{M}+""")
private val NON_WORD_REGEX = Regex("""[^\p{L}\p{N}]+""")
private val WHITESPACE_REGEX = Regex("""\s+""")
private val IMDB_ID_IN_TEXT_REGEX = Regex("""\btt\d{7,10}\b""")
private val IMDB_ID_REGEX = Regex("""tt\d{7,10}""")
private val NUMERIC_ID_REGEX = Regex("""\d{1,12}""")
private val LANGUAGE_CODE_REGEX = Regex("[a-z]{2,3}")
private val HTML_TAG_REGEX = Regex("<[^>]*>")

data class SubtitleSearchResult(
    val id: String,
    val fileName: String,
    val language: String,
    val downloadUrl: String,
    val movieName: String,
    val year: String = "",
    val source: String = "OpenSubtitles",
    val matchedByImdb: Boolean = false
)

enum class SubtitleProviderFailureKind {
    NOT_CONFIGURED,
    AUTHENTICATION,
    RATE_LIMITED,
    TIMEOUT,
    NETWORK,
    HTTP,
    INVALID_RESPONSE,
    UNKNOWN,
}

data class SubtitleProviderFailure(
    val provider: String,
    val kind: SubtitleProviderFailureKind,
    val detail: String? = null,
)

data class SearchResult(
    val subtitles: List<SubtitleSearchResult>,
    val errorMessage: String?,
    val failures: List<SubtitleProviderFailure> = emptyList(),
)

class SubtitleDownloadException(message: String, val isUnauthorized: Boolean = false) : Exception(message)

internal data class SubtitlePayloadLimits(
    val maxDownloadBytes: Int = 8 * 1024 * 1024,
    val maxSubtitleBytes: Int = 4 * 1024 * 1024,
    val maxArchiveEntries: Int = 128,
    val maxArchiveExpandedBytes: Int = 12 * 1024 * 1024,
    val maxCompressionRatio: Int = 100
)

/** Pure, bounded payload utilities kept internal so hostile archive cases can be unit-tested. */
internal object SubtitlePayloadCodec {
    fun readLimited(input: InputStream, maximumBytes: Int, label: String): ByteArray {
        require(maximumBytes > 0)
        val output = ByteArrayOutputStream(minOf(maximumBytes, 16 * 1024))
        val buffer = ByteArray(8 * 1024)
        var total = 0
        var consecutiveZeroReads = 0
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (count == 0) {
                consecutiveZeroReads++
                if (consecutiveZeroReads >= 8) {
                    throw SubtitleDownloadException("$label nu a mai furnizat date")
                }
                continue
            }
            consecutiveZeroReads = 0
            total += count
            if (total > maximumBytes) {
                throw SubtitleDownloadException("$label exceeds the safety limit")
            }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    fun decodeSubtitle(
        bytes: ByteArray,
        limits: SubtitlePayloadLimits = SubtitlePayloadLimits(),
        nestingDepth: Int = 0
    ): String? {
        if (bytes.isEmpty()) return ""
        if (bytes.size > limits.maxSubtitleBytes) {
            throw SubtitleDownloadException("Subtitrarea este prea mare")
        }
        if (nestingDepth > 1) throw SubtitleDownloadException("Nested compressed archive")

        if (bytes.size >= 2 && bytes[0] == 0x1F.toByte() && bytes[1] == 0x8B.toByte()) {
            val expanded = GZIPInputStream(ByteArrayInputStream(bytes)).use {
                readLimited(it, limits.maxSubtitleBytes, "GZIP content")
            }
            enforceRatio(bytes.size, expanded.size, limits)
            return decodeSubtitle(expanded, limits, nestingDepth + 1)
        }

        val decoded = SubtitleParser.decodeText(bytes) ?: return null
        return decoded.takeIf { SubtitleParser.detectFormat(it) != null }
    }

    fun extractSrtFromZip(
        archive: ByteArray,
        limits: SubtitlePayloadLimits = SubtitlePayloadLimits()
    ): String? {
        if (archive.size < 4 || archive[0] != 'P'.code.toByte() || archive[1] != 'K'.code.toByte()) {
            return null
        }
        if (archive.size > limits.maxDownloadBytes) {
            throw SubtitleDownloadException("Archive exceeds the size limit")
        }

        var entryCount = 0
        var totalExpanded = 0L
        val fallbacks = ArrayList<ByteArray>(3)

        ZipInputStream(ByteArrayInputStream(archive)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                entryCount++
                if (entryCount > limits.maxArchiveEntries) {
                    throw SubtitleDownloadException("Archive contains too many files")
                }
                if (!entry.isDirectory) {
                    if (entry.size > limits.maxSubtitleBytes) {
                        throw SubtitleDownloadException("Archive entry exceeds the size limit")
                    }
                    val content = readLimited(zip, limits.maxSubtitleBytes, "Archive entry")
                    totalExpanded += content.size
                    if (totalExpanded > limits.maxArchiveExpandedBytes) {
                        throw SubtitleDownloadException("Expanded archive exceeds the size limit")
                    }
                    enforceRatio(archive.size, totalExpanded.toInt(), limits)

                    val normalizedName = entry.name.replace('\\', '/').substringAfterLast('/')
                    when {
                        ARCHIVE_SUBTITLE_EXTENSIONS.any { normalizedName.endsWith(it, ignoreCase = true) } -> {
                            decodeSubtitle(content, limits)?.let { return it }
                        }
                        fallbacks.size < 3 && (
                            normalizedName.endsWith(".txt", ignoreCase = true) ||
                                !normalizedName.contains('.')
                            ) -> fallbacks += content
                    }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }

        return fallbacks.firstNotNullOfOrNull { decodeSubtitle(it, limits) }
    }

    /** Every format [SubtitleParser] reads; the order does not matter, the first match wins. */
    private val ARCHIVE_SUBTITLE_EXTENSIONS =
        listOf(".srt", ".vtt", ".ass", ".ssa", ".smi", ".sami", ".ttml", ".dfxp")

    private fun enforceRatio(
        compressedBytes: Int,
        expandedBytes: Int,
        limits: SubtitlePayloadLimits
    ) {
        val permitted = maxOf(
            64L * 1024L,
            compressedBytes.toLong() * limits.maxCompressionRatio
        )
        if (expandedBytes.toLong() > permitted) {
            throw SubtitleDownloadException("Unsafe compression ratio")
        }
    }
}

internal fun isPublicDownloadUrl(url: String): Boolean {
    val parsed = url.toHttpUrlOrNull() ?: return false
    if (!parsed.isHttps || parsed.username.isNotEmpty() || parsed.password.isNotEmpty()) return false
    if (parsed.fragment != null || parsed.port != 443) return false

    // Hostnames are resolved by NetworkClient's PublicOnlyDns at connection time. This helper
    // rejects numeric private/reserved destinations without creating a separate DNS lookup that
    // could be raced through DNS rebinding.
    return isAllowedPublicHttpsUrl(parsed)
}

internal fun isTrustedHttpsUrl(url: String, trustedDomains: Set<String>): Boolean {
    val uri = try {
        URI(url).takeIf { it.isAbsolute }
    } catch (_: Exception) {
        null
    } ?: return false

    if (!uri.scheme.equals("https", ignoreCase = true)) return false
    if (uri.userInfo != null) return false
    if (uri.fragment != null) return false
    if (uri.port != -1 && uri.port != 443) return false

    val host = uri.host?.lowercase(Locale.US) ?: return false

    return trustedDomains.any { domain ->
        val lowerDomain = domain.lowercase(Locale.US)
        host == lowerDomain || host.endsWith(".$lowerDomain")
    }
}

/**
 * Service for searching and downloading subtitles from multiple online sources.
 * Optimized with OkHttp for resilience against connection issues (timeouts, Cloudflare).
 */
class SubtitleSearchService(providerConfig: SubtitleProviderConfig = SubtitleProviderConfig()) {
    private val TAG = "SubtitleSearchService"

    private val payloadLimits = SubtitlePayloadLimits()

    private val OS_BASE_URL = "https://api.opensubtitles.com/api/v1"
    private val normalizedProviderConfig = providerConfig.normalized()
    private val OS_API_KEY = normalizedProviderConfig.openSubtitlesApiKey.ifBlank { BuildConfig.OS_API_KEY }

    private val SUBS_RO_BASE_URL = "https://api.subs.ro/v1.0"
    private val SUBS_RO_API_KEY = normalizedProviderConfig.subsRoApiKey.ifBlank { BuildConfig.SUBS_RO_API_KEY }

    private val SUBDL_BASE_URL = "https://api.subdl.com/api/v1"
    private val SUBDL_API_KEY = normalizedProviderConfig.subDlApiKey.ifBlank { BuildConfig.SUBDL_API_KEY }

    private val TMDB_API_KEY = normalizedProviderConfig.tmdbApiKey.ifBlank { BuildConfig.TMDB_API_KEY }

    private val TITRARI_RO_BASE = "https://www.titrari.ro"

    private val USER_AGENT = "OxigenPlayer/${BuildConfig.VERSION_NAME} (Android; subtitle-client)"

    private val apiClient = NetworkClient.instance.newBuilder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(18, TimeUnit.SECONDS)
        .callTimeout(25, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(true)
        .build()

    private val subsRoClient = apiClient.newBuilder()
        .readTimeout(25, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val downloadClient = NetworkClient.instance.newBuilder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(35, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(true)
        .build()

    suspend fun searchSubtitles(query: String, lang: String = "ro,en", token: String? = null): SearchResult = coroutineScope {
        val normalizedQuery = query.trim().take(MAX_RAW_QUERY_CHARS)
        if (normalizedQuery.isBlank()) return@coroutineScope SearchResult(emptyList(), null)
        val languages = normalizeSearchLanguages(lang)
        val languageParameter = languages.joinToString(",")
        val titleSearches = mutableListOf<kotlinx.coroutines.Deferred<SearchResult>>()
        val imdbSearches  = mutableListOf<kotlinx.coroutines.Deferred<SearchResult>>()

        // Title providers start immediately; IMDb resolution no longer blocks all of them.
        val imdbResolution = async {
            extractImdbId(normalizedQuery) ?: withTimeoutOrNull(IMDB_RESOLUTION_TIMEOUT_MS) {
                resolveImdbId(normalizedQuery)
            }
        }

        titleSearches += async { providerCall("OpenSubtitles") { searchOpenSubtitles(normalizedQuery, languageParameter, token) } }
        titleSearches += async { providerCall("SubDL") { searchSubDL(normalizedQuery, languageParameter) } }
        titleSearches += async { providerCall("Titrari.ro") { searchTitrariRo(normalizedQuery) } }

        languages.forEach { language ->
            titleSearches += async { providerCall("Subs.ro") { searchSubsRo(normalizedQuery, language) } }
        }

        val imdbId = imdbResolution.await()

        if (imdbId != null) {
            imdbSearches += async {
                providerCall("OpenSubtitles") { searchOpenSubtitlesByImdb(imdbId, languageParameter, token) }
            }
            imdbSearches += async {
                providerCall("SubDL") { searchSubDLByImdb(imdbId, languageParameter) }
            }
            languages.forEach { language ->
                imdbSearches += async {
                    providerCall("Subs.ro") { searchSubsRoByImdb(imdbId, language) }
                }
            }
        }

        val allResults = (titleSearches + imdbSearches).awaitAll()

        val combined = mutableListOf<SubtitleSearchResult>()
        allResults.forEach { combined.addAll(it.subtitles) }

        val targetNorm = normalizeForMatch(normalizedQuery)
        val targetWords = targetNorm.split(" ").filter { it.length > 2 }
        val finalList = combined
            .distinctBy { "${it.source}_${it.id}_${it.fileName}" }
            .filter { result ->
                if (result.matchedByImdb || targetNorm.isEmpty()) return@filter true
                val resultNorm = normalizeForMatch(result.movieName.ifBlank { result.fileName })
                if (resultNorm.isEmpty()) return@filter false
                if (targetWords.isEmpty()) {
                    resultNorm.contains(targetNorm) || targetNorm.contains(resultNorm)
                } else {
                    val resultWords = resultNorm.split(" ").toSet()
                    val matchCount = targetWords.count { word ->
                        word in resultWords || resultNorm.contains(word)
                    }
                    val matchRatio = matchCount.toFloat() / targetWords.size
                    matchRatio >= 0.6f || resultNorm.contains(targetNorm) || targetNorm.contains(resultNorm)
                }
            }
            .sortedWith(
                compareByDescending<SubtitleSearchResult> { it.matchedByImdb }
                    .thenByDescending { it.year.toIntOrNull() ?: 0 }
                    .thenBy { it.language != "RO" }
                    .thenBy { it.fileName.lowercase(Locale.ROOT) }
            )

        val allFailures = allResults.flatMap { it.failures }.distinct()
        val allErrors = allResults.mapNotNull { it.errorMessage }.distinct()
        SearchResult(
            subtitles = finalList,
            errorMessage = allErrors.firstOrNull().takeIf { finalList.isEmpty() },
            failures = allFailures,
        )
    }

    private suspend fun providerCall(
        provider: String,
        block: suspend () -> SearchResult
    ): SearchResult = withTimeoutOrNull(PROVIDER_TIMEOUT_MS) { block() }
        ?: providerFailure(provider, SubtitleProviderFailureKind.TIMEOUT, "timeout")

    private fun providerFailure(
        provider: String,
        kind: SubtitleProviderFailureKind,
        detail: String? = null,
    ): SearchResult {
        val message = buildString {
            append(provider).append(": ")
            append(
                when (kind) {
                    SubtitleProviderFailureKind.NOT_CONFIGURED -> "not configured"
                    SubtitleProviderFailureKind.AUTHENTICATION -> "authentication required"
                    SubtitleProviderFailureKind.RATE_LIMITED -> "rate limit reached"
                    SubtitleProviderFailureKind.TIMEOUT -> "timeout"
                    SubtitleProviderFailureKind.NETWORK -> "network error"
                    SubtitleProviderFailureKind.HTTP -> "server error"
                    SubtitleProviderFailureKind.INVALID_RESPONSE -> "invalid response"
                    SubtitleProviderFailureKind.UNKNOWN -> "unknown error"
                }
            )
            detail?.takeIf(String::isNotBlank)?.let { append(" (").append(it).append(')') }
        }
        return SearchResult(
            subtitles = emptyList(),
            errorMessage = message,
            failures = listOf(SubtitleProviderFailure(provider, kind, detail)),
        )
    }

    private fun providerHttpFailure(provider: String, code: Int): SearchResult = providerFailure(
        provider = provider,
        kind = when (code) {
            401, 403 -> SubtitleProviderFailureKind.AUTHENTICATION
            408, 504 -> SubtitleProviderFailureKind.TIMEOUT
            429 -> SubtitleProviderFailureKind.RATE_LIMITED
            else -> SubtitleProviderFailureKind.HTTP
        },
        detail = "HTTP $code",
    )

    private fun providerException(provider: String, error: Exception): SearchResult {
        if (error is CancellationException) throw error
        val kind = when (error) {
            is SocketTimeoutException -> SubtitleProviderFailureKind.TIMEOUT
            is JSONException, is SubtitleDownloadException -> SubtitleProviderFailureKind.INVALID_RESPONSE
            is IOException -> SubtitleProviderFailureKind.NETWORK
            else -> SubtitleProviderFailureKind.UNKNOWN
        }
        Log.w(TAG, "$provider failed (${error.javaClass.simpleName})")
        return providerFailure(provider, kind, error.javaClass.simpleName)
    }

    private fun normalizeSearchLanguages(raw: String): List<String> {
        val normalized = raw.split(',')
            .asSequence()
            .map { it.trim().lowercase(Locale.ROOT) }
            .filter { it.matches(LANGUAGE_CODE_REGEX) }
            .distinct()
            .take(MAX_SEARCH_LANGUAGES)
            .toList()
        return normalized.ifEmpty { listOf("ro", "en") }
    }

    private fun readBodyBytes(body: ResponseBody?, maximumBytes: Int): ByteArray {
        body ?: throw SubtitleDownloadException("Empty response")
        val declaredLength = body.contentLength()
        if (declaredLength > maximumBytes) {
            throw SubtitleDownloadException("Response exceeds the safety limit")
        }
        return body.byteStream().use {
            SubtitlePayloadCodec.readLimited(it, maximumBytes, "Response")
        }
    }

    private fun readBodyText(body: ResponseBody?, maximumBytes: Int = MAX_API_RESPONSE_BYTES): String? {
        if (body == null) return null
        return String(readBodyBytes(body, maximumBytes), Charsets.UTF_8)
    }

    private suspend fun downloadTrustedBytes(
        initialUrl: String,
        trustedDomains: Set<String>,
        attachSubsRoApiKey: Boolean = false,
    ): ByteArray {
        var currentUrl = initialUrl.toHttpUrlOrNull()
            ?: throw SubtitleDownloadException("Invalid download URL")

        repeat(MAX_DOWNLOAD_REDIRECTS + 1) { redirectCount ->
            val isInitial = redirectCount == 0
            if (!isPublicDownloadUrl(currentUrl.toString()) ||
                (isInitial && !isTrustedHttpsUrl(currentUrl.toString(), trustedDomains))
            ) {
                throw SubtitleDownloadException("Untrusted download destination")
            }

            val request = Request.Builder()
                .url(currentUrl)
                .header("User-Agent", USER_AGENT)
                .apply {
                    // The credential is sent to any subs.ro subdomain, but never to an external CDN redirect.
                    val host = currentUrl.host
                    if (attachSubsRoApiKey && (host.equals("subs.ro", true) || host.endsWith(".subs.ro", true))) {
                        header("X-Subs-Api-Key", SUBS_RO_API_KEY)
                        header("Referer", "https://subs.ro/")
                    }
                }
                .build()

            var redirectUrl: HttpUrl? = null
            downloadClient.newCall(request).awaitResponse().use { response ->
                if (response.code == 401) {
                    throw SubtitleDownloadException("Neautorizat (401)", isUnauthorized = true)
                }
                if (response.code in 300..399) {
                    if (redirectCount >= MAX_DOWNLOAD_REDIRECTS) {
                        throw SubtitleDownloadException("Too many redirects")
                    }
                    val location = response.header("Location")
                        ?: throw SubtitleDownloadException("Invalid redirect")
                    val nextUrl = currentUrl.resolve(location)
                        ?: throw SubtitleDownloadException("Invalid redirect")

                    if (!isPublicDownloadUrl(nextUrl.toString())) {
                        throw SubtitleDownloadException("Untrusted redirect destination")
                    }
                    redirectUrl = nextUrl
                } else {
                    if (!response.isSuccessful) {
                        throw SubtitleDownloadException("HTTP ${response.code}")
                    }
                    return readBodyBytes(response.body, payloadLimits.maxDownloadBytes)
                }
            }
            currentUrl = redirectUrl
                ?: throw SubtitleDownloadException("Invalid redirect")
        }
        throw SubtitleDownloadException("Download could not be completed")
    }

    private suspend fun searchOpenSubtitles(query: String, lang: String, token: String?): SearchResult = withContext(Dispatchers.IO) {
        if (OS_API_KEY.isBlank()) return@withContext providerFailure("OpenSubtitles", SubtitleProviderFailureKind.NOT_CONFIGURED)
        try {
            val cleanQuery = cleanQuery(query)
            if (cleanQuery.isEmpty()) return@withContext SearchResult(emptyList(), null)

            val encodedQuery = java.net.URLEncoder.encode(cleanQuery, "UTF-8")
            val url = "$OS_BASE_URL/subtitles?query=$encodedQuery&languages=$lang"
            val request = Request.Builder()
                .url(url)
                .header("Api-Key", OS_API_KEY)
                .header("User-Agent", USER_AGENT)
                .apply { if (!token.isNullOrEmpty()) header("Authorization", "Bearer $token") }
                .header("Accept", "application/json")
                .build()

            apiClient.newCall(request).awaitResponse().use { response ->
                if (!response.isSuccessful) return@withContext providerHttpFailure("OpenSubtitles", response.code)
                val body = readBodyText(response.body)
                    ?.takeIf(String::isNotBlank)
                    ?: return@withContext providerFailure("OpenSubtitles", SubtitleProviderFailureKind.INVALID_RESPONSE)
                val data = JSONObject(body).optJSONArray("data") ?: JSONArray()
                SearchResult(parseOpenSubtitlesItems(data, matchedByImdb = false), null)
            }
        } catch (error: Exception) {
            providerException("OpenSubtitles", error)
        }
    }

    private suspend fun searchOpenSubtitlesByImdb(imdbId: String, lang: String, token: String?): SearchResult = withContext(Dispatchers.IO) {
        if (OS_API_KEY.isBlank()) return@withContext providerFailure("OpenSubtitles", SubtitleProviderFailureKind.NOT_CONFIGURED)
        try {
            val numericId = imdbId.removePrefix("tt").trimStart('0')
            if (numericId.isEmpty() || !numericId.all(Char::isDigit)) {
                return@withContext providerFailure("OpenSubtitles", SubtitleProviderFailureKind.INVALID_RESPONSE, "invalid IMDb id")
            }

            val url = "$OS_BASE_URL/subtitles?imdb_id=$numericId&languages=$lang"
            val request = Request.Builder()
                .url(url)
                .header("Api-Key", OS_API_KEY)
                .header("User-Agent", USER_AGENT)
                .apply { if (!token.isNullOrEmpty()) header("Authorization", "Bearer $token") }
                .header("Accept", "application/json")
                .build()

            apiClient.newCall(request).awaitResponse().use { response ->
                if (!response.isSuccessful) return@withContext providerHttpFailure("OpenSubtitles", response.code)
                val body = readBodyText(response.body)
                    ?.takeIf(String::isNotBlank)
                    ?: return@withContext providerFailure("OpenSubtitles", SubtitleProviderFailureKind.INVALID_RESPONSE)
                val data = JSONObject(body).optJSONArray("data") ?: JSONArray()
                SearchResult(parseOpenSubtitlesItems(data, matchedByImdb = true), null)
            }
        } catch (error: Exception) {
            providerException("OpenSubtitles", error)
        }
    }

    private fun parseOpenSubtitlesItems(
        data: JSONArray,
        matchedByImdb: Boolean,
    ): List<SubtitleSearchResult> {
        val results = mutableListOf<SubtitleSearchResult>()
        for (itemIndex in 0 until data.length()) {
            val item = data.optJSONObject(itemIndex) ?: continue
            val attributes = item.optJSONObject("attributes") ?: continue
            val feature = attributes.optJSONObject("feature_details")
            val files = attributes.optJSONArray("files") ?: continue
            for (fileIndex in 0 until files.length()) {
                val file = files.optJSONObject(fileIndex) ?: continue
                val fileId = file.optString("file_id").trim()
                if (fileId.isEmpty()) continue
                val fileName = file.optString("file_name").trim()
                results += SubtitleSearchResult(
                    id = fileId,
                    fileName = attributes.optString("release").ifBlank { fileName.ifBlank { "OpenSubtitles" } },
                    language = attributes.optString("language", "und").uppercase(Locale.ROOT),
                    downloadUrl = fileId,
                    movieName = feature?.optString("title").orEmpty().ifBlank { fileName },
                    year = feature?.optString("year").orEmpty(),
                    source = "OpenSubtitles",
                    matchedByImdb = matchedByImdb,
                )
            }
        }
        return results
    }

    private suspend fun searchSubsRo(query: String, lang: String): SearchResult = withContext(Dispatchers.IO) {
        if (SUBS_RO_API_KEY.isBlank()) return@withContext providerFailure("Subs.ro", SubtitleProviderFailureKind.NOT_CONFIGURED)
        try {
            val cleanQuery = cleanQuery(query)
            if (cleanQuery.isEmpty()) return@withContext SearchResult(emptyList(), null)

            val subsRoLang = mapToSubsRoLanguage(lang)
            val encodedQuery = java.net.URLEncoder.encode(cleanQuery, "UTF-8")
            val url = "$SUBS_RO_BASE_URL/search/title/$encodedQuery?language=$subsRoLang"
            val request = Request.Builder()
                .url(url)
                .header("X-Subs-Api-Key", SUBS_RO_API_KEY)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json")
                .build()

            subsRoClient.newCall(request).awaitResponse().use { response ->
                if (!response.isSuccessful) return@withContext searchSubsRoFallback(cleanQuery, subsRoLang)
                parseSubsRoResponse(readBodyText(response.body), matchedByImdb = false)
            }
        } catch (error: Exception) {
            providerException("Subs.ro", error)
        }
    }

    private suspend fun searchSubsRoFallback(query: String, lang: String): SearchResult = withContext(Dispatchers.IO) {
        if (SUBS_RO_API_KEY.isBlank()) return@withContext providerFailure("Subs.ro", SubtitleProviderFailureKind.NOT_CONFIGURED)
        try {
            val encoded = java.net.URLEncoder.encode(query, "UTF-8")
            val url = "$SUBS_RO_BASE_URL/search/release/$encoded?language=$lang"
            val request = Request.Builder()
                .url(url)
                .header("X-Subs-Api-Key", SUBS_RO_API_KEY)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json")
                .build()

            subsRoClient.newCall(request).awaitResponse().use { response ->
                if (!response.isSuccessful) return@withContext providerHttpFailure("Subs.ro", response.code)
                parseSubsRoResponse(readBodyText(response.body), matchedByImdb = false)
            }
        } catch (error: Exception) {
            providerException("Subs.ro", error)
        }
    }

    private suspend fun searchSubsRoByImdb(imdbId: String, lang: String): SearchResult = withContext(Dispatchers.IO) {
        if (SUBS_RO_API_KEY.isBlank()) return@withContext providerFailure("Subs.ro", SubtitleProviderFailureKind.NOT_CONFIGURED)
        try {
            if (!imdbId.matches(IMDB_ID_REGEX)) {
                return@withContext providerFailure("Subs.ro", SubtitleProviderFailureKind.INVALID_RESPONSE, "invalid IMDb id")
            }
            val subsRoLang = mapToSubsRoLanguage(lang)
            val url = "$SUBS_RO_BASE_URL/search/imdbid/$imdbId?language=$subsRoLang"
            val request = Request.Builder()
                .url(url)
                .header("X-Subs-Api-Key", SUBS_RO_API_KEY)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json")
                .build()

            subsRoClient.newCall(request).awaitResponse().use { response ->
                if (!response.isSuccessful) return@withContext providerHttpFailure("Subs.ro", response.code)
                parseSubsRoResponse(readBodyText(response.body), matchedByImdb = true)
            }
        } catch (error: Exception) {
            providerException("Subs.ro", error)
        }
    }

    private fun parseSubsRoResponse(body: String?, matchedByImdb: Boolean): SearchResult {
        val payload = body?.takeIf(String::isNotBlank)
            ?: return providerFailure("Subs.ro", SubtitleProviderFailureKind.INVALID_RESPONSE)
        val json = JSONObject(payload)
        val items = json.optJSONArray("items") ?: JSONArray()
        val results = buildList {
            for (index in 0 until items.length()) {
                val item = items.optJSONObject(index) ?: continue
                val parsed = parseSubsRoItem(item)
                if (parsed.id.isNotBlank() || parsed.downloadUrl.startsWith("https://")) {
                    add(parsed.copy(matchedByImdb = matchedByImdb))
                }
            }
        }
        return SearchResult(results, null)
    }

    private fun mapToSubsRoLanguage(lang: String): String = when (lang.lowercase(Locale.ROOT).take(2)) {
        "ro" -> "ro"
        "en" -> "en"
        "it" -> "ita"
        "fr" -> "fra"
        "de" -> "ger"
        "hu" -> "ung"
        "es" -> "spa"
        else -> "alt"
    }

    private fun parseSubsRoItem(item: JSONObject): SubtitleSearchResult {
        val id = item.optString("id", "")
        val description = item.optString("description", "").trim()
        val title = item.optString("title", "").trim().ifBlank {
            description.ifBlank { "Subs.ro" }
        }
        val lang = item.optString("language", "RO").uppercase(Locale.ROOT)
        val year = item.optString("year", "")
        val directDownloadUrl = item.optString("downloadLink", "")
            .takeIf { isPublicDownloadUrl(it) }
            .orEmpty()

        return SubtitleSearchResult(
            id          = id,
            fileName    = description,
            language    = lang,
            downloadUrl = directDownloadUrl.ifEmpty { id },
            movieName   = title,
            year        = year,
            source      = "Subs.ro"
        )
    }

    private suspend fun searchSubDL(query: String, lang: String): SearchResult = withContext(Dispatchers.IO) {
        if (SUBDL_API_KEY.isBlank()) return@withContext providerFailure("SubDL", SubtitleProviderFailureKind.NOT_CONFIGURED)
        try {
            val cleanQuery = cleanQuery(query)
            if (cleanQuery.isEmpty()) return@withContext SearchResult(emptyList(), null)
            val encoded = java.net.URLEncoder.encode(cleanQuery, "UTF-8")
            val url = "$SUBDL_BASE_URL/subtitles?api_key=$SUBDL_API_KEY&film_name=$encoded&languages=$lang&subs_per_page=20"
            val request = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
            apiClient.newCall(request).awaitResponse().use { response ->
                if (!response.isSuccessful) return@withContext providerHttpFailure("SubDL", response.code)
                parseSubDlResponse(readBodyText(response.body), cleanQuery, matchedByImdb = false)
            }
        } catch (error: Exception) {
            providerException("SubDL", error)
        }
    }

    private suspend fun searchSubDLByImdb(imdbId: String, lang: String): SearchResult = withContext(Dispatchers.IO) {
        if (SUBDL_API_KEY.isBlank()) return@withContext providerFailure("SubDL", SubtitleProviderFailureKind.NOT_CONFIGURED)
        try {
            if (!imdbId.matches(IMDB_ID_REGEX)) {
                return@withContext providerFailure("SubDL", SubtitleProviderFailureKind.INVALID_RESPONSE, "invalid IMDb id")
            }
            val url = "$SUBDL_BASE_URL/subtitles?api_key=$SUBDL_API_KEY&imdb_id=$imdbId&languages=$lang&subs_per_page=20"
            val request = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
            apiClient.newCall(request).awaitResponse().use { response ->
                if (!response.isSuccessful) return@withContext providerHttpFailure("SubDL", response.code)
                parseSubDlResponse(readBodyText(response.body), imdbId, matchedByImdb = true)
            }
        } catch (error: Exception) {
            providerException("SubDL", error)
        }
    }

    private fun parseSubDlResponse(
        body: String?,
        fallbackMovieName: String,
        matchedByImdb: Boolean,
    ): SearchResult {
        val payload = body?.takeIf(String::isNotBlank)
            ?: return providerFailure("SubDL", SubtitleProviderFailureKind.INVALID_RESPONSE)
        val json = JSONObject(payload)
        if (!json.optBoolean("status", false)) return SearchResult(emptyList(), null)
        val subtitles = json.optJSONArray("subtitles") ?: JSONArray()
        val results = buildList {
            for (index in 0 until subtitles.length()) {
                val item = subtitles.optJSONObject(index) ?: continue
                val zipUrl = normalizeSubDlDownloadUrl(item.optString("url"))
                if (zipUrl == null) continue
                add(
                    SubtitleSearchResult(
                        id = item.optString("subtitlePage", zipUrl).ifBlank { zipUrl },
                        fileName = item.optString("release_name").ifBlank {
                            item.optString("name").ifBlank { "SubDL" }
                        },
                        language = item.optString("language", "und").uppercase(Locale.ROOT),
                        downloadUrl = zipUrl,
                        movieName = item.optString("name", fallbackMovieName).ifBlank { fallbackMovieName },
                        year = item.optInt("year", 0).takeIf { it > 0 }?.toString().orEmpty(),
                        source = "SubDL",
                        matchedByImdb = matchedByImdb,
                    )
                )
            }
        }
        return SearchResult(results, null)
    }

    private suspend fun searchTitrariRo(query: String): SearchResult = withContext(Dispatchers.IO) {
        try {
            val cleanQuery = cleanQuery(query)
            if (cleanQuery.isEmpty()) return@withContext SearchResult(emptyList(), null)

            val encoded = java.net.URLEncoder.encode(cleanQuery, "UTF-8")
            val url = "$TITRARI_RO_BASE/index.php?page=cautare&z2=$encoded"

            val request = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .build()

            apiClient.newCall(request).awaitResponse().use { response ->
                if (response.isSuccessful) {
                    val html = readBodyText(response.body, MAX_HTML_RESPONSE_BYTES) ?: ""
                    val list = mutableListOf<SubtitleSearchResult>()

                    val sections = html.split("<td class=\"row1 test\"")
                    for (section in sections) {
                        if (!section.contains("get.php?id=")) continue

                        val idMatcher = Pattern.compile("get\\.php\\?id=(\\d+)").matcher(section)
                        val titleMatcher = Pattern.compile("<h1><a[^>]*>(.*?)</a></h1>").matcher(section)
                        val langMatcher = Pattern.compile("\\[ (.*?) ]").matcher(section)

                        if (idMatcher.find()) {
                            val id = idMatcher.group(1) ?: continue
                            val name = if (titleMatcher.find()) {
                                titleMatcher.group(1)?.replace(HTML_TAG_REGEX, "")?.trim() ?: "Titrari.ro Subtitle"
                            } else "Titrari.ro Subtitle"

                            val lang = if (langMatcher.find()) langMatcher.group(1)?.trim()?.uppercase(Locale.ROOT) ?: "RO" else "RO"

                            list.add(SubtitleSearchResult(
                                id = id,
                                fileName = name,
                                language = if (lang == "ROMANA") "RO" else lang,
                                downloadUrl = "$TITRARI_RO_BASE/get.php?id=$id",
                                movieName = name,
                                source = "Titrari.ro"
                            ))
                        }
                    }

                    if (list.isEmpty()) {
                        val pattern = Pattern.compile("href=[\"']?get\\.php\\?id=(\\d+)", Pattern.CASE_INSENSITIVE)
                        val matcher = pattern.matcher(html)
                        while (matcher.find()) {
                            val id = matcher.group(1) ?: continue
                            list.add(SubtitleSearchResult(
                                id = id,
                                fileName = "Titrari.ro Subtitle $id",
                                language = "RO",
                                downloadUrl = "$TITRARI_RO_BASE/get.php?id=$id",
                                movieName = "Subtitle $id",
                                source = "Titrari.ro"
                            ))
                        }
                    }
                    SearchResult(list, null)
                } else providerHttpFailure("Titrari.ro", response.code)
            }
        } catch (error: Exception) {
            providerException("Titrari.ro", error)
        }
    }

    suspend fun downloadSubtitle(result: SubtitleSearchResult, token: String? = null): List<SubtitleEntry> {
        return when (result.source) {
            "Subs.ro"    -> downloadFromSubsRo(result.downloadUrl)
            "SubDL"      -> downloadFromSubDL(result.downloadUrl)
            "Titrari.ro" -> downloadFromTitrariRo(result.id)
            "OpenSubtitles" -> downloadFromOpenSubtitles(result.id, token)
            else -> throw SubtitleDownloadException("Unknown subtitle provider")
        }
    }

    private suspend fun downloadFromSubsRo(urlOrId: String): List<SubtitleEntry> = withContext(Dispatchers.IO) {
        try {
            if (SUBS_RO_API_KEY.isBlank()) {
                throw SubtitleDownloadException("Subs.ro is not configured")
            }
            val url = if (urlOrId.startsWith("https://", ignoreCase = true) || urlOrId.startsWith("http://", ignoreCase = true)) {
                urlOrId
            } else {
                if (!urlOrId.matches(SAFE_PROVIDER_ID)) {
                    throw SubtitleDownloadException("Identificator Subs.ro invalid")
                }
                "$SUBS_RO_BASE_URL/subtitle/$urlOrId/download"
            }

            val bytes = downloadTrustedBytes(
                initialUrl = url,
                trustedDomains = SUBS_RO_DOWNLOAD_DOMAINS,
                attachSubsRoApiKey = true,
            )
            parseDownloadedSubtitle(bytes, "Subs.ro")
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (e: SubtitleDownloadException) {
            throw e
        } catch (e: Exception) {
            throw SubtitleDownloadException("Subs.ro: ${e.message}")
        }
    }

    private suspend fun downloadFromSubDL(zipUrl: String): List<SubtitleEntry> = withContext(Dispatchers.IO) {
        try {
            val trustedUrl = normalizeSubDlDownloadUrl(zipUrl)
                ?: throw SubtitleDownloadException("URL SubDL neautorizat")
            val bytes = downloadTrustedBytes(trustedUrl, SUBDL_DOWNLOAD_DOMAINS)
            parseDownloadedSubtitle(bytes, "SubDL")
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (e: SubtitleDownloadException) {
            throw e
        } catch (e: Exception) {
            throw SubtitleDownloadException("SubDL: ${e.message}")
        }
    }

    private suspend fun downloadFromTitrariRo(id: String): List<SubtitleEntry> = withContext(Dispatchers.IO) {
        try {
            if (!id.matches(NUMERIC_ID_REGEX)) {
                throw SubtitleDownloadException("Identificator Titrari.ro invalid")
            }
            val url = "$TITRARI_RO_BASE/get.php?id=$id"
            val bytes = downloadTrustedBytes(url, TITRARI_DOWNLOAD_DOMAINS)
            parseDownloadedSubtitle(bytes, "Titrari.ro")
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (e: SubtitleDownloadException) {
            throw e
        } catch (e: Exception) {
            throw SubtitleDownloadException("Titrari.ro: ${e.message}")
        }
    }

    private suspend fun downloadFromOpenSubtitles(fileId: String, token: String?): List<SubtitleEntry> = withContext(Dispatchers.IO) {
        try {
            if (OS_API_KEY.isBlank()) {
                throw SubtitleDownloadException("OpenSubtitles is not configured")
            }
            val fId = fileId.toIntOrNull() ?: throw SubtitleDownloadException("OS: file_id invalid")
            val jsonInput = JSONObject().put("file_id", fId).toString()
            val request = Request.Builder()
                .url("$OS_BASE_URL/download")
                .post(jsonInput.toRequestBody("application/json".toMediaType()))
                .header("Api-Key", OS_API_KEY)
                .header("User-Agent", USER_AGENT)
                .apply { if (!token.isNullOrEmpty()) header("Authorization", "Bearer $token") }
                .header("Accept", "application/json")
                .build()

            apiClient.newCall(request).awaitResponse().use { response ->
                if (response.isSuccessful) {
                    val downloadBody = readBodyText(response.body) ?: ""
                    if (downloadBody.isBlank()) throw SubtitleDownloadException("OpenSubtitles returned an empty download response")
                    
                    val downloadLink = try {
                        JSONObject(downloadBody).optString("link")
                    } catch (e: Exception) {
                        throw SubtitleDownloadException("OS: format JSON invalid")
                    }
                    
                    if (downloadLink.isNullOrBlank()) {
                        throw SubtitleDownloadException("OpenSubtitles response has no download link")
                    }
                    
                    val bytes = downloadTrustedBytes(downloadLink, OPEN_SUBTITLES_DOWNLOAD_DOMAINS)
                    parseDownloadedSubtitle(bytes, "OpenSubtitles")
                } else if (response.code == 401) {
                    throw SubtitleDownloadException("OpenSubtitles session expired or unauthorized (401)", isUnauthorized = true)
                } else throw SubtitleDownloadException("OS: HTTP ${response.code} la cererea de link")
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (e: SubtitleDownloadException) {
            throw e
        } catch (e: Exception) {
            throw SubtitleDownloadException("OS: ${e.message}")
        }
    }

    private fun parseDownloadedSubtitle(bytes: ByteArray, provider: String): List<SubtitleEntry> {
        val content = SubtitlePayloadCodec.extractSrtFromZip(bytes, payloadLimits)
            ?: SubtitlePayloadCodec.decodeSubtitle(bytes, payloadLimits)
            ?: throw SubtitleDownloadException("$provider: format de subtitrare invalid")
        return when (val parsed = SubtitleParser.parseContent(content)) {
            is SubtitleParseResult.Success -> parsed.entries
            is SubtitleParseResult.Partial -> parsed.entries
            is SubtitleParseResult.Failure -> {
                val detail = when (parsed.reason) {
                    SubtitleParseFailure.EMPTY -> "fisier gol"
                    SubtitleParseFailure.UNSUPPORTED_ENCODING -> "codare nesuportata"
                    SubtitleParseFailure.UNSUPPORTED_FORMAT -> "format nesuportat"
                    SubtitleParseFailure.NO_VALID_CUES -> "nu s-au gasit intrari"
                    SubtitleParseFailure.TOO_LARGE -> "fisier prea mare"
                }
                throw SubtitleDownloadException("$provider: $detail")
            }
        }
    }

    private fun normalizeSubDlDownloadUrl(rawUrl: String): String? {
        if (rawUrl.isBlank()) return null
        val candidate = when {
            rawUrl.startsWith('/') -> "https://dl.subdl.com$rawUrl"
            rawUrl.startsWith("http://", ignoreCase = true) -> "https://" + rawUrl.substring(7)
            else -> rawUrl
        }
        return candidate.takeIf { isPublicDownloadUrl(it) }
    }

    private fun cleanQuery(query: String): String = normalizeUnicodeWords(
        query.take(MAX_RAW_QUERY_CHARS).replace(
            RELEASE_TAG_REGEX,
            " ",
        )
    ).take(MAX_CLEAN_QUERY_CHARS)

    private fun normalizeForMatch(text: String): String {
        val withoutYear = text.replace(YEAR_REGEX, " ")
        val withoutMarks = Normalizer.normalize(withoutYear, Normalizer.Form.NFKD)
            .replace(COMBINING_MARKS_REGEX, "")
        return normalizeUnicodeWords(withoutMarks)
    }

    private fun normalizeUnicodeWords(value: String): String = Normalizer
        .normalize(value, Normalizer.Form.NFKC)
        .lowercase(Locale.ROOT)
        .replace(NON_WORD_REGEX, " ")
        .replace(WHITESPACE_REGEX, " ")
        .trim()

    private fun extractImdbId(query: String): String? = IMDB_ID_IN_TEXT_REGEX.find(query.trim())?.value

    private suspend fun resolveImdbId(query: String): String? = withContext(Dispatchers.IO) {
        if (TMDB_API_KEY.isBlank()) return@withContext null
        try {
            val yearMatch = YEAR_REGEX.find(query)
            val year = yearMatch?.value
            val cleanTitle = cleanQuery(query.replace(yearMatch?.value ?: "", ""))
            if (cleanTitle.isEmpty()) return@withContext null
            val encoded = java.net.URLEncoder.encode(cleanTitle, "UTF-8")
            val searchUrl = "https://api.themoviedb.org/3/search/movie?api_key=$TMDB_API_KEY&query=$encoded${if (year != null) "&year=$year" else ""}"

            val request = Request.Builder().url(searchUrl).build()
            apiClient.newCall(request).awaitResponse().use { response ->
                if (response.isSuccessful) {
                    val body = readBodyText(response.body)
                    if (body.isNullOrBlank()) return@withContext null

                    val results = JSONObject(body).optJSONArray("results")
                    if (results == null || results.length() == 0) return@withContext null
                    val tmdbId = results.getJSONObject(0).optInt("id")

                    val externalUrl = "https://api.themoviedb.org/3/movie/$tmdbId/external_ids?api_key=$TMDB_API_KEY"
                    val extRequest = Request.Builder().url(externalUrl).build()
                    apiClient.newCall(extRequest).awaitResponse().use { extResponse ->
                        if (extResponse.isSuccessful) {
                            val extBody = readBodyText(extResponse.body)
                            if (extBody.isNullOrBlank()) null
                            else JSONObject(extBody).optString("imdb_id").takeIf { it.startsWith("tt") }
                        } else null
                    }
                } else null
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) { null }
    }

    suspend fun loginOpenSubtitles(username: String, password: String): String? = withContext(Dispatchers.IO) {
        if (OS_API_KEY.isBlank()) return@withContext null
        try {
            val jsonInput = JSONObject().apply { put("username", username); put("password", password) }.toString()
            val request = Request.Builder()
                .url("$OS_BASE_URL/login")
                .post(jsonInput.toRequestBody("application/json".toMediaType()))
                .header("Api-Key", OS_API_KEY)
                .header("User-Agent", USER_AGENT)
                .build()

            apiClient.newCall(request).awaitResponse().use { response ->
                if (response.isSuccessful) {
                    val body = readBodyText(response.body)
                    if (body.isNullOrBlank()) null
                    else JSONObject(body).optString("token")
                } else null
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) { null }
    }

    companion object {
        const val MAX_API_RESPONSE_BYTES = 2 * 1024 * 1024
        const val MAX_HTML_RESPONSE_BYTES = 3 * 1024 * 1024
        const val MAX_DOWNLOAD_REDIRECTS = 3
        const val PROVIDER_TIMEOUT_MS = 32_000L
        const val IMDB_RESOLUTION_TIMEOUT_MS = 12_000L
        const val MAX_RAW_QUERY_CHARS = 500
        const val MAX_CLEAN_QUERY_CHARS = 200
        const val MAX_SEARCH_LANGUAGES = 8

        val SAFE_PROVIDER_ID = Regex("[A-Za-z0-9_-]{1,128}")
        val SUBS_RO_DOWNLOAD_DOMAINS = setOf("subs.ro")
        val SUBDL_DOWNLOAD_DOMAINS = setOf("subdl.com")
        val TITRARI_DOWNLOAD_DOMAINS = setOf("titrari.ro")
        val OPEN_SUBTITLES_DOWNLOAD_DOMAINS = setOf(
            "opensubtitles.com",
            "opensubtitles.org",
            "os-cdn.com",
        )
    }
}
