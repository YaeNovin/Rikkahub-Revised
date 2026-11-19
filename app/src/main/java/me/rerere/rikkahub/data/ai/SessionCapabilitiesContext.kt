package me.rerere.rikkahub.data.ai

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.tools.SESSION_CAPABILITIES_TOOL_NAME

/** Refresh only the app's read-only capability query in the request copy.
 * Persisted history, IDs, approvals, image tasks and other tool outputs stay intact.
 * Run after message transforms, including on stream recovery, so old snapshots
 * cannot reappear when the user enables/disables a feature between turns. */
internal suspend fun List<UIMessage>.withCurrentSessionCapabilities(
    capabilityTool: Tool?,
): List<UIMessage> {
    require(capabilityTool == null || capabilityTool.name == SESSION_CAPABILITIES_TOOL_NAME)
    if (none { message -> message.getTools().any { it.toolName == SESSION_CAPABILITIES_TOOL_NAME && it.isExecuted } }) return this

    fun unavailable(reason: String) = listOf(UIMessagePart.Text(buildJsonObject {
        put("snapshot_scope", "historical_not_current")
        put("status", "unavailable")
        put("reason", reason)
        put("instruction", "Do not infer current feature availability from this historical query or earlier assistant statements. Use current tool declarations; query again if discovery is available.")
    }.toString()))

    val snapshots = mutableMapOf<String, List<UIMessagePart>>()
    return map { message ->
        val parts = message.parts.map { part ->
            if (part !is UIMessagePart.Tool || part.toolName != SESSION_CAPABILITIES_TOOL_NAME || !part.isExecuted) part
            else {
                val output = snapshots[part.input] ?: (if (capabilityTool == null) {
                    unavailable("Capability discovery is not available in this request.")
                } else {
                    try {
                        capabilityTool.execute(part.inputAsJson())
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        // Never silently send stale enabled/disabled flags on refresh failure.
                        unavailable("The current capability snapshot could not be refreshed.")
                    }
                }).also { snapshots[part.input] = it }
                if (output == part.output) part else part.copy(output = output)
            }
        }
        if (parts == message.parts) message else message.copy(parts = parts)
    }
}
