package me.rerere.rikkahub.data.model

import kotlinx.serialization.Serializable
import me.rerere.ai.provider.ModelType
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.findModelById

@Serializable
enum class ConversationMemoryMode { DISABLED, ENABLED, RAG_ONLY, INHERIT, EXTRACTION_ONLY }

fun Settings.usesVectorMemory(): Boolean =
    memoryExtractionModelId?.let(::findModelById)?.type == ModelType.EMBEDDING

/** Request-local configuration; never writes the assistant's shared settings. */
fun Conversation.memoryAssistant(assistant: Assistant, settings: Settings): Assistant {
    val configured = when (memoryMode) {
        ConversationMemoryMode.DISABLED -> assistant.copy(enableMemory = false, enableMemoryRag = false)
        ConversationMemoryMode.ENABLED -> assistant.copy(enableMemory = true)
        ConversationMemoryMode.RAG_ONLY -> assistant.copy(enableMemory = false, enableMemoryRag = true)
        ConversationMemoryMode.INHERIT -> assistant
        ConversationMemoryMode.EXTRACTION_ONLY -> assistant.copy(enableMemory = true, enableMemoryRag = false)
    }
    val vectorOnly = settings.usesVectorMemory()
    return if (vectorOnly && configured.enableMemory && memoryMode != ConversationMemoryMode.EXTRACTION_ONLY) {
        configured.copy(enableMemory = false, enableMemoryRag = true)
    } else configured
}

/** Keep model-visible diagnostics consistent for RAG-only conversations too. */
fun Assistant.memoryCapabilityMode(): String = when {
    enableMemoryRag -> "rag_background"
    enableMemory -> "basic_prompt"
    else -> "disabled"
}
