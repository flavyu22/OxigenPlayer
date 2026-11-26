// SPDX-License-Identifier: GPL-3.0-only
package com.oxigenplayer.app.ui

import android.content.Context
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.oxigenplayer.app.*
import kotlinx.coroutines.CoroutineScope

/**
 * Encapsulates all state required for player dialogs.
 */
@Immutable
data class PlayerDialogState(
    val activeDialog: DialogType?,
    val localMediaSnapshot: LocalMediaSnapshot,
    val localMediaAccess: LocalMediaAccess,
    val isLocalMediaLoading: Boolean,
    val isLocalMediaPermissionRequired: Boolean,
    val didLocalMediaLoadFail: Boolean,
    val isTranslationEnabled: Boolean,
    val translationSource: TranslationSource,
    val translationProviderConfig: TranslationProviderConfig,
    val mlKitAllowed: Boolean,
    val geminiNanoIncluded: Boolean,
    val geminiNanoAvailable: Boolean,
    val sourceLang: String,
    val subtitleFontSize: Float,
    val subtitleVerticalOffset: Float,
    val subtitleSyncOffset: Long,
    val subtitleColor: Color,
    val subtitleBgColor: Color,
    val subtitleEdgeStyle: SubtitleEdgeStyle,
    val targetLang: String,
    val playbackSpeed: Float,
    val videoQualityProfile: VideoQualityProfile,
    val matchContentFrameRate: Boolean,
    val audioOffloadEnabled: Boolean,
    val preferredAudioLanguage: String,
    val audioDelayMs: Int,
    val repeatCurrentMedia: Boolean,
    val showDiagnostics: Boolean,
    val sleepTimerMinutes: Int,
    val skipSilenceEnabled: Boolean,
    val videoResizeMode: VideoResizeMode,
    val aspectRatioOverride: VideoAspectRatioOverride,
    val videoZoom: Float,
    val videoPanX: Float,
    val videoPanY: Float,
    val availableLanguages: List<String>,
    val currentAppLang: String,
    val externalSubtitles: List<SubtitleEntry>,
    val translatedMapSize: Int,
    val isTranslatingAll: Boolean,
    val useExternalSubtitles: Boolean,
    val currentVideoUri: Uri?,
    val autoSyncState: AutoSyncState,
    val subtitleSyncState: SubtitleSyncState,
)

/**
 * Encapsulates all actions (callbacks) for player dialogs.
 */
