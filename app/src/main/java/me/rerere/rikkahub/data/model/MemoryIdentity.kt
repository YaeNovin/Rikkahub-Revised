package me.rerere.rikkahub.data.model

import kotlinx.serialization.Serializable
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.utils.JsonInstant
import kotlinx.serialization.encodeToString

@Serializable enum class MemoryScopeType { CONVERSATION, ASSISTANT, GLOBAL, UNASSIGNED }

fun memoryScope(type: MemoryType, assistantId: String, conversationId: String?): Pair<MemoryScopeType, String> = when {
    type == MemoryType.EPISODIC && !conversationId.isNullOrBlank() -> MemoryScopeType.CONVERSATION to conversationId
    type == MemoryType.EPISODIC -> MemoryScopeType.UNASSIGNED to ""
    assistantId == "__global__" -> MemoryScopeType.GLOBAL to assistantId
    else -> MemoryScopeType.ASSISTANT to assistantId
}

/** A local model UUID alone is insufficient when its endpoint/model/embedding options are edited. */
fun memoryEmbeddingKey(model: Model, provider: ProviderSetting, chunkingVersion: String = "memory-v1"): String {
    val endpoint = when (provider) {
        is ProviderSetting.OpenAI -> provider.baseUrl
        is ProviderSetting.Google -> provider.baseUrl
        is ProviderSetting.Claude -> provider.baseUrl
    }
    val routing = (provider as? ProviderSetting.Google)?.let { "${it.vertexAI}:${it.projectId}:${it.location}" }.orEmpty()
    return memoryContentHash(listOf(provider.id.toString(), endpoint, routing, model.modelId,
        JsonInstant.encodeToString(model.customBodies), "retrieval_document", chunkingVersion).joinToString("\u0000"))
}

class MemoryRevisionConflict : IllegalStateException("记忆已经被更新，请刷新后再保存")
