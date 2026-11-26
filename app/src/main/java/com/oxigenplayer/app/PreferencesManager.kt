// SPDX-License-Identifier: GPL-3.0-only
package com.oxigenplayer.app

import android.content.Context
import android.content.SharedPreferences
import android.app.LocaleManager
import android.os.Build
import android.os.LocaleList
import androidx.core.content.edit
import java.security.MessageDigest
import java.util.Locale

class PreferencesManager(context: Context) {
    private val appContext = context.applicationContext
    private val prefs: SharedPreferences = appContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    /** Last-write time of each per-title key in [prefs], used to evict the oldest titles. */
    private val titleRecency: SharedPreferences =
        appContext.getSharedPreferences(TITLE_RECENCY_NAME, Context.MODE_PRIVATE)

    @Volatile
    private var secureStore: AndroidKeystoreSecretStore? = null

    /** Initializes Android Keystore-backed storage once, preferably from an IO dispatcher. */
    fun prewarmSecureStorage(): Boolean {
        if (secureStore != null) return true
        synchronized(SECURE_STORE_LOCK) {
            if (secureStore == null) secureStore = AndroidKeystoreSecretStore.create(appContext)
        }
        return secureStore != null
    }

    /**
     * Returns the encrypted store, initializing it if the background prewarm has not finished.
     *
     * Falling back to the in-memory [SecureSessionStore] while the Keystore was still warming up
     * made cold-start reads return empty credentials and, worse, made writes non-durable: the
     * value only reached the process-local cache and was gone after the next launch. Callers can
     * arrive here well before [prewarmSecureStorage] completes, so initialization has to be
     * on demand. The prewarm is kept purely to move the first-run key generation off this path.
     */
    private fun secrets(): AndroidKeystoreSecretStore? {
        secureStore?.let { return it }
        prewarmSecureStorage()
        return secureStore
    }

    fun saveLogin(user: String) {
        SecureSessionStore.username = user
        secrets()?.apply {
            putString("os_user", user)
            remove("os_pass")
        }
    }

    fun getUsername(): String = secrets()?.getString("os_user")
        ?: SecureSessionStore.username

    fun saveToken(token: String) {
        SecureSessionStore.token = token
        secrets()?.apply {
            putString("os_token", token)
            remove("os_pass")
        }
    }

    fun getToken(): String = secrets()?.getString("os_token")
        ?: SecureSessionStore.token

    fun clearAccount() {
        SecureSessionStore.username = ""
        SecureSessionStore.token = ""
        secrets()?.apply {
            remove("os_user")
            remove("os_pass")
            remove("os_token")
        }
    }

    fun isSecureCredentialStorageAvailable(): Boolean = secureStore != null

    fun saveVideoPosition(uri: String, position: Long) {
        val key = videoPositionKey(uri)
        val safePosition = position.coerceAtLeast(0L)
        // A finished or never-started title resumes from 0 anyway; storing the 0 only grew
        // the preferences file by one key per file ever opened.
        if (safePosition == 0L) {
            if (prefs.contains(key)) prefs.edit { remove(key) }
            return
        }
        prefs.edit { putLong(key, safePosition) }
        touchTitleKey(key)
    }

    fun getVideoPosition(uri: String): Long {
        val key = videoPositionKey(uri)
        if (prefs.contains(key)) return prefs.getLong(key, 0L).coerceAtLeast(0L)

        // One-time migration from versions that exposed the complete URI as a preference key.
        val legacyKey = "pos_$uri"
        val legacyPosition = prefs.getLong(legacyKey, 0L).coerceAtLeast(0L)
        if (prefs.contains(legacyKey)) {
            prefs.edit {
                putLong(key, legacyPosition)
                remove(legacyKey)
            }
        }
        return legacyPosition
    }

    // Subtitle Customization
    fun saveSubtitleFontSize(size: Float) {
        prefs.edit { putFloat("sub_font_size", size) }
    }
    fun getSubtitleFontSize(): Float = prefs.getFloat("sub_font_size", 36f).coerceIn(10f, 100f)

