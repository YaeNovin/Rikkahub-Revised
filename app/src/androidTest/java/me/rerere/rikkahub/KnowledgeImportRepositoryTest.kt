package me.rerere.rikkahub

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.entity.KnowledgeChunkEntity
import me.rerere.rikkahub.data.db.entity.KnowledgeDocumentEntity
import me.rerere.rikkahub.data.repository.KnowledgeBaseRepository
import org.junit.Assert.*
import org.junit.Test

class KnowledgeImportRepositoryTest {
    @Test fun deletedOrCancelledImportsCannotBeResurrectedByLateWorkerWrites() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val repository = KnowledgeBaseRepository(db.knowledgeBaseDao(), db)
            val base = repository.createBase("test")
            val document = KnowledgeDocumentEntity("document", base.id, "title", "source", "text/plain", "hash", createdAt = 0, updatedAt = 0)
            repository.insertDocumentWithChunks(document, emptyList())
            val chunks = listOf(KnowledgeChunkEntity("chunk", document.id, base.id, 0, "text"))
            assertTrue(repository.transitionImport(document.id, listOf("indexing"), "cancelled"))
            assertTrue(runCatching { repository.saveImportChunks(document, chunks, replace = true) }.isFailure)
            assertEquals("cancelled", repository.getDocument(document.id)?.status)
            assertTrue(repository.getDocumentChunks(document.id).isEmpty())
            repository.deleteDocument(document.id)
            assertTrue(runCatching { repository.saveImportChunks(document, chunks, replace = true) }.isFailure)
            assertNull(repository.getDocument(document.id))
        } finally { db.close() }
    }

    @Test fun keysetPagingIncludesLateDocumentsAndEveryIndexedModel() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val dao = db.knowledgeBaseDao()
            val repository = KnowledgeBaseRepository(dao, db)
            val base = repository.createBase("test")
            val document = KnowledgeDocumentEntity("document", base.id, "title", "source", "text/plain", "hash", status = "ready", createdAt = 0, updatedAt = 0)
            val chunks = (0 until 1505).map { index ->
                KnowledgeChunkEntity(index.toString().padStart(6, '0'), document.id, base.id, index,
                    if (index == 1504) "unique-late-match" else "ordinary",
                    embedding = byteArrayOf(0, 0, 0, 0), embeddingModelId = if (index < 1500) "first" else "late", embeddingDimension = 1)
            }
            repository.insertDocumentWithChunks(document, chunks)
            val found = mutableListOf<String>()
            var cursor = ""
            while (true) {
                val page = dao.getChunks(listOf(base.id), 128, cursor)
                if (page.isEmpty()) break
                assertTrue(page.all { it.embedding == null })
                found += page.map { it.id }
                cursor = page.last().id
            }
            assertEquals(chunks.map { it.id }, found)
            assertEquals(setOf("first", "late"), dao.getEmbeddingModels(listOf(base.id)).map { it.embeddingModelId }.toSet())
            assertEquals(5, dao.getEmbeddedChunks(listOf(base.id), "late", 128).size)
        } finally { db.close() }
    }
}
