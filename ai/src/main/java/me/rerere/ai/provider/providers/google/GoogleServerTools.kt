package me.rerere.ai.provider.providers.google

import kotlinx.serialization.json.*
import me.rerere.ai.ui.*
import kotlin.uuid.Uuid

/** Apply the generateContent tool-combination contract after all custom request bodies. */
internal fun JsonObject.withGoogleToolContextCirculation(): JsonObject {
    val tools = (get("tools") as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
    // Inspect both spellings independently: an empty/null camelCase field must not
    // hide declarations supplied with protobuf's snake_case spelling by a relay.
    val functions = tools.any { tool ->
        listOf("functionDeclarations", "function_declarations").any { key ->
            (tool[key] as? JsonArray)?.isNotEmpty() == true
        }
    }
    val builtIn = tools.any { tool -> tool.keys.any { it in setOf(
        "googleSearch", "google_search", "urlContext", "url_context", "codeExecution", "code_execution",
        "googleMaps", "google_maps", "fileSearch", "file_search",
    ) } }
    // Assistant/model custom bodies may contribute either root spelling. Preserve
    // their other options while emitting one canonical ToolConfig with the mandatory
    // flag. In particular, a custom false value cannot disable this mixed-tool contract.
    val config = mergeGoogleToolConfig(
        canonicalGoogleToolConfig((get("tool_config") as? JsonObject) ?: JsonObject(emptyMap())),
        canonicalGoogleToolConfig((get("toolConfig") as? JsonObject) ?: JsonObject(emptyMap())),
    )
    // The same mode restriction also applies when the user explicitly enables
    // circulation for built-ins alone. Leave unrelated single-category requests intact.
    val explicitlyEnabled = (config["includeServerSideToolInvocations"] as? JsonPrimitive)?.booleanOrNull == true
    if (!(functions && builtIn) && !explicitlyEnabled) return this
    val functionConfig = (config["functionCallingConfig"] as? JsonObject) ?: JsonObject(emptyMap())
    val mode = (functionConfig["mode"] as? JsonPrimitive)?.contentOrNull
    // Official tool-combination docs: default to VALIDATED; AUTO is unsupported.
    // Preserve explicit ANY/NONE/VALIDATED and the user's allowed-function restriction.
    val validatedConfig = if (mode == null || mode.equals("AUTO", ignoreCase = true)) {
        JsonObject(functionConfig + ("mode" to JsonPrimitive("VALIDATED")))
    } else functionConfig
    return JsonObject((this - "tool_config") + ("toolConfig" to JsonObject(
        config + ("includeServerSideToolInvocations" to JsonPrimitive(true)) +
            ("functionCallingConfig" to validatedConfig)
    )))
}

/** Normalize each root before merging, so an overlay's NONE cannot lose to a base AUTO. */
private fun canonicalGoogleToolConfig(config: JsonObject): JsonObject {
    val normalized = config.mergeGoogleAliases(mapOf(
        "include_server_side_tool_invocations" to "includeServerSideToolInvocations",
        "function_calling_config" to "functionCallingConfig",
    ))
    val functionConfig = normalized["functionCallingConfig"] as? JsonObject ?: return normalized
    return JsonObject(normalized + ("functionCallingConfig" to
        functionConfig.mergeGoogleAliases(mapOf("allowed_function_names" to "allowedFunctionNames"))))
}

private fun JsonObject.mergeGoogleAliases(aliases: Map<String, String>): JsonObject = mergeGoogleToolConfig(
    JsonObject(filterKeys { it in aliases }.mapKeys { (key, _) -> aliases.getValue(key) }),
    JsonObject(filterKeys { it !in aliases }),
)

private fun mergeGoogleToolConfig(base: JsonObject, overlay: JsonObject): JsonObject {
    val merged = base.toMutableMap()
    overlay.forEach { (key, value) ->
        val old = merged[key]
        merged[key] = if (old is JsonObject && value is JsonObject) mergeGoogleToolConfig(old, value) else value
    }
    return JsonObject(merged)
}

/** Keep each server wire part at its original position, even when text/client calls intervene.
 * These are NOT executable client tools. Provider IDs and thought signatures remain opaque.
 */
internal fun parseGoogleServerToolPart(part: JsonObject): UIMessagePart.ServerTool? {
    val call = part["toolCall"] as? JsonObject
    val result = part["toolResponse"] as? JsonObject
    val payload = call ?: result ?: return null
    return UIMessagePart.ServerTool(
        toolCallId = "google-server:${Uuid.random()}",
        toolName = payload["toolType"]?.jsonPrimitive?.contentOrNull ?: "Google built-in tool",
        input = call?.get("args"),
        output = result?.get("response"),
        status = if (call != null) ServerToolStatus.IN_PROGRESS else ServerToolStatus.COMPLETED,
        metadata = ServerToolMetadata(
            protocol = ServerToolProtocol.GOOGLE_GENERATE_CONTENT,
            call = part.takeIf { call != null },
            result = part.takeIf { result != null },
        ).toMetadata(),
    )
}

internal fun UIMessagePart.ServerTool.googleWirePart(): JsonObject? = metadataAs<ServerToolMetadata>()
    ?.takeIf { it.protocol == ServerToolProtocol.GOOGLE_GENERATE_CONTENT }
    ?.let { it.call ?: it.result }

internal fun UIMessagePart.ServerTool.googleServerInvocationId(): String? = googleWirePart()?.let { part ->
    ((part["toolCall"] ?: part["toolResponse"]) as? JsonObject)?.get("id")?.jsonPrimitive?.contentOrNull
}

internal fun completeGoogleServerCalls(parts: List<UIMessagePart>): List<UIMessagePart> {
    val results = parts.filterIsInstance<UIMessagePart.ServerTool>()
        .filter { it.googleWirePart()?.containsKey("toolResponse") == true }
        .mapNotNull { result -> result.googleServerInvocationId()?.let { it to result } }.toMap()
    return parts.map { part ->
        if (part is UIMessagePart.ServerTool && part.googleWirePart()?.containsKey("toolCall") == true) {
            results[part.googleServerInvocationId()]?.let { part.copy(output = it.output, status = it.status) }
                ?: part.copy(status = ServerToolStatus.FAILED)
        } else part
    }
}
