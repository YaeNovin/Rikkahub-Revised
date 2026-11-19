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

internal const val SESSION_CAPABILITIES_TOOL_NAME = "get_session_capabilities"

/** Reserve discovery for the app and bind it to exactly the manifest being sent.
 * A stale caller-supplied definition must not shadow this request's snapshot. */
internal fun List<Tool>.withSessionCapabilitiesTool(
    assistant: Assistant,
    knowledgeBaseCapabilities: KnowledgeBaseCapabilities = KnowledgeBaseCapabilities.fromAssistant(assistant),
    suggestionCapabilities: ((details: Boolean) -> JsonObject)? = null,
    searchCapabilities: JsonObject? = null,
): List<Tool> {
    val registered = filterNot { it.name == SESSION_CAPABILITIES_TOOL_NAME }
    val names = registered.map(Tool::name) + SESSION_CAPABILITIES_TOOL_NAME
    return registered + createSessionCapabilitiesTool(
        assistant = assistant,
        toolCallsAvailable = true,
        availableToolNames = { names },
        knowledgeBaseCapabilities = knowledgeBaseCapabilities,
        suggestionCapabilities = suggestionCapabilities,
        searchCapabilities = searchCapabilities,
    )
}

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
    searchCapabilities: JsonObject? = null,
): Tool = Tool(
    name = SESSION_CAPABILITIES_TOOL_NAME,
    description = "Inspect the active assistant session's non-sensitive feature flags, retrieval modes, chat suggestion configuration and available tool names. Use include_suggestion_details=true for suggestion fields, draft actions and local form rules. This query cannot create suggestion cards or change settings.",
    parameters = { InputSchema.Obj(properties = buildJsonObject {
        put("include_ask_user_details", buildJsonObject { put("type", "boolean"); put("description", "Include ask_user field rules and examples; default false.") })
        put("include_suggestion_details", buildJsonObject { put("type", "boolean"); put("description", "Include chat suggestion output fields, limits and local draft-form examples; default false.") })
    }) },
    systemPrompt = { _, messages ->
        if (messages.any { message -> message.getTools().any { it.toolName == SESSION_CAPABILITIES_TOOL_NAME && it.isExecuted } }) {
            "Session capability results marked snapshot_scope=current_request are refreshed for this request. " +
                "Use these results and the current tool declarations as the source of truth; earlier assistant statements about enabled/disabled features may be outdated. " +
                "A capability query does not change settings or grant tool approval."
        } else ""
    },
    execute = { input ->
        val names = if (toolCallsAvailable) availableToolNames().distinct().sorted() else emptyList()
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
                    put("snapshot_scope", "current_request")
                    put("toolCallsAvailable", toolCallsAvailable)
                    searchCapabilities?.let { put("search", it) }
                    suggestionCapabilities?.let { capabilities ->
                        val includeDetails = ((input as? JsonObject)?.get("include_suggestion_details") as? JsonPrimitive)?.booleanOrNull == true
                        put("chat_suggestions", capabilities(includeDetails))
                    }
                    put("ask_user", buildJsonObject {
                        val enabled = toolCallsAvailable && "ask_user" in names
                        put("enabled", enabled)
                        if (enabled) AskUserContract.capabilities(details).forEach { (key, value) -> put(key, value) }
                    })
                    put("image_generation", buildJsonObject {
                        val enabled = CHAT_IMAGE_TOOL_NAME in names
                        put("enabled", enabled)
                        if (!enabled) {
                            put("status", "disabled")
                        } else {
                            put("tool", CHAT_IMAGE_TOOL_NAME)
                            put("requires_user_confirmation", !assistant.autoApproveImageGeneration)
                            put("autonomous_invocation", assistant.autoApproveImageGeneration)
                            put("inspection", if (INSPECT_GENERATED_IMAGE_TOOL_NAME in names) "on_demand" else "unavailable")
                            put("ask_user_changes_parameters", false)
                            put("max_requests_per_user_turn", 1)
                            put("max_images_per_request", CHAT_IMAGE_TOOL_MAX_IMAGES)
                            put("parameters_selected_by_user", true)
                        }
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
                        put("webSearch", "search_web" in names ||
                            (searchCapabilities?.get("native") as? JsonPrimitive)?.booleanOrNull == true)
                        put("workspaceBound", assistant.workspaceId != null)
                        put("skillsEnabled", assistant.enabledSkills.isNotEmpty())
                    })
                    put("rendering", buildJsonObject {
                        val enabled = LIST_RENDERERS_TOOL_NAME in names && RENDER_CONTENT_TOOL_NAME in names
                        put("enabled", enabled)
                        if (enabled) {
                            put("list_tool", LIST_RENDERERS_TOOL_NAME)
                            put("render_tool", RENDER_CONTENT_TOOL_NAME)
                            put("catalog_version", me.rerere.rikkahub.data.render.RendererCatalog.version)
                            put("html_auto_render", false)
                            put("author_scripts_allowed", false)
                        }
                    })
                    put("availableTools", buildJsonArray {
                        names.forEach(::add)
                    })
                }.toString()
            )
        )
    },
)
