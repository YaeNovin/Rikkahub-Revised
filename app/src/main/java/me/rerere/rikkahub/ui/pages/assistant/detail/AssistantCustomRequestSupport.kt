package me.rerere.rikkahub.ui.pages.assistant.detail

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.provider.CustomBody
import me.rerere.ai.provider.CustomHeader
import me.rerere.ai.provider.providers.claude.resolveClaudeModelParameterSupport
import me.rerere.ai.provider.providers.openai.resolveDeepSeekModelParameterSupport
import me.rerere.ai.provider.providers.openai.resolveOpenAIModelParameterSupport
import me.rerere.ai.provider.providers.openai.resolveQwenModelParameterSupport
import me.rerere.ai.util.hasValidHttpSyntax
import me.rerere.ai.util.mergeCustomBody

internal enum class CustomRequestIssueKind {
    EMPTY_HEADER_NAME,
    INVALID_HEADER,
    DUPLICATE_HEADER,
    AUTH_HEADER,
    EMPTY_BODY_KEY,
    DUPLICATE_BODY,
    MODEL_OVERRIDES_HEADER,
    MODEL_OVERRIDES_BODY,
    APP_MANAGED_BODY,
    PROTOCOL_MISMATCH,
}

internal data class CustomRequestIssue(
    val kind: CustomRequestIssueKind,
    val field: String,
)

internal enum class CustomRequestPresetId {
    OPENAI_NO_STORAGE,
    OPENAI_AUTO_TRUNCATION,
    GEMINI_JSON_OUTPUT,
    CLAUDE_USER_METADATA,
    DEEPSEEK_JSON_OUTPUT,
    QWEN_WEB_SEARCH,
}

internal data class CustomRequestPreset(
    val id: CustomRequestPresetId,
    val bodies: List<CustomBody>,
)

internal fun customRequestPresets(
    route: ParameterRequestRoute?,
    modelId: String,
): List<CustomRequestPreset> {
    if (route == null || route.endpoint == ParameterEndpoint.THIRD_PARTY) return emptyList()
    return when {
        route.endpoint == ParameterEndpoint.OPENAI &&
            resolveOpenAIModelParameterSupport(modelId).available -> buildList {
            add(
                CustomRequestPreset(
                    id = CustomRequestPresetId.OPENAI_NO_STORAGE,
                    bodies = listOf(CustomBody("store", JsonPrimitive(false), enabled = false)),
                )
            )
            if (route.protocol == ParameterWireProtocol.OPENAI_RESPONSES) {
                add(
                    CustomRequestPreset(
                        id = CustomRequestPresetId.OPENAI_AUTO_TRUNCATION,
                        bodies = listOf(CustomBody("truncation", JsonPrimitive("auto"), enabled = false)),
                    )
                )
            }
        }

        route.protocol == ParameterWireProtocol.GOOGLE_GENERATE_CONTENT -> listOf(
            CustomRequestPreset(
                id = CustomRequestPresetId.GEMINI_JSON_OUTPUT,
                bodies = listOf(
                    CustomBody(
                        key = "generationConfig",
                        value = buildJsonObject { put("responseMimeType", "application/json") },
                        enabled = false,
                    )
                ),
            )
        )

        route.endpoint == ParameterEndpoint.ANTHROPIC &&
            resolveClaudeModelParameterSupport(modelId).available -> listOf(
            CustomRequestPreset(
                id = CustomRequestPresetId.CLAUDE_USER_METADATA,
                bodies = listOf(
                    CustomBody(
                        key = "metadata",
                        value = buildJsonObject { put("user_id", "replace_with_anonymous_id") },
                        enabled = false,
                    )
                ),
            )
        )

        route.endpoint == ParameterEndpoint.DEEPSEEK &&
            route.protocol == ParameterWireProtocol.OPENAI_CHAT_COMPLETIONS &&
            resolveDeepSeekModelParameterSupport(modelId).available -> listOf(
            CustomRequestPreset(
                id = CustomRequestPresetId.DEEPSEEK_JSON_OUTPUT,
                bodies = listOf(
                    CustomBody(
                        key = "response_format",
                        value = buildJsonObject { put("type", "json_object") },
                        enabled = false,
                    )
                ),
            )
        )

        route.endpoint == ParameterEndpoint.ALIBABA_MODEL_STUDIO &&
            route.protocol == ParameterWireProtocol.OPENAI_CHAT_COMPLETIONS &&
            resolveQwenModelParameterSupport(modelId).available -> listOf(
            CustomRequestPreset(
                id = CustomRequestPresetId.QWEN_WEB_SEARCH,
                bodies = listOf(
                    CustomBody("enable_search", JsonPrimitive(true), enabled = false),
                    CustomBody(
                        key = "search_options",
                        value = buildJsonObject {
                            put("forced_search", false)
                            put("search_strategy", "turbo")
                        },
                        enabled = false,
                    ),
                ),
            )
        )

        else -> emptyList()
    }
}

