// SPDX-License-Identifier: GPL-3.0-only
package com.oxigenplayer.app

import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink

internal const val AUDIO_DELAY_MIN_MS = -5_000
internal const val AUDIO_DELAY_MAX_MS = 5_000
internal const val AUDIO_DELAY_STEP_MS = 50

/** Clamps a user or stored value to the supported lip-sync range. */
internal fun clampAudioDelayMs(delayMs: Int): Int =
    delayMs.coerceIn(AUDIO_DELAY_MIN_MS, AUDIO_DELAY_MAX_MS)

/**
 * Offset added to the audio-driven media clock for a given lip-sync setting.
 *
 * The audio renderer is the player's master clock, and video (and the subtitle timeline, which
 * follows the player position) is scheduled against it. Reporting the clock [delayMs] ahead of
 * what is really audible makes the picture run ahead of the sound by that amount, which the
 * viewer perceives as the audio being delayed. A negative value does the opposite.
 */
internal fun audioDelayClockOffsetUs(delayMs: Int): Long = clampAudioDelayMs(delayMs) * 1_000L

/**
 * Applies [audioDelayClockOffsetUs] to the reported clock position.
 *
 * [unsetPosition] is the sink's "no position yet" sentinel and must pass through untouched,
 * otherwise the renderer would treat an offset sentinel as a real timestamp.
 */
internal fun applyAudioDelayToClockUs(positionUs: Long, delayMs: Int, unsetPosition: Long): Long =
    if (positionUs == unsetPosition) positionUs else positionUs + audioDelayClockOffsetUs(delayMs)

/**
 * Process-wide lip-sync setting shared by the playback service and every audio sink it builds.
 *
 * The value outlives a single player on purpose: the software-decoder recovery path rebuilds the
 * player, and the replacement sink must keep the offset without another preference read.
 */
internal object AudioDelayState {
    @Volatile
    var delayMs: Int = 0
        set(value) {
            field = clampAudioDelayMs(value)
        }
}

/**
 * Audio sink wrapper that implements audio delay / lip-sync.
 *
 * Media3 has no public audio-offset API, so the offset is applied where the master clock is
 * read. All audio data still flows unmodified to the wrapped sink, which keeps passthrough,
 * offload and every decoder path working exactly as before.
 */
@UnstableApi
internal class DelayedAudioSink(
    sink: AudioSink,
    private val delayMs: () -> Int = { AudioDelayState.delayMs },
) : ForwardingAudioSink(sink) {
    override fun getCurrentPositionUs(sourceEnded: Boolean): Long =
        applyAudioDelayToClockUs(
            positionUs = super.getCurrentPositionUs(sourceEnded),
            delayMs = delayMs(),
            unsetPosition = AudioSink.CURRENT_POSITION_NOT_SET,
        )
}
