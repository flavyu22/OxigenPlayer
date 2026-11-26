// SPDX-License-Identifier: GPL-3.0-only
package com.oxigenplayer.app

/**
 * Stable, UI-facing playback phases. Keeping Media3 constants outside the UI state makes
 * transitions explicit and lets the state rules be tested without an Android runtime.
 */
enum class PlaybackPhase {
    IDLE,
    BUFFERING,
    READY,
    ENDED,
    ERROR,
}

data class PlaybackSnapshot(
    val hasMedia: Boolean,
    val isPlaying: Boolean,
    val playWhenReady: Boolean,
    val phase: PlaybackPhase,
    val positionMs: Long,
    val durationMs: Long,
    val bufferedPositionMs: Long,
    val isLive: Boolean,
    val isSeekable: Boolean,
    val mediaUri: String?,
    val errorMessage: String? = null,
    val videoWidth: Int = 0,
    val videoHeight: Int = 0,
    val pixelWidthHeightRatio: Float = 1f,
) {
    val shouldKeepScreenOn: Boolean
        get() = hasMedia && playWhenReady && (phase == PlaybackPhase.BUFFERING || phase == PlaybackPhase.READY)
}

/**
 * Display aspect ratio of the current video, or null when it is not known yet.
 *
 * Anamorphic content stores a non-square pixel ratio, so the stored resolution alone is not
 * the ratio the user sees.
 */
internal fun PlaybackSnapshot.displayAspectRatio(): Float? {
    if (videoWidth <= 0 || videoHeight <= 0) return null
    val ratio = pixelWidthHeightRatio.takeIf { it.isFinite() && it > 0f } ?: 1f
    return (videoWidth * ratio / videoHeight).takeIf { it.isFinite() && it > 0f }
}

/**
 * Android rejects Picture-in-Picture parameters outside roughly 1:2.39 .. 2.39:1, so the
 * requested ratio is clamped rather than allowed to throw.
 */
internal const val MIN_PIP_ASPECT_RATIO: Float = 1f / 2.39f
internal const val MAX_PIP_ASPECT_RATIO: Float = 2.39f

internal fun pictureInPictureRational(aspectRatio: Float?): Pair<Int, Int> {
    val ratio = (aspectRatio ?: (16f / 9f)).coerceIn(MIN_PIP_ASPECT_RATIO, MAX_PIP_ASPECT_RATIO)
    // A fixed denominator keeps the numerator well inside the platform's accepted range and
    // avoids the overflow that a naive width/height Rational hits on large resolutions.
    val denominator = 1_000
    val numerator = (ratio * denominator).toInt().coerceAtLeast(1)
    return numerator to denominator
}

internal fun normalizedTimelineValue(value: Long): Long = value.takeIf { it > 0L } ?: 0L

internal fun shouldForcePlaybackControlsVisible(snapshot: PlaybackSnapshot): Boolean =
    !snapshot.hasMedia ||
        !snapshot.playWhenReady ||
        snapshot.phase == PlaybackPhase.IDLE ||
        snapshot.phase == PlaybackPhase.ENDED ||
        snapshot.phase == PlaybackPhase.ERROR

internal fun shouldAcceptPlaybackSnapshot(
    pendingMediaUri: String?,
    snapshotMediaUri: String?,
): Boolean = pendingMediaUri == null || snapshotMediaUri == pendingMediaUri

internal fun canPersistPlaybackPosition(
    pendingMediaUri: String?,
    hasMedia: Boolean,
    isLive: Boolean,
    durationMs: Long,
): Boolean = pendingMediaUri == null && hasMedia && !isLive && durationMs > 0L

internal fun playbackCheckpoint(positionMs: Long, durationMs: Long, ended: Boolean): Long {
    if (ended || durationMs <= 0L) return 0L
    val position = positionMs.coerceIn(0L, durationMs)
    return if (position < 5_000L || durationMs - position < 5_000L) 0L else position
}
