// SPDX-License-Identifier: GPL-3.0-only
package com.oxigenplayer.app

import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration

internal fun isTelevisionDevice(context: Context): Boolean {
    val manager = context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
    return manager?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION ||
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
}
