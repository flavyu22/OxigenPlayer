// SPDX-License-Identifier: GPL-3.0-only

package com.oxigenplayer.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.key.KeyEvent as ComposeKeyEvent
import android.view.KeyEvent as NativeKeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.launch
import com.oxigenplayer.app.*

@Composable
fun MainSettingsDialog(
    onDismiss: () -> Unit,
    isTranslationEnabled: Boolean,
    onTranslationToggle: (Boolean) -> Unit,
    translationSource: TranslationSource,
    onSourceChange: (TranslationSource) -> Unit,
    translationProviderConfig: TranslationProviderConfig,
    mlKitAllowed: Boolean,
    geminiNanoIncluded: Boolean,
    geminiNanoAvailable: Boolean,
    onTranslationProviderConfigChange: (TranslationProviderConfig) -> Unit,
    sourceLang: String,
    onSourceLangChange: (String) -> Unit,
    subtitleFontSize: Float,
    onFontSizeChange: (Float) -> Unit,
    subtitleVerticalOffset: Float,
    onVerticalOffsetChange: (Float) -> Unit,
    subtitleSyncOffset: Long,
    onSyncOffsetChange: (Long) -> Unit,
    subtitleColor: Color,
    onColorChange: (Color) -> Unit,
    subtitleBgColor: Color,
    onBgColorChange: (Color) -> Unit,
    subtitleEdgeStyle: SubtitleEdgeStyle, onEdgeStyleChange: (SubtitleEdgeStyle) -> Unit,
    targetLang: String,
    onTargetLangChange: (String) -> Unit,
    playbackSpeed: Float,
    onPlaybackSpeedChange: (Float) -> Unit,
    videoQualityProfile: VideoQualityProfile,
    onVideoQualityProfileChange: (VideoQualityProfile) -> Unit,
    matchContentFrameRate: Boolean,
    onMatchContentFrameRateChange: (Boolean) -> Unit,
    audioOffloadEnabled: Boolean,
    onAudioOffloadChange: (Boolean) -> Unit,
    preferredAudioLanguage: String,
    onPreferredAudioLanguageChange: (String) -> Unit,
    audioDelayMs: Int,
    onAudioDelayChange: (Int) -> Unit,
    repeatCurrentMedia: Boolean,
    onRepeatCurrentMediaChange: (Boolean) -> Unit,
    skipSilenceEnabled: Boolean,
    autoSyncState: AutoSyncState,
    onStartAutoSync: () -> Unit,
    onMarkAutoSyncAudio: () -> Unit,
    onMarkAutoSyncSubtitle: () -> Unit,
    onApplyAutoSync: () -> Unit,
    onCancelAutoSync: () -> Unit,
    onSkipSilenceChange: (Boolean) -> Unit,
    videoResizeMode: VideoResizeMode,
    onVideoResizeModeChange: (VideoResizeMode) -> Unit,
    aspectRatioOverride: VideoAspectRatioOverride,
    onAspectRatioOverrideChange: (VideoAspectRatioOverride) -> Unit,
    videoZoom: Float,
    videoPanX: Float,
    videoPanY: Float,
    onZoomChange: (Float) -> Unit,
    onPanChange: (Float, Float) -> Unit,
    showDiagnostics: Boolean,
    onShowDiagnosticsChange: (Boolean) -> Unit,
    sleepTimerMinutes: Int,
    onSleepTimerMinutesChange: (Int) -> Unit,
    availableLanguages: List<String>,
    onAppLanguageChange: (String) -> Unit,
    currentAppLang: String,
    onShowAbout: () -> Unit,
    externalSubtitles: List<SubtitleEntry>,
    translatedMapSize: Int,
    isTranslatingAll: Boolean,
    onTranslateAll: () -> Unit,
    onSaveTranslated: () -> Unit,
    onCheckUpdate: () -> Unit
) {
    var activeCategory by remember { mutableStateOf<String?>(null) }
    val categoryFocusRequester = remember { FocusRequester() }
    val subcategoryFocusRequester = remember { FocusRequester() }

    LaunchedEffect(activeCategory) {
        // Dialog content is hosted in a separate window. Waiting for the next frame
        // ensures that the newly selected screen has attached its focus target.
        withFrameNanos { }
        val requester = if (activeCategory == null) {
            categoryFocusRequester
        } else {
            subcategoryFocusRequester
        }
        runCatching { requester.requestFocus() }
    }

    FastDialog(
        onDismiss = {
            if (activeCategory != null) activeCategory = null else onDismiss()
        },
    ) {
        OxigenCard(
            modifier = Modifier
                .standardDialogCardSize(),
        ) {
            Box(modifier = Modifier.fillMaxSize().padding(8.dp)) {
                if (activeCategory == null) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .verticalScroll(rememberScrollState())
                                .padding(horizontal = 16.dp, vertical = 4.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(OxigenStrings.get(currentAppLang, "settings_title"), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)

                            CategoryButton(
                                text = OxigenStrings.get(currentAppLang, "translation_label"),
                                icon = Icons.Default.Translate,
                                focusRequester = categoryFocusRequester,
                            ) { activeCategory = "translation" }
                            CategoryButton(OxigenStrings.get(currentAppLang, "style_label"), Icons.Default.ColorLens) { activeCategory = "style" }
                            CategoryButton(OxigenStrings.get(currentAppLang, "playback_label"), Icons.Default.Speed) { activeCategory = "playback" }
                            CategoryButton(OxigenStrings.get(currentAppLang, "language_label"), Icons.Default.Language) { activeCategory = "lang" }
                            CategoryButton(
                                OxigenStrings.get(currentAppLang, "provider_credentials"),
                                Icons.Default.VpnKey,
                            ) { activeCategory = "account" }

                            Spacer(modifier = Modifier.height(8.dp))

                            // Fix double click for About button
                            Box(modifier = Modifier.fillMaxWidth().tvFocusable(onClick = onShowAbout)) {
                                Text(
                                    OxigenStrings.get(currentAppLang, "about_dev_btn"),
                                    modifier = Modifier.padding(8.dp).align(Alignment.Center),
                                    color = Color.White
                                )
                            }

                            if (BuildConfig.DIRECT_UPDATES_ENABLED) {
                                Box(modifier = Modifier.fillMaxWidth().tvFocusable(onClick = onCheckUpdate)) {
                                    Text(
                                        OxigenStrings.get(currentAppLang, "check_update"),
                                        modifier = Modifier.padding(8.dp).align(Alignment.Center),
                                        color = MaterialTheme.colorScheme.secondary
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(4.dp))

                        // Fix double click for Close button
                        Box(modifier = Modifier.align(Alignment.End).tvFocusable(onClick = onDismiss)) {
                            Text(
                                OxigenStrings.get(currentAppLang, "close_btn"),
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                color = Color.White
                            )
                        }
                    }
                } else {
                    when (activeCategory) {
                        "translation" -> TranslationSettingsContent(
                            onBack = { activeCategory = null },
                            initialFocusRequester = subcategoryFocusRequester,
                            isTranslationEnabled = isTranslationEnabled,
                            onTranslationToggle = onTranslationToggle,
                            translationSource = translationSource,
                            onSourceChange = onSourceChange,
                            translationProviderConfig = translationProviderConfig,
                            mlKitAllowed = mlKitAllowed,
                            geminiNanoIncluded = geminiNanoIncluded,
                            geminiNanoAvailable = geminiNanoAvailable,
                            onTranslationProviderConfigChange = onTranslationProviderConfigChange,
                            sourceLang = sourceLang,
                            onSourceLangChange = onSourceLangChange,
                            targetLang = targetLang,
                            onTargetLangChange = onTargetLangChange,
                            availableLanguages = availableLanguages,
                            currentAppLang = currentAppLang,
                            externalSubtitles = externalSubtitles,
                            translatedMapSize = translatedMapSize,
                            isTranslatingAll = isTranslatingAll,
                            onTranslateAll = onTranslateAll,
                            onSaveTranslated = onSaveTranslated
                        )
                        "style" -> SubtitleStyleContent(
                            onBack = { activeCategory = null },
                            initialFocusRequester = subcategoryFocusRequester,
                            subtitleFontSize = subtitleFontSize,
                            onFontSizeChange = onFontSizeChange,
                            subtitleVerticalOffset = subtitleVerticalOffset,
                            onVerticalOffsetChange = onVerticalOffsetChange,
                            subtitleSyncOffset = subtitleSyncOffset,
                            onSyncOffsetChange = onSyncOffsetChange,
                            subtitleColor = subtitleColor,
                            onColorChange = onColorChange,
                            subtitleBgColor = subtitleBgColor,
                            onBgColorChange = onBgColorChange,
                            subtitleEdgeStyle = subtitleEdgeStyle,
                            onEdgeStyleChange = onEdgeStyleChange,
                            autoSyncState = autoSyncState,
                            onStartAutoSync = onStartAutoSync,
                            onMarkAutoSyncAudio = onMarkAutoSyncAudio,
                            onMarkAutoSyncSubtitle = onMarkAutoSyncSubtitle,
                            onApplyAutoSync = onApplyAutoSync,
                            onCancelAutoSync = onCancelAutoSync,
                            currentAppLang = currentAppLang
                        )
                        "playback" -> PlaybackSettingsContent(
                            onBack = { activeCategory = null },
                            initialFocusRequester = subcategoryFocusRequester,
                            playbackSpeed = playbackSpeed,
                            onPlaybackSpeedChange = onPlaybackSpeedChange,
                            videoQualityProfile = videoQualityProfile,
                            onVideoQualityProfileChange = onVideoQualityProfileChange,
                            matchContentFrameRate = matchContentFrameRate,
                            onMatchContentFrameRateChange = onMatchContentFrameRateChange,
                            audioOffloadEnabled = audioOffloadEnabled,
                            onAudioOffloadChange = onAudioOffloadChange,
                            preferredAudioLanguage = preferredAudioLanguage,
                            onPreferredAudioLanguageChange = onPreferredAudioLanguageChange,
                            audioDelayMs = audioDelayMs,
                            onAudioDelayChange = onAudioDelayChange,
                            repeatCurrentMedia = repeatCurrentMedia,
                            onRepeatCurrentMediaChange = onRepeatCurrentMediaChange,
                            skipSilenceEnabled = skipSilenceEnabled,
                            onSkipSilenceChange = onSkipSilenceChange,
                            videoResizeMode = videoResizeMode,
                            onVideoResizeModeChange = onVideoResizeModeChange,
                            aspectRatioOverride = aspectRatioOverride,
                            onAspectRatioOverrideChange = onAspectRatioOverrideChange,
                            videoZoom = videoZoom,
                            videoPanX = videoPanX,
                            videoPanY = videoPanY,
                            onZoomChange = onZoomChange,
                            onPanChange = onPanChange,
                            showDiagnostics = showDiagnostics,
                            onShowDiagnosticsChange = onShowDiagnosticsChange,
                            sleepTimerMinutes = sleepTimerMinutes,
                            onSleepTimerMinutesChange = onSleepTimerMinutesChange,
                            currentAppLang = currentAppLang,
                        )
                        "lang" -> LanguageSettingsContent(
                            onBack = { activeCategory = null },
                            initialFocusRequester = subcategoryFocusRequester,
                            currentAppLang = currentAppLang,
                            onAppLanguageChange = onAppLanguageChange
                        )
                        "account" -> AccountSettingsContent(
                            onBack = { activeCategory = null },
                            initialFocusRequester = subcategoryFocusRequester,
                            currentAppLang = currentAppLang
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun CategoryButton(
    text: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    focusRequester: FocusRequester? = null,
    onClick: () -> Unit,
) {
    // Custom Box with tvFocusable handles single click better than Material Button on TV
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .tvFocusable(focusRequester = focusRequester, onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(text, fontWeight = FontWeight.Medium, fontSize = 14.sp)
            Spacer(Modifier.weight(1f))
            Icon(Icons.Default.ChevronRight, null, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
fun TranslationSettingsContent(
    onBack: () -> Unit,
    initialFocusRequester: FocusRequester? = null,
    isTranslationEnabled: Boolean,
    onTranslationToggle: (Boolean) -> Unit,
    translationSource: TranslationSource,
    onSourceChange: (TranslationSource) -> Unit,
    translationProviderConfig: TranslationProviderConfig,
    mlKitAllowed: Boolean,
    geminiNanoIncluded: Boolean,
    geminiNanoAvailable: Boolean,
    onTranslationProviderConfigChange: (TranslationProviderConfig) -> Unit,
    sourceLang: String,
    onSourceLangChange: (String) -> Unit,
    targetLang: String,
    onTargetLangChange: (String) -> Unit,
    availableLanguages: List<String>,
    currentAppLang: String,
    externalSubtitles: List<SubtitleEntry>,
    translatedMapSize: Int,
    isTranslatingAll: Boolean,
    onTranslateAll: () -> Unit,
    onSaveTranslated: () -> Unit
) {
    var showSourcePicker by remember { mutableStateOf(false) }
    var showTargetPicker by remember { mutableStateOf(false) }
    var libreUrl by remember(translationProviderConfig.libreTranslateBaseUrl) {
        mutableStateOf(translationProviderConfig.libreTranslateBaseUrl)
    }
    var libreKey by remember(translationProviderConfig.libreTranslateApiKey) {
        mutableStateOf(translationProviderConfig.libreTranslateApiKey)
    }
    var mozhiUrl by remember(translationProviderConfig.mozhiBaseUrl) {
        mutableStateOf(translationProviderConfig.mozhiBaseUrl)
    }
    var mozhiEngine by remember(translationProviderConfig.mozhiEngine) {
        mutableStateOf(translationProviderConfig.mozhiEngine)
    }
    var deepLKey by remember(translationProviderConfig.deepLAuthKey) {
        mutableStateOf(translationProviderConfig.deepLAuthKey)
    }
    var ollamaUrl by remember(translationProviderConfig.ollamaBaseUrl) {
        mutableStateOf(translationProviderConfig.ollamaBaseUrl)
    }
    var ollamaModel by remember(translationProviderConfig.ollamaModel) {
        mutableStateOf(translationProviderConfig.ollamaModel)
    }
    var ollamaToken by remember(translationProviderConfig.ollamaBearerToken) {
        mutableStateOf(translationProviderConfig.ollamaBearerToken)
    }

    val editedProviderConfig = TranslationProviderConfig(
        libreTranslateBaseUrl = libreUrl,
        libreTranslateApiKey = libreKey,
        mozhiBaseUrl = mozhiUrl,
        mozhiEngine = mozhiEngine,
        deepLAuthKey = deepLKey,
        ollamaBaseUrl = ollamaUrl,
        ollamaModel = ollamaModel,
        ollamaBearerToken = ollamaToken,
    )

    LaunchedEffect(libreKey, mozhiUrl, mozhiEngine, deepLKey, ollamaModel, ollamaToken) {
        onTranslationProviderConfigChange(editedProviderConfig)
    }

    val endpointFieldsValid =
        (libreUrl.isBlank() || normalizeTranslationBaseUrl(libreUrl) != null) &&
            normalizeTranslationBaseUrl(mozhiUrl, TranslationProviderConfig.DEFAULT_MOZHI_BASE_URL) != null &&
            (ollamaUrl.isBlank() || normalizeTranslationBaseUrl(ollamaUrl) != null)

    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier.tvFocusable(
                    focusRequester = initialFocusRequester,
                    isCircle = true,
                    onClick = onBack,
                ),
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, OxigenStrings.get(currentAppLang, "back"), modifier = Modifier.padding(12.dp))
            }
            Text(OxigenStrings.get(currentAppLang, "translation_label"), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }

        SettingsRow(OxigenStrings.get(currentAppLang, "enable_translation_label"), "") {
            Switch(
                checked = isTranslationEnabled,
                onCheckedChange = null,
                modifier = Modifier.tvFocusable(onClick = { onTranslationToggle(!isTranslationEnabled) })
            )
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))

        Text(OxigenStrings.get(currentAppLang, "translation_engine"), fontWeight = FontWeight.Bold)
        Column(
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val normalizedConfig = editedProviderConfig.normalized()
            val providers = buildList {
                add(TranslationSource.AUTO to true)
                if (mlKitAllowed) add(TranslationSource.MLKIT to true)
                if (geminiNanoIncluded) {
                    add(TranslationSource.GEMINI_NANO to geminiNanoAvailable)
                }
                add(TranslationSource.MY_MEMORY to true)
                add(TranslationSource.MOZHI to normalizedConfig.isMozhiConfigured)
                add(TranslationSource.LIBRE_TRANSLATE to normalizedConfig.isLibreTranslateConfigured)
                add(TranslationSource.OLLAMA to normalizedConfig.isOllamaConfigured)
                if (normalizedConfig.deepLAuthKey.isNotBlank() || ProviderAvailability.deepL) {
                    add(TranslationSource.DEEPL to true)
                }
            }
            providers.chunked(2).forEach { rowProviders ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    rowProviders.forEach { (source, enabled) ->
                        TranslationEngineButton(
                            source = source,
                            selected = translationSource == source,
                            enabled = enabled,
                            modifier = Modifier.weight(1f),
                            currentAppLang = currentAppLang,
                            onClick = { onSourceChange(source) },
                        )
                    }
                    if (rowProviders.size == 1) Spacer(Modifier.weight(1f))
                }
            }

            if (isTelevisionDevice(LocalContext.current)) {
                Text(
                    OxigenStrings.get(currentAppLang, "mlkit_tv_unavailable"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (geminiNanoIncluded) {
                Text(
                    OxigenStrings.get(currentAppLang, "gemini_nano_device_note"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            val selectedMayUseCloud = when (translationSource) {
                TranslationSource.MLKIT,
                TranslationSource.GEMINI_NANO -> false
                TranslationSource.OLLAMA ->
                    translationEndpointScope(normalizedConfig.ollamaBaseUrl) != TranslationEndpointScope.LOCAL
                else -> translationSource.mayUseCloud
            }
            if (selectedMayUseCloud) {
                Text(
                    OxigenStrings.get(currentAppLang, "cloud_translation_privacy"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
            Text(
                OxigenStrings.get(currentAppLang, "free_translation_servers"),
                fontWeight = FontWeight.Bold,
            )
            OutlinedTextField(
                value = libreUrl,
                onValueChange = { libreUrl = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(OxigenStrings.get(currentAppLang, "libretranslate_server_url")) },
                placeholder = { Text("http://192.168.1.10:5000") },
                singleLine = true,
            )
            OutlinedTextField(
                value = libreKey,
                onValueChange = { libreKey = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(OxigenStrings.get(currentAppLang, "libretranslate_api_key")) },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
            )
            OutlinedTextField(
                value = mozhiUrl,
                onValueChange = { mozhiUrl = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(OxigenStrings.get(currentAppLang, "mozhi_server_url")) },
                placeholder = { Text(TranslationProviderConfig.DEFAULT_MOZHI_BASE_URL) },
                singleLine = true,
            )
            OutlinedTextField(
                value = mozhiEngine,
                onValueChange = { mozhiEngine = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(OxigenStrings.get(currentAppLang, "mozhi_engine")) },
                placeholder = { Text(TranslationProviderConfig.DEFAULT_MOZHI_ENGINE) },
                supportingText = { Text("google / duckduckgo / mymemory / libretranslate / reverso / yandex / deepl") },
                singleLine = true,
            )
            OutlinedTextField(
                value = deepLKey,
                onValueChange = { deepLKey = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(OxigenStrings.get(currentAppLang, "deepl_api_key")) },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
            )
            OutlinedTextField(
                value = ollamaUrl,
                onValueChange = { ollamaUrl = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(OxigenStrings.get(currentAppLang, "ollama_server_url")) },
                placeholder = { Text("http://192.168.1.10:11434") },
                singleLine = true,
            )
            OutlinedTextField(
                value = ollamaModel,
                onValueChange = { ollamaModel = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(OxigenStrings.get(currentAppLang, "ollama_model")) },
                placeholder = { Text("qwen3:4b") },
                singleLine = true,
            )
            OutlinedTextField(
                value = ollamaToken,
                onValueChange = { ollamaToken = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(OxigenStrings.get(currentAppLang, "optional_token")) },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
            )
            Text(
                OxigenStrings.get(currentAppLang, "local_server_hint"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(
                onClick = { onTranslationProviderConfigChange(editedProviderConfig) },
                enabled = endpointFieldsValid,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(OxigenStrings.get(currentAppLang, "save_translation_servers"))
            }
            if (!endpointFieldsValid) {
                Text(
                    OxigenStrings.get(currentAppLang, "invalid_server_url"),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .tvFocusable(
                    enabled = !isTranslatingAll && externalSubtitles.isNotEmpty(),
                    onClick = onTranslateAll,
                )
        ) {
            Column(
                modifier = Modifier.padding(8.dp).align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    OxigenStrings.get(currentAppLang, "translate_all") + " (${externalSubtitles.size})",
                    color = if (externalSubtitles.isEmpty()) Color.Gray else Color.White,
                    fontWeight = FontWeight.Bold
                )

                if (isTranslatingAll) {
                    Spacer(modifier = Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = {
                            if (externalSubtitles.isEmpty()) 0f
                            else (translatedMapSize.toFloat() / externalSubtitles.size.toFloat()).coerceIn(0f, 1f)
                        },
                        modifier = Modifier.fillMaxWidth(0.8f).height(4.dp),
                        color = MaterialTheme.colorScheme.secondary,
                        trackColor = Color.Gray.copy(alpha = 0.3f)
                    )
                    Text(
                        "$translatedMapSize / ${externalSubtitles.size}",
                        fontSize = 10.sp,
                        color = Color.White.copy(alpha = 0.7f),
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        }

        if (!isTranslatingAll && externalSubtitles.isNotEmpty() && translatedMapSize == externalSubtitles.size) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .tvFocusable(onClick = onSaveTranslated)
                    .padding(top = 8.dp)
            ) {
                Text(
                    OxigenStrings.get(currentAppLang, "save_translated_subtitle"),
                    modifier = Modifier.padding(8.dp).align(Alignment.Center),
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        if (!isTranslatingAll && translatedMapSize > 0 && translatedMapSize < externalSubtitles.size) {
            Text(
                OxigenStrings.get(currentAppLang, "translated_progress").format(translatedMapSize, externalSubtitles.size),
                fontSize = 12.sp,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )
        }


        HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))

        val sourceLabel = if (sourceLang == "auto") {
            OxigenStrings.get(currentAppLang, "automatic_track")
        } else {
            localizedLanguageName(sourceLang, currentAppLang)
        }
        SettingsRow(OxigenStrings.get(currentAppLang, "source_lang"), sourceLabel) {
            Box(modifier = Modifier.tvFocusable(onClick = { showSourcePicker = true })) {
                Text(OxigenStrings.get(currentAppLang, "change"), modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            }
        }
        SettingsRow(
            OxigenStrings.get(currentAppLang, "target_lang"),
            localizedLanguageName(targetLang, currentAppLang),
        ) {
            Box(modifier = Modifier.tvFocusable(onClick = { showTargetPicker = true })) {
                Text(OxigenStrings.get(currentAppLang, "change"), modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            }
        }

        if (showSourcePicker) LanguagePickerDialog(
            title = OxigenStrings.get(currentAppLang, "source_lang"),
            languages = listOf("auto") + availableLanguages,
            currentAppLang = currentAppLang,
            onDismiss = { showSourcePicker = false },
            onSelect = { onSourceLangChange(it); showSourcePicker = false },
        )
        if (showTargetPicker) LanguagePickerDialog(
            title = OxigenStrings.get(currentAppLang, "target_lang"),
            languages = availableLanguages,
            currentAppLang = currentAppLang,
            onDismiss = { showTargetPicker = false },
            onSelect = { onTargetLangChange(it); showTargetPicker = false },
        )
    }
}

@Composable
private fun TranslationEngineButton(
    source: TranslationSource,
    selected: Boolean,
    enabled: Boolean,
    currentAppLang: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val label = when (source) {
        TranslationSource.AUTO -> "AUTO"
        TranslationSource.MLKIT -> "ML Kit"
        TranslationSource.GEMINI_NANO -> "Gemini Nano"
        TranslationSource.MY_MEMORY -> "MyMemory"
        TranslationSource.MOZHI -> "Mozhi"
        TranslationSource.LIBRE_TRANSLATE -> "LibreTranslate"
        TranslationSource.OLLAMA -> "Ollama"
        TranslationSource.DEEPL -> "DeepL"
    }
    Box(
        modifier = modifier
            .tvFocusable(
                isSelected = selected,
                enabled = enabled,
                onClick = onClick,
            )
            .background(
                if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
                else Color.Transparent,
                RoundedCornerShape(12.dp),
            )
            .semantics {
                this.selected = selected
                stateDescription = if (enabled) label else
                    "$label ${OxigenStrings.get(currentAppLang, "provider_unavailable")}"
            },
    ) {
        Text(
            label,
            modifier = Modifier.padding(8.dp).align(Alignment.Center),
            color = when {
                !enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                selected -> MaterialTheme.colorScheme.secondary
                else -> MaterialTheme.colorScheme.onSurface
            },
            fontWeight = if (source == TranslationSource.AUTO) FontWeight.ExtraBold else FontWeight.Normal,
        )
    }
}

@Composable
fun SubtitleStyleContent(
    onBack: () -> Unit,
    initialFocusRequester: FocusRequester? = null,
    subtitleFontSize: Float, onFontSizeChange: (Float) -> Unit,
    subtitleVerticalOffset: Float, onVerticalOffsetChange: (Float) -> Unit,
    subtitleSyncOffset: Long, onSyncOffsetChange: (Long) -> Unit,
    subtitleColor: Color, onColorChange: (Color) -> Unit,
    subtitleBgColor: Color, onBgColorChange: (Color) -> Unit,
    subtitleEdgeStyle: SubtitleEdgeStyle, onEdgeStyleChange: (SubtitleEdgeStyle) -> Unit,
    autoSyncState: AutoSyncState,
    onStartAutoSync: () -> Unit,
    onMarkAutoSyncAudio: () -> Unit,
    onMarkAutoSyncSubtitle: () -> Unit,
    onApplyAutoSync: () -> Unit,
    onCancelAutoSync: () -> Unit,
    currentAppLang: String
) {
    val colors = listOf(Color.White, Color.Yellow, Color.Cyan, Color.Green, Color.Red, Color.Magenta)
    val bgColors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.5f), Color.Black, Color.DarkGray.copy(alpha = 0.5f))

    var activeSlider by remember { mutableStateOf<String?>(null) }

    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier.tvFocusable(
                    focusRequester = initialFocusRequester,
                    isCircle = true,
                    onClick = onBack,
                ),
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, OxigenStrings.get(currentAppLang, "back"), modifier = Modifier.padding(12.dp))
            }
            Text(OxigenStrings.get(currentAppLang, "style_label"), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }

        Spacer(modifier = Modifier.height(4.dp))

        // Font Size
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            val label = OxigenStrings.get(currentAppLang, "font_size_label")
            val status = if (activeSlider == "font") OxigenStrings.get(currentAppLang, "adjusting_suffix") else ""
            Text("$label: ${subtitleFontSize.toInt()} sp$status", modifier = Modifier.weight(1f))
        }

        TvSlider(
            value = subtitleFontSize,
            onValueChange = onFontSizeChange,
            valueRange = 10f..100f,
            isActive = activeSlider == "font",
            currentAppLang = currentAppLang,
            onToggleActive = { activeSlider = if (activeSlider == "font") null else "font" }
        )

        Spacer(modifier = Modifier.height(4.dp))

        // Vertical Offset
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            val offsetLabel = OxigenStrings.get(currentAppLang, "vertical_position")
            val status = if (activeSlider == "offset") OxigenStrings.get(currentAppLang, "adjusting_suffix") else ""
            Text("$offsetLabel: ${subtitleVerticalOffset.toInt()} dp$status", modifier = Modifier.weight(1f))
        }

        TvSlider(
            value = subtitleVerticalOffset,
            onValueChange = onVerticalOffsetChange,
            valueRange = -60f..500f,
            isActive = activeSlider == "offset",
            currentAppLang = currentAppLang,
            onToggleActive = { activeSlider = if (activeSlider == "offset") null else "offset" }
        )

        Spacer(modifier = Modifier.height(4.dp))

        // Presets rapide: ±5 s, ±1 s, ±500 ms, reset — disponibili mereu, fără a activa sliderul
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            listOf(-5000L, -1000L, -500L, 0L, 500L, 1000L, 5000L).forEach { ms ->
                val selected = subtitleSyncOffset == ms
                SyncPresetChip(
                    label = presetLabel(ms),
                    onClick = { onSyncOffsetChange(ms) },
                    isSelected = selected,
                    currentAppLang = currentAppLang,
                )
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        TvSlider(
            value = subtitleSyncOffset.toFloat(),
            onValueChange = { onSyncOffsetChange(it.toLong()) },
            valueRange = -10000f..10000f,
            isActive = activeSlider == "sync",
            currentAppLang = currentAppLang,
            onToggleActive = { activeSlider = if (activeSlider == "sync") null else "sync" },
            step = 100f,
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Fine adjustment in 50 ms steps.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SyncManualButton(
                label = "−",
                onClick = { onSyncOffsetChange(nudgeSubtitleSyncOffset(subtitleSyncOffset, -SUBTITLE_SYNC_FINE_STEP_MS)) },
                currentAppLang = currentAppLang,
            )
            Text(
                OxigenStrings.get(currentAppLang, "sync_label") + ": ${formatSubtitleSyncOffset(subtitleSyncOffset)}",
                modifier = Modifier.weight(1f),
                color = if (subtitleSyncOffset != 0L) MaterialTheme.colorScheme.secondary else Color.White,
            )
            SyncManualButton(
                label = "+",
                onClick = { onSyncOffsetChange(nudgeSubtitleSyncOffset(subtitleSyncOffset, SUBTITLE_SYNC_FINE_STEP_MS)) },
                currentAppLang = currentAppLang,
            )
        }

        Text(
            OxigenStrings.get(currentAppLang, "subtitle_sync_hint"),
            style = MaterialTheme.typography.bodySmall,
            color = Color.Gray,
        )

        Spacer(modifier = Modifier.height(4.dp))

        // Timing can only be judged with the film visible, so this closes the settings and
        // opens the on-screen panel (manual steps plus the two-mark calibration).
        AutoSyncButton(
            label = OxigenStrings.get(currentAppLang, "subtitle_sync_open"),
            onClick = onStartAutoSync,
            isPrimary = true,
            currentAppLang = currentAppLang,
        )

        Spacer(modifier = Modifier.height(4.dp))
        Text(OxigenStrings.get(currentAppLang, "style_label"))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(vertical = 8.dp)) {
            items(items = colors, key = { it.toArgb() }) { color ->
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .semantics {
                            contentDescription = accessibleColorName(color, currentAppLang)
                            selected = subtitleColor == color
                        }
                        .tvFocusable(isCircle = true, isSelected = subtitleColor == color, onClick = { onColorChange(color) })
                        .background(color, CircleShape)
                )
            }
        }

        Spacer(modifier = Modifier.height(4.dp))
        Text(OxigenStrings.get(currentAppLang, "subtitle_background"))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(vertical = 8.dp)) {
            items(items = bgColors, key = { it.toArgb() }) { color ->
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .semantics {
                            contentDescription = "${OxigenStrings.get(currentAppLang, "subtitle_background")}: ${accessibleColorName(color, currentAppLang)}"
                            selected = subtitleBgColor == color
                        }
                        .tvFocusable(isCircle = true, isSelected = subtitleBgColor == color, onClick = { onBgColorChange(color) })
                        .background(color, CircleShape)
                )
            }
        }

        Spacer(modifier = Modifier.height(4.dp))
        Text(OxigenStrings.get(currentAppLang, "edge_style_label"))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Button(
                onClick = { onEdgeStyleChange(SubtitleEdgeStyle.NONE) },
                modifier = Modifier.tvFocusable(isSelected = subtitleEdgeStyle == SubtitleEdgeStyle.NONE, onClick = { onEdgeStyleChange(SubtitleEdgeStyle.NONE) }),
                enabled = true
            ) {
                Text(OxigenStrings.get(currentAppLang, "none"))
            }
            Button(
                onClick = { onEdgeStyleChange(SubtitleEdgeStyle.OUTLINE) },
                modifier = Modifier.tvFocusable(isSelected = subtitleEdgeStyle == SubtitleEdgeStyle.OUTLINE, onClick = { onEdgeStyleChange(SubtitleEdgeStyle.OUTLINE) }),
                enabled = true
            ) {
                Text(OxigenStrings.get(currentAppLang, "outline"))
            }
            Button(
                onClick = { onEdgeStyleChange(SubtitleEdgeStyle.SHADOW) },
                modifier = Modifier.tvFocusable(isSelected = subtitleEdgeStyle == SubtitleEdgeStyle.SHADOW, onClick = { onEdgeStyleChange(SubtitleEdgeStyle.SHADOW) }),
                enabled = true
            ) {
                Text(OxigenStrings.get(currentAppLang, "shadow"))
            }
        }
    }
}

@Composable
fun TvSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    isActive: Boolean,
    currentAppLang: String,
    onToggleActive: () -> Unit,
    step: Float = (valueRange.endInclusive - valueRange.start) / 20f,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {
                contentDescription = OxigenStrings.get(currentAppLang, "setting_value")
                progressBarRangeInfo = ProgressBarRangeInfo(value, valueRange)
                stateDescription = String.format(java.util.Locale.US, "%.2f", value)
            }
            .tvFocusable(
                isSelected = isActive,
                onClick = onToggleActive
            )
            .onKeyEvent { keyEvent ->
                if (isActive && keyEvent.type == KeyEventType.KeyDown) {
                    when (keyEvent.nativeKeyEvent.keyCode) {
                        NativeKeyEvent.KEYCODE_DPAD_LEFT -> {
                            onValueChange((value - step).coerceIn(valueRange))
                            true
                        }
                        NativeKeyEvent.KEYCODE_DPAD_RIGHT -> {
                            onValueChange((value + step).coerceIn(valueRange))
                            true
                        }
                        else -> false
                    }
                } else {
                    false
                }
            }
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            enabled = isActive,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun SyncPresetChip(
    label: String,
    onClick: () -> Unit,
    isSelected: Boolean,
    currentAppLang: String,
) {
    Box(
        modifier = Modifier
            .tvFocusable(isSelected = isSelected, onClick = onClick)
            .background(
                if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
                else Color.Transparent,
                RoundedCornerShape(8.dp),
            )
            .padding(horizontal = 10.dp, vertical = 6.dp)
            .semantics { contentDescription = label },
    ) {
        Text(
            label,
            modifier = Modifier.align(Alignment.Center),
            color = if (isSelected) MaterialTheme.colorScheme.secondary else Color.White,
            fontWeight = FontWeight.Bold,
        )
    }
}

/**
 * Human-readable offset: milliseconds below one second, seconds with millisecond precision
 * above it ("+150 ms", "-3.141 s"). The sign is always shown so the direction is unambiguous.
 */
internal fun formatSubtitleSyncOffset(offset: Long): String {
    if (offset == 0L) return "0 ms"
    val sign = if (offset > 0L) "+" else "-"
    val magnitude = Math.abs(offset)
    if (magnitude < 1_000L) return "$sign$magnitude ms"
    val millis = (magnitude % 1_000L).toString().padStart(3, '0').trimEnd('0')
    val seconds = magnitude / 1_000L
    return if (millis.isEmpty()) "$sign$seconds s" else "$sign$seconds.$millis s"
}

private fun presetLabel(ms: Long): String = when {
    ms == 0L -> "0"
    ms % 1000L == 0L -> "${if (ms > 0L) "+" else ""}${ms / 1000} s"
    else -> "${if (ms > 0L) "+" else ""}$ms ms"
}

@Composable
private fun AutoSyncButton(
    label: String,
    onClick: () -> Unit,
    isPrimary: Boolean = false,
    currentAppLang: String,
) {
    Box(
        modifier = Modifier
            .tvFocusable(isSelected = isPrimary, onClick = onClick)
            .background(
                if (isPrimary) MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
                else Color.Transparent,
                RoundedCornerShape(8.dp),
            )
            .padding(horizontal = 14.dp, vertical = 8.dp)
            .semantics { contentDescription = label },
    ) {
        Text(
            label,
            modifier = Modifier.align(Alignment.Center),
            color = if (isPrimary) MaterialTheme.colorScheme.secondary else Color.White,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun SyncManualButton(
    label: String,
    onClick: () -> Unit,
    currentAppLang: String,
) {
    Box(
        modifier = Modifier
            .tvFocusable(isSelected = false, onClick = onClick)
            .background(
                MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                RoundedCornerShape(8.dp),
            )
            .size(52.dp)
            .semantics { contentDescription = "${OxigenStrings.get(currentAppLang, "sync_label")} $label" },
    ) {
        Text(
            label,
            modifier = Modifier.align(Alignment.Center),
            color = MaterialTheme.colorScheme.secondary,
            fontWeight = FontWeight.Bold,
            fontSize = 20.sp,
        )
    }
}

private fun accessibleColorName(color: Color, currentAppLang: String): String = OxigenStrings.get(
    currentAppLang,
    when {
        color.alpha == 0f -> "transparent"
        color == Color.White -> "white"
        color == Color.Yellow -> "yellow"
        color == Color.Cyan -> "cyan"
        color == Color.Green -> "green"
        color == Color.Red -> "red"
        color == Color.Magenta -> "magenta"
        color == Color.Black -> "black"
        color.red == color.green && color.green == color.blue -> "gray"
        else -> "custom_color"
    },
)

@Composable
fun LanguageSettingsContent(
    onBack: () -> Unit,
    currentAppLang: String,
    onAppLanguageChange: (String) -> Unit,
    initialFocusRequester: FocusRequester? = null,
) {
    val appLanguages = mapOf(
        "ro" to "Română",
        "en" to "English",
        "es" to "Español",
        "fr" to "Français",
        "de" to "Deutsch",
        "it" to "Italiano",
        "pt" to "Português",
        "ru" to "Русский"
    )

    Column(modifier = Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier.tvFocusable(
                    focusRequester = initialFocusRequester,
                    isCircle = true,
                    onClick = onBack,
                ),
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, OxigenStrings.get(currentAppLang, "back"), modifier = Modifier.padding(12.dp))
            }
            Text(OxigenStrings.get(currentAppLang, "language_label"), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }

        LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 12.dp)) {
            items(items = appLanguages.entries.toList(), key = { it.key }) { entry ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .tvFocusable(isSelected = currentAppLang == entry.key, onClick = { onAppLanguageChange(entry.key) })
                ) {
                    Text(
                        entry.value,
                        modifier = Modifier.padding(16.dp).align(Alignment.Center),
                        color = if (currentAppLang == entry.key) MaterialTheme.colorScheme.secondary else Color.White
                    )
                }
            }
        }
    }
}

@Composable
fun SettingsRow(title: String, sub: String, content: @Composable () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Column(Modifier.weight(1f)) { Text(title, fontWeight = FontWeight.Bold); if (sub.isNotEmpty()) Text(sub, style = MaterialTheme.typography.bodySmall) }
        content()
    }
}

@Composable
fun LanguagePickerDialog(
    title: String,
    languages: List<String>,
    currentAppLang: String,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit,
) {
    val sortedLanguages = remember(languages, currentAppLang) {
        languages.distinct().sortedBy { code ->
            if (code == "auto") "" else localizedLanguageName(code, currentAppLang)
        }
    }
    FastDialog(onDismiss = onDismiss) {
        OxigenCard(modifier = Modifier.standardDialogCardSize()) {
            Column(modifier = Modifier.padding(8.dp)) {
                Text(title, style = MaterialTheme.typography.headlineSmall)
                LazyColumn(Modifier.weight(1f)) {
                    items(items = sortedLanguages, key = { it }) { lang ->
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .tvFocusable(onClick = { onSelect(lang) })
                                .padding(16.dp),
                        ) {
                            Text(
                                if (lang == "auto") OxigenStrings.get(currentAppLang, "automatic_track")
                                else localizedLanguageName(lang, currentAppLang),
                                color = Color.White,
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun localizedLanguageName(code: String, appLang: String): String {
    val displayLocale = java.util.Locale.forLanguageTag(appLang)
    val value = java.util.Locale.forLanguageTag(code).getDisplayLanguage(displayLocale)
    return value.takeIf(String::isNotBlank)?.replaceFirstChar { it.titlecase(displayLocale) }
        ?: code.uppercase(java.util.Locale.ROOT)
}
