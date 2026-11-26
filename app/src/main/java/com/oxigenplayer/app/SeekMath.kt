// SPDX-License-Identifier: GPL-3.0-only
package com.oxigenplayer.app

internal const val TV_REMOTE_SEEK_STEP_MS = 10_000L

/**
 * Calculates the next preview position for TV/touch seeking without overflowing Long.
 * Repeated remote presses accumulate from the pending preview position, not from the
 * decoder's older position, so no key press is lost while the final seek is debounced.
 */
internal fun calculateSeekTarget(
    currentPositionMs: Long,
    pendingPositionMs: Long,
    isSeeking: Boolean,
    durationMs: Long,
    deltaMs: Long,
): Long {
    val upperBound = if (durationMs > 0L) durationMs else Long.MAX_VALUE
    val start = (if (isSeeking && pendingPositionMs >= 0L) pendingPositionMs else currentPositionMs)
        .coerceIn(0L, upperBound)

    if (deltaMs >= 0L) {
        val room = upperBound - start
        return if (deltaMs >= room) upperBound else start + deltaMs
    }

    // Avoid negating Long.MIN_VALUE. Any delta whose magnitude is greater than
    // the current position simply clamps to the start of the media.
    if (deltaMs == Long.MIN_VALUE) return 0L
    val backwards = -deltaMs
    return if (backwards >= start) 0L else start - backwards
}
