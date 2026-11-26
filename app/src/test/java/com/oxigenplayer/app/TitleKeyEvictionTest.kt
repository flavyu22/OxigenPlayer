// SPDX-License-Identifier: GPL-3.0-only
package com.oxigenplayer.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TitleKeyEvictionTest {
    @Test
    fun nothingEvictedWithinLimit() {
        val keys = mapOf("pos_a" to 1L, "pic_b" to 2L)
        assertTrue(selectTitleKeysToEvict(keys, keepCount = 2).isEmpty())
        assertTrue(selectTitleKeysToEvict(keys, keepCount = 5).isEmpty())
    }

    @Test
    fun oldestKeysAreEvictedFirst() {
        val keys = mapOf("pos_new" to 300L, "pos_old" to 100L, "pic_mid" to 200L, "sub_sync_x" to 400L)
        assertEquals(listOf("pos_old", "pic_mid"), selectTitleKeysToEvict(keys, keepCount = 2))
    }

    @Test
    fun keysWithoutRecencyAreTreatedAsOldest() {
        val keys = mapOf("pos_recent" to 5_000L, "pos_legacy" to 0L)
        assertEquals(listOf("pos_legacy"), selectTitleKeysToEvict(keys, keepCount = 1))
    }

    @Test
    fun negativeKeepCountEvictsEverything() {
        val keys = mapOf("pos_a" to 1L, "pos_b" to 2L)
        assertEquals(2, selectTitleKeysToEvict(keys, keepCount = -1).size)
    }

    @Test
    fun onlyPerTitlePrefixesAreTitleKeys() {
        assertTrue(PreferencesManager.isTitleKey("pos_abc"))
        assertTrue(PreferencesManager.isTitleKey("pic_abc"))
        assertTrue(PreferencesManager.isTitleKey("sub_sync_abc"))
        assertFalse(PreferencesManager.isTitleKey("playback_speed"))
        assertFalse(PreferencesManager.isTitleKey("sub_font_size"))
        assertFalse(PreferencesManager.isTitleKey("sub_vertical_offset"))
    }
}
