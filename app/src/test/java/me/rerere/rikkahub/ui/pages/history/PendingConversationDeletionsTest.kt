package me.rerere.rikkahub.ui.pages.history

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

class PendingConversationDeletionsTest {
    @Test fun `undo retains both conversation and attachments without rewriting either`() = runBlocking {
        val pending = PendingConversationDeletions()
        val id = Uuid.random()
        val decision = CompletableDeferred<Boolean>()
        var removed = false
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            pending.run(id, { decision.await() }) { removed = true }
        }
        assertEquals(setOf(id), pending.ids.value)
        assertFalse(removed)
        decision.complete(false)
        job.join()
        assertFalse(removed)
        assertTrue(pending.ids.value.isEmpty())
    }

    @Test fun `leaving during undo window cancels deletion`() = runBlocking {
        val pending = PendingConversationDeletions()
        var deletions = 0
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            pending.run(Uuid.random(), { CompletableDeferred<Boolean>().await() }) { deletions++ }
        }
        job.cancelAndJoin()
        assertEquals(0, deletions)
        assertTrue(pending.ids.value.isEmpty())
    }

    @Test fun `duplicate taps cannot start a second deletion`() = runBlocking {
        val pending = PendingConversationDeletions()
        val id = Uuid.random()
        val decision = CompletableDeferred<Boolean>()
        var deletions = 0
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            pending.run(id, { decision.await() }) { deletions++ }
        }
        pending.run(id, { error("duplicate confirmation") }) { deletions++ }
        decision.complete(true)
        job.join()
        assertEquals(1, deletions)
        assertTrue(pending.ids.value.isEmpty())
    }

    @Test fun `a committed deletion finishes even when its screen closes`() = runBlocking {
        val pending = PendingConversationDeletions()
        val release = CompletableDeferred<Unit>()
        var finished = false
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            pending.run(Uuid.random(), { true }) { release.await(); finished = true }
        }
        job.cancel()
        assertFalse(finished)
        release.complete(Unit)
        job.join()
        assertTrue(finished)
        assertTrue(pending.ids.value.isEmpty())
    }

    @Test fun `failed deletion restores list visibility`() = runBlocking {
        val pending = PendingConversationDeletions()
        assertTrue(runCatching { pending.run(Uuid.random(), { true }) { error("database unavailable") } }.isFailure)
        assertTrue(pending.ids.value.isEmpty())
    }
}
