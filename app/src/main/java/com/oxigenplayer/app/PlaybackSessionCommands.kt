// SPDX-License-Identifier: GPL-3.0-only
package com.oxigenplayer.app

import android.os.Bundle
import androidx.media3.session.SessionCommand

/** Private commands used only between OxigenPlayer's UI controller and playback service. */
internal object PlaybackSessionCommands {
    const val ACTION_SET_MATCH_CONTENT_FRAME_RATE =
        "com.oxigenplayer.app.action.SET_MATCH_CONTENT_FRAME_RATE"
    const val ACTION_SET_SKIP_SILENCE =
        "com.oxigenplayer.app.action.SET_SKIP_SILENCE"
    const val ACTION_SET_AUDIO_DELAY =
        "com.oxigenplayer.app.action.SET_AUDIO_DELAY"
    const val ARG_ENABLED = "enabled"
    const val ARG_DELAY_MS = "delay_ms"

    val setAudioDelay: SessionCommand
        get() = SessionCommand(ACTION_SET_AUDIO_DELAY, Bundle.EMPTY)

    val setMatchContentFrameRate: SessionCommand
        get() = SessionCommand(ACTION_SET_MATCH_CONTENT_FRAME_RATE, Bundle.EMPTY)

    val setSkipSilence: SessionCommand
        get() = SessionCommand(ACTION_SET_SKIP_SILENCE, Bundle.EMPTY)
}