class PlayerDialogActions(
    val onDismiss: () -> Unit,
    val onRequestMediaAccess: () -> Unit,
    val onRefreshLocalMedia: () -> Unit,
    val onOpenSystemMediaPicker: () -> Unit,
    val onLocalMediaSelected: (LocalMediaFile) -> Unit,
    val onCheckUpdate: () -> Unit,
    val onTranslationToggle: (Boolean) -> Unit,
    val onSourceChange: (TranslationSource) -> Unit,
    val onTranslationProviderConfigChange: (TranslationProviderConfig) -> Unit,
    val onSourceLangChange: (String) -> Unit,
    val onFontSizeChange: (Float) -> Unit,
    val onVerticalOffsetChange: (Float) -> Unit,
    val onSyncOffsetChange: (Long) -> Unit,
    val onColorChange: (Color) -> Unit,
    val onBgColorChange: (Color) -> Unit,
    val onEdgeStyleChange: (SubtitleEdgeStyle) -> Unit,
    val onTargetLangChange: (String) -> Unit,
    val onPlaybackSpeedChange: (Float) -> Unit,
    val onVideoQualityProfileChange: (VideoQualityProfile) -> Unit,
    val onMatchContentFrameRateChange: (Boolean) -> Unit,
    val onAudioOffloadChange: (Boolean) -> Unit,
    val onPreferredAudioLanguageChange: (String) -> Unit,
    val onAudioDelayChange: (Int) -> Unit,
    val onRepeatCurrentMediaChange: (Boolean) -> Unit,
    val onShowDiagnosticsChange: (Boolean) -> Unit,
    val onSleepTimerMinutesChange: (Int) -> Unit,
    val onSkipSilenceChange: (Boolean) -> Unit,
    val onVideoResizeModeChange: (VideoResizeMode) -> Unit,
    val onAspectRatioOverrideChange: (VideoAspectRatioOverride) -> Unit,
    val onZoomChange: (Float) -> Unit,
    val onPanChange: (Float, Float) -> Unit,
    val onOpenStream: (String) -> Unit,
    val onAppLanguageChange: (String) -> Unit,
    val onShowAbout: () -> Unit,
    val onTranslateAll: () -> Unit,
    val onSaveTranslated: () -> Unit,
    val onUseExternalSubtitlesChange: (Boolean) -> Unit,
    val onPickExternalSubtitles: () -> Unit,
    val onSubtitleSelected: (List<SubtitleEntry>) -> Unit,
    val onUpdateDownload: suspend (UpdateInfo) -> UpdateInstallResult,

    // Auto-sync calibration
    val onStartAutoSync: () -> Unit,
    val onMarkAutoSyncAudio: () -> Unit,
    val onMarkAutoSyncSubtitle: () -> Unit,
    val onApplyAutoSync: () -> Unit,
    val onCancelAutoSync: () -> Unit,

    // Subtitle sync live on-screen controls (bottom-right overlay)
    val onStartSubtitleSyncAdjust: () -> Unit,
    val onCancelSubtitleSyncAdjust: () -> Unit,
)

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun PlayerDialogsOverlay(
    state: PlayerDialogState,
    actions: PlayerDialogActions,
    player: Player?,
    subtitleSearchService: SubtitleSearchService?,
    scope: CoroutineScope,
    context: Context,
    prefs: PreferencesManager
) {
    val activeDialog = state.activeDialog ?: return

    when (activeDialog) {
        DialogType.MediaBrowser -> {
            DeviceMediaPickerDialog(
                files = state.localMediaSnapshot.files,
                volumes = state.localMediaSnapshot.volumes,
                access = state.localMediaAccess,
                isLoading = state.isLocalMediaLoading,
                permissionRequired = state.isLocalMediaPermissionRequired,
                loadFailed = state.didLocalMediaLoadFail,
                appLang = state.currentAppLang,
                onRequestAccess = actions.onRequestMediaAccess,
                onRefresh = actions.onRefreshLocalMedia,
                onOpenSystemPicker = actions.onOpenSystemMediaPicker,
                onMediaSelected = actions.onLocalMediaSelected,
                onDismiss = actions.onDismiss,
            )
        }

        DialogType.Settings -> {
            MainSettingsDialog(
                onDismiss = actions.onDismiss,
                isTranslationEnabled = state.isTranslationEnabled,
                onTranslationToggle = actions.onTranslationToggle,
                translationSource = state.translationSource,
                onSourceChange = actions.onSourceChange,
                translationProviderConfig = state.translationProviderConfig,
                mlKitAllowed = state.mlKitAllowed,
                geminiNanoIncluded = state.geminiNanoIncluded,
                geminiNanoAvailable = state.geminiNanoAvailable,
                onTranslationProviderConfigChange = actions.onTranslationProviderConfigChange,
                sourceLang = state.sourceLang,
                onSourceLangChange = actions.onSourceLangChange,
                subtitleFontSize = state.subtitleFontSize,
                onFontSizeChange = actions.onFontSizeChange,
                subtitleVerticalOffset = state.subtitleVerticalOffset,
                onVerticalOffsetChange = actions.onVerticalOffsetChange,
                subtitleSyncOffset = state.subtitleSyncOffset,
                onSyncOffsetChange = actions.onSyncOffsetChange,
                subtitleColor = state.subtitleColor,
                onColorChange = actions.onColorChange,
                subtitleBgColor = state.subtitleBgColor,
                onBgColorChange = actions.onBgColorChange,
                subtitleEdgeStyle = state.subtitleEdgeStyle,
                onEdgeStyleChange = actions.onEdgeStyleChange,
                targetLang = state.targetLang,
                onTargetLangChange = actions.onTargetLangChange,
                playbackSpeed = state.playbackSpeed,
                onPlaybackSpeedChange = actions.onPlaybackSpeedChange,
                videoQualityProfile = state.videoQualityProfile,
                onVideoQualityProfileChange = actions.onVideoQualityProfileChange,
                matchContentFrameRate = state.matchContentFrameRate,
                onMatchContentFrameRateChange = actions.onMatchContentFrameRateChange,
                audioOffloadEnabled = state.audioOffloadEnabled,
                onAudioOffloadChange = actions.onAudioOffloadChange,
                audioDelayMs = state.audioDelayMs,
                onAudioDelayChange = actions.onAudioDelayChange,
                preferredAudioLanguage = state.preferredAudioLanguage,
                onPreferredAudioLanguageChange = actions.onPreferredAudioLanguageChange,
                repeatCurrentMedia = state.repeatCurrentMedia,
                onRepeatCurrentMediaChange = actions.onRepeatCurrentMediaChange,
                showDiagnostics = state.showDiagnostics,
                onShowDiagnosticsChange = actions.onShowDiagnosticsChange,
                sleepTimerMinutes = state.sleepTimerMinutes,
                onSleepTimerMinutesChange = actions.onSleepTimerMinutesChange,
                skipSilenceEnabled = state.skipSilenceEnabled,
                onSkipSilenceChange = actions.onSkipSilenceChange,
                autoSyncState = state.autoSyncState,
                onStartAutoSync = actions.onStartAutoSync,
                onMarkAutoSyncAudio = actions.onMarkAutoSyncAudio,
                onMarkAutoSyncSubtitle = actions.onMarkAutoSyncSubtitle,
                onApplyAutoSync = actions.onApplyAutoSync,
                onCancelAutoSync = actions.onCancelAutoSync,
                videoResizeMode = state.videoResizeMode,
                onVideoResizeModeChange = actions.onVideoResizeModeChange,
                aspectRatioOverride = state.aspectRatioOverride,
                onAspectRatioOverrideChange = actions.onAspectRatioOverrideChange,
                videoZoom = state.videoZoom,
                videoPanX = state.videoPanX,
                videoPanY = state.videoPanY,
                onZoomChange = actions.onZoomChange,
                onPanChange = actions.onPanChange,
                availableLanguages = state.availableLanguages,
                onAppLanguageChange = actions.onAppLanguageChange,
                currentAppLang = state.currentAppLang,
                onShowAbout = actions.onShowAbout,
                externalSubtitles = state.externalSubtitles,
                translatedMapSize = state.translatedMapSize,
                isTranslatingAll = state.isTranslatingAll,
                onTranslateAll = actions.onTranslateAll,
                onSaveTranslated = actions.onSaveTranslated,
                onCheckUpdate = actions.onCheckUpdate
            )
        }

        DialogType.Tracks -> {
            if (player != null) {
                TrackSelectionDialog(
                    player = player,
                    onDismiss = actions.onDismiss,
                    currentAppLang = state.currentAppLang,
                    useExternalSubtitles = state.useExternalSubtitles,
                    onUseExternalSubtitlesChange = actions.onUseExternalSubtitlesChange,
                    onPickExternalSubtitles = actions.onPickExternalSubtitles,
                )
            } else {
                LaunchedEffect(activeDialog) { actions.onDismiss() }
            }
        }

        DialogType.SubtitleSearch -> {
            // remember() must not sit on one side of an elvis expression: the slot is then
            // claimed only on some compositions. Remember unconditionally, then choose.
            val fallbackService = remember(prefs) {
                SubtitleSearchService(prefs.getSubtitleProviderConfig())
            }
            val service = subtitleSearchService ?: fallbackService
            SubtitleSearchDialog(
                onDismiss = actions.onDismiss,
                onSubtitleSelected = actions.onSubtitleSelected,
                subtitleSearchService = service,
                context = context,
                currentVideoUri = state.currentVideoUri,
                prefs = prefs
            )
        }

        DialogType.OpenStream -> {
            OpenStreamDialog(
                onDismiss = actions.onDismiss,
                onPlay = actions.onOpenStream,
                currentAppLang = state.currentAppLang,
            )
        }

        DialogType.AboutDeveloper -> {
            AboutDeveloperDialog(
                onDismiss = actions.onDismiss,
                appLang = state.currentAppLang
            )
        }


        is DialogType.Update -> {
            UpdateDialog(
                updateInfo = activeDialog.info,
                appLang = state.currentAppLang,
                onUpdate = { actions.onUpdateDownload(activeDialog.info) },
                onDismiss = actions.onDismiss
            )
        }
    }
}
