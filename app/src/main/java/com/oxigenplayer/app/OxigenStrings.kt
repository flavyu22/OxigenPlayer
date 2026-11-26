// SPDX-License-Identifier: GPL-3.0-only
package com.oxigenplayer.app

import android.content.Context
import android.content.res.Configuration
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Compatibility facade over Android string resources.
 *
 * Existing call sites pass an explicit app-language tag. The text itself now lives in
 * localized Android string-resource directories, so Android Lint, translators and per-app locale tooling can validate it.
 */
object OxigenStrings {
    /** Used when the caller passes a tag outside [supportedLanguages]; mirrors the picker's fallback. */
    private const val FALLBACK_LANGUAGE = "en"

    @Volatile
    private var applicationContext: Context? = null
    private val localizedContexts = ConcurrentHashMap<String, Context>()

    private val resourceIds: Map<String, Int> = mapOf(
        "settings_title" to R.string.settings_title,
        "translate_all" to R.string.translate_all,
        "load_subtitles" to R.string.load_subtitles,
        "translating" to R.string.translating,
        "translation_settings" to R.string.translation_settings,
        "style_settings" to R.string.style_settings,
        "language_settings" to R.string.language_settings,
        "about_dev_btn" to R.string.about_dev_btn,
        "close_dialog" to R.string.close_dialog,
        "translation_label" to R.string.translation_label,
        "enable_translation_label" to R.string.enable_translation_label,
        "translation_engine" to R.string.translation_engine,
        "source_lang" to R.string.source_lang,
        "target_lang" to R.string.target_lang,
        "change" to R.string.change,
        "style_label" to R.string.style_label,
        "font_size_label" to R.string.font_size_label,
        "sync_label" to R.string.sync_label,
        "subtitle_sync_label" to R.string.subtitle_sync_label,
        "subtitle_sync_display" to R.string.subtitle_sync_display,
        "subtitle_sync_status" to R.string.subtitle_sync_status,
        "auto_sync_btn" to R.string.auto_sync_btn,
        "auto_sync_mark_audio" to R.string.auto_sync_mark_audio,
        "auto_sync_mark_subtitle" to R.string.auto_sync_mark_subtitle,
        "auto_sync_instruction" to R.string.auto_sync_instruction,
        "auto_sync_result" to R.string.auto_sync_result,
        "auto_sync_apply" to R.string.auto_sync_apply,
        "auto_sync_cancel" to R.string.auto_sync_cancel,
        "subtitle_sync_hint" to R.string.subtitle_sync_hint,
        "subtitle_sync_open" to R.string.subtitle_sync_open,
        "sync_button" to R.string.sync_button,
        "error_no_permission" to R.string.error_no_permission,
        "error_not_found" to R.string.error_not_found,
        "error_network" to R.string.error_network,
        "error_unsupported_format" to R.string.error_unsupported_format,
        "error_decoder" to R.string.error_decoder,
        "language_label" to R.string.language_label,
        "close_btn" to R.string.close_btn,
        "about_dev_title" to R.string.about_dev_title,
        "about_dev_text" to R.string.about_dev_text,
        "done_btn" to R.string.done_btn,
        "tracks_title" to R.string.tracks_title,
        "automatic_track" to R.string.automatic_track,
        "track_number" to R.string.track_number,
        "select_subtitle_file" to R.string.select_subtitle_file,
        "edge_style_label" to R.string.edge_style_label,
        "none" to R.string.none,
        "outline" to R.string.outline,
        "shadow" to R.string.shadow,
        "video_subtitles" to R.string.video_subtitles,
        "device_subtitles" to R.string.device_subtitles,
        "disable_subtitles" to R.string.disable_subtitles,
        "select_media" to R.string.select_media,
        "media_label" to R.string.media_label,
        "media_browser_title" to R.string.media_browser_title,
        "media_file_count" to R.string.media_file_count,
        "all_storage" to R.string.all_storage,
        "device_storage" to R.string.device_storage,
        "external_storage" to R.string.external_storage,
        "refresh_media" to R.string.refresh_media,
        "system_picker" to R.string.system_picker,
        "media_access_explanation" to R.string.media_access_explanation,
        "request_media_access" to R.string.request_media_access,
        "media_access_partial" to R.string.media_access_partial,
        "change_media_access" to R.string.change_media_access,
        "no_media_files" to R.string.no_media_files,
        "media_load_error" to R.string.media_load_error,
        "video_label" to R.string.video_label,
        "settings_label" to R.string.settings_label,
        "online_search" to R.string.online_search,
        "online_search_privacy" to R.string.online_search_privacy,
        "voice_search" to R.string.voice_search,
        "search" to R.string.search,
        "movie_name_placeholder" to R.string.movie_name_placeholder,
        "enter_name_search" to R.string.enter_name_search,
        "no_subtitle_results" to R.string.no_subtitle_results,
        "speak_now" to R.string.speak_now,
        "audio_label" to R.string.audio_label,
        "subtitles_label" to R.string.subtitles_label,
        "search_lang_label" to R.string.search_lang_label,
        "lang_ro" to R.string.lang_ro,
        "lang_en" to R.string.lang_en,
        "lang_both" to R.string.lang_both,
        "lang_more" to R.string.lang_more,
        "select_search_lang" to R.string.select_search_lang,
        "update_available" to R.string.update_available,
        "new_version_available" to R.string.new_version_available,
        "install_btn" to R.string.install_btn,
        "later_btn" to R.string.later_btn,
        "check_update" to R.string.check_update,
        "no_update" to R.string.no_update,
        "resume_movie_title" to R.string.resume_movie_title,
        "resume_movie_question" to R.string.resume_movie_question,
        "resume_btn" to R.string.resume_btn,
        "restart_btn" to R.string.restart_btn,
        "grant_storage_permission" to R.string.grant_storage_permission,
        "saved_to" to R.string.saved_to,
        "save_error" to R.string.save_error,
        "opensubtitles_account" to R.string.opensubtitles_account,
        "provider_credentials" to R.string.provider_credentials,
        "save_provider_credentials" to R.string.save_provider_credentials,
        "provider_credentials_saved" to R.string.provider_credentials_saved,
        "api_keys_private_notice" to R.string.api_keys_private_notice,
        "secure_storage_session_only" to R.string.secure_storage_session_only,
        "provider_credentials_saved_session" to R.string.provider_credentials_saved_session,
        "deepl_api_key" to R.string.deepl_api_key,
        "provider_api_key" to R.string.provider_api_key,
        "playback_label" to R.string.playback_label,
        "username" to R.string.username,
        "password" to R.string.password,
        "show_password" to R.string.show_password,
        "hide_password" to R.string.hide_password,
        "provider_unavailable" to R.string.provider_unavailable,
        "back" to R.string.back,
        "update_disabled" to R.string.update_disabled,
        "update_network_error" to R.string.update_network_error,
        "update_invalid_metadata" to R.string.update_invalid_metadata,
        "cloud_translation_privacy" to R.string.cloud_translation_privacy,
        "retry" to R.string.retry,
        "search_failed" to R.string.search_failed,
        "voice_search_unavailable" to R.string.voice_search_unavailable,
        "subtitle_processing_error" to R.string.subtitle_processing_error,
        "download_failed" to R.string.download_failed,
        "login_success" to R.string.login_success,
        "enter_credentials" to R.string.enter_credentials,
        "invalid_credentials" to R.string.invalid_credentials,
        "confirm_save" to R.string.confirm_save,
        "sign_out" to R.string.sign_out,
        "save_translated_subtitle" to R.string.save_translated_subtitle,
        "translated_progress" to R.string.translated_progress,
        "adjusting_suffix" to R.string.adjusting_suffix,
        "vertical_position" to R.string.vertical_position,
        "playback_settings" to R.string.playback_settings,
        "playback_speed" to R.string.playback_speed,
        "playback_speed_help" to R.string.playback_speed_help,
        "video_quality_profile" to R.string.video_quality_profile,
        "quality_auto" to R.string.quality_auto,
        "quality_uhd_4k" to R.string.quality_uhd_4k,
        "quality_full_hd" to R.string.quality_full_hd,
        "quality_hd" to R.string.quality_hd,
        "quality_data_saver" to R.string.quality_data_saver,
        "match_content_frame_rate" to R.string.match_content_frame_rate,
        "match_content_frame_rate_help" to R.string.match_content_frame_rate_help,
        "repeat_current_media" to R.string.repeat_current_media,
        "repeat_current_media_help" to R.string.repeat_current_media_help,
        "playback_diagnostics" to R.string.playback_diagnostics,
        "playback_diagnostics_help" to R.string.playback_diagnostics_help,
        "state_label" to R.string.state_label,
        "state_buffering" to R.string.state_buffering,
        "state_ended" to R.string.state_ended,
        "state_idle" to R.string.state_idle,
        "sleep_timer" to R.string.sleep_timer,
        "sleep_timer_finished" to R.string.sleep_timer_finished,
        "decoder_overloaded" to R.string.decoder_overloaded,
        "off" to R.string.off,
        "minutes_format" to R.string.minutes_format,
        "lan_edition_warning" to R.string.lan_edition_warning,
        "developed_by" to R.string.developed_by,
        "about_description" to R.string.about_description,
        "update_in_progress" to R.string.update_in_progress,
        "update_failed" to R.string.update_failed,
        "update_download_failed" to R.string.update_download_failed,
        "update_timed_out" to R.string.update_timed_out,
        "update_storage_failed" to R.string.update_storage_failed,
        "update_verification_failed" to R.string.update_verification_failed,
        "update_install_permission_required" to R.string.update_install_permission_required,
        "update_installer_unavailable" to R.string.update_installer_unavailable,
        "update_local_state_failed" to R.string.update_local_state_failed,
        "subtitle_saved_success" to R.string.subtitle_saved_success,
        "subtitle_permission_denied" to R.string.subtitle_permission_denied,
        "subtitle_cannot_open" to R.string.subtitle_cannot_open,
        "subtitle_too_large" to R.string.subtitle_too_large,
        "subtitle_empty_invalid" to R.string.subtitle_empty_invalid,
        "subtitle_read_failed" to R.string.subtitle_read_failed,
        "mlkit_tv_unavailable" to R.string.mlkit_tv_unavailable,
        "gemini_nano_device_note" to R.string.gemini_nano_device_note,
        "free_translation_servers" to R.string.free_translation_servers,
        "libretranslate_server_url" to R.string.libretranslate_server_url,
        "libretranslate_api_key" to R.string.libretranslate_api_key,
        "mozhi_server_url" to R.string.mozhi_server_url,
        "mozhi_engine" to R.string.mozhi_engine,
        "ollama_server_url" to R.string.ollama_server_url,
        "ollama_model" to R.string.ollama_model,
        "optional_token" to R.string.optional_token,
        "local_server_hint" to R.string.local_server_hint,
        "save_translation_servers" to R.string.save_translation_servers,
        "invalid_server_url" to R.string.invalid_server_url,
        "press_back_again" to R.string.press_back_again,
        "press_again_to_close_total" to R.string.press_again_to_close_total,
        "external_media_not_allowed" to R.string.external_media_not_allowed,
        "session_only_access" to R.string.session_only_access,
        "video_picker_unavailable" to R.string.video_picker_unavailable,
        "version_label" to R.string.version_label,
        "license_label" to R.string.license_label,
        "playback_position" to R.string.playback_position,
        "live_label" to R.string.live_label,
        "seeking_label" to R.string.seeking_label,
        "buffered_label" to R.string.buffered_label,
        "setting_value" to R.string.setting_value,
        "subtitle_background" to R.string.subtitle_background,
        "play" to R.string.play,
        "pause" to R.string.pause,
        "playing" to R.string.playing,
        "paused" to R.string.paused,
        "transparent" to R.string.transparent,
        "white" to R.string.white,
        "yellow" to R.string.yellow,
        "cyan" to R.string.cyan,
        "green" to R.string.green,
        "red" to R.string.red,
        "magenta" to R.string.magenta,
        "black" to R.string.black,
        "gray" to R.string.gray,
        "custom_color" to R.string.custom_color,
        "playback_service_unavailable" to R.string.playback_service_unavailable,
        "shared_storage" to R.string.shared_storage,
        "subtitle_write_failed" to R.string.subtitle_write_failed,
        "skip_silence" to R.string.skip_silence,
        "skip_silence_help" to R.string.skip_silence_help,
        "video_resize_mode" to R.string.video_resize_mode,
        "video_resize_mode_fit" to R.string.video_resize_mode_fit,
        "video_resize_mode_fill" to R.string.video_resize_mode_fill,
        "video_resize_mode_stretch" to R.string.video_resize_mode_stretch,
        "aspect_ratio_override" to R.string.aspect_ratio_override,
        "aspect_ratio_original" to R.string.aspect_ratio_original,
        "zoom" to R.string.zoom,
        "audio_offload" to R.string.audio_offload,
        "audio_offload_help" to R.string.audio_offload_help,
        "audio_delay" to R.string.audio_delay,
        "audio_delay_help" to R.string.audio_delay_help,
        "preferred_audio_language" to R.string.preferred_audio_language,
        "audio_language_auto" to R.string.audio_language_auto,
        "ab_repeat" to R.string.ab_repeat,
        "ab_repeat_set_end" to R.string.ab_repeat_set_end,
        "ab_repeat_clear" to R.string.ab_repeat_clear,
        "open_stream" to R.string.open_stream,
        "stream_url_label" to R.string.stream_url_label,
        "invalid_stream_url" to R.string.invalid_stream_url,
    )

