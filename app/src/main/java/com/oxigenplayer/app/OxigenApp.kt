// SPDX-License-Identifier: GPL-3.0-only
package com.oxigenplayer.app

import android.app.Application
import android.util.Log
import android.content.ComponentCallbacks2
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay

// TRIM_MEMORY_RUNNING_CRITICAL is deprecated from API 34: the RUNNING_*
// levels are no longer reported on newer platforms. Keep the raw legacy
// value (15) so pre-34 behaviour is preserved without a deprecation warning.
private const val LEGACY_TRIM_MEMORY_RUNNING_CRITICAL = 15

class OxigenApp : Application() {
    val prefs by lazy { PreferencesManager(this) }
    private val maintenanceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        OxigenStrings.initialize(this)
        NetworkClient.initialize(this)
        maintenanceScope.launch {
            prefs.prewarmSecureStorage()
        }
        // Android TV users commonly open the media card immediately after launch. Warm the
        // MediaStore snapshot off the main thread so that first open is usually cache-backed.
        // A short delay keeps this work away from the critical cold-start/player-connection path.
        if (isTelevisionDevice(this)) {
            maintenanceScope.launch {
                val delayMs = if (DevicePerformance.isLowRamDevice(this@OxigenApp)) 1_250L else 650L
                delay(delayMs)
                val access = LocalMediaLibrary.access(this@OxigenApp)
                if (access.hasAnyAccess) {
                    runCatching {
                        LocalMediaLibrary.ensureSnapshot(this@OxigenApp, access)
                    }.onFailure { error ->
                        Log.d("OxigenApp", "MediaStore prewarm skipped/failed", error)
                    }
                }
            }
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level == LEGACY_TRIM_MEMORY_RUNNING_CRITICAL ||
            level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND
        ) {
            clearOptionalCaches()
        }
    }

    override fun onLowMemory() {
        super.onLowMemory()
        clearOptionalCaches()
    }

    private fun clearOptionalCaches() {
        LocalMediaLibrary.invalidateMediaCache()
        maintenanceScope.launch {
            runCatching { NetworkClient.evictCacheIfInitialized() }
                .onFailure { Log.w("OxigenApp", "Unable to trim network cache", it) }
        }
    }
}
