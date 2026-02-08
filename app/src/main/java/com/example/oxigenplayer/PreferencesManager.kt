package com.example.oxigenplayer

import android.content.Context
import android.content.SharedPreferences

class PreferencesManager(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("oxigen_prefs", Context.MODE_PRIVATE)

    fun saveLogin(user: String, pass: String) {
        prefs.edit().apply {
            putString("os_user", user)
            putString("os_pass", pass)
            apply()
        }
    }

    fun getUsername(): String = prefs.getString("os_user", "") ?: ""
    fun getPassword(): String = prefs.getString("os_pass", "") ?: ""
    
    fun saveToken(token: String) {
        prefs.edit().putString("os_token", token).apply()
    }
    
    fun getToken(): String = prefs.getString("os_token", "") ?: ""

    fun saveVideoPosition(uri: String, position: Long) {
        prefs.edit().putLong("pos_$uri", position).apply()
    }

    fun getVideoPosition(uri: String): Long {
        return prefs.getLong("pos_$uri", 0L)
    }

    // Subtitle Customization
    fun saveSubtitleFontSize(size: Float) {
        prefs.edit().putFloat("sub_font_size", size).apply()
    }
    fun getSubtitleFontSize(): Float = prefs.getFloat("sub_font_size", 36f)

    fun saveSubtitleColor(color: Int) {
        prefs.edit().putInt("sub_color", color).apply()
    }
    fun getSubtitleColor(): Int = prefs.getInt("sub_color", -1) // -1 is white in ARGB

    fun saveSubtitleBackgroundColor(color: Int) {
        prefs.edit().putInt("sub_bg_color", color).apply()
    }
    fun getSubtitleBackgroundColor(): Int = prefs.getInt("sub_bg_color", 0x00000000) // Transparent

    fun saveAppLanguage(lang: String) {
        prefs.edit().putString("app_language", lang).apply()
    }
    fun getAppLanguage(): String = prefs.getString("app_language", "ro") ?: "ro"

    fun saveSubtitlesVisible(visible: Boolean) {
        prefs.edit().putBoolean("subtitles_visible", visible).apply()
    }

    fun setSubtitlesVisible(visible: Boolean) {
        saveSubtitlesVisible(visible)
    }

    fun isSubtitlesVisible(): Boolean = prefs.getBoolean("subtitles_visible", true)

    fun saveSearchLanguage(lang: String) {
        prefs.edit().putString("search_language", lang).apply()
    }
    fun getSearchLanguage(): String = prefs.getString("search_language", "ro,en") ?: "ro,en"

    fun saveTranslationSource(source: String) {
        prefs.edit().putString("trans_source", source).apply()
    }
    fun getTranslationSource(): String = prefs.getString("trans_source", "MLKIT") ?: "MLKIT"

    fun saveSourceLanguage(lang: String) {
        prefs.edit().putString("source_lang", lang).apply()
    }
    fun getSourceLanguage(): String = prefs.getString("source_lang", "en") ?: "en"

    fun saveTargetLanguage(lang: String) {
        prefs.edit().putString("target_lang", lang).apply()
    }
    fun getTargetLanguage(): String = prefs.getString("target_lang", "ro") ?: "ro"
}
