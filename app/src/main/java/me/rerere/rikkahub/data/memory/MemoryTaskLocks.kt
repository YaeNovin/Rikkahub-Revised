package me.rerere.rikkahub.data.memory

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Entries include waiters; removing a held mutex early would break serialization. */
internal class MemoryTaskLocks {
    private class Entry(val mutex: Mutex = Mutex(), var users: Int = 0)
    private val entries = mutableMapOf<String, Entry>()
    internal val activeKeyCount: Int get() = synchronized(entries) { entries.size }

    suspend fun <T> withLock(key: String, action: suspend () -> T): T {
        val entry = synchronized(entries) { entries.getOrPut(key) { Entry() }.also { it.users++ } }
        try {
            return entry.mutex.withLock { action() }
        } finally {
            synchronized(entries) {
                entry.users--
                if (entry.users == 0) entries.remove(key)
            }
        }
    }
}
