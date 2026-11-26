// SPDX-License-Identifier: GPL-3.0-only
package com.oxigenplayer.app

import android.app.ActivityManager
import android.content.Context

/**
 * Process-wide device capability snapshot. ActivityManager queries are intentionally
 * executed once so Compose focus/card code never performs system-service work while
 * the user navigates with a TV remote.
 */
object DevicePerformance {
    private const val LOW_RAM_FALLBACK_MB = 3_072L
    private const val VERY_LOW_RAM_MB = 2_048L

    data class Profile(
        val totalRamMb: Long,
        val lowRam: Boolean,
        val veryLowRam: Boolean,
        val cpuCores: Int,
    )

    @Volatile
    private var cachedProfile: Profile? = null

    fun profile(context: Context): Profile {
        cachedProfile?.let { return it }
        return synchronized(this) {
            cachedProfile ?: buildProfile(context.applicationContext).also { cachedProfile = it }
        }
    }

    fun isLowRamDevice(context: Context): Boolean = profile(context).lowRam

    private fun buildProfile(context: Context): Profile {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memoryInfo = ActivityManager.MemoryInfo().apply { activityManager.getMemoryInfo(this) }
        val totalRamMb = memoryInfo.totalMem / (1024L * 1024L)
        return Profile(
            totalRamMb = totalRamMb,
            lowRam = activityManager.isLowRamDevice || (totalRamMb <= LOW_RAM_FALLBACK_MB),
            // Deliberately not gated on isLowRamDevice: doing so collapsed the two tiers, so a
            // 3 GB low-RAM television received the 5 s/15 s buffers meant for 2 GB hardware and
            // rebuffered more than intended. Only the measured memory selects the harshest tier.
            veryLowRam = totalRamMb <= VERY_LOW_RAM_MB,
            cpuCores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1),
        )
    }
}
