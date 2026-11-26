// SPDX-License-Identifier: GPL-3.0-only
package com.oxigenplayer.app

import kotlin.math.roundToInt

/** Pure timeline helpers shared by the player UI and unit tests. */
internal fun timelineFraction(positionMs: Long, durationMs: Long): Float {
    if (durationMs <= 0L) return 0f
    return (positionMs.toDouble() / durationMs.toDouble()).toFloat().coerceIn(0f, 1f)
}

internal fun timelinePositionFromPixels(
    xPx: Float,
    widthPx: Float,
    durationMs: Long,
): Long {
    if (durationMs <= 0L || widthPx <= 0f || !xPx.isFinite() || !widthPx.isFinite()) return 0L
    val fraction = (xPx / widthPx).coerceIn(0f, 1f)
    return (durationMs.toDouble() * fraction.toDouble()).toLong().coerceIn(0L, durationMs)
}

internal fun timelineRemainingMs(positionMs: Long, durationMs: Long): Long =
    if (durationMs <= 0L) 0L else (durationMs - positionMs).coerceIn(0L, durationMs)

internal fun timelineBufferedPercent(bufferedPositionMs: Long, durationMs: Long): Int =
    if (durationMs <= 0L) 0 else (timelineFraction(bufferedPositionMs, durationMs) * 100f)
        .roundToInt()
        .coerceIn(0, 100)