internal fun analyzeCustomRequest(
    assistantHeaders: List<CustomHeader>,
    assistantBodies: List<CustomBody>,
    modelHeaders: List<CustomHeader>,
    modelBodies: List<CustomBody>,
    route: ParameterRequestRoute?,
): List<CustomRequestIssue> = buildList {
    val activeHeaders = assistantHeaders.filter(CustomHeader::enabled)
    val duplicateHeaders = activeHeaders
        .filter { it.name.isNotBlank() }
        .groupBy { it.name.trim().lowercase() }
        .filterValues { it.size > 1 }
        .keys
    val modelHeaderNames = modelHeaders
        .filter { it.enabled && it.name.isNotBlank() }
        .mapTo(hashSetOf()) { it.name.trim().lowercase() }

    activeHeaders.forEach { header ->
        val name = header.name.trim()
        when {
            name.isBlank() -> add(CustomRequestIssue(CustomRequestIssueKind.EMPTY_HEADER_NAME, name))
            !header.hasValidHttpSyntax() -> add(CustomRequestIssue(CustomRequestIssueKind.INVALID_HEADER, name))
            name.lowercase() in duplicateHeaders ->
                add(CustomRequestIssue(CustomRequestIssueKind.DUPLICATE_HEADER, name))
        }
        if (name.lowercase() in AUTHENTICATION_HEADERS) {
            add(CustomRequestIssue(CustomRequestIssueKind.AUTH_HEADER, name))
        }
        if (name.lowercase() in modelHeaderNames) {
            add(CustomRequestIssue(CustomRequestIssueKind.MODEL_OVERRIDES_HEADER, name))
        }
    }

    val activeBodies = assistantBodies.filter(CustomBody::enabled)
    val duplicateBodies = activeBodies
        .filter { it.key.isNotBlank() }
        .groupBy { it.key.trim() }
        .filterValues { it.size > 1 }
        .keys
    val modelBodyKeys = modelBodies
        .filter { it.enabled && it.key.isNotBlank() }
        .mapTo(hashSetOf()) { it.key.trim() }
    val appManagedKeys = route?.appManagedBodyKeys().orEmpty()

    activeBodies.forEach { body ->
        val key = body.key.trim()
        when {
            key.isBlank() -> add(CustomRequestIssue(CustomRequestIssueKind.EMPTY_BODY_KEY, key))
            key in duplicateBodies -> add(CustomRequestIssue(CustomRequestIssueKind.DUPLICATE_BODY, key))
        }
        if (key in modelBodyKeys) {
            add(CustomRequestIssue(CustomRequestIssueKind.MODEL_OVERRIDES_BODY, key))
        }
        if (key in appManagedKeys) {
            add(CustomRequestIssue(CustomRequestIssueKind.APP_MANAGED_BODY, key))
        }
        if (route != null && !key.isCompatibleWith(route.protocol)) {
            add(CustomRequestIssue(CustomRequestIssueKind.PROTOCOL_MISMATCH, key))
        }
    }
}.distinct()

internal fun importCustomBodyObject(
    existing: List<CustomBody>,
    imported: JsonObject,
): List<CustomBody> {
    if (imported.isEmpty()) return existing
    val importedKeys = imported.keys
    return existing.filterNot { it.key.trim() in importedKeys } + imported.map { (key, value) ->
        CustomBody(key = key, value = value)
    }
}

