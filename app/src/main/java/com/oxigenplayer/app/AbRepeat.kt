// SPDX-License-Identifier: GPL-3.0-only
package com.oxigenplayer.app

/** Phases of the A-B repeat control: off, loop start chosen, loop running. */
enum class AbRepeatPhase {
    INACTIVE,
    POINT_A_SET,
    LOOPING,
}

/**
 * Immutable A-B loop state. Positions are milliseconds inside the current media; -1 marks an
 * unset point. The state is media-scoped: selecting a new item always resets it to [INACTIVE].
 */
data class AbRepeatState(
    val phase: AbRepeatPhase = AbRepeatPhase.INACTIVE,
    val pointAMs: Long = -1L,
    val pointBMs: Long = -1L,
) {
    val isActive: Boolean get() = phase != AbRepeatPhase.INACTIVE
}

/** Loop points closer than this would feel like a stutter rather than a repeat. */
internal const val AB_REPEAT_MIN_GAP_MS = 1_000L

/**
 * Advances the state machine when the A-B button is pressed.
 *
 * INACTIVE -> POINT_A_SET at the current position; POINT_A_SET -> LOOPING once the user picks
 * an end point at least [AB_REPEAT_MIN_GAP_MS] after A (a press too close to A is ignored so
 * the user can reposition first); LOOPING -> INACTIVE clears the loop. Positions are clamped
 * into the media bounds when a positive duration is known.
 */
internal fun abRepeatButtonTransition(
    state: AbRepeatState,
    positionMs: Long,
    durationMs: Long,
): AbRepeatState {
    val position = positionMs.coerceAtLeast(0L).let { pos ->
        if (durationMs > 0L) pos.coerceAtMost(durationMs) else pos
    }
    return when (state.phase) {
        AbRepeatPhase.INACTIVE -> AbRepeatState(
            phase = AbRepeatPhase.POINT_A_SET,
            pointAMs = position,
        )
        AbRepeatPhase.POINT_A_SET -> {
            if (position < state.pointAMs + AB_REPEAT_MIN_GAP_MS) return state
            state.copy(phase = AbRepeatPhase.LOOPING, pointBMs = position)
        }
        AbRepeatPhase.LOOPING -> AbRepeatState()
    }
}

/**
 * Returns the seek target that restarts the loop, or null while playback is inside it.
 *
 * The position poller runs at a bounded rate, so the check tolerates one poll interval past
 * point B instead of requiring an exact hit.
 */
internal fun abRepeatSeekTarget(
    state: AbRepeatState,
    positionMs: Long,
    toleranceMs: Long = 750L,
): Long? {
    if (state.phase != AbRepeatPhase.LOOPING) return null
    if (state.pointAMs < 0L || state.pointBMs <= state.pointAMs) return null
    return if (positionMs >= state.pointBMs - toleranceMs.coerceAtLeast(0L)) {
        state.pointAMs
    } else {
        null
    }
}
