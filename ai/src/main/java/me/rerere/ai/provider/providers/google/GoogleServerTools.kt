package me.rerere.ai.provider.providers.google

import kotlinx.serialization.json.*
import me.rerere.ai.ui.*
import kotlin.uuid.Uuid

/** Context circulation is required only for the actual mixed wire request, including custom bodies. */
internal fun JsonObject.withGoogleToolContextCirculation(): JsonObject {
    val tools = (get("tools") as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
    val functions = tools.any { ((it["functionDeclarations"] ?: it["function_declarations"]) as? JsonArray)?.isNotEmpty() == true }
    val builtIn = tools.any { tool -> tool.keys.any { it in setOf("googleSearch", "google_search", "urlContext", "url_context", "codeExecution", "code_execution", "googleMaps", "google_maps") } }
    if (!functions || !builtIn) return this
    val config = (get("toolConfig") ?: get("tool_config")) as? JsonObject ?: JsonObject(emptyMap())
    return JsonObject((this - "tool_config") + ("toolConfig" to JsonObject(
        (config - "include_server_side_tool_invocations") + ("includeServerSideToolInvocations" to JsonPrimitive(true))
    )))
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
