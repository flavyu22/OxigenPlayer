// SPDX-License-Identifier: GPL-3.0-only
package com.oxigenplayer.app

internal const val SUBTITLE_SYNC_MIN_MS = -10_000L
internal const val SUBTITLE_SYNC_MAX_MS = 10_000L

/** One remote press: small enough to land on lip-sync, large enough to be usable from a D-pad. */
internal const val SUBTITLE_SYNC_FINE_STEP_MS = 50L
internal const val SUBTITLE_SYNC_COARSE_STEP_MS = 500L

/**
 * Subtitle timing convention used everywhere in the app: a cue is shown while
 * `playerPosition - offset` falls inside the cue. A positive offset therefore makes subtitles
 * appear later, a negative one earlier.
 */
internal fun clampSubtitleSyncOffset(offsetMs: Long): Long =
    offsetMs.coerceIn(SUBTITLE_SYNC_MIN_MS, SUBTITLE_SYNC_MAX_MS)

internal fun nudgeSubtitleSyncOffset(currentOffsetMs: Long, deltaMs: Long): Long =
    clampSubtitleSyncOffset(clampSubtitleSyncOffset(currentOffsetMs) + deltaMs)

/**
 * Two-mark calibration. The viewer marks the moment a line is *heard* and the moment the
 * subtitle for that same line *appears*, in whichever order they happen.
 *
 * If the subtitle shows up `lag = subtitle - audio` after the line is spoken, it has to be
 * pulled earlier by that amount, so the lag is subtracted from the offset already in effect.
 * (The previous implementation used the lag itself as the new offset: wrong sign, and it
 * discarded any offset the viewer had already dialled in.)
 */
internal fun suggestSubtitleSyncOffset(
    currentOffsetMs: Long,
    audioPositionMs: Long,
    subtitlePositionMs: Long,
): Long = clampSubtitleSyncOffset(
    clampSubtitleSyncOffset(currentOffsetMs) - (subtitlePositionMs - audioPositionMs),
)

/** Phase of the two-mark calibration. */
enum class AutoSyncPhase { IDLE, MARKING, SHOW_RESULT }

/** Calibration state. A mark of `-1` has not been set yet. */
data class AutoSyncState(
    val phase: AutoSyncPhase = AutoSyncPhase.IDLE,
    val audioMs: Long = -1L,
    val subtitleMs: Long = -1L,
    val suggestedOffsetMs: Long = 0L,
) {
    val hasAudioMark: Boolean get() = audioMs >= 0L
    val hasSubtitleMark: Boolean get() = subtitleMs >= 0L
}

internal enum class AutoSyncMark { AUDIO, SUBTITLE }

/**
 * Records one mark. Marks may arrive in either order and may be re-pressed to correct a slip;
 * the result is produced as soon as both exist. Ignored outside [AutoSyncPhase.MARKING].
 */
internal fun autoSyncMarkTransition(
    state: AutoSyncState,
    mark: AutoSyncMark,
    positionMs: Long,
    currentOffsetMs: Long,
): AutoSyncState {
    if (state.phase != AutoSyncPhase.MARKING || positionMs < 0L) return state
    val marked = when (mark) {
        AutoSyncMark.AUDIO -> state.copy(audioMs = positionMs)
        AutoSyncMark.SUBTITLE -> state.copy(subtitleMs = positionMs)
    }
    if (!marked.hasAudioMark || !marked.hasSubtitleMark) return marked
    return marked.copy(
        phase = AutoSyncPhase.SHOW_RESULT,
        suggestedOffsetMs = suggestSubtitleSyncOffset(
            currentOffsetMs = currentOffsetMs,
            audioPositionMs = marked.audioMs,
            subtitlePositionMs = marked.subtitleMs,
        ),
    )
}
