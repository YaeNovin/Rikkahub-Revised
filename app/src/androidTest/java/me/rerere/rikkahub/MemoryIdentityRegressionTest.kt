package me.rerere.rikkahub

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.entity.*
import me.rerere.rikkahub.data.memory.MemoryMaintenanceService
import me.rerere.rikkahub.data.model.*
import me.rerere.rikkahub.data.repository.MemoryRepository
import org.junit.Assert.*
import org.junit.Test

class MemoryIdentityRegressionTest {
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
