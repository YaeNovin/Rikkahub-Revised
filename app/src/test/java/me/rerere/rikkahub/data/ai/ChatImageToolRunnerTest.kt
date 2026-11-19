package me.rerere.rikkahub.data.ai

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import me.rerere.rikkahub.data.db.dao.ChatImageToolTaskDAO
import me.rerere.rikkahub.data.db.entity.ChatImageToolTaskEntity
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.*
import org.junit.Test

class ChatImageToolRunnerTest {
    private fun task(status: String = "approved") = ChatImageToolTaskEntity(
        "task", "conversation", "message", "source", "call", "hash", "original", "proposal", "{}",
        status = status, createdAt = 0, updatedAt = 0)

    @Test fun `simultaneous approvals and later retries make one image request`() = runBlocking {
        val store = Store(task())
        val runner = ChatImageToolRunner(store)
        val release = CompletableDeferred<Unit>()
        var calls = 0
        val first = async(start = CoroutineStart.UNDISPATCHED) {
            runner.run("task") { save -> calls++; release.await(); save("file:///saved.png") }
        }
        val second = async(start = CoroutineStart.UNDISPATCHED) {
            runner.run("task") { calls++; error("must not repeat") }
        }
        release.complete(Unit)
        assertEquals("succeeded", first.await().status)
        assertEquals("succeeded", second.await().status)
        val afterRestart = ChatImageToolRunner(store).run("task") { calls++ }
        assertEquals(listOf("file:///saved.png"), JsonInstant.decodeFromString<List<String>>(afterRestart.imagesJson))
        assertEquals(1, calls)
    }

    @Test fun `unknown remote result is persisted and cannot be retried automatically`() = runBlocking {
        val store = Store(task())
        var calls = 0
        val failed = ChatImageToolRunner(store).run("task") { calls++; error("connection lost after submission") }
        assertEquals("unknown", failed.status)
        ChatImageToolRunner(store).run("task") { calls++ }
        assertEquals(1, calls)
    }

    @Test fun `waiting stays running and persists each image before returning`() = runBlocking {
        val store = Store(task())
        val release = CompletableDeferred<Unit>()
        val firstSaved = CompletableDeferred<Unit>()
        val result = async {
            ChatImageToolRunner(store).run("task") { save ->
                save("file:///first.png")
                firstSaved.complete(Unit)
                release.await()
                save("file:///second.png")
            }
        }
        withTimeout(5_000) { firstSaved.await() }
        assertFalse(result.isCompleted)
        assertEquals("running", store.row.status)
        assertEquals(listOf("file:///first.png"), JsonInstant.decodeFromString<List<String>>(store.row.imagesJson))
        release.complete(Unit)
        assertEquals("succeeded", withTimeout(5_000) { result.await() }.status)
        assertEquals(2, JsonInstant.decodeFromString<List<String>>(store.row.imagesJson).size)
    }

    @Test fun `cancellation preserves saved images and prevents a second POST`() = runBlocking {
        val store = Store(task())
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            ChatImageToolRunner(store).run("task") { save -> save("file:///first.png"); awaitCancellation() }
        }
        job.cancelAndJoin()
        assertEquals("partial", store.row.status)
        val result = ChatImageToolRunner(store).run("task") { error("must reuse saved result") }
        assertEquals(listOf("file:///first.png"), JsonInstant.decodeFromString<List<String>>(result.imagesJson))
    }

    @Test fun `pending cancelled expired and interrupted tasks never start generation`() = runBlocking {
        for (status in listOf("pending", "cancelled", "expired", "unknown", "running")) {
            val store = Store(task(status))
            ChatImageToolRunner(store).run("task") { error("unexpected paid request for $status") }
            assertEquals(status, store.row.status)
        }
    }

    private class Store(var row: ChatImageToolTaskEntity) : ChatImageToolTaskDAO {
        override suspend fun get(id: String) = row.takeIf { it.id == id }
        override fun observe(id: String) = MutableStateFlow(getRow(id))
        private fun getRow(id: String) = row.takeIf { it.id == id }
        override suspend fun findForTurn(conversationId: String, sourceMessageId: String) = row
        override suspend fun insert(task: ChatImageToolTaskEntity): Long { row = task; return 1 }
        override suspend fun approve(id: String, request: String, now: Long): Int {
            if (row.status != "pending") return 0
            row = row.copy(status = "approved", requestJson = request); return 1
        }
        override suspend fun transition(id: String, expected: List<String>, status: String, error: String?, now: Long): Int {
            if (row.id != id || row.status !in expected) return 0
            row = row.copy(status = status, error = error, updatedAt = now); return 1
        }
        override suspend fun saveImages(id: String, images: String, now: Long): Int {
            if (row.id != id || row.status != "running") return 0
            row = row.copy(imagesJson = images); return 1
        }
        override suspend fun recoverInterrupted(now: Long) { if (row.status == "running") row = row.copy(status = "unknown") }
        override suspend fun expirePending(conversationId: String, now: Long) { if (row.status == "pending") row = row.copy(status = "expired") }
    }
}
