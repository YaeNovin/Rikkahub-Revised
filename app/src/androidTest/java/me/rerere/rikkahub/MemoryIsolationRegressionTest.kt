package me.rerere.rikkahub

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.entity.ConversationEntity
import me.rerere.rikkahub.data.db.entity.ConversationMemoryEntity
import me.rerere.rikkahub.data.model.MemoryLifecycleState
import me.rerere.rikkahub.data.model.MemoryType
import me.rerere.rikkahub.data.model.memoryContentHash
import me.rerere.rikkahub.data.repository.MemoryRepository
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

class MemoryIsolationRegressionTest {
    @Test fun deletionSurvivesLateVectorAndAutomaticReindex() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "memory-delete-${Uuid.random()}.db"
        val db = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
        try {
            db.conversationDao().insert(ConversationEntity("a", "assistant", "test", "[]", 0, 0, "[]", false))
            val dao = db.conversationMemoryDao()
            val original = ConversationMemoryEntity("a:message:0", "a", "assistant", "message", 0, "content", memoryContentHash("content"), updatedAt = 0)
            dao.upsertVisibleChunks(listOf(original))
            assertFalse(dao.deleteChunkForUser("other", original.id))
            assertTrue(dao.deleteChunkForUser("a", original.id))
            dao.upsertVisibleChunks(listOf(original))
            assertTrue(dao.getChunks("a").isEmpty())
            assertEquals(0, dao.updateVectorIfCurrent("a", original.id, original.contentHash, byteArrayOf(0, 0, 0, 0), "model", 1))
            val changed = original.copy(content = "new version", contentHash = memoryContentHash("new version"))
            dao.upsertVisibleChunks(listOf(changed))
            assertEquals(changed.content, dao.getChunks("a").single().content)
            assertEquals(0, dao.updateVectorIfCurrent("a", original.id, original.contentHash, byteArrayOf(0, 0, 0, 0), "old-model", 1))
        } finally { db.close(); context.deleteDatabase(name) }
    }

    @Test fun factsDeduplicateAcrossChatsWhileEventsAndLifecycleRemainIsolated() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "memory-scope-${Uuid.random()}.db"
        val db = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
        try {
            val repository = MemoryRepository(db.memoryDao())
            val a = repository.addMemoryIfAbsent("assistant", "Same event", MemoryType.EPISODIC, "a")!!
            val b = repository.addMemoryIfAbsent("assistant", "Same event", MemoryType.EPISODIC, "b")!!
            assertNotEquals(a.id, b.id)
            assertNull(repository.addMemoryIfAbsent("assistant", "Same event", MemoryType.EPISODIC, "a"))
            val fact = repository.addMemoryIfAbsent("assistant", "Shared fact", MemoryType.FACT, "a")!!
            assertNull(repository.addMemoryIfAbsent("assistant", "Shared fact", MemoryType.FACT, "b"))
            assertEquals(setOf(a.id, fact.id), repository.getMemoriesForConversation("assistant", "a").map { it.id }.toSet())
            assertEquals(setOf(b.id, fact.id), repository.getMemoriesForConversation("assistant", "b").map { it.id }.toSet())
            assertNull(repository.updateEpisodicLifecycle("assistant", a.id, MemoryLifecycleState.COMPLETED, conversationId = "b"))
            repository.updateContent(a.id, "Edited event")
            repository.updateEmbedding(a.id, byteArrayOf(0, 0, 0, 0), "stale-model", 1, "Same event")
            assertNull(db.memoryDao().getMemoryById(a.id)?.embedding)
        } finally { db.close(); context.deleteDatabase(name) }
    }
}
