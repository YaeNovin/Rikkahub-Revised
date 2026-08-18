package me.rerere.rikkahub.data.memory

import android.util.Log
import me.rerere.ai.provider.EmbeddingGenerationParams
import me.rerere.ai.provider.ProviderManager
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.datastore.resolveEmbeddingModel
import me.rerere.rikkahub.data.model.AssistantMemory
import me.rerere.rikkahub.data.model.MemoryType
import me.rerere.rikkahub.data.repository.MemoryRepository
import java.nio.ByteBuffer
import java.nio.ByteOrder

private const val TAG = "MemoryEmbedding"

class MemoryEmbeddingService(
    private val repository: MemoryRepository,
    private val providerManager: ProviderManager,
) {
    suspend fun addMemory(
        assistantId: String,
        content: String,
        settings: Settings,
        type: MemoryType = MemoryType.FACT,
        sourceConversationId: String? = null,
    ): AssistantMemory {
        val memory = repository.addMemory(
            assistantId = assistantId,
            content = content,
            type = type,
            sourceConversationId = sourceConversationId,
        )
        index(memory, settings)
        return memory
    }

    suspend fun updateMemory(
        assistantId: String,
        id: Int,
        content: String,
        settings: Settings,
    ): AssistantMemory? {
        val memory = repository.updateContent(
            assistantId = assistantId,
            id = id,
            content = content,
        ) ?: return null
        index(memory, settings)
        return memory
    }

    private suspend fun index(memory: AssistantMemory, settings: Settings) {
        if (memory.content.isBlank()) return
        runCatching {
            val model = settings.resolveEmbeddingModel() ?: return
            val providerSetting = model.findProvider(settings.providers)
                ?: error("Embedding provider not found for ${model.modelId}")
            val result = providerManager.getProviderByType(providerSetting).generateEmbedding(
                providerSetting = providerSetting,
                params = EmbeddingGenerationParams(
                    model = model,
                    input = listOf(memory.content),
                    customHeaders = model.customHeaders,
                    customBody = model.customBodies,
                )
            )
            val vector = result.embeddings.firstOrNull()
                ?.takeIf { it.isNotEmpty() && it.all(Float::isFinite) }
                ?: error("Embedding provider returned an empty or invalid vector")
            repository.updateEmbedding(
                id = memory.id,
                embedding = vector.toByteArray(),
                modelId = model.id.toString(),
                dimension = vector.size,
            )
        }.onFailure { error ->
            // Basic memory remains available when an embedding provider is not configured.
            Log.w(TAG, "Failed to index memory #${memory.id}; lexical retrieval will be used", error)
        }
    }
}

private fun List<Float>.toByteArray(): ByteArray {
    val buffer = ByteBuffer.allocate(size * Float.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
    forEach(buffer::putFloat)
    return buffer.array()
}
