// SPDX-License-Identifier: GPL-3.0-only
package com.oxigenplayer.app

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.Immutable
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Locale

@Immutable
data class PlayerUiState(
    val hasMedia: Boolean = false,
    val isPlaying: Boolean = false,
    val playWhenReady: Boolean = false,
    val playbackPhase: PlaybackPhase = PlaybackPhase.IDLE,
    val isLive: Boolean = false,
    val isSeekable: Boolean = false,
    val shouldKeepScreenOn: Boolean = false,
    val duration: Long = 0L,
    val showControls: Boolean = true,
    val showOnlyTopBar: Boolean = false,
    val showOnlyBottomBar: Boolean = false,
    val showOnlyPlayButton: Boolean = false,
    val isLoading: Boolean = false,
    val playbackError: String? = null,
    val isPipMode: Boolean = false,
    val playbackSpeed: Float = 1.0f,
    val activeDialog: DialogType? = null,
    val isSeeking: Boolean = false,
    val seekPosition: Long = -1L,
    val lockedMode: LockedMode = LockedMode.NONE,
    val abRepeat: AbRepeatState = AbRepeatState(),
)

enum class LockedMode {
    NONE,           // Nu este nici un mod blocat
    TOP_BAR_ONLY,   // Doar top bar este vizibil, bottom/play sunt blocate
    BOTTOM_BAR_ONLY // Doar bottom bar este vizibil, top/play sunt blocate
}

sealed interface DialogType {
    object MediaBrowser : DialogType
    object Settings : DialogType
    object Tracks : DialogType
    object SubtitleSearch : DialogType
    object AboutDeveloper : DialogType
    object OpenStream : DialogType
    data class Update(val info: UpdateInfo) : DialogType
}

@Immutable
enum class SubtitleEdgeStyle {
    NONE, OUTLINE, SHADOW
}

/** Subtitle sync panel state. The calibration itself lives in [AutoSyncState]. */
@Immutable
data class SubtitleSyncState(
    val syncOffset: Long = 0L,
    val isAdjusting: Boolean = false,
)

@Immutable
data class SubtitleState(
    val originalText: String = "",
    val translatedText: String = "",
    val isVisible: Boolean = true,
    val fontSize: Float = 36f,
    val textColorArgb: Int = -1,
    val backgroundColorArgb: Int = 0x00000000,
    val verticalOffset: Float = 0f,
    val syncOffset: Long = 0L,
    val subtitleEdgeStyle: SubtitleEdgeStyle = SubtitleEdgeStyle.SHADOW,
    val isTranslating: Boolean = false,
    val useExternal: Boolean = true,
    val isTranslationEnabled: Boolean = false,
    val translationSource: TranslationSource = TranslationSource.AUTO,
    val sourceLang: String = "auto",
    val targetLang: String = "ro",
    val translationProviderConfig: TranslationProviderConfig = TranslationProviderConfig(),
    val mlKitAllowed: Boolean = true,
    val geminiNanoIncluded: Boolean = false,
    val geminiNanoAvailable: Boolean = false,
)

/**
 * Orchestrates playback-related engines and retains UI state across configuration changes.
 */