    /**
     * Delegates to [PreferencesManager.SUPPORTED_APP_LANGUAGES] rather than repeating the list.
     * The two copies could drift, which would make a language selectable in the picker while
     * this facade quietly fell back to English for it.
     */
    internal val supportedLanguages: Set<String> get() = PreferencesManager.SUPPORTED_APP_LANGUAGES

    /**
     * Resolved strings memoised per language tag and resource id.
     *
     * The cache has exactly the lifetime of [localizedContexts] — both are dropped together in
     * [initialize] — so a cached value can never be staler than the `Context` it was resolved
     * from. See [LocalizedStringCache] for the full rationale.
     */
    private val resolvedStrings = LocalizedStringCache()

    fun initialize(context: Context) {
        applicationContext = context.applicationContext
        localizedContexts.clear()
        resolvedStrings.clear()
    }

    fun get(lang: String, key: String): String {
        val resourceId = resourceIds[key] ?: return key
        val normalized = lang.takeIf { it in supportedLanguages } ?: FALLBACK_LANGUAGE
        // Hot path: two allocation-free hash lookups instead of a JNI resource lookup that
        // allocates a new String on every recomposition.
        resolvedStrings[normalized, resourceId]?.let { return it }
        // A missing Context deliberately does NOT cache the verbatim-key fallback: the real
        // translation must still resolve once OxigenApp.onCreate installs the application context.
        val resolved = resolve(normalized, resourceId) ?: return key
        return resolvedStrings.put(normalized, resourceId, resolved)
    }

    /** Resolves one resource id against the per-language configuration context. */
    private fun resolve(lang: String, resourceId: Int): String? {
        val context = applicationContext ?: return null
        val localized = localizedContexts.getOrPut(lang) {
            val configuration = Configuration(context.resources.configuration).apply {
                setLocale(Locale.forLanguageTag(lang))
            }
            context.createConfigurationContext(configuration)
        }
        return localized.getString(resourceId)
    }

    internal fun keysFor(lang: String): Set<String> =
        if (lang in supportedLanguages) resourceIds.keys else emptySet()
}