    fun saveSubtitleColor(color: Int) {
        prefs.edit { putInt("sub_color", color) }
    }
    fun getSubtitleColor(): Int = prefs.getInt("sub_color", -1) // -1 is white in ARGB

    fun saveSubtitleBackgroundColor(color: Int) {
        prefs.edit { putInt("sub_bg_color", color) }
    }
    fun getSubtitleBackgroundColor(): Int = prefs.getInt("sub_bg_color", 0x00000000) // Transparent

    fun saveSubtitleVerticalOffset(offset: Float) {
        prefs.edit { putFloat("sub_vertical_offset", offset) }
    }
    fun getSubtitleVerticalOffset(): Float = prefs.getFloat("sub_vertical_offset", 0f).coerceIn(-60f, 500f)

    /** Subtitle offsets are stored per title; an untouched title has none. */
    fun saveSubtitleSyncOffset(uri: String?, offset: Long) {
        if (uri.isNullOrBlank()) return
        val key = subtitleSyncOffsetKey(uri)
        val clamped = clampSubtitleSyncOffset(offset)
        prefs.edit { if (clamped == 0L) remove(key) else putLong(key, clamped) }
        if (clamped != 0L) touchTitleKey(key)
    }

    fun getSubtitleEdgeStyle(): String = prefs.getString("sub_title_edge_style", "shadow") ?: "shadow"

    fun saveSubtitleEdgeStyle(style: String) {
        prefs.edit { putString("sub_title_edge_style", style) }
    }
    fun getSubtitleSyncOffset(uri: String?): Long {
        if (uri.isNullOrBlank()) return 0L
        return clampSubtitleSyncOffset(prefs.getLong(subtitleSyncOffsetKey(uri), 0L))
    }

    fun saveAppLanguage(lang: String) {
        val normalized = lang.takeIf { it in SUPPORTED_APP_LANGUAGES } ?: DEFAULT_APP_LANGUAGE
        prefs.edit { putString(APP_LANGUAGE_KEY, normalized) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appContext.getSystemService(LocaleManager::class.java)
                .applicationLocales = LocaleList.forLanguageTags(normalized)
        }
    }

    fun getAppLanguage(): String = readStoredLanguage(appContext)

    fun saveSubtitlesVisible(visible: Boolean) {
        prefs.edit { putBoolean("subtitles_visible", visible) }
    }

    fun setSubtitlesVisible(visible: Boolean) {
        saveSubtitlesVisible(visible)
    }

    fun isSubtitlesVisible(): Boolean = prefs.getBoolean("subtitles_visible", true)

    fun saveSearchLanguage(lang: String) {
        prefs.edit { putString("search_language", lang) }
    }
    fun getSearchLanguage(): String {
        val stored = prefs.getString("search_language", "ro,en") ?: "ro,en"
        return stored.takeIf { SEARCH_LANGUAGE_PATTERN.matches(it) } ?: "ro,en"
    }

    fun saveTranslationSource(source: String) {
        prefs.edit { putString("trans_source", source) }
    }

    fun getTranslationSource(): String {
        val stored = prefs.getString("trans_source", TranslationSource.AUTO.name)
            ?: TranslationSource.AUTO.name
        // Migrate versions that exposed the old unsupported direct Google web endpoint.
        if (stored == "GOOGLE_TRANSLATE" || stored == "GOOGLE") {
            prefs.edit { putString("trans_source", TranslationSource.AUTO.name) }
            return TranslationSource.AUTO.name
        }
        return TranslationSource.entries.firstOrNull { it.name == stored }?.name
            ?: TranslationSource.AUTO.name
    }

