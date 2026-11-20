package me.rerere.rikkahub

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.CoroutineStart
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.entity.*
import me.rerere.rikkahub.data.memory.MemoryMaintenanceService
import me.rerere.rikkahub.data.model.*
import me.rerere.rikkahub.data.repository.MemoryRepository
import org.junit.Assert.*
import org.junit.Test

class MemoryIdentityRegressionTest {
    @Test fun assistantClearFencesSuspendedWorkWithoutCancellingOtherOwners() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext, AppDatabase::class.java).build()
        try {
            val repo = MemoryRepository(db.memoryDao())
            repo.addMemory("a", "delete me")
            val a = repo.captureWriteEpoch("a")
            val b = repo.captureWriteEpoch("b")
            val global = repo.captureWriteEpoch(MemoryRepository.GLOBAL_MEMORY_ID)
            val resume = CompletableDeferred<Unit>()
            val oldExtraction = async(start = CoroutineStart.UNDISPATCHED) {
                resume.await()
                runCatching { repo.addMemoryIfAbsent("a", "late new UID", expectedWriteEpoch = a) }.exceptionOrNull()
            }
            repo.deleteMemoriesOfAssistant("a")
            resume.complete(Unit)
            assertTrue(oldExtraction.await() is CancellationException)
            assertTrue(repo.getMemoriesOfAssistant("a").isEmpty())
            assertTrue(repo.isWriteEpochCurrent(b))
            assertTrue(repo.isWriteEpochCurrent(global))
            assertNotNull(repo.addMemoryIfAbsent("b", "unrelated work", expectedWriteEpoch = b))
            val fresh = repo.captureWriteEpoch("a")
            val event = repo.addMemoryIfAbsent("a", "new event", MemoryType.EPISODIC, "chat", expectedWriteEpoch = fresh)!!
            assertTrue(runCatching { repo.updateEpisodicLifecycle("a", event.id, MemoryLifecycleState.COMPLETED,
                expectedWriteEpoch = a) }.exceptionOrNull() is CancellationException)
            assertEquals(MemoryLifecycleState.ACTIVE, repo.getMemoriesOfAssistant("a").single().lifecycleState)
            repo.withBulkDeletion { db.memoryDao().deleteAllMemories() }
            assertFalse(repo.isWriteEpochCurrent(fresh))
            assertFalse(repo.isWriteEpochCurrent(b))
            assertFalse(repo.isWriteEpochCurrent(global))
        } finally { db.close() }
    }

    @Test fun sqlRetrievalMatchesScopeRulesAndFiltersLifecycleBeforeLoadingVectors() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext, AppDatabase::class.java).build()
        try {
            val repo = MemoryRepository(db.memoryDao())
            repo.addMemory("a", "shared fact")
            repo.addMemory("a", "event A", MemoryType.EPISODIC, "chat-a")
            repo.addMemory("a", "completed A", MemoryType.EPISODIC, "chat-a", MemoryLifecycleState.COMPLETED)
            repo.addMemory("a", "event B", MemoryType.EPISODIC, "chat-b")
            repo.addMemory("a", "unassigned", MemoryType.EPISODIC)
            repo.addMemory("b", "other assistant")
            repo.addMemory(MemoryRepository.GLOBAL_MEMORY_ID, "global fact")
            db.memoryDao().insertMemory(MemoryEntity(assistantId = "a", content = "uppercase event", memoryType = "EPISODIC",
                scopeType = "CONVERSATION", scopeId = "chat-a", lifecycleState = "ACTIVE"))
            db.memoryDao().insertMemory(MemoryEntity(assistantId = "a", content = "legacy lifecycle", memoryType = "fact",
                lifecycleState = "superseded_by_newer"))
            for (chat in listOf("chat-a", "chat-b", null, " ")) {
                val expected = repo.getMemoriesOfAssistant("a").filter { it.isVisibleInConversation(chat) }.map { it.id }.toSet()
                assertEquals(expected, repo.getRecordsForConversation("a", chat).map { it.memory.id }.toSet())
            }
            val active = repo.getRecordsForConversation("a", "chat-a", activeOnly = true)
            assertEquals(setOf("shared fact", "event A", "uppercase event"), active.map { it.memory.content }.toSet())
            assertEquals(listOf("shared fact"), repo.getRecordsForConversation("a", "chat-a", activeOnly = true, includeEpisodic = false).map { it.memory.content })
            assertEquals(listOf("global fact"), repo.getRecordsForConversation(MemoryRepository.GLOBAL_MEMORY_ID, "chat-a", activeOnly = true).map { it.memory.content })
        } finally { db.close() }
    }

    @Test fun workStartedDuringBulkDeletionCannotWriteAfterItFinishes() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext, AppDatabase::class.java).build()
        try {
            val repo = MemoryRepository(db.memoryDao())
            val finish = CompletableDeferred<Unit>()
            val deleting = async(start = CoroutineStart.UNDISPATCHED) {
                repo.withBulkDeletion {
                    finish.await()
                    db.memoryDao().deleteAllMemories()
                }
            }
            val duringClear = repo.captureWriteEpoch("a")
            finish.complete(Unit)
            deleting.await()
            assertFalse(repo.isWriteEpochCurrent(duringClear))
            assertTrue(runCatching { repo.addMemoryIfAbsent("a", "started while clearing", expectedWriteEpoch = duringClear) }.exceptionOrNull() is CancellationException)
            assertNotNull(repo.addMemoryIfAbsent("a", "started after clearing", expectedWriteEpoch = repo.captureWriteEpoch("a")))
        } finally { db.close() }
    }

    @Test fun stableIdentityRevisionSourcesAndDeletionWorkTogether() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext, AppDatabase::class.java).build()
        try {
            val repo = MemoryRepository(db.memoryDao())
            val memory = repo.addMemory("a", "original", MemoryType.EPISODIC, "chat", sourceMessageIds = listOf("u", "v"))
            assertTrue(memory.uid.isNotBlank())
            assertEquals(2, repo.getSources(memory.uid).size)
            val edited = repo.updateMemory("a", memory.id, "edited", expectedRevision = memory.revision)!!
            assertEquals(memory.uid, edited.uid); assertEquals(2L, edited.revision)
            assertNotEquals(memory.contentHash, edited.contentHash)
            assertTrue(runCatching { repo.updateMemory("a", memory.id, "stale", expectedRevision = memory.revision) }.exceptionOrNull() is MemoryRevisionConflict)
            repo.prepareEmbedding(edited, "new-config")
            repo.updateEmbedding(edited.id, byteArrayOf(0,0,0,0), "model", 1, edited.content, "old-config")
            assertNull(db.memoryDao().getMemoryById(edited.id)?.embedding)
            assertTrue(repo.deleteMemory("a", memory.id))
            assertEquals(memory.uid, db.memoryDao().getDeletion(memory.uid)?.uid)
            assertTrue(repo.getSources(memory.uid).isEmpty())
            assertTrue(runCatching { db.memoryDao().insertMemoryWithSources(MemoryEntity(assistantId = "a", content = "restore", uid = memory.uid), emptyList()) }.isFailure)
        } finally { db.close() }
    }

    @Test fun bulkClearBlocksOldExtractionAndIndexWritesWithoutDeletingChat() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext, AppDatabase::class.java).build()
        try {
            val repo = MemoryRepository(db.memoryDao()); val maintenance = MemoryMaintenanceService(db, repo)
            db.conversationDao().insert(ConversationEntity("chat", "a", "Keep conversation", "[]", 0, 0, "[]", false))
            val fact = repo.addMemory("__global__", "fact")
            repo.addMemory("a", "episode", MemoryType.EPISODIC, "chat")
            val chunk = ConversationMemoryEntity("chunk", "chat", "a", "source", 0, "text", memoryContentHash("text"), updatedAt = 0)
            db.conversationMemoryDao().upsertVisibleChunks(listOf(chunk))
            val epoch = repo.captureWriteEpoch()
            val removed = maintenance.clearAll()
            assertEquals(2, removed.memories); assertEquals(1, removed.chunks)
            assertEquals(0, db.memoryDao().countAllMemories()); assertEquals(0, db.conversationMemoryDao().countAllChunks())
            assertNotNull(db.memoryDao().getDeletion(fact.uid))
            assertTrue(runCatching { repo.addMemoryIfAbsent("a", "late", expectedWriteEpoch = epoch) }.exceptionOrNull() is CancellationException)
            assertTrue(runCatching { repo.withCurrentWriteEpoch(epoch) { db.conversationMemoryDao().upsertVisibleChunks(listOf(chunk.copy(id = "late"))) } }.exceptionOrNull() is CancellationException)
            db.conversationMemoryDao().upsertVisibleChunks(listOf(chunk))
            assertEquals(0, db.conversationMemoryDao().countAllChunks())
            db.openHelper.readableDatabase.query("SELECT title FROM conversationentity WHERE id='chat'").use { assertTrue(it.moveToFirst()); assertEquals("Keep conversation", it.getString(0)) }
            assertNotNull(repo.addMemoryIfAbsent("a", "new memory", expectedWriteEpoch = repo.captureWriteEpoch()))
        } finally { db.close() }
    }
}
