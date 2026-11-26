// SPDX-License-Identifier: GPL-3.0-only
package com.oxigenplayer.app

/** Prefix maxima let overlapping cues be queried without scanning the entire subtitle document. */
internal fun buildSubtitlePrefixMaxEnd(subtitles: List<SubtitleEntry>): LongArray =
    LongArray(subtitles.size).also { prefix ->
        var maximum = Long.MIN_VALUE
        subtitles.forEachIndexed { index, entry ->
            maximum = maxOf(maximum, entry.endTime)
            prefix[index] = maximum
        }
    }

/** Returns the latest cue whose start time is at or before [positionMs], or -1. */
internal fun findLatestStartedSubtitleIndex(
    subtitles: List<SubtitleEntry>,
    positionMs: Long,
): Int {
    var low = 0
    var high = subtitles.lastIndex
    var best = -1
    while (low <= high) {
        val middle = (low + high) ushr 1
        if (subtitles[middle].startTime <= positionMs) {
            best = middle
            low = middle + 1
        } else {
            high = middle - 1
        }
    }
    return best
}

/**
 * Returns the list index of the latest-starting active cue. The list must be sorted by start time,
 * and [prefixMaxEnd] must be produced by [buildSubtitlePrefixMaxEnd] for the same list.
 */
internal fun findActiveSubtitleIndex(
    subtitles: List<SubtitleEntry>,
    prefixMaxEnd: LongArray,
    positionMs: Long,
): Int {
    if (prefixMaxEnd.size != subtitles.size) return -1
    var index = findLatestStartedSubtitleIndex(subtitles, positionMs)
    while (index >= 0) {
        if (positionMs <= subtitles[index].endTime) return index
        if (index == 0 || prefixMaxEnd[index - 1] < positionMs) break
        index--
    }
    return -1
}
