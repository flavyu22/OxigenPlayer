// SPDX-License-Identifier: GPL-3.0-only
package com.oxigenplayer.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RefCountedLruPoolTest {
    private data class FakeValue(val key: String, var closed: Boolean = false)

    @Test
    fun evictsLeastRecentlyUsedIdleValue() {
        val closedKeys = mutableListOf<String>()
        val pool = RefCountedLruPool<String, FakeValue>(
            maximumSize = 2,
            createValue = ::FakeValue,
            closeValue = { value ->
                value.closed = true
                closedKeys += value.key
            },
        )

        pool.acquire("a").close()
        pool.acquire("b").close()
        pool.acquire("a").close()
        pool.acquire("c").close()

        assertEquals(listOf("b"), closedKeys)
        assertEquals(2, pool.cachedValueCount())
    }

    @Test
    fun neverClosesAValueWhileItIsLeased() {
        val pool = RefCountedLruPool<String, FakeValue>(
            maximumSize = 1,
            createValue = ::FakeValue,
            closeValue = { it.closed = true },
        )
        val active = pool.acquire("active")

        pool.acquire("other").close()

        assertFalse(active.value.closed)
        assertEquals(1, pool.cachedValueCount())
        active.close()
    }

    @Test
    fun shutdownDefersCloseUntilFinalLeaseReturns() {
        val pool = RefCountedLruPool<String, FakeValue>(
            maximumSize = 2,
            createValue = ::FakeValue,
            closeValue = { it.closed = true },
        )
        val active = pool.acquire("active")
        val idle = pool.acquire("idle").also { it.close() }.value

        pool.close()

        assertTrue(idle.closed)
        assertFalse(active.value.closed)
        active.close()
        assertTrue(active.value.closed)
        assertEquals(0, pool.cachedValueCount())
    }
}