    fun saveTranslationProviderConfig(config: TranslationProviderConfig) {
        val normalized = config.normalized()
        prefs.edit {
            remove("apertium_base_url") // Apertium was removed: public server is offline.
            putString("libretranslate_base_url", normalized.libreTranslateBaseUrl)
            putString("mozhi_base_url", normalized.mozhiBaseUrl)
            putString("mozhi_engine", normalized.mozhiEngine)
            putString("ollama_base_url", normalized.ollamaBaseUrl)
            putString("ollama_model", normalized.ollamaModel)
        }
        SecureSessionStore.libreTranslateApiKey = normalized.libreTranslateApiKey
        SecureSessionStore.deepLAuthKey = normalized.deepLAuthKey
        SecureSessionStore.ollamaBearerToken = normalized.ollamaBearerToken
        secrets()?.apply {
            putOrRemove("libretranslate_api_key", normalized.libreTranslateApiKey)
            putOrRemove("deepl_auth_key", normalized.deepLAuthKey)
            putOrRemove("ollama_bearer_token", normalized.ollamaBearerToken)
        }
    }

    fun getTranslationProviderConfig(): TranslationProviderConfig = TranslationProviderConfig(
        libreTranslateBaseUrl = prefs.getString("libretranslate_base_url", "").orEmpty(),
        libreTranslateApiKey = secrets()?.getString("libretranslate_api_key")
            ?: SecureSessionStore.libreTranslateApiKey,
        mozhiBaseUrl = prefs.getString(
            "mozhi_base_url",
            TranslationProviderConfig.DEFAULT_MOZHI_BASE_URL,
        ) ?: TranslationProviderConfig.DEFAULT_MOZHI_BASE_URL,
        mozhiEngine = prefs.getString(
            "mozhi_engine",
            TranslationProviderConfig.DEFAULT_MOZHI_ENGINE,
        ) ?: TranslationProviderConfig.DEFAULT_MOZHI_ENGINE,
        deepLAuthKey = secrets()?.getString("deepl_auth_key")
            ?: SecureSessionStore.deepLAuthKey,
        ollamaBaseUrl = prefs.getString("ollama_base_url", "").orEmpty(),
        ollamaModel = prefs.getString("ollama_model", "").orEmpty(),
        ollamaBearerToken = secrets()?.getString("ollama_bearer_token")
            ?: SecureSessionStore.ollamaBearerToken,
    ).normalized()

    fun saveSubtitleProviderConfig(config: SubtitleProviderConfig) {
        val normalized = config.normalized()
        SecureSessionStore.openSubtitlesApiKey = normalized.openSubtitlesApiKey
        SecureSessionStore.subsRoApiKey = normalized.subsRoApiKey
        SecureSessionStore.subDlApiKey = normalized.subDlApiKey
        SecureSessionStore.tmdbApiKey = normalized.tmdbApiKey
        secrets()?.apply {
            putOrRemove("provider_os_api_key", normalized.openSubtitlesApiKey)
            putOrRemove("provider_subs_ro_api_key", normalized.subsRoApiKey)
            putOrRemove("provider_subdl_api_key", normalized.subDlApiKey)
            putOrRemove("provider_tmdb_api_key", normalized.tmdbApiKey)
        }
    }

    fun getSubtitleProviderConfig(): SubtitleProviderConfig = SubtitleProviderConfig(
        openSubtitlesApiKey = secrets()?.getString("provider_os_api_key")
            ?: SecureSessionStore.openSubtitlesApiKey,
        subsRoApiKey = secrets()?.getString("provider_subs_ro_api_key")
            ?: SecureSessionStore.subsRoApiKey,
        subDlApiKey = secrets()?.getString("provider_subdl_api_key")
            ?: SecureSessionStore.subDlApiKey,
        tmdbApiKey = secrets()?.getString("provider_tmdb_api_key")
            ?: SecureSessionStore.tmdbApiKey,
    ).normalized()

    fun saveTranslationEnabled(enabled: Boolean) {
        prefs.edit { putBoolean("trans_enabled", enabled) }
    }
    fun isTranslationEnabled(): Boolean = prefs.getBoolean("trans_enabled", false)

    fun saveSourceLanguage(lang: String) {
        val normalized = if (lang == "auto") "auto" else validatedLanguage(lang, "auto")
        prefs.edit { putString("translation_source_language", normalized) }
    }
    fun getSourceLanguage(): String {
        val stored = prefs.getString("translation_source_language", "auto") ?: "auto"
        return if (stored == "auto") "auto" else validatedLanguage(stored, "auto")
    }

