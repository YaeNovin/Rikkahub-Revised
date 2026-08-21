package me.rerere.rikkahub.data.ai

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.ServerToolStatus
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.finishReasoning
import me.rerere.rikkahub.data.model.Conversation

/**
 * Builds a provider-only continuation request for a partially received response.
 * The returned user instruction must never be persisted in the conversation.
 */
internal fun buildInterruptedStreamContinuation(
    requestMessages: List<UIMessage>,
    currentMessages: List<UIMessage>,
    hasClientTools: Boolean,
    hasServerTools: Boolean,
): List<UIMessage>? {
    val partialResponse = currentMessages.lastOrNull()
        ?.takeIf { it.role == MessageRole.ASSISTANT }
        ?: return null

    val safePartialResponse = partialResponse.toProviderSafeContinuationMessage()
    val history = requestMessages.dropLastWhile { it.id == partialResponse.id }
    val continuationPrompt = if (hasClientTools || hasServerTools) {
        TOOL_AWARE_STREAM_CONTINUATION_PROMPT
    } else {
        STREAM_CONTINUATION_PROMPT
    }

    return history + safePartialResponse + UIMessage.user(continuationPrompt)
}

internal fun UIMessage.isInterruptedAssistantResponse(): Boolean {
    if (role != MessageRole.ASSISTANT) return false
    return interrupted || parts.any { part ->
        when (part) {
            is UIMessagePart.Tool -> part.isInterruptedTool() ||
                (!part.isExecuted && part.approvalState !is ToolApprovalState.Pending)
            is UIMessagePart.ServerTool -> part.status == ServerToolStatus.IN_PROGRESS ||
                part.isMarkedInterrupted()
            else -> false
        }
    }
}

internal fun Conversation.shouldResumeInterruptedResponseAt(message: UIMessage): Boolean {
    if (message.role != MessageRole.ASSISTANT) return false
    val targetNode = getMessageNodeByMessage(message) ?: return false
    if (messageNodes.indexOf(targetNode) != messageNodes.lastIndex) return false
    val currentMessage = currentMessages.lastOrNull() ?: return false
    return currentMessage.id == message.id && currentMessage.isInterruptedAssistantResponse()
}

/**
 * Makes unfinished tools inert before a manual continuation. Completed tool outputs are untouched.
 */
internal fun UIMessage.markInterruptedToolsForContinuation(): UIMessage {
    if (role != MessageRole.ASSISTANT) return this

    var changed = false
    val updatedParts = parts.map { part ->
        when {
            part is UIMessagePart.Tool && !part.isExecuted -> {
                changed = true
                part.copy(
                    output = listOf(UIMessagePart.Text(INTERRUPTED_CLIENT_TOOL_OUTPUT)),
                    approvalState = ToolApprovalState.Denied(INTERRUPTED_TOOL_REASON),
                )
            }

            part is UIMessagePart.ServerTool && part.status == ServerToolStatus.IN_PROGRESS -> {
                changed = true
                part.copy(
                    output = buildJsonObject {
                        put("status", "interrupted")
                        put("error", INTERRUPTED_TOOL_REASON)
                    },
                    status = ServerToolStatus.FAILED,
                )
            }

            else -> part
        }
    }
    val wasAlreadyMarked = parts.any { part ->
        (part is UIMessagePart.Tool && part.isInterruptedTool()) ||
            (part is UIMessagePart.ServerTool && part.isMarkedInterrupted())
    }
    if (!changed && !wasAlreadyMarked && interrupted && finishedAt == null) return this

    return copy(
        parts = updatedParts,
        finishedAt = null,
        interrupted = true,
    ).finishReasoning()
}

