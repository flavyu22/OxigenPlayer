// SPDX-License-Identifier: GPL-3.0-only
package com.oxigenplayer.app

import android.Manifest
import org.junit.Assert.assertArrayEquals
import org.junit.Test

class LocalMediaLibraryTest {

    @Test
    fun legacyDevices_requestSharedStorageReadAccess() {
        assertArrayEquals(
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE),
            LocalMediaLibrary.requiredPermissionsForSdk(32),
        )
    }

    @Test
    fun android13_requestsGranularVideoAndAudioAccess() {
        assertArrayEquals(
            arrayOf(
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_MEDIA_AUDIO,
            ),
            LocalMediaLibrary.requiredPermissionsForSdk(33),
        )
    }

    @Test
    fun android14AndNewer_supportsPartialVideoReselection() {
        assertArrayEquals(
            arrayOf(
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_MEDIA_AUDIO,
                Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
            ),
            LocalMediaLibrary.requiredPermissionsForSdk(34),
        )
        assertArrayEquals(
            LocalMediaLibrary.requiredPermissionsForSdk(34),
            LocalMediaLibrary.requiredPermissionsForSdk(37),
        )
    }
}