    fun saveTargetLanguage(lang: String) {
        prefs.edit { putString("target_lang", lang) }
    }
    fun getTargetLanguage(): String = validatedLanguage(prefs.getString("target_lang", "ro"), "ro")

    fun savePlaybackSpeed(speed: Float) {
        prefs.edit { putFloat("playback_speed", speed) }
    }
    fun getPlaybackSpeed(): Float = prefs.getFloat("playback_speed", 1.0f).coerceIn(0.5f, 2.0f)

    fun saveVideoQualityProfile(profile: VideoQualityProfile) {
        prefs.edit { putString("video_quality_profile", profile.name) }
    }

    fun getVideoQualityProfile(): VideoQualityProfile = VideoQualityProfile.fromStored(
        prefs.getString("video_quality_profile", VideoQualityProfile.AUTO.name)
    )

    fun saveMatchContentFrameRate(enabled: Boolean) {
        prefs.edit { putBoolean("match_content_frame_rate", enabled) }
    }

    fun shouldMatchContentFrameRate(): Boolean = prefs.getBoolean("match_content_frame_rate", false)

    fun saveRepeatCurrentMedia(enabled: Boolean) {
        prefs.edit { putBoolean("repeat_current_media", enabled) }
    }

    fun shouldRepeatCurrentMedia(): Boolean = prefs.getBoolean("repeat_current_media", false)

    fun saveDiagnosticsVisible(visible: Boolean) {
        prefs.edit { putBoolean("playback_diagnostics_visible", visible) }
    }

    fun areDiagnosticsVisible(): Boolean = prefs.getBoolean("playback_diagnostics_visible", false)

    fun saveSkipSilence(enabled: Boolean) {
        prefs.edit { putBoolean("skip_silence", enabled) }
    }

    fun shouldSkipSilence(): Boolean = prefs.getBoolean("skip_silence", false)

    fun shouldAudioOffload(): Boolean = prefs.getBoolean("audio_offload", true)

    fun saveAudioOffload(enabled: Boolean) {
        prefs.edit { putBoolean("audio_offload", enabled) }
    }

    fun getAudioDelayMs(): Int = clampAudioDelayMs(prefs.getInt("audio_delay_ms", 0))

    fun saveAudioDelayMs(ms: Int) {
        prefs.edit { putInt("audio_delay_ms", clampAudioDelayMs(ms)) }
    }

    fun getPreferredAudioLanguage(): String = prefs.getString("preferred_audio_language", "") ?: ""

    fun savePreferredAudioLanguage(lang: String) {
        prefs.edit { putString("preferred_audio_language", lang) }
    }

    fun saveVideoResizeMode(mode: VideoResizeMode) {
        prefs.edit { putString("video_resize_mode", mode.name) }
    }

    fun getVideoResizeMode(): VideoResizeMode = VideoResizeMode.fromStored(
        prefs.getString("video_resize_mode", VideoResizeMode.FIT.name)
    )

    fun saveVideoAspectRatioOverride(override: VideoAspectRatioOverride) {
        prefs.edit { putString("video_aspect_ratio_override", override.name) }
    }

    fun getVideoAspectRatioOverride(): VideoAspectRatioOverride = VideoAspectRatioOverride.fromStored(
        prefs.getString("video_aspect_ratio_override", VideoAspectRatioOverride.ORIGINAL.name)
    )

    fun getVideoZoom(): Float = prefs.getFloat("video_zoom", 1f).coerceIn(1f, 3f)

    fun saveVideoZoom(zoom: Float) {
        prefs.edit { putFloat("video_zoom", zoom.coerceIn(1f, 3f)) }
    }

    fun getVideoPanX(): Float = prefs.getFloat("video_pan_x", 0f)

    fun saveVideoPanX(value: Float) {
        prefs.edit { putFloat("video_pan_x", value) }
    }

    fun getVideoPanY(): Float = prefs.getFloat("video_pan_y", 0f)

