// SPDX-License-Identifier: GPL-3.0-only
package com.oxigenplayer.app

import java.util.LinkedHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A small access-ordered pool whose values may only be evicted while they are idle.
 *
 * The pool can temporarily exceed [maximumSize] only when every eviction candidate is leased.
 * Once a lease is returned, idle entries are trimmed back to the configured limit. Closing the
 * pool closes idle values immediately and defers active values until their final lease is returned.
 */
internal class RefCountedLruPool<K : Any, V : Any>(
    private val maximumSize: Int,
    private val createValue: (K) -> V,
    private val closeValue: (V) -> Unit,
) : AutoCloseable {
    private data class Entry<K, V>(
        val key: K,
        val value: V,
        var leaseCount: Int = 0,
        var closeWhenIdle: Boolean = false,
    )

    internal class Lease<V : Any> private constructor(
        val value: V,
        private val releaseAction: () -> Unit,
    ) : AutoCloseable {
        private val returned = AtomicBoolean(false)

        override fun close() {
            if (returned.compareAndSet(false, true)) releaseAction()
        }

        internal companion object {
            fun <V : Any> create(value: V, releaseAction: () -> Unit): Lease<V> =
                Lease(value, releaseAction)
        }
    }

    private val lock = Any()
    private val entries = LinkedHashMap<K, Entry<K, V>>(maximumSize, 0.75f, true)
    private var isClosed = false

    init {
        require(maximumSize > 0) { "maximumSize must be positive" }
    }

    fun acquire(key: K): Lease<V> {
        val valuesToClose = mutableListOf<V>()
        val entry = synchronized(lock) {
            check(!isClosed) { "Pool is closed" }
            val selected = entries[key] ?: Entry(key, createValue(key)).also {
                entries[key] = it
            }
            selected.leaseCount++
            trimIdleLocked(valuesToClose)
            selected
        }
        closeAll(valuesToClose)
        return Lease.create(entry.value) { release(entry) }
    }

    private fun release(entry: Entry<K, V>) {
        val valuesToClose = mutableListOf<V>()
        synchronized(lock) {
            check(entry.leaseCount > 0) { "Lease returned more than once" }
            entry.leaseCount--
            if (entry.leaseCount == 0 && (isClosed || entry.closeWhenIdle)) {
                if (entries[entry.key] === entry) entries.remove(entry.key)
                valuesToClose += entry.value
            } else {
                trimIdleLocked(valuesToClose)
            }
        }
        closeAll(valuesToClose)
    }

    /** Must be called while holding [lock]. */
    private fun trimIdleLocked(valuesToClose: MutableList<V>) {
        while (entries.size > maximumSize) {
            val iterator = entries.entries.iterator()
            var removed = false
            while (iterator.hasNext()) {
                val candidate = iterator.next().value
                if (candidate.leaseCount == 0) {
                    iterator.remove()
                    valuesToClose += candidate.value
                    removed = true
                    break
                }
            }
            if (!removed) return
        }
    }

    override fun close() {
        val valuesToClose = mutableListOf<V>()
        synchronized(lock) {
            if (isClosed) return
            isClosed = true
            val iterator = entries.entries.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next().value
                if (entry.leaseCount == 0) {
                    iterator.remove()
                    valuesToClose += entry.value
                } else {
                    entry.closeWhenIdle = true
                }
            }
        }
        closeAll(valuesToClose)
    }

    internal fun cachedValueCount(): Int = synchronized(lock) { entries.size }

    private fun closeAll(values: List<V>) {
        values.forEach { value -> runCatching { closeValue(value) } }
    }
}
