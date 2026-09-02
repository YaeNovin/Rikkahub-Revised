package me.rerere.rikkahub.service

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.datastore.DEFAULT_ASSISTANT_ID
import me.rerere.rikkahub.data.model.Conversation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class ConversationSessionTest {
    @Test
    fun `conversation is loaded only once per active session`() = runBlocking {
        val id = Uuid.random()
        val loaded = Conversation.ofId(id = id, assistantId = DEFAULT_ASSISTANT_ID)
            .copy(title = "loaded")
        val session = newSession(id)
        var loads = 0

        assertEquals(loaded, session.initializeOnce { loads++; loaded })
        assertEquals(loaded, session.initializeOnce {
            loads++
            loaded.copy(title = "stale reload")
        })
        assertEquals(1, loads)
    }

    @Test
    fun `an in-memory update cannot be overwritten by late initialization`() = runBlocking {
        val id = Uuid.random()
        val session = newSession(id)
        val current = Conversation.ofId(id = id, assistantId = DEFAULT_ASSISTANT_ID)
            .copy(title = "current")
        val loadStarted = CompletableDeferred<Unit>()
        val finishLoad = CompletableDeferred<Unit>()
        val initialization = async {
            session.initializeOnce {
                loadStarted.complete(Unit)
                finishLoad.await()
                current.copy(title = "database snapshot")
            }
        }

        loadStarted.await()
        session.state.value = current
        session.markInitialized()
        finishLoad.complete(Unit)

        assertEquals(current, initialization.await())
        assertEquals(current, session.state.value)
    }

    @Test
    fun `failed initialization remains retryable`() = runBlocking {
        val id = Uuid.random()
        val session = newSession(id)
        val loaded = Conversation.ofId(id = id, assistantId = DEFAULT_ASSISTANT_ID)
            .copy(title = "loaded after retry")

        runCatching {
            session.initializeOnce { error("temporary database failure") }
        }
        assertFalse(session.isInitialized)

        assertEquals(loaded, session.initializeOnce { loaded })
        assertTrue(session.isInitialized)
    }

    @Test
    fun `completion of a replaced job does not clear the current job`() = runBlocking {
        val session = newSession(Uuid.random())
        val first = Job()
        val second = Job()

        session.setJob(first)
        session.setJob(second)
        first.complete()

        assertEquals(second, session.getJob())
        second.cancel()
    }

    private fun newSession(id: Uuid) = ConversationSession(
        id = id,
        initial = Conversation.ofId(id = id, assistantId = DEFAULT_ASSISTANT_ID),
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
        onIdle = {},
    )
}
