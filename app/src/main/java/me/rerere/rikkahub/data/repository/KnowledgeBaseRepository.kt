package me.rerere.rikkahub.data.repository

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import me.rerere.ai.ui.UIMessageAnnotation
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.dao.KnowledgeBaseDAO
import me.rerere.rikkahub.data.db.dao.KnowledgeChunkSearchRow
import me.rerere.rikkahub.data.db.dao.KnowledgeDocumentPreviewChunk
import me.rerere.rikkahub.data.db.entity.KnowledgeBaseEntity
import me.rerere.rikkahub.data.db.entity.KnowledgeChunkEntity
import me.rerere.rikkahub.data.db.entity.KnowledgeCitationEntity
import me.rerere.rikkahub.data.db.entity.KnowledgeDocumentEntity
import kotlin.time.Clock
import kotlin.uuid.Uuid

class KnowledgeBaseRepository(
    private val dao: KnowledgeBaseDAO,
    private val database: AppDatabase,
) {
    fun observeBases(): Flow<List<KnowledgeBaseEntity>> = dao.observeBases()

    fun observeDocuments(baseId: String): Flow<List<KnowledgeDocumentEntity>> = dao.observeDocuments(baseId)

    suspend fun getBases(ids: Set<String>): List<KnowledgeBaseEntity> {
        if (ids.isEmpty()) return emptyList()
        return dao.getBases(ids.toList())
    }

    suspend fun getEnabledBaseIds(ids: Set<String>): Set<String> {
        if (ids.isEmpty()) return emptySet()
        return dao.getEnabledBaseIds(ids.toList()).toSet()
    }

    suspend fun getRagEnabledBaseIds(ids: Set<String>): Set<String> {
        if (ids.isEmpty()) return emptySet()
        return dao.getRagEnabledBaseIds(ids.toList()).toSet()
    }

    suspend fun createBase(name: String, description: String = "", embeddingModelId: String? = null): KnowledgeBaseEntity {
        val now = Clock.System.now().toEpochMilliseconds()
        return KnowledgeBaseEntity(
            id = Uuid.random().toString(),
            name = name.trim(),
            description = description.trim(),
            embeddingModelId = embeddingModelId,
            createdAt = now,
            updatedAt = now,
        ).also { dao.insertBase(it) }
    }

    suspend fun deleteBase(id: String) {
        dao.deleteBase(id)
    }

    suspend fun updateBaseEnabled(id: String, enabled: Boolean) {
        dao.updateBaseEnabled(id, enabled, Clock.System.now().toEpochMilliseconds())
    }

    suspend fun updateBaseRagEnabled(id: String, ragEnabled: Boolean) {
        dao.updateBaseRagEnabled(id, ragEnabled, Clock.System.now().toEpochMilliseconds())
    }

    suspend fun insertDocumentWithChunks(
        document: KnowledgeDocumentEntity,
        chunks: List<KnowledgeChunkEntity>,
    ) {
        database.withTransaction {
            dao.insertDocument(document)
            dao.deleteChunks(document.id)
            if (chunks.isNotEmpty()) dao.insertChunks(chunks)
        }
    }

    suspend fun updateChunks(chunks: List<KnowledgeChunkEntity>) {
        if (chunks.isNotEmpty()) dao.updateChunks(chunks)
    }

    suspend fun updateDocumentStatus(
        id: String,
        status: String,
        errorMessage: String? = null,
    ) {
        dao.updateDocumentStatus(
            id = id,
            status = status,
            errorMessage = errorMessage,
            updatedAt = Clock.System.now().toEpochMilliseconds(),
        )
    }

    suspend fun deleteDocument(id: String) = dao.deleteDocument(id)

    suspend fun getDocumentPreview(documentId: String): KnowledgeDocumentPreview {
        val chunks = dao.getDocumentPreviewChunks(
            documentId = documentId,
            limit = KNOWLEDGE_PREVIEW_CHUNK_LIMIT + 1,
        )
        return KnowledgeDocumentPreview(
            content = buildKnowledgeDocumentPreview(chunks.take(KNOWLEDGE_PREVIEW_CHUNK_LIMIT)),
            truncated = chunks.size > KNOWLEDGE_PREVIEW_CHUNK_LIMIT,
        )
    }

    suspend fun searchEmbeddedChunks(baseIds: Set<String>, modelId: String, limit: Int): List<KnowledgeChunkSearchRow> {
        if (baseIds.isEmpty()) return emptyList()
        return dao.getEmbeddedChunks(baseIds.toList(), modelId, limit)
    }

    suspend fun searchChunks(baseIds: Set<String>, limit: Int): List<KnowledgeChunkSearchRow> {
        if (baseIds.isEmpty()) return emptyList()
        return dao.getChunks(baseIds.toList(), limit)
    }

    suspend fun countChunks(baseId: String): Int = dao.countChunks(baseId)

    suspend fun replaceCitations(
        conversationId: String,
        messageId: String,
        citations: List<UIMessageAnnotation.KnowledgeCitation>,
    ) {
        database.withTransaction {
            val validCitations = citations.filterExistingChunks(
                dao.getExistingChunkIds(citations.map(UIMessageAnnotation.KnowledgeCitation::chunkId).distinct()).toSet()
            )
            dao.deleteCitations(conversationId, messageId)
            if (validCitations.isNotEmpty()) {
                val now = Clock.System.now().toEpochMilliseconds()
                dao.insertCitations(validCitations.mapIndexed { index, citation ->
                    KnowledgeCitationEntity(
                        id = Uuid.random().toString(),
                        conversationId = conversationId,
                        messageId = messageId,
                        chunkId = citation.chunkId,
                        rank = index + 1,
                        score = citation.score,
                        excerpt = citation.excerpt,
                        pageStart = citation.pageStart,
                        pageEnd = citation.pageEnd,
                        sectionPath = citation.sectionPath,
                        createdAt = now,
                    )
                })
            }
        }
    }
}

data class KnowledgeDocumentPreview(
    val content: String,
    val truncated: Boolean,
)

private const val KNOWLEDGE_PREVIEW_CHUNK_LIMIT = 80

internal fun buildKnowledgeDocumentPreview(
    chunks: List<KnowledgeDocumentPreviewChunk>,
): String = buildString {
    var consumedEnd = -1
    chunks.sortedBy(KnowledgeDocumentPreviewChunk::ordinal).forEach { chunk ->
        val overlap = (consumedEnd - chunk.charStart).coerceAtLeast(0)
        val text = chunk.content.drop(overlap.coerceAtMost(chunk.content.length))
        if (text.isBlank()) {
            consumedEnd = maxOf(consumedEnd, chunk.charEnd)
            return@forEach
        }
        if (isNotEmpty() && chunk.charStart >= consumedEnd) append("\n\n")
        append(text)
        consumedEnd = maxOf(consumedEnd, chunk.charEnd)
    }
}.trim()

internal fun List<UIMessageAnnotation.KnowledgeCitation>.filterExistingChunks(
    existingChunkIds: Set<String>,
): List<UIMessageAnnotation.KnowledgeCitation> = distinctBy(UIMessageAnnotation.KnowledgeCitation::chunkId)
    .filter { it.chunkId in existingChunkIds }