@Suppress("DEPRECATION")
private fun UIMessage.toProviderSafeContinuationMessage(): UIMessage {
    val safeParts = buildList {
        parts.forEach { part ->
            when (part) {
                is UIMessagePart.Text -> addContinuationText(part)
                is UIMessagePart.Reasoning -> Unit
                is UIMessagePart.Tool -> {
                    if (part.isExecuted && !part.isInterruptedTool()) {
                        add(part)
                    } else {
                        addContinuationNote(interruptedToolNote(part.toolName))
                    }
                }

                is UIMessagePart.ServerTool -> {
                    if (part.status != ServerToolStatus.IN_PROGRESS && !part.isMarkedInterrupted()) {
                        add(part)
                    } else {
                        addContinuationNote(interruptedToolNote(part.toolName))
                    }
                }

                is UIMessagePart.Image -> addContinuationNote(
                    "[An image output was already emitted before the interruption. Do not emit it again.]"
                )
                is UIMessagePart.Video -> addContinuationNote(
                    "[A video output was already emitted before the interruption. Do not emit it again.]"
                )
                is UIMessagePart.Audio -> addContinuationNote(
                    "[An audio output was already emitted before the interruption. Do not emit it again.]"
                )
                is UIMessagePart.Document -> addContinuationNote(
                    "[A document output was already emitted before the interruption. Do not emit it again.]"
                )
                is UIMessagePart.ToolCall -> addContinuationNote(interruptedToolNote(part.toolName))
                is UIMessagePart.ToolResult -> addContinuationNote(
                    "[A legacy tool result was received before the interruption. Do not repeat the tool call.]"
                )
                UIMessagePart.Search -> addContinuationNote(
                    "[A search operation was interrupted. Start a new complete search only if it is still needed.]"
                )
            }
        }
        if (isEmpty()) {
            add(
                UIMessagePart.Text(
                    "[The response was interrupted before any replay-safe final output was completed.]"
                )
            )
        }
    }
    return copy(
        parts = safeParts,
        annotations = emptyList(),
        finishedAt = null,
        usage = null,
        interrupted = false,
    )
}

private fun MutableList<UIMessagePart>.addContinuationNote(note: String) {
    val previous = lastOrNull()
    if (previous is UIMessagePart.Text) {
        this[lastIndex] = previous.copy(text = previous.text.trimEnd() + "\n" + note)
    } else {
        add(UIMessagePart.Text(note))
    }
}

private fun MutableList<UIMessagePart>.addContinuationText(part: UIMessagePart.Text) {
    val previous = lastOrNull()
    if (previous is UIMessagePart.Text && previous.metadata == part.metadata) {
        this[lastIndex] = previous.copy(text = previous.text + part.text)
    } else {
        add(part)
    }
}

private fun interruptedToolNote(toolName: String): String {
    val displayName = toolName.ifBlank { "unknown tool" }
    return "[The $displayName tool call was interrupted and was not completed. " +
        "Do not reuse partial arguments. If it is still needed, issue a new complete tool call.]"
}

private fun UIMessagePart.Tool.isInterruptedTool(): Boolean {
    val deniedReason = (approvalState as? ToolApprovalState.Denied)?.reason
    if (deniedReason == INTERRUPTED_TOOL_REASON || deniedReason == LEGACY_CANCELLED_TOOL_REASON) {
        return true
    }
    return output.filterIsInstance<UIMessagePart.Text>().any { textPart ->
        textPart.text.contains("\"status\":\"interrupted\"") ||
            textPart.text.contains("\"status\":\"cancelled\"")
    }
}

private fun UIMessagePart.ServerTool.isMarkedInterrupted(): Boolean {
    return output?.toString()?.contains("\"status\":\"interrupted\"") == true
}

internal fun List<UIMessage>.mergeLastAssistantTextParts(): List<UIMessage> {
    val assistant = lastOrNull()?.takeIf { it.role == MessageRole.ASSISTANT } ?: return this
    val mergedParts = assistant.parts.fold(mutableListOf<UIMessagePart>()) { parts, part ->
        val previous = parts.lastOrNull()
        if (previous is UIMessagePart.Text &&
            part is UIMessagePart.Text &&
            previous.metadata == part.metadata
        ) {
            parts[parts.lastIndex] = previous.copy(text = previous.text + part.text)
        } else {
            parts += part
        }
        parts
    }
    return if (mergedParts == assistant.parts) this else dropLast(1) + assistant.copy(parts = mergedParts)
}

private const val STREAM_CONTINUATION_PROMPT =
    "Continue the assistant response from exactly where it was interrupted. " +
        "Do not repeat any text already written. Do not expose or repeat hidden reasoning. " +
        "Output only the continuation."

private const val TOOL_AWARE_STREAM_CONTINUATION_PROMPT =
    "$STREAM_CONTINUATION_PROMPT Treat completed tool results above as authoritative and do not call those tools " +
        "again. An interrupted tool call must never be continued from partial arguments. If it is still required, " +
        "issue one new complete tool call and follow the normal approval process."

private const val INTERRUPTED_TOOL_REASON =
    "Generation interrupted before tool execution completed"
private const val LEGACY_CANCELLED_TOOL_REASON = "Generation cancelled by user"
private const val INTERRUPTED_CLIENT_TOOL_OUTPUT =
    "{\"status\":\"interrupted\",\"error\":\"$INTERRUPTED_TOOL_REASON\"}"
