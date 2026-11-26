// SPDX-License-Identifier: GPL-3.0-only
package com.oxigenplayer.app

import android.view.Window
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * Immersive playback on phones and tablets: status and navigation bars are hidden, and a swipe
 * from the screen edge shows them only briefly before they hide again on their own.
 *
 * It has to be applied to every window that can take focus. Compose dialogs are separate
 * windows, and the system shows the bars again when one opens and does not hide them when it
 * closes, so callers re-apply this on dialog creation and whenever the player regains focus.
 */
fun Window.hideSystemBarsImmersive() {
    WindowCompat.getInsetsController(this, decorView).apply {
        systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        hide(WindowInsetsCompat.Type.systemBars())
    }
}