internal fun addCustomRequestPreset(
    existing: List<CustomBody>,
    preset: CustomRequestPreset,
): List<CustomBody> {
    val existingKeys = existing.mapTo(hashSetOf()) { it.key.trim() }
    return existing + preset.bodies.filterNot { it.key.trim() in existingKeys }
}

internal fun effectiveCustomHeaders(
    assistantHeaders: List<CustomHeader>,
    modelHeaders: List<CustomHeader>,
): Map<String, String> {
    val values = linkedMapOf<String, Pair<String, String>>()
    (assistantHeaders + modelHeaders).forEach { header ->
        if (!header.enabled || header.name.isBlank() || !header.hasValidHttpSyntax()) return@forEach
        val name = header.name.trim()
        values[name.lowercase()] = name to if (name.isSensitiveName()) "******" else header.value.trim()
    }
    return values.values.associate { it }
}

internal fun effectiveCustomBody(
    assistantBodies: List<CustomBody>,
    modelBodies: List<CustomBody>,
): JsonObject = JsonObject(emptyMap())
    .mergeCustomBody(assistantBodies + modelBodies)
    .redactSensitiveValues() as JsonObject

private fun ParameterRequestRoute.appManagedBodyKeys(): Set<String> = when (protocol) {
    ParameterWireProtocol.OPENAI_CHAT_COMPLETIONS -> setOf("model", "messages", "tools", "stream")
    ParameterWireProtocol.OPENAI_RESPONSES -> setOf("model", "input", "instructions", "tools", "stream")
    ParameterWireProtocol.GOOGLE_GENERATE_CONTENT -> setOf(
        "contents", "systemInstruction", "tools", "toolConfig"
    )
    ParameterWireProtocol.ANTHROPIC_MESSAGES -> setOf("model", "messages", "system", "tools", "stream")
}

private fun String.isCompatibleWith(protocol: ParameterWireProtocol): Boolean = when {
    this in GOOGLE_BODY_KEYS -> protocol == ParameterWireProtocol.GOOGLE_GENERATE_CONTENT
    this in RESPONSES_BODY_KEYS -> protocol == ParameterWireProtocol.OPENAI_RESPONSES
    this in ANTHROPIC_BODY_KEYS -> protocol == ParameterWireProtocol.ANTHROPIC_MESSAGES
    this in OPENAI_CHAT_BODY_KEYS -> protocol == ParameterWireProtocol.OPENAI_CHAT_COMPLETIONS
    else -> true
}

private fun JsonElement.redactSensitiveValues(key: String = ""): JsonElement = when (this) {
    is JsonArray -> JsonArray(map { it.redactSensitiveValues(key) })
    is JsonObject -> JsonObject(mapValues { (childKey, childValue) ->
        if (childKey.isSensitiveName()) JsonPrimitive("******")
        else childValue.redactSensitiveValues(childKey)
    })
    else -> if (key.isSensitiveName()) JsonPrimitive("******") else this
}

private fun String.isSensitiveName(): Boolean {
    val normalized = lowercase().replace('-', '_')
    return SENSITIVE_NAME_PARTS.any(normalized::contains)
}

private val AUTHENTICATION_HEADERS = setOf(
    "authorization",
    "proxy-authorization",
    "x-api-key",
    "api-key",
)

private val SENSITIVE_NAME_PARTS = setOf("authorization", "api_key", "apikey", "password", "secret", "token", "cookie")

private val GOOGLE_BODY_KEYS = setOf("generationConfig", "safetySettings", "cachedContent")
private val RESPONSES_BODY_KEYS = setOf("input", "instructions", "truncation", "previous_response_id")
private val ANTHROPIC_BODY_KEYS = setOf("stop_sequences", "inference_geo", "output_config", "cache_control")
private val OPENAI_CHAT_BODY_KEYS = setOf(
    "response_format",
    "stop",
    "enable_thinking",
    "thinking_budget",
    "preserve_thinking",
    "tool_stream",
    "enable_search",
    "search_options",
    "vl_high_resolution_images",
    "user_id",
)
