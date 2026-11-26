// SPDX-License-Identifier: GPL-3.0-only
package com.oxigenplayer.app

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** Minimum frames in one watchdog window before a drop ratio is trusted. */
internal const val DECODER_LOAD_MIN_FRAMES = 30

/** Share of frames dropped in a window above which the decoder is considered overloaded. */
internal const val DECODER_LOAD_MAX_DROP_RATIO = 0.2

/** Consecutive overloaded windows required, so one seek or GC pause never triggers it. */
internal const val DECODER_LOAD_CONSECUTIVE_WINDOWS = 2

/**
 * True when a watchdog window dropped so many frames that the picture visibly stutters.
 *
 * On an entry-level TV (4x Cortex-A53) a 1080p HEVC 10-bit file falls back to FFmpeg and cannot be
 * decoded in real time; the renderer then drops late frames and the viewer sees only judder.
 */
internal fun isDecoderOverloaded(renderedDelta: Int, droppedDelta: Int): Boolean {
    val total = renderedDelta + droppedDelta
    if (total < DECODER_LOAD_MIN_FRAMES || droppedDelta <= 0) return false
    return droppedDelta.toDouble() / total > DECODER_LOAD_MAX_DROP_RATIO
}

/**
 * Process-wide signal from the playback service's video diagnostics to the UI that the current
 * file is too heavy for this device. The player service and the activity share one process.
 */
internal object DecoderLoadMonitor {
    private val _overloaded = MutableSharedFlow<String>(extraBufferCapacity = 1)

    /** Emits the decoder name once per video renderer session that cannot keep up. */
    val overloaded: SharedFlow<String> = _overloaded.asSharedFlow()

    fun report(decoderName: String) {
        _overloaded.tryEmit(decoderName)
    }
}