    fun saveVideoPanY(value: Float) {
        prefs.edit { putFloat("video_pan_y", value) }
    }

    /** Per-title picture settings so each channel/file remembers its own framing. */
    fun getTitlePictureSettings(title: String): TitlePictureSettings? {
        val key = pictureKey(title) ?: return null
        val raw = prefs.getString(key, null) ?: return null
        val parts = raw.split('|')
        if (parts.size != 5) return null
        return TitlePictureSettings(
            resizeMode = VideoResizeMode.fromStored(parts[0]),
            aspectOverride = VideoAspectRatioOverride.fromStored(parts[1]),
            zoom = sanitizeZoom(parts[2].toFloatOrNull()),
            panX = sanitizePan(parts[3].toFloatOrNull()),
            panY = sanitizePan(parts[4].toFloatOrNull()),
        )
    }

    fun saveTitlePictureSettings(title: String, settings: TitlePictureSettings) {
        val key = pictureKey(title) ?: return
        val raw = listOf(
            settings.resizeMode.name,
            settings.aspectOverride.name,
            settings.zoom.toString(),
            settings.panX.toString(),
            settings.panY.toString(),
        ).joinToString("|")
        if (prefs.getString(key, null) != raw) prefs.edit { putString(key, raw) }
        touchTitleKey(key)
    }

    /**
     * Records that a per-title key was just written and, when a new title pushes the stored
     * count over [MAX_TITLE_KEYS], drops the least recently written ones. Without this every
     * file or stream ever opened kept its position, framing and subtitle offset forever.
     */
    private fun touchTitleKey(key: String) {
        val now = System.currentTimeMillis()
        val previous = titleRecency.getLong(key, Long.MIN_VALUE)
        val isNew = previous == Long.MIN_VALUE
        // The position checkpoint calls this every 10 s during playback. Each apply() rewrites
        // the whole recency file (up to 1,500 rows), yet LRU eviction only needs coarse
        // ordering, so a timestamp younger than RECENCY_REFRESH_MS is left as it is.
        if (isNew || now - previous !in 0 until RECENCY_REFRESH_MS) {
            titleRecency.edit { putLong(key, now) }
        }
        if (isNew) pruneTitleKeysIfNeeded()
    }

    /**
     * Hex SHA-256 of [value], memoised for the last few inputs. The playback checkpoint hashes the
     * same URI every 10 s; the old `joinToString { "%02x".format(it) }` built 32 formatters each time.
     */
    private fun sha256Hex(value: String): String {
        synchronized(hashCache) { hashCache[value]?.let { return it } }
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        val chars = CharArray(digest.size * 2)
        digest.forEachIndexed { i, byte ->
            val v = byte.toInt() and 0xFF
            chars[i * 2] = HEX_DIGITS[v ushr 4]
            chars[i * 2 + 1] = HEX_DIGITS[v and 0x0F]
        }
        val hex = String(chars)
        synchronized(hashCache) { hashCache[value] = hex }
        return hex
    }