class PlayerViewModel(
    application: Application,
    private val savedStateHandle: SavedStateHandle,
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(PlayerUiState())
    val uiState = _uiState.asStateFlow()

    private val _currentPosition = MutableStateFlow(0L)
    val currentPosition = _currentPosition.asStateFlow()

    // Kept out of PlayerUiState on purpose: the buffered position advances on almost every
    // poll while a stream loads, and every uiState copy recomposes the whole player screen.
    // The timeline reads this flow only from its draw phase.
    private val _bufferedPosition = MutableStateFlow(0L)
    val bufferedPosition = _bufferedPosition.asStateFlow()

    private val _subtitleState = MutableStateFlow(SubtitleState())
    val subtitleState = _subtitleState.asStateFlow()

    private val _autoSyncState = MutableStateFlow(AutoSyncState())
    val autoSyncState = _autoSyncState.asStateFlow()

    private val _subtitleSyncState = MutableStateFlow(SubtitleSyncState())
    val subtitleSyncState = _subtitleSyncState.asStateFlow()

    /** Sets the subtitle offset of the current title: applied live and remembered per file. */
    fun updateSubtitleSyncOffset(offset: Long) {
        val clamped = clampSubtitleSyncOffset(offset)
        prefs.saveSubtitleSyncOffset(selectedMediaUri.value, clamped)
        applySubtitleSyncOffset(clamped)
    }

    fun nudgeSubtitleSync(deltaMs: Long) {
        updateSubtitleSyncOffset(nudgeSubtitleSyncOffset(_subtitleSyncState.value.syncOffset, deltaMs))
    }

    /**
     * Opens the on-screen sync panel over the playing video. Any dialog is closed first: timing
     * can only be judged with picture, sound and subtitle visible and the position advancing.
     */
    fun openSubtitleSync() {
        _uiState.update {
            it.copy(
                activeDialog = null,
                showControls = true,
                showOnlyTopBar = false,
                showOnlyBottomBar = false,
                showOnlyPlayButton = false,
                lockedMode = LockedMode.NONE,
            )
        }
        _subtitleSyncState.update { it.copy(isAdjusting = true) }
        cancelHideTimer()
    }

    fun closeSubtitleSync() {
        _autoSyncState.value = AutoSyncState()
        _subtitleSyncState.update { it.copy(isAdjusting = false) }
        startHideTimer()
    }

    fun startSubtitleSyncAdjust() = openSubtitleSync()

    fun cancelSubtitleSyncAdjust() = closeSubtitleSync()

    /** Loads the offset remembered for [uri]; a title never inherits another title's offset. */
    private fun restoreSubtitleSyncFor(uri: String?) {
        _autoSyncState.value = AutoSyncState()
        applySubtitleSyncOffset(prefs.getSubtitleSyncOffset(uri))
    }

    private fun applySubtitleSyncOffset(offset: Long) {
        _subtitleSyncState.update { it.copy(syncOffset = offset) }
        _subtitleState.update { it.copy(syncOffset = offset) }
        syncEngine()
    }

    val prefs = (application as OxigenApp).prefs
    private val lowRamMode = DevicePerformance.isLowRamDevice(application)
    private val mlKitAllowed = true
    private val localGenAiTranslator = LocalGenAiTranslatorFactory.create()
    private val geminiNanoIncluded = localGenAiTranslator.isIncluded
    private val savedProviderConfig = prefs.getTranslationProviderConfig()
    private val savedSourceLanguage = prefs.getSourceLanguage()

    private val savedTranslationSource = ProviderAvailability.safeTranslationSource(
        source = runCatching {
            TranslationSource.valueOf(prefs.getTranslationSource())
        }.getOrDefault(TranslationSource.AUTO),
        config = savedProviderConfig,
        mlKitAllowed = mlKitAllowed,
        geminiNanoAvailable = geminiNanoIncluded,
    )

    val selectedMediaUri = savedStateHandle.getStateFlow<String?>(KEY_MEDIA_URI, null)
    val selectedMediaMimeType = savedStateHandle.getStateFlow<String?>(KEY_MEDIA_MIME_TYPE, null)
    private val restorePlayWhenReady = savedStateHandle.getStateFlow(KEY_PLAY_WHEN_READY, false)
    private val _mediaSelectionRevision = MutableStateFlow(0L)
    val mediaSelectionRevision = _mediaSelectionRevision.asStateFlow()
    private var pendingExplicitSelection: String? = null

    // Engines are scoped to ViewModel to persist across config changes
    val translationManager = TranslationManager(
        mlKitAllowed = mlKitAllowed,
        localGenAiTranslator = localGenAiTranslator,
        initialProviderConfig = savedProviderConfig,
        lowRamMode = lowRamMode,
    ).apply {
        setTargetLanguage(prefs.getTargetLanguage())
        if (savedSourceLanguage == "auto") useAutomaticSourceLanguage()
        else setSourceLanguage(savedSourceLanguage)
        currentSource = savedTranslationSource
    }

    val subtitleEngine = SubtitleEngine(translationManager, viewModelScope, lowRamMode)

    val isTranslatingAll = subtitleEngine.isTranslatingAll
    val translatedCount = subtitleEngine.translatedCount

    private var hideTimerJob: Job? = null
    private var seekJob: Job? = null
    private var lastSeekInputAtMs = 0L
    private var lastPersistedPosition = -1L

    init {
        // Load initial settings
        _uiState.update { it.copy(
            playbackSpeed = prefs.getPlaybackSpeed()
        )}

        _subtitleState.update { it.copy(
            isVisible = prefs.isSubtitlesVisible(),
            fontSize = prefs.getSubtitleFontSize(),
            textColorArgb = prefs.getSubtitleColor(),
            backgroundColorArgb = prefs.getSubtitleBackgroundColor(),
            verticalOffset = prefs.getSubtitleVerticalOffset(),
            syncOffset = prefs.getSubtitleSyncOffset(selectedMediaUri.value),
            // The stored value is a lowercase tag written by saveSubtitleEdgeStyle; an unknown or
            // corrupted tag falls back to the default instead of crashing the settings dialog.
            subtitleEdgeStyle = runCatching {
                SubtitleEdgeStyle.valueOf(prefs.getSubtitleEdgeStyle().uppercase(Locale.ROOT))
            }.getOrDefault(SubtitleEdgeStyle.SHADOW),
            isTranslationEnabled = prefs.isTranslationEnabled(),
            translationSource = savedTranslationSource,
            sourceLang = savedSourceLanguage,
            targetLang = prefs.getTargetLanguage(),
            translationProviderConfig = savedProviderConfig,
            mlKitAllowed = mlKitAllowed,
            geminiNanoIncluded = geminiNanoIncluded,
            geminiNanoAvailable = false,
        )}

        // Sync SubtitleEngine with saved preferences
        val currentState = _subtitleState.value
        _subtitleSyncState.update { it.copy(syncOffset = currentState.syncOffset) }
        subtitleEngine.updateConfig(
            enabled = currentState.isTranslationEnabled,
            lang = currentState.targetLang,
            offset = currentState.syncOffset,
            useExternal = currentState.useExternal
        )
        subtitleEngine.setPresentationActive(currentState.isVisible)

        requestLocalTranslationModelIfNeeded()
        refreshGeminiNanoAvailability()
    }

    fun selectMedia(uri: Uri, autoPlay: Boolean = true, mimeType: String? = null) {
        val uriString = uri.toString()
        // Save the active item before changing the SavedState URI. Otherwise a final poll from
        // the old player could be written under the newly selected media key.
        persistPlaybackPosition(force = true)
        resetSubtitlesOnMediaChange(selectedMediaUri.value, uriString)
        pendingExplicitSelection = uriString
        savedStateHandle[KEY_MEDIA_URI] = uriString
        savedStateHandle[KEY_MEDIA_MIME_TYPE] = normalizeMimeType(mimeType)
        savedStateHandle[KEY_PLAY_WHEN_READY] = autoPlay
        _mediaSelectionRevision.value += 1L
        lastPersistedPosition = -1L
        resetSeekState()
        _currentPosition.value = 0L
        _bufferedPosition.value = 0L

        restoreSubtitleSyncFor(uriString)
        _uiState.update {
            it.copy(
                hasMedia = true,
                isPlaying = false,
                playWhenReady = autoPlay,
                playbackPhase = PlaybackPhase.IDLE,
                isLive = false,
                isSeekable = false,
                shouldKeepScreenOn = false,
                duration = 0L,
                isLoading = false,
                playbackError = null,
                showControls = true,
                showOnlyTopBar = false,
                showOnlyBottomBar = false,
                showOnlyPlayButton = false,
                lockedMode = LockedMode.NONE,
                abRepeat = AbRepeatState(),
            )
        }
    }

    fun adoptSessionMedia(uri: String) = adoptSessionMedia(uri, mimeType = null)

    fun adoptSessionMedia(uri: String, mimeType: String?) {
        if ((pendingExplicitSelection == null) && (selectedMediaUri.value != uri)) {
            resetSubtitlesOnMediaChange(selectedMediaUri.value, uri)
            savedStateHandle[KEY_MEDIA_URI] = uri
            // A session item can originate outside this Activity. Never carry a
            // MIME hint from the previously selected URI into the new item.
            savedStateHandle[KEY_MEDIA_MIME_TYPE] = normalizeMimeType(mimeType)
        } else if (selectedMediaUri.value == uri && mimeType != null) {
            savedStateHandle[KEY_MEDIA_MIME_TYPE] = normalizeMimeType(mimeType)
        }
    }

    fun shouldPlayWhenReady(): Boolean = restorePlayWhenReady.value

    fun consumeExplicitSelection(uri: Uri): Boolean {
        val uriString = uri.toString()
        if (pendingExplicitSelection != uriString) return false
        pendingExplicitSelection = null
        return true
    }

    fun resumePositionFor(uri: Uri): Long = prefs.getVideoPosition(uri.toString()).coerceAtLeast(0L)

    fun beginMediaLoad(uri: Uri, mimeType: String? = null) {
        val uriString = uri.toString()
        if (selectedMediaUri.value != uriString) {
            resetSubtitlesOnMediaChange(selectedMediaUri.value, uriString)
            savedStateHandle[KEY_MEDIA_URI] = uriString
            savedStateHandle[KEY_MEDIA_MIME_TYPE] = normalizeMimeType(mimeType)
        } else if (mimeType != null) {
            savedStateHandle[KEY_MEDIA_MIME_TYPE] = normalizeMimeType(mimeType)
        }
        resetSeekState()
        _currentPosition.value = 0L
        _bufferedPosition.value = 0L
        _uiState.update {
            it.copy(
                hasMedia = true,
                isPlaying = false,
                playWhenReady = restorePlayWhenReady.value,
                playbackPhase = PlaybackPhase.BUFFERING,
                isLive = false,
                isSeekable = false,
                shouldKeepScreenOn = restorePlayWhenReady.value,
                duration = 0L,
                isLoading = true,
                playbackError = null,
                showControls = true,
                lockedMode = LockedMode.NONE,
                abRepeat = AbRepeatState(),
            )
        }
    }

    fun onPlaybackSnapshot(snapshot: PlaybackSnapshot) {
        val pendingSelection = pendingExplicitSelection
        if (!shouldAcceptPlaybackSnapshot(pendingSelection, snapshot.mediaUri)) {
            // A just-selected URI wins over the stale service snapshot until setMediaItem runs.
            return
        }

        if (snapshot.mediaUri != null && selectedMediaUri.value != snapshot.mediaUri) {
            resetSubtitlesOnMediaChange(selectedMediaUri.value, snapshot.mediaUri)
            savedStateHandle[KEY_MEDIA_URI] = snapshot.mediaUri
            savedStateHandle[KEY_MEDIA_MIME_TYPE] = null
        }

        val selectedUri = selectedMediaUri.value
        val snapshotMatchesSelection = snapshot.mediaUri != null &&
            (selectedUri == null || selectedUri == snapshot.mediaUri)
        if (snapshotMatchesSelection) {
            savedStateHandle[KEY_PLAY_WHEN_READY] = snapshot.playWhenReady
        }

        val position = normalizedTimelineValue(snapshot.positionMs)
        val duration = normalizedTimelineValue(snapshot.durationMs)
        val bufferedPosition = normalizedTimelineValue(snapshot.bufferedPositionMs)
        _currentPosition.value = position
        _bufferedPosition.value = bufferedPosition

        val mustShowControls = shouldForcePlaybackControlsVisible(snapshot)

        _uiState.update {
            it.copy(
                hasMedia = snapshot.hasMedia,
                isPlaying = snapshot.isPlaying,
                playWhenReady = snapshot.playWhenReady,
                playbackPhase = snapshot.phase,
                isLive = snapshot.isLive,
                isSeekable = snapshot.hasMedia && snapshot.isSeekable,
                shouldKeepScreenOn = snapshot.shouldKeepScreenOn,
                duration = duration,
                isLoading = snapshot.phase == PlaybackPhase.BUFFERING,
                playbackError = snapshot.errorMessage,
                showControls = mustShowControls || it.showControls,
                showOnlyTopBar = !mustShowControls && it.showOnlyTopBar,
                showOnlyBottomBar = !mustShowControls && it.showOnlyBottomBar,
                showOnlyPlayButton = !mustShowControls && it.showOnlyPlayButton,
                lockedMode = if (mustShowControls) LockedMode.NONE else it.lockedMode,
            )
        }

        subtitleEngine.onPositionChanged(position)
        when {
            snapshot.phase == PlaybackPhase.ENDED && snapshot.mediaUri != null -> {
                prefs.saveVideoPosition(snapshot.mediaUri, 0L)
                lastPersistedPosition = 0L
            }
            snapshotMatchesSelection -> persistPlaybackPosition()
        }

        if (snapshot.isPlaying) startHideTimer() else cancelHideTimer()
    }

    private fun resetSubtitlesOnMediaChange(previousUri: String?, nextUri: String) {
        if (previousUri != nextUri) {
            subtitleEngine.resetForNewMedia()
            restoreSubtitleSyncFor(nextUri)
        }
    }

    fun onPositionChanged(pos: Long, duration: Long, bufferPos: Long) {
        // Position polling belongs to the previously loaded player until the explicit selection
        // is consumed. Do not let it repopulate the freshly reset timeline.
        if (pendingExplicitSelection != null) return
        val safePosition = normalizedTimelineValue(pos)
        val safeDuration = normalizedTimelineValue(duration)
        val safeBufferPosition = normalizedTimelineValue(bufferPos)
        _currentPosition.value = safePosition
        _bufferedPosition.value = safeBufferPosition
        if (_uiState.value.duration != safeDuration) {
            _uiState.update { it.copy(duration = safeDuration) }
        }
        subtitleEngine.onPositionChanged(safePosition)
        persistPlaybackPosition()
    }

    fun persistPlaybackPosition(force: Boolean = false) {
        val uri = selectedMediaUri.value ?: return
        val state = _uiState.value
        if (!canPersistPlaybackPosition(
                pendingMediaUri = pendingExplicitSelection,
                hasMedia = state.hasMedia,
                isLive = state.isLive,
                durationMs = state.duration,
            )
        ) return

        val position = playbackCheckpoint(
            positionMs = _currentPosition.value,
            durationMs = state.duration,
            ended = state.playbackPhase == PlaybackPhase.ENDED,
        )
        if (!force && lastPersistedPosition >= 0L &&
            kotlin.math.abs(position - lastPersistedPosition) < POSITION_SAVE_INTERVAL_MS
        ) {
            return
        }
        prefs.saveVideoPosition(uri, position)
        lastPersistedPosition = position
    }

    fun toggleControls() {
        // Keep controls visible while no media is loaded.
        if (!_uiState.value.hasMedia) {
            _uiState.update { it.copy(
                showControls = true,
                showOnlyTopBar = false,
                showOnlyBottomBar = false,
                showOnlyPlayButton = false,
                lockedMode = LockedMode.NONE
            )}
            return
        }

        val newState = !uiState.value.showControls
        _uiState.update { it.copy(
            showControls = newState,
            showOnlyTopBar = false,
            showOnlyBottomBar = false,
            showOnlyPlayButton = false,
            lockedMode = LockedMode.NONE
        )}
        if (newState) startHideTimer() else cancelHideTimer()
    }

    fun showControlsTemporarily(
        top: Boolean = false,
        bottom: Boolean = false,
        play: Boolean = false,
        all: Boolean = false
    ) {
        // In idle state, show every control and clear modal interaction locks.
        val isIdle = !_uiState.value.hasMedia
        val forceAll = all || isIdle

        _uiState.update { it.copy(
            showControls = forceAll,
            showOnlyTopBar = top && !forceAll,
            showOnlyBottomBar = bottom && !forceAll,
            showOnlyPlayButton = play && !forceAll,
            lockedMode = if (isIdle) LockedMode.NONE else when {
                top && !forceAll -> LockedMode.TOP_BAR_ONLY
                bottom && !forceAll -> LockedMode.BOTTOM_BAR_ONLY
                else -> LockedMode.NONE
            }
        )}
        if (_uiState.value.isPlaying) startHideTimer() else cancelHideTimer()
    }

    fun hideControls() {
        // Do not hide controls when nothing is playing.
        if (!_uiState.value.hasMedia) {
            _uiState.update { it.copy(
                showControls = true,
                showOnlyTopBar = false,
                showOnlyBottomBar = false,
                showOnlyPlayButton = false,
                lockedMode = LockedMode.NONE
            )}
            return
        }

        _uiState.update { it.copy(
            showControls = false,
            showOnlyTopBar = false,
            showOnlyBottomBar = false,
            showOnlyPlayButton = false,
            lockedMode = LockedMode.NONE
        )}
        _autoSyncState.value = AutoSyncState()
        _subtitleSyncState.update { it.copy(isAdjusting = false) }
        cancelHideTimer()
    }

    fun startHideTimer() {
        hideTimerJob?.cancel()

        // Paused, ended, idle and failed playback must keep their controls on
        // screen. Several UI paths call this method unconditionally after a
        // seek/dialog action, so the guard belongs here rather than at callers.
        val state = _uiState.value
        if (!state.hasMedia || !state.isPlaying || state.activeDialog != null) return
        // The sync panel is used while the film plays and the viewer waits for a line of
        // dialogue, often longer than the hide delay. It stays until it is closed.
        if (_subtitleSyncState.value.isAdjusting) return

        hideTimerJob = viewModelScope.launch {
            delay(3500) // Reduced from 5000 ms for faster interaction.
            val latestState = _uiState.value
            if (!latestState.isPlaying || latestState.activeDialog != null) return@launch
            if (_subtitleSyncState.value.isAdjusting) return@launch
            if (latestState.isSeeking) {
                // Reset the timer while seeking continues.
                startHideTimer()
                return@launch
            }
            hideControls()
        }
    }

    fun onSeekEvent(delta: Long) {
        if (delta == 0L) return
        val state = _uiState.value
        if (!state.isSeekable) return

        val target = calculateSeekTarget(
            currentPositionMs = _currentPosition.value,
            pendingPositionMs = state.seekPosition,
            isSeeking = state.isSeeking,
            durationMs = state.duration,
            deltaMs = delta,
        )

        _uiState.update {
            it.copy(
                isSeeking = true,
                seekPosition = target,
                // Ensure the bottom bar (batbarul) appears during seeking if no other UI is shown.
                showOnlyBottomBar = if (!it.showControls) true else it.showOnlyBottomBar,
                lockedMode = if (!it.showControls) LockedMode.BOTTOM_BAR_ONLY else it.lockedMode
            )
        }
        
        // Keep the UI visible while seeking
        startHideTimer()

        // One coroutine per seek burst instead of cancelling/recreating a Job on every
        // remote key repeat. This keeps CPU/allocation pressure low on entry-level TVs.
        lastSeekInputAtMs = System.nanoTime() / 1_000_000L
        if (seekJob?.isActive != true) {
            seekJob = viewModelScope.launch {
                while (true) {
                    val idleForMs = (System.nanoTime() / 1_000_000L) - lastSeekInputAtMs
                    val waitMs = REMOTE_SEEK_COMMIT_IDLE_MS - idleForMs
                    if (waitMs <= 0L) break
                    delay(waitMs)
                }
                onSeekFinished()
            }
        }
    }

    fun onManualSeek(pos: Long) {
        if (!_uiState.value.isSeekable) return
        val duration = _uiState.value.duration
        val upperBound = if (duration > 0L) duration else Long.MAX_VALUE
        _uiState.update { it.copy(
            isSeeking = true,
            seekPosition = pos.coerceIn(0L, upperBound),
            showOnlyBottomBar = if (!it.showControls) true else it.showOnlyBottomBar,
            lockedMode = if (!it.showControls) LockedMode.BOTTOM_BAR_ONLY else it.lockedMode
        ) }
        startHideTimer()
        // Touch scrubbing is preview-only. The slider calls onSeekFinished on release/cancel,
        // which prevents repeated decoder flushes while the finger is moving.
        seekJob?.cancel()
    }

    fun onSeekFinished() {
        seekJob?.cancel()
        val finalPos = _uiState.value.seekPosition
        if (finalPos >= 0) {
            _currentPosition.value = finalPos
        }
        _uiState.update { it.copy(isSeeking = false) }
    }

    /**
     * Clears residual seek state when a new video is loaded
     * to prevent a stale seek from overriding the resume position.
     */
    fun resetSeekState() {
        seekJob?.cancel()
        _uiState.update { it.copy(isSeeking = false, seekPosition = -1L) }
        lastSeekInputAtMs = 0L
    }

    /** Consumes the final seek command after the player has applied it once. */
    fun consumeFinalSeek(position: Long) {
        _uiState.update { state ->
            if (!state.isSeeking && state.seekPosition == position) {
                state.copy(seekPosition = -1L)
            } else state
        }
    }

    fun cancelHideTimer() {
        hideTimerJob?.cancel()
    }

    fun updateSubtitleVisibility(visible: Boolean) {
        _subtitleState.update { it.copy(isVisible = visible) }
        subtitleEngine.setPresentationActive(visible)
        prefs.saveSubtitlesVisible(visible)
    }

    fun updateFontSize(size: Float) {
        _subtitleState.update { it.copy(fontSize = size) }
        prefs.saveSubtitleFontSize(size)
    }

    fun updateSubtitleColor(colorArgb: Int) {
        _subtitleState.update { it.copy(textColorArgb = colorArgb) }
        prefs.saveSubtitleColor(colorArgb)
    }

    fun updateSubtitleBackgroundColor(colorArgb: Int) {
        _subtitleState.update { it.copy(backgroundColorArgb = colorArgb) }
        prefs.saveSubtitleBackgroundColor(colorArgb)
    }

    fun updateSubtitleEdgeStyle(style: SubtitleEdgeStyle) {
        _subtitleState.update { it.copy(subtitleEdgeStyle = style) }
        prefs.saveSubtitleEdgeStyle(style.name.lowercase(Locale.ROOT))
    }

    fun updateVerticalOffset(offset: Float) {
        _subtitleState.update { it.copy(verticalOffset = offset) }
        prefs.saveSubtitleVerticalOffset(offset)
    }

    // ----- Two-mark calibration -----

    fun startAutoSync() {
        if (!_subtitleSyncState.value.isAdjusting) openSubtitleSync()
        _autoSyncState.value = AutoSyncState(phase = AutoSyncPhase.MARKING)
    }

    /** [positionMs] should be read from the player at the key press, not from the 250 ms poll. */
    fun markAutoSyncAudio(positionMs: Long = _currentPosition.value) =
        markAutoSync(AutoSyncMark.AUDIO, positionMs)

    fun markAutoSyncSubtitle(positionMs: Long = _currentPosition.value) =
        markAutoSync(AutoSyncMark.SUBTITLE, positionMs)

    private fun markAutoSync(mark: AutoSyncMark, positionMs: Long) {
        _autoSyncState.update {
            autoSyncMarkTransition(it, mark, positionMs, _subtitleSyncState.value.syncOffset)
        }
    }

    fun applyAutoSync() {
        val state = _autoSyncState.value
        if (state.phase == AutoSyncPhase.SHOW_RESULT) {
            updateSubtitleSyncOffset(state.suggestedOffsetMs)
        }
        cancelAutoSync()
    }

    fun cancelAutoSync() {
        _autoSyncState.value = AutoSyncState()
    }

    fun setUseExternalSubtitles(use: Boolean) {
        _subtitleState.update { it.copy(useExternal = use) }
        syncEngine()
    }

    fun setTranslationEnabled(enabled: Boolean) {
        _subtitleState.update { it.copy(isTranslationEnabled = enabled) }
        prefs.saveTranslationEnabled(enabled)
        syncEngine()
        requestLocalTranslationModelIfNeeded()
        if (enabled) refreshGeminiNanoAvailability()
    }

    fun setTranslationSource(source: TranslationSource) {
        val state = _subtitleState.value
        val availableSource = ProviderAvailability.safeTranslationSource(
            source = source,
            config = state.translationProviderConfig,
            mlKitAllowed = state.mlKitAllowed,
            geminiNanoAvailable = state.geminiNanoAvailable,
        )
        _subtitleState.update { it.copy(translationSource = availableSource) }
        prefs.saveTranslationSource(availableSource.name)
        translationManager.currentSource = availableSource
        subtitleEngine.clearCache()
        syncEngine()
        requestLocalTranslationModelIfNeeded()
    }

    fun setSourceLang(lang: String) {
        val normalized = if (lang == "auto") "auto" else lang.lowercase(Locale.ROOT)
        if (normalized == "auto") translationManager.useAutomaticSourceLanguage()
        else translationManager.setSourceLanguage(normalized)
        _subtitleState.update { it.copy(sourceLang = normalized) }
        prefs.saveSourceLanguage(normalized)
        subtitleEngine.clearCache()
        syncEngine()
        requestLocalTranslationModelIfNeeded()
    }

    fun setTargetLang(lang: String) {
        _subtitleState.update { it.copy(targetLang = lang) }
        prefs.saveTargetLanguage(lang)
        syncEngine()
        requestLocalTranslationModelIfNeeded()
    }

    fun setTranslationProviderConfig(config: TranslationProviderConfig) {
        val normalized = config.normalized()
        prefs.saveTranslationProviderConfig(normalized)
        translationManager.updateProviderConfig(normalized)
        _subtitleState.update { state ->
            val safeSource = ProviderAvailability.safeTranslationSource(
                source = state.translationSource,
                config = normalized,
                mlKitAllowed = state.mlKitAllowed,
                geminiNanoAvailable = state.geminiNanoAvailable,
            )
            if (safeSource != state.translationSource) {
                prefs.saveTranslationSource(safeSource.name)
                translationManager.currentSource = safeSource
            }
            state.copy(
                translationSource = safeSource,
                translationProviderConfig = normalized,
            )
        }
        subtitleEngine.clearCache()
        syncEngine()
    }

    fun translateAll() {
        subtitleEngine.translateAll()
    }

    fun getTranslatedSrtContent(): String = subtitleEngine.getTranslatedSrtContent()

    private fun syncEngine() {
        val s = _subtitleState.value
        subtitleEngine.updateConfig(
            enabled = s.isTranslationEnabled,
            lang = s.targetLang,
            offset = s.syncOffset,
            useExternal = s.useExternal
        )
    }

    private fun refreshGeminiNanoAvailability() {
        if (!geminiNanoIncluded) return
        viewModelScope.launch {
            val available = runCatching { localGenAiTranslator.isSupported() }
                .getOrDefault(false)
            _subtitleState.update { state ->
                val safeSource = if (!available && state.translationSource == TranslationSource.GEMINI_NANO) {
                    TranslationSource.AUTO
                } else {
                    state.translationSource
                }
                if (safeSource != state.translationSource) {
                    prefs.saveTranslationSource(safeSource.name)
                    translationManager.currentSource = safeSource
                }
                state.copy(
                    translationSource = safeSource,
                    geminiNanoAvailable = available,
                )
            }
        }
    }

    private fun requestLocalTranslationModelIfNeeded() {
        val state = _subtitleState.value
        if (!state.isTranslationEnabled ||
            state.translationSource !in setOf(
                TranslationSource.AUTO,
                TranslationSource.MLKIT,
                TranslationSource.GEMINI_NANO,
            )
        ) {
            return
        }
        viewModelScope.launch { translationManager.downloadModelIfNeeded() }
    }

    fun setPipMode(enabled: Boolean) {
        _uiState.update { it.copy(isPipMode = enabled) }
    }

    fun updatePlaybackSpeed(speed: Float) {
        _uiState.update { it.copy(playbackSpeed = speed) }
        prefs.savePlaybackSpeed(speed)
    }

    /**
     * Advances the A-B loop control. While the user is scrubbing, the loop start/end follows
     * the previewed seek position rather than the last polled playback position.
     */
    fun onAbRepeatPressed() {
        _uiState.update { state ->
            val position = if (state.isSeeking && state.seekPosition >= 0L) {
                state.seekPosition
            } else {
                _currentPosition.value
            }
            state.copy(
                abRepeat = abRepeatButtonTransition(
                    state = state.abRepeat,
                    positionMs = position,
                    durationMs = state.duration,
                )
            )
        }
    }

    fun clearAbRepeat() {
        _uiState.update { it.copy(abRepeat = AbRepeatState()) }
    }

    fun showDialog(type: DialogType) {
        _uiState.update { it.copy(activeDialog = type) }
        cancelHideTimer()
    }

    fun dismissDialog() {
        _uiState.update { it.copy(activeDialog = null) }
        startHideTimer()
    }

    override fun onCleared() {
        persistPlaybackPosition(force = true)
        subtitleEngine.release()
        translationManager.release()
    }

    companion object {
        private const val REMOTE_SEEK_COMMIT_IDLE_MS = 160L
        private const val KEY_MEDIA_URI = "playback_media_uri"
        private const val KEY_MEDIA_MIME_TYPE = "playback_media_mime_type"
        private const val KEY_PLAY_WHEN_READY = "playback_play_when_ready"
        private const val POSITION_SAVE_INTERVAL_MS = 10_000L
    }
}

private fun normalizeMimeType(mimeType: String?): String? =
    mimeType?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT)?.takeIf { '/' in it }
