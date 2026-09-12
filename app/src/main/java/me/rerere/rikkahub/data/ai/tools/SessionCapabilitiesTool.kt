package me.rerere.rikkahub.data.ai.tools

import me.rerere.rikkahub.data.model.memoryCapabilityMode

import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.put
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import me.rerere.ai.ui.AskUserContract
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
    suggestionCapabilities: ((details: Boolean) -> JsonObject)? = null,
): Tool = Tool(
    name = "get_session_capabilities",
    description = "Inspect the active assistant session's non-sensitive feature flags, retrieval modes, chat suggestion configuration and available tool names. Use include_suggestion_details=true for suggestion fields, draft actions and local form rules. This query cannot create suggestion cards or change settings.",
    parameters = { InputSchema.Obj(properties = buildJsonObject {
        put("include_ask_user_details", buildJsonObject { put("type", "boolean"); put("description", "Include ask_user field rules and examples; default false.") })
        put("include_suggestion_details", buildJsonObject { put("type", "boolean"); put("description", "Include chat suggestion output fields, limits and local draft-form examples; default false.") })
    }) },
    execute = { input ->
        val names = availableToolNames().distinct().sorted()
        val details = ((input as? JsonObject)?.get("include_ask_user_details") as? JsonPrimitive)?.booleanOrNull == true
        val knowledgeEnabled = knowledgeBaseCapabilities.enabledCount > 0
        val knowledgeRetrievalMode = when {
            knowledgeBaseCapabilities.boundCount == 0 -> "disabled"
            !knowledgeEnabled -> "disabled_by_user"
            knowledgeBaseCapabilities.ragEnabledCount > 0 -> "background_silent_with_forced_sources"
            else -> "tool_only"
        }
        val memoryMode = assistant.memoryCapabilityMode()
        listOf(
            UIMessagePart.Text(
                buildJsonObject {
                    put("toolCallsAvailable", toolCallsAvailable)
                    suggestionCapabilities?.let { capabilities ->
                        val includeDetails = ((input as? JsonObject)?.get("include_suggestion_details") as? JsonPrimitive)?.booleanOrNull == true
                        put("chat_suggestions", capabilities(includeDetails))
                    }
                    put("ask_user", buildJsonObject {
                        val enabled = toolCallsAvailable && "ask_user" in names
                        put("enabled", enabled)
                        if (enabled) AskUserContract.capabilities(details).forEach { (key, value) -> put(key, value) }
                    })
                    put("memory", buildJsonObject {
                        put("enabled", assistant.enableMemory || assistant.enableMemoryRag)
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
                        names.forEach(::add)
                    })
                }.toString()
            )
        )
    },
)
