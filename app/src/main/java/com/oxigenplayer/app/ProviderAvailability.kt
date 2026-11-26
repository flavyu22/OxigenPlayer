// SPDX-License-Identifier: GPL-3.0-only
package com.oxigenplayer.app

/** Central source of truth for build-time and runtime provider availability. */
object ProviderAvailability {
    val openSubtitles: Boolean
        get() = BuildConfig.OS_API_KEY.isNotBlank()

    val subsRo: Boolean
        get() = BuildConfig.SUBS_RO_API_KEY.isNotBlank()

    val subDl: Boolean
        get() = BuildConfig.SUBDL_API_KEY.isNotBlank()

    val tmdb: Boolean
        get() = BuildConfig.TMDB_API_KEY.isNotBlank()

    val deepL: Boolean
        get() = BuildConfig.DEEPL_AUTH_KEY.isNotBlank()

    fun supports(
        source: TranslationSource,
        config: TranslationProviderConfig = TranslationProviderConfig(),
        mlKitAllowed: Boolean = true,
        geminiNanoAvailable: Boolean = false,
    ): Boolean = when (source) {
        TranslationSource.AUTO,
        TranslationSource.MY_MEMORY -> true
        TranslationSource.MLKIT -> mlKitAllowed
        TranslationSource.GEMINI_NANO -> geminiNanoAvailable
        TranslationSource.LIBRE_TRANSLATE -> config.normalized().isLibreTranslateConfigured
        TranslationSource.OLLAMA -> config.normalized().isOllamaConfigured
        TranslationSource.MOZHI -> config.normalized().isMozhiConfigured
        TranslationSource.DEEPL -> config.normalized().deepLAuthKey.isNotBlank() || deepL
    }

    fun safeTranslationSource(
        source: TranslationSource,
        config: TranslationProviderConfig = TranslationProviderConfig(),
        mlKitAllowed: Boolean = true,
        geminiNanoAvailable: Boolean = false,
    ): TranslationSource = source.takeIf {
        supports(it, config, mlKitAllowed, geminiNanoAvailable)
    }
        ?: TranslationSource.AUTO
}
