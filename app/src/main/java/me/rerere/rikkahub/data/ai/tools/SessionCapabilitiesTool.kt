package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Assistant

data class KnowledgeBaseCapabilities(
    val boundCount: Int,
    val enabledCount: Int,
    val ragEnabledCount: Int,
) {
    companion object {
        fun fromAssistant(assistant: Assistant) = KnowledgeBaseCapabilities(
            boundCount = assistant.knowledgeBaseIds.size,
            enabledCount = assistant.knowledgeBaseIds.size,
            ragEnabledCount = assistant.knowledgeBaseIds.size,
        )
    }
}

/**
 * Reports non-sensitive feature flags for the active assistant session. This
 * lets a model make decisions from actual configuration instead of guessing.
 */
fun createSessionCapabilitiesTool(
    assistant: Assistant,
    toolCallsAvailable: Boolean,
    availableToolNames: () -> List<String>,
    knowledgeBaseCapabilities: KnowledgeBaseCapabilities = KnowledgeBaseCapabilities.fromAssistant(assistant),
): Tool = Tool(
    name = "get_session_capabilities",
    description = "Inspect the active assistant session's non-sensitive feature flags, retrieval modes, and available tool names. Use this before relying on an optional capability.",
    parameters = { InputSchema.Obj(properties = buildJsonObject {}) },
    execute = {
        val knowledgeEnabled = knowledgeBaseCapabilities.enabledCount > 0
        val knowledgeRetrievalMode = when {
            knowledgeBaseCapabilities.boundCount == 0 -> "disabled"
            !knowledgeEnabled -> "disabled_by_user"
            knowledgeBaseCapabilities.ragEnabledCount > 0 -> "background_silent_with_forced_sources"
            else -> "tool_only"
        }
        val memoryMode = when {
            !assistant.enableMemory -> "disabled"
            assistant.enableMemoryRag -> "rag_background"
            else -> "basic_prompt"
        }
        listOf(
            UIMessagePart.Text(
                buildJsonObject {
                    put("toolCallsAvailable", toolCallsAvailable)
                    put("memory", buildJsonObject {
                        put("enabled", assistant.enableMemory)
                        put("mode", memoryMode)
                        put("episodicEnabled", assistant.enableMemory && assistant.enableEpisodicMemory)
                        put("usesGlobalMemory", assistant.enableMemory && assistant.useGlobalMemory)
                    })
                    put("knowledgeBase", buildJsonObject {
                        put("enabled", knowledgeEnabled)
                        put("boundCount", knowledgeBaseCapabilities.boundCount)
                        put("enabledCount", knowledgeBaseCapabilities.enabledCount)
                        put("ragEnabledCount", knowledgeBaseCapabilities.ragEnabledCount)
                        put("retrievalMode", knowledgeRetrievalMode)
                        put("sourceCardsEnabled", knowledgeEnabled)
                        put("sourceTextAppended", false)
                    })
                    put("features", buildJsonObject {
                        put("recentChatsReference", assistant.enableRecentChatsReference)
                        put("webSearch", assistant.enableWebSearch)
                        put("workspaceBound", assistant.workspaceId != null)
                        put("skillsEnabled", assistant.enabledSkills.isNotEmpty())
                    })
                    put("availableTools", buildJsonArray {
                        availableToolNames().distinct().sorted().forEach(::add)
                    })
                }.toString()
            )
        )
    },
)
