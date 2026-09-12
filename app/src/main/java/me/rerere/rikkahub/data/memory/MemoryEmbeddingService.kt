package me.rerere.rikkahub.data.memory

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.rerere.ai.provider.EmbeddingGenerationParams
import me.rerere.ai.provider.EmbeddingTaskType
import me.rerere.ai.provider.ProviderManager
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.datastore.resolveEmbeddingModel
import me.rerere.rikkahub.data.model.AssistantMemory
import me.rerere.rikkahub.data.model.MemoryLifecycleState
import me.rerere.rikkahub.data.model.MemoryType
import me.rerere.rikkahub.data.model.memoryEmbeddingKey
import me.rerere.rikkahub.data.repository.MemoryRepository
import java.nio.ByteBuffer
import java.nio.ByteOrder

private const val TAG = "MemoryEmbedding"

class MemoryEmbeddingService(
    private val repository: MemoryRepository,
    private val providerManager: ProviderManager,
) {
    private val insertionMutex = Mutex()

    suspend fun rebuildIndex(memory: AssistantMemory, settings: Settings) {
        val model = settings.resolveEmbeddingModel() ?: error("请先配置可用的向量模型")
        val provider = model.findProvider(settings.providers)?.takeIf { it.enabled } ?: error("向量供应商不可用")
        val epoch = repository.captureWriteEpoch()
        index(memory, settings, isAllowed = { repository.isWriteEpochCurrent(epoch) })
        check(repository.hasCurrentVector(memory, memoryEmbeddingKey(model, provider))) { "索引未更新，记录可能已修改或供应商返回失败，请检查日志" }
    }

    suspend fun addMemory(
        assistantId: String,
        content: String,
        settings: Settings,
        type: MemoryType = MemoryType.FACT,
        sourceConversationId: String? = null,
        lifecycleState: MemoryLifecycleState = MemoryLifecycleState.ACTIVE,
    ): AssistantMemory {
        val memory = repository.addMemory(
            assistantId = assistantId,
            content = content,
            type = type,
            sourceConversationId = sourceConversationId,
            lifecycleState = lifecycleState,
        )
        index(memory, settings)
        return memory
    }

    suspend fun addMemoryIfAbsent(
        assistantId: String,
        content: String,
        settings: Settings,
        type: MemoryType = MemoryType.FACT,
        sourceConversationId: String? = null,
        lifecycleState: MemoryLifecycleState = MemoryLifecycleState.ACTIVE,
        indexMemory: Boolean = true,
        isAllowed: () -> Boolean = { true },
        expectedWriteEpoch: Long? = null,
        sourceMessageIds: List<String> = emptyList(),
        runId: String? = null,
    ): AssistantMemory? {
        val memory = insertionMutex.withLock {
            if (!isAllowed()) throw CancellationException("Conversation memory settings changed")
            repository.addMemoryIfAbsent(
                assistantId = assistantId,
                content = content,
                type = type,
                sourceConversationId = sourceConversationId,
                lifecycleState = lifecycleState,
                expectedWriteEpoch = expectedWriteEpoch,
                sourceMessageIds = sourceMessageIds,
                runId = runId,
            )
        } ?: return null
        if (indexMemory && isAllowed()) index(memory, settings, isAllowed)
        return memory
    }

    suspend fun updateMemory(
        assistantId: String,
        id: Int,
        content: String,
        settings: Settings,
        type: MemoryType? = null,
        lifecycleState: MemoryLifecycleState? = null,
        expectedRevision: Long? = null,
    ): AssistantMemory? {
        val memory = repository.updateMemory(
            assistantId = assistantId,
            id = id,
            content = content,
            type = type,
            lifecycleState = lifecycleState,
            expectedRevision = expectedRevision,
        ) ?: return null
        index(memory, settings)
        return memory
    }

    private suspend fun index(memory: AssistantMemory, settings: Settings, isAllowed: () -> Boolean = { true }) {
        if (memory.content.isBlank() || !isAllowed()) return
        runCatching {
            val model = settings.resolveEmbeddingModel() ?: return
            val providerSetting = model.findProvider(settings.providers)
                ?.takeIf { it.enabled }
                ?: error("Embedding provider not found for ${model.modelId}")
            val embeddingKey = memoryEmbeddingKey(model, providerSetting)
            repository.prepareEmbedding(memory, embeddingKey)
            repository.trackRun(memory.sourceConversationId, model.id.toString(), "memory_embedding", memory.contentHash) { runId ->
            val result = providerManager.getProviderByType(providerSetting).generateEmbedding(
                providerSetting = providerSetting,
                params = EmbeddingGenerationParams(
                    model = model,
                    input = listOf(memory.content),
                    taskType = EmbeddingTaskType.RETRIEVAL_DOCUMENT,
                    customHeaders = model.customHeaders,
                    customBody = model.customBodies,
                    requestId = runId,
                )
            )
            val vector = result.embeddings.firstOrNull()
                ?.takeIf { it.isNotEmpty() && it.all(Float::isFinite) }
                ?: error("Embedding provider returned an empty or invalid vector")
            if (!isAllowed()) throw CancellationException("Memory settings changed")
            repository.updateEmbedding(
                id = memory.id,
                embedding = vector.toByteArray(),
                modelId = model.id.toString(),
                dimension = vector.size,
                expectedContent = memory.content,
                expectedKey = embeddingKey,
            )
            }
        }.onFailure { error ->
            if (error is CancellationException) throw error
            // Basic memory remains available when an embedding provider is not configured.
            Log.w(TAG, "Failed to index memory #${memory.id}; lexical retrieval will be used", error)
            me.rerere.common.android.Logging.logSoftwareError(TAG, "MemoryEmbedding", error)
        }
    }
}

private fun List<Float>.toByteArray(): ByteArray {
    val buffer = ByteBuffer.allocate(size * Float.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
    forEach(buffer::putFloat)
    return buffer.array()
}