    private val hashCache = object : LinkedHashMap<String, String>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean =
            size > HASH_CACHE_SIZE
    }

    private fun pruneTitleKeysIfNeeded() {
        val titleKeys = prefs.all.keys.filter(::isTitleKey)
        if (titleKeys.size <= MAX_TITLE_KEYS) return
        val recency = titleRecency.all
        val lastUsed = titleKeys.associateWith { key -> (recency[key] as? Long) ?: 0L }
        val evicted = selectTitleKeysToEvict(lastUsed, TITLE_KEYS_AFTER_PRUNE)
        if (evicted.isEmpty()) return
        prefs.edit { evicted.forEach(::remove) }
        titleRecency.edit {
            evicted.forEach(::remove)
            // Forget recency rows whose preference was removed by other code paths.
            recency.keys.filter { it !in lastUsed }.forEach(::remove)
        }
    }

    private fun sanitizeZoom(value: Float?): Float =
        value?.takeIf(Float::isFinite)?.coerceIn(1f, 3f) ?: 1f

    private fun sanitizePan(value: Float?): Float =
        value?.takeIf(Float::isFinite)?.coerceIn(-1f, 1f) ?: 0f

    private fun pictureKey(title: String): String? {
        val trimmed = title.trim()
        if (trimmed.isEmpty()) return null
        return "pic_" + sha256Hex(trimmed)
    }

    data class TitlePictureSettings(
        val resizeMode: VideoResizeMode = VideoResizeMode.FIT,
        val aspectOverride: VideoAspectRatioOverride = VideoAspectRatioOverride.ORIGINAL,
        val zoom: Float = 1f,
        val panX: Float = 0f,
        val panY: Float = 0f,
    )

    private fun videoPositionKey(uri: String): String {
        return "pos_" + sha256Hex(uri)
    }

    private fun subtitleSyncOffsetKey(uri: String): String {
        return "sub_sync_" + sha256Hex(uri)
    }

    private fun validatedLanguage(value: String?, fallback: String): String {
        val language = value?.lowercase(Locale.ROOT) ?: return fallback
        return language.takeIf { LANGUAGE_PATTERN.matches(it) } ?: fallback
    }

    private fun AndroidKeystoreSecretStore.putOrRemove(key: String, value: String) {
        if (value.isBlank()) remove(key) else putString(key, value)
    }

    private object SecureSessionStore {
        @Volatile var username: String = ""
        @Volatile var token: String = ""
        @Volatile var libreTranslateApiKey: String = ""
        @Volatile var deepLAuthKey: String = ""
        @Volatile var ollamaBearerToken: String = ""
        @Volatile var openSubtitlesApiKey: String = ""
        @Volatile var subsRoApiKey: String = ""
        @Volatile var subDlApiKey: String = ""
        @Volatile var tmdbApiKey: String = ""
    }

    companion object {
        private const val PREFERENCES_NAME = "oxigen_prefs"
        private const val TITLE_RECENCY_NAME = "oxigen_title_recency"

        /** About 500 titles when each has a position, framing and subtitle offset. */
        const val MAX_TITLE_KEYS = 1_500
        private const val RECENCY_REFRESH_MS = 60L * 60L * 1_000L
        private const val HASH_CACHE_SIZE = 8
        private val HEX_DIGITS = "0123456789abcdef".toCharArray()
        const val TITLE_KEYS_AFTER_PRUNE = 1_200
        private val TITLE_KEY_PREFIXES = listOf("pos_", "pic_", "sub_sync_")

        fun isTitleKey(key: String): Boolean = TITLE_KEY_PREFIXES.any(key::startsWith)
        private const val APP_LANGUAGE_KEY = "app_language"
        private const val DEFAULT_APP_LANGUAGE = "ro"
        private val SECURE_STORE_LOCK = Any()
        val SUPPORTED_APP_LANGUAGES = setOf("ro", "en", "es", "fr", "de", "it", "pt", "ru")
        val LANGUAGE_PATTERN = Regex("^[a-z]{2,3}$")
        val SEARCH_LANGUAGE_PATTERN = Regex("^[a-z]{2,3}(,[a-z]{2,3})?$")

        fun readStoredLanguage(context: Context): String {
            val stored = context.applicationContext
                .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
                .getString(APP_LANGUAGE_KEY, DEFAULT_APP_LANGUAGE)
                ?: DEFAULT_APP_LANGUAGE
            return stored.takeIf { it in SUPPORTED_APP_LANGUAGES } ?: DEFAULT_APP_LANGUAGE
        }
    }
}

/**
 * Picks the least recently written per-title keys so that [keepCount] remain. Keys with no
 * recorded time (written by versions before the recency index existed) count as oldest.
 */
internal fun selectTitleKeysToEvict(lastUsedByKey: Map<String, Long>, keepCount: Int): List<String> {
    val excess = lastUsedByKey.size - keepCount.coerceAtLeast(0)
    if (excess <= 0) return emptyList()
    return lastUsedByKey.entries
        .sortedWith(compareBy<Map.Entry<String, Long>> { it.value }.thenBy { it.key })
        .take(excess)
        .map { it.key }
}
