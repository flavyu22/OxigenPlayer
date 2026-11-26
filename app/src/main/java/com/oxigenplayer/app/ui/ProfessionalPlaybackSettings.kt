// SPDX-License-Identifier: GPL-3.0-only
package com.oxigenplayer.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.oxigenplayer.app.AUDIO_DELAY_STEP_MS
import com.oxigenplayer.app.BuildConfig
import com.oxigenplayer.app.OxigenStrings
import com.oxigenplayer.app.VideoQualityProfile
import com.oxigenplayer.app.PreferencesManager
import com.oxigenplayer.app.VideoAspectRatioOverride
import com.oxigenplayer.app.VideoResizeMode
import com.oxigenplayer.app.clampAudioDelayMs
import com.oxigenplayer.app.tvFocusable

@Composable
fun PlaybackSettingsContent(
    onBack: () -> Unit,
    initialFocusRequester: FocusRequester? = null,
    playbackSpeed: Float,
    onPlaybackSpeedChange: (Float) -> Unit,
    videoQualityProfile: VideoQualityProfile,
    onVideoQualityProfileChange: (VideoQualityProfile) -> Unit,
    matchContentFrameRate: Boolean,
    onMatchContentFrameRateChange: (Boolean) -> Unit,
    audioOffloadEnabled: Boolean,
    onAudioOffloadChange: (Boolean) -> Unit,
    audioDelayMs: Int,
    onAudioDelayChange: (Int) -> Unit,
    preferredAudioLanguage: String,
    onPreferredAudioLanguageChange: (String) -> Unit,
    repeatCurrentMedia: Boolean,
    onRepeatCurrentMediaChange: (Boolean) -> Unit,
    skipSilenceEnabled: Boolean,
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
    currentAppLang: String,
) {
    var isSliderActive by remember { mutableStateOf(false) }
    val sleepOptions = remember { listOf(0, 15, 30, 60, 90, 120) }
    val zoomOptions = remember { listOf(1f to "1x", 1.25f to "1.25x", 1.5f to "1.5x", 2f to "2x") }
    val audioLanguageOptions = remember { listOf("") + PreferencesManager.SUPPORTED_APP_LANGUAGES.sorted() }

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
            Text(OxigenStrings.get(currentAppLang, "playback_settings"), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }

        Spacer(modifier = Modifier.height(4.dp))
        Text(
            OxigenStrings.get(currentAppLang, "playback_speed").format(String.format(java.util.Locale.US, "%.2fx", playbackSpeed)),
            modifier = Modifier.padding(horizontal = 8.dp),
            color = Color.White,
        )
        TvSlider(
            value = playbackSpeed,
            onValueChange = onPlaybackSpeedChange,
            valueRange = 0.5f..2.0f,
            isActive = isSliderActive,
            currentAppLang = currentAppLang,
            onToggleActive = { isSliderActive = !isSliderActive },
        )
        Text(
            OxigenStrings.get(currentAppLang, "playback_speed_help"),
            style = MaterialTheme.typography.bodySmall,
            color = Color.Gray,
            modifier = Modifier.padding(horizontal = 8.dp),
        )

        Spacer(modifier = Modifier.height(12.dp))
        Text(OxigenStrings.get(currentAppLang, "video_quality_profile"), fontWeight = FontWeight.Bold)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 8.dp)) {
            items(items = VideoQualityProfile.entries, key = { it.name }) { profile ->
                Box(
                    modifier = Modifier.tvFocusable(
                        isSelected = videoQualityProfile == profile,
                        onClick = { onVideoQualityProfileChange(profile) },
                    ),
                ) {
                    Text(
                        OxigenStrings.get(currentAppLang, "quality_${profile.name.lowercase(java.util.Locale.ROOT)}"),
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        color = if (videoQualityProfile == profile) MaterialTheme.colorScheme.secondary else Color.White,
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))
        Text(OxigenStrings.get(currentAppLang, "video_resize_mode"), fontWeight = FontWeight.Bold)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 8.dp)) {
            items(items = VideoResizeMode.entries, key = { it.name }) { mode ->
                Box(
                    modifier = Modifier.tvFocusable(
                        isSelected = videoResizeMode == mode,
                        onClick = { onVideoResizeModeChange(mode) },
                    ),
                ) {
                    Text(
                        OxigenStrings.get(currentAppLang, "video_resize_mode_${mode.name.lowercase(java.util.Locale.ROOT)}"),
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        color = if (videoResizeMode == mode) MaterialTheme.colorScheme.secondary else Color.White,
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))
        Text(OxigenStrings.get(currentAppLang, "aspect_ratio_override"), fontWeight = FontWeight.Bold)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 8.dp)) {
            items(items = VideoAspectRatioOverride.entries, key = { it.name }) { option ->
                Box(
                    modifier = Modifier.tvFocusable(
                        isSelected = aspectRatioOverride == option,
                        onClick = { onAspectRatioOverrideChange(option) },
                    ),
                ) {
                    val label = if (option == VideoAspectRatioOverride.ORIGINAL) {
                        OxigenStrings.get(currentAppLang, "aspect_ratio_original")
                    } else {
                        option.ratioLabel
                    }
                    Text(
                        label,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        color = if (aspectRatioOverride == option) MaterialTheme.colorScheme.secondary else Color.White,
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))
        Text(OxigenStrings.get(currentAppLang, "zoom"), fontWeight = FontWeight.Bold)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 8.dp)) {
            items(items = zoomOptions, key = { it.second }) { (level, label) ->
                Box(
                    modifier = Modifier.tvFocusable(
                        isSelected = videoZoom == level,
                        onClick = { onZoomChange(level) },
                    ),
                ) {
                    Text(
                        label,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        color = if (videoZoom == level) MaterialTheme.colorScheme.secondary else Color.White,
                    )
                }
            }
        }

        if (videoZoom > 1f) {
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.padding(vertical = 4.dp, horizontal = 8.dp),
            ) {
                Text("↔", color = Color.White)
                Box(modifier = Modifier.tvFocusable(isSelected = false, onClick = { onPanChange((videoPanX - 0.05f).coerceIn(-1f, 1f), videoPanY) })) {
                    Text("−", modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp), color = Color.White)
                }
                Text(String.format(java.util.Locale.US, "%+.0f%%", videoPanX * 100f), color = MaterialTheme.colorScheme.secondary)
                Box(modifier = Modifier.tvFocusable(isSelected = false, onClick = { onPanChange((videoPanX + 0.05f).coerceIn(-1f, 1f), videoPanY) })) {
                    Text("+", modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp), color = Color.White)
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.padding(vertical = 4.dp, horizontal = 8.dp),
            ) {
                Text("↕", color = Color.White)
                Box(modifier = Modifier.tvFocusable(isSelected = false, onClick = { onPanChange(videoPanX, (videoPanY - 0.05f).coerceIn(-1f, 1f)) })) {
                    Text("−", modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp), color = Color.White)
                }
                Text(String.format(java.util.Locale.US, "%+.0f%%", videoPanY * 100f), color = MaterialTheme.colorScheme.secondary)
                Box(modifier = Modifier.tvFocusable(isSelected = false, onClick = { onPanChange(videoPanX, (videoPanY + 0.05f).coerceIn(-1f, 1f)) })) {
                    Text("+", modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp), color = Color.White)
                }
            }
        }

        ProfessionalToggleRow(
            title = OxigenStrings.get(currentAppLang, "match_content_frame_rate"),
            subtitle = OxigenStrings.get(currentAppLang, "match_content_frame_rate_help"),
            checked = matchContentFrameRate,
            onCheckedChange = onMatchContentFrameRateChange,
        )
        ProfessionalToggleRow(
            title = OxigenStrings.get(currentAppLang, "audio_offload"),
            subtitle = OxigenStrings.get(currentAppLang, "audio_offload_help"),
            checked = audioOffloadEnabled,
            onCheckedChange = onAudioOffloadChange,
        )

        Spacer(modifier = Modifier.height(12.dp))
        Text(OxigenStrings.get(currentAppLang, "audio_delay"), fontWeight = FontWeight.Bold)
        Text(
            OxigenStrings.get(currentAppLang, "audio_delay_help"),
            style = MaterialTheme.typography.bodySmall,
            color = Color.Gray,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier.tvFocusable(
                    isSelected = false,
                    onClick = { onAudioDelayChange(clampAudioDelayMs(audioDelayMs - AUDIO_DELAY_STEP_MS)) },
                ),
            ) {
                Text("−", modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp), color = Color.White)
            }
            Text("${audioDelayMs} ms", color = MaterialTheme.colorScheme.secondary)
            Box(
                modifier = Modifier.tvFocusable(
                    isSelected = false,
                    onClick = { onAudioDelayChange(clampAudioDelayMs(audioDelayMs + AUDIO_DELAY_STEP_MS)) },
                ),
            ) {
                Text("+", modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp), color = Color.White)
            }
        }

        Spacer(modifier = Modifier.height(12.dp))
        Text(OxigenStrings.get(currentAppLang, "preferred_audio_language"), fontWeight = FontWeight.Bold)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 8.dp)) {
            items(items = audioLanguageOptions, key = { it }) { code ->
                Box(
                    modifier = Modifier.tvFocusable(
                        isSelected = preferredAudioLanguage == code,
                        onClick = { onPreferredAudioLanguageChange(code) },
                    ),
                ) {
                    val label = if (code.isEmpty()) {
                        OxigenStrings.get(currentAppLang, "audio_language_auto")
                    } else {
                        val displayLocale = java.util.Locale.forLanguageTag(currentAppLang)
                        java.util.Locale.forLanguageTag(code).getDisplayLanguage(displayLocale)
                            .replaceFirstChar { it.titlecase(displayLocale) }
                    }
                    Text(
                        label,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        color = if (preferredAudioLanguage == code) MaterialTheme.colorScheme.secondary else Color.White,
                    )
                }
            }
        }
        ProfessionalToggleRow(
            title = OxigenStrings.get(currentAppLang, "repeat_current_media"),
            subtitle = OxigenStrings.get(currentAppLang, "repeat_current_media_help"),
            checked = repeatCurrentMedia,
            onCheckedChange = onRepeatCurrentMediaChange,
        )
        ProfessionalToggleRow(
            title = OxigenStrings.get(currentAppLang, "playback_diagnostics"),
            subtitle = OxigenStrings.get(currentAppLang, "playback_diagnostics_help"),
            checked = showDiagnostics,
            onCheckedChange = onShowDiagnosticsChange,
        )
        ProfessionalToggleRow(
            title = OxigenStrings.get(currentAppLang, "skip_silence"),
            subtitle = OxigenStrings.get(currentAppLang, "skip_silence_help"),
            checked = skipSilenceEnabled,
            onCheckedChange = onSkipSilenceChange,
        )

        Spacer(modifier = Modifier.height(12.dp))
        Text(OxigenStrings.get(currentAppLang, "sleep_timer"), fontWeight = FontWeight.Bold)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 8.dp)) {
            items(items = sleepOptions, key = { it }) { minutes ->
                Box(
                    modifier = Modifier.tvFocusable(
                        isSelected = sleepTimerMinutes == minutes,
                        onClick = { onSleepTimerMinutesChange(minutes) },
                    ),
                ) {
                    val text = if (minutes == 0) {
                        OxigenStrings.get(currentAppLang, "off")
                    } else {
                        OxigenStrings.get(currentAppLang, "minutes_format").format(minutes)
                    }
                    Text(
                        text,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        color = if (sleepTimerMinutes == minutes) MaterialTheme.colorScheme.secondary else Color.White,
                    )
                }
            }
        }

        if (BuildConfig.CLEARTEXT_LAN_ENABLED) {
            Text(
                OxigenStrings.get(currentAppLang, "lan_edition_warning"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.padding(8.dp),
            )
        }
    }
}

@Composable
private fun ProfessionalToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .tvFocusable(isSelected = checked, onClick = { onCheckedChange(!checked) })
            .padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Medium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}

