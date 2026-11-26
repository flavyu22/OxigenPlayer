// SPDX-License-Identifier: GPL-3.0-only
package com.oxigenplayer.app

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4

import org.junit.Test
import org.junit.runner.RunWith

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

/**
 * Instrumented test, which will execute on an Android device.
 *
 * See [testing documentation](http://d.android.com/tools/testing).
 */
@RunWith(AndroidJUnit4::class)
class PreferencesInstrumentedTest {
    @Test
    fun encryptedAccountCanBeStoredAndCompletelyCleared() {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("com.oxigenplayer.app", appContext.packageName)

        val preferences = PreferencesManager(appContext)
        preferences.clearAccount()
        preferences.saveLogin("test-user")
        preferences.saveToken("test-token")

        assertEquals("test-user", preferences.getUsername())
        assertEquals("test-token", preferences.getToken())

        preferences.clearAccount()
        assertTrue(preferences.getUsername().isEmpty())
        assertTrue(preferences.getToken().isEmpty())
    }
}
