package me.rerere.rikkahub.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import me.rerere.rikkahub.data.db.dao.MemoryDAO
import me.rerere.rikkahub.data.db.entity.MemoryEntity
import me.rerere.rikkahub.data.model.AssistantMemory
import me.rerere.rikkahub.data.model.MemoryType
import kotlin.time.Clock

class MemoryRepository(private val memoryDAO: MemoryDAO) {
    companion object {
        const val GLOBAL_MEMORY_ID = "__global__"
    }

    fun getMemoriesOfAssistantFlow(assistantId: String): Flow<List<AssistantMemory>> =
        memoryDAO.getMemoriesOfAssistantFlow(assistantId)
            .map { entities -> entities.map(MemoryEntity::toAssistantMemory) }

    suspend fun getMemoriesOfAssistant(assistantId: String): List<AssistantMemory> {
        return memoryDAO.getMemoriesOfAssistant(assistantId)
            .map(MemoryEntity::toAssistantMemory)
    }

    suspend fun getMemoryRecordsOfAssistant(assistantId: String): List<MemorySearchRecord> =
        memoryDAO.getMemoriesOfAssistant(assistantId).map { entity ->
            MemorySearchRecord(
                memory = entity.toAssistantMemory(),
                embedding = entity.embedding,
                embeddingModelId = entity.embeddingModelId,
                embeddingDimension = entity.embeddingDimension,
            )
        }

    fun getGlobalMemoriesFlow(): Flow<List<AssistantMemory>> =
        memoryDAO.getMemoriesOfAssistantFlow(GLOBAL_MEMORY_ID)
            .map { entities -> entities.map(MemoryEntity::toAssistantMemory) }

    suspend fun getGlobalMemories(): List<AssistantMemory> {
        return memoryDAO.getMemoriesOfAssistant(GLOBAL_MEMORY_ID)
            .map(MemoryEntity::toAssistantMemory)
    }

    suspend fun deleteMemoriesOfAssistant(assistantId: String) {
        memoryDAO.deleteMemoriesOfAssistant(assistantId)
    }

    suspend fun updateContent(id: Int, content: String): AssistantMemory {
        val old = memoryDAO.getMemoryById(id) ?: error("Memory record #$id not found")
        return updateContent(old, content)
    }

    suspend fun updateContent(
        assistantId: String,
        id: Int,
        content: String,
    ): AssistantMemory? {
        val old = memoryDAO.getMemoryByIdOfAssistant(id, assistantId) ?: return null
        return updateContent(old, content)
    }

    private suspend fun updateContent(old: MemoryEntity, content: String): AssistantMemory {
        val newMemory = old.copy(
            content = content,
            embedding = null,
            embeddingModelId = null,
            embeddingDimension = null,
        )
        memoryDAO.updateMemory(newMemory)
        return AssistantMemory(
            id = newMemory.id,
            content = newMemory.content,
            type = MemoryType.fromWireName(newMemory.memoryType),
            createdAt = newMemory.createdAt,
            sourceConversationId = newMemory.sourceConversationId,
        )
    }

    suspend fun addMemory(
        assistantId: String,
        content: String,
        type: MemoryType = MemoryType.FACT,
        sourceConversationId: String? = null,
    ): AssistantMemory {
        val createdAt = Clock.System.now().toEpochMilliseconds()
        val memory = AssistantMemory(
            id = 0,
            content = content,
            type = type,
            createdAt = createdAt,
            sourceConversationId = sourceConversationId,
        )
        val newMemory = memory.copy(
            id = memoryDAO.insertMemory(
                MemoryEntity(
                    assistantId = assistantId,
                    content = memory.content,
                    memoryType = type.name.lowercase(),
                    createdAt = createdAt,
                    sourceConversationId = sourceConversationId,
                )
            ).toInt()
        )
        return newMemory
    }

    suspend fun deleteMemory(id: Int): Boolean = memoryDAO.deleteMemory(id) > 0

    suspend fun deleteMemory(assistantId: String, id: Int): Boolean =
        memoryDAO.deleteMemoryOfAssistant(id, assistantId) > 0

    suspend fun updateEmbedding(
        id: Int,
        embedding: ByteArray,
        modelId: String,
        dimension: Int,
    ) {
        memoryDAO.updateEmbedding(
            id = id,
            embedding = embedding,
            embeddingModelId = modelId,
            embeddingDimension = dimension,
        )
    }
}

data class MemorySearchRecord(
    val memory: AssistantMemory,
    val embedding: ByteArray?,
    val embeddingModelId: String?,
    val embeddingDimension: Int?,
)

private fun MemoryEntity.toAssistantMemory() = AssistantMemory(
    id = id,
    content = content,
    type = MemoryType.fromWireName(memoryType),
    createdAt = createdAt,
    sourceConversationId = sourceConversationId,
)
