// SPDX-License-Identifier: GPL-3.0-only
package com.oxigenplayer.app

import androidx.compose.runtime.staticCompositionLocalOf

/** One process-level flag propagated through Compose without per-card system-service lookups. */
val LocalLowRamMode = staticCompositionLocalOf { false }

/** Cached once by MainActivity so dialogs and focus code avoid repeated TV capability lookups. */
val LocalTelevisionMode = staticCompositionLocalOf { false }
