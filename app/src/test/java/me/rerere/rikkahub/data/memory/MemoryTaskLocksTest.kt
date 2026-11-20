package me.rerere.rikkahub.data.memory

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class MemoryTaskLocksTest {
    @Test fun waitersAndNewArrivalsShareOneLockUntilAllFinish() = runBlocking {
        val locks = MemoryTaskLocks()
        val release = CompletableDeferred<Unit>()
        val order = mutableListOf<Int>()
        var active = 0
        val first = launch(start = CoroutineStart.UNDISPATCHED) {
            locks.withLock("chat") { active++; order += 1; release.await(); active-- }
        }
        val waiters = (2..4).map { number -> launch(start = CoroutineStart.UNDISPATCHED) {
            locks.withLock("chat") {
                assertEquals(0, active)
                active++
                yield()
                order += number
                active--
            }
        } }
        assertEquals(listOf(1), order)
        assertEquals(1, locks.activeKeyCount)
        release.complete(Unit)
        first.join()
        val newcomer = launch(start = CoroutineStart.UNDISPATCHED) {
            locks.withLock("chat") { assertEquals(0, active); order += 5 }
        }
        (waiters + newcomer).joinAll()
        assertEquals(listOf(1, 2, 3, 4, 5), order)
        assertEquals(0, locks.activeKeyCount)
    }

    @Test fun cancellingAWaiterDoesNotRemoveTheHoldersLock() = runBlocking {
        val locks = MemoryTaskLocks()
        val release = CompletableDeferred<Unit>()
        val holder = launch(start = CoroutineStart.UNDISPATCHED) { locks.withLock("chat") { release.await() } }
        val waiter = launch(start = CoroutineStart.UNDISPATCHED) { locks.withLock("chat") { fail("Cancelled waiter entered") } }
        waiter.cancelAndJoin()
        assertEquals(1, locks.activeKeyCount)
        var entered = false
        val next = launch(start = CoroutineStart.UNDISPATCHED) { locks.withLock("chat") { entered = true } }
        assertFalse(entered)
        release.complete(Unit)
        joinAll(holder, next)
        assertTrue(entered)
        assertEquals(0, locks.activeKeyCount)
    }

    @Test fun independentConversationsCanRunTogether() = runBlocking {
        val locks = MemoryTaskLocks()
        val release = CompletableDeferred<Unit>()
        var entered = 0
        val jobs = listOf("a", "b").map { key -> launch(start = CoroutineStart.UNDISPATCHED) {
            locks.withLock(key) { entered++; release.await() }
        } }
        assertEquals(2, entered)
        release.complete(Unit)
        jobs.joinAll()
        assertEquals(0, locks.activeKeyCount)
    }

    @Test fun completedAndFailedConversationsDoNotAccumulateEntries() = runBlocking {
        val locks = MemoryTaskLocks()
        repeat(1_000) { locks.withLock("chat-$it") { } }
        assertEquals(0, locks.activeKeyCount)
        assertTrue(runCatching { locks.withLock("failed") { error("fixture") } }.isFailure)
        assertEquals(0, locks.activeKeyCount)
        locks.withLock("failed") { }
        assertEquals(0, locks.activeKeyCount)
    }
}
