// SPDX-License-Identifier: GPL-3.0-only
package com.oxigenplayer.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Generates the Baseline Profile packaged into release artifacts.
 *
 * Run explicitly, for example:
 * ```
 * gradlew :app:generateReleaseBaselineProfile
 * ```
 * Requires a connected device or emulator running API 33+ (or a rooted device on older APIs).
 * The generated `baseline-prof.txt` is stored under
 * `app/src/<variant>/generated/baselineProfiles/`; copy it to
 * `app/src/main/generated/baselineProfiles/` so every release variant ships it.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class BaselineProfileGenerator {

    @get:Rule
    val rule: BaselineProfileRule = BaselineProfileRule()

    @Test
    fun generate() = rule.collect(
        packageName = BuildConfig.TARGET_APPLICATION_ID,
    ) {
        // Critical user journey: cold start from the launcher into the player screen.
        // Every release build benefits from AOT-compiling this path at install time.
        pressHome()
        startActivityAndWait()
    }
}
