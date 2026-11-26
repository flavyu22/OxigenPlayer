// SPDX-License-Identifier: GPL-3.0-only
package com.oxigenplayer.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivateStoragePathTest {
    private val roots = listOf("/data/user/0/com.oxigenplayer.app", "/data/data/com.oxigenplayer.app/")

    @Test
    fun privateFilesAreInside() {
        assertTrue(isPathInsideAny("/data/user/0/com.oxigenplayer.app/shared_prefs/oxigen_prefs.xml", roots))
        assertTrue(isPathInsideAny("/data/data/com.oxigenplayer.app", roots))
    }

    @Test
    fun siblingPackageWithSamePrefixIsOutside() {
        assertFalse(isPathInsideAny("/data/data/com.oxigenplayer.app2/video.mp4", roots))
    }

    @Test
    fun sharedStorageIsOutside() {
        assertFalse(isPathInsideAny("/storage/emulated/0/Movies/film.mkv", roots))
        assertFalse(isPathInsideAny("/storage/emulated/0/Movies/film.mkv", listOf("", "/")))
    }
}
