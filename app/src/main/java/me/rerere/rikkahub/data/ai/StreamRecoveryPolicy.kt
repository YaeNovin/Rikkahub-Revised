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
 * Builds a provider-native continuation request ending with the safe assistant prefix.
 * No synthetic user or system instruction is added.
 */
internal fun buildInterruptedStreamContinuation(
    requestMessages: List<UIMessage>,
    currentMessages: List<UIMessage>,
): List<UIMessage>? {
    val partialResponse = currentMessages.lastOrNull()
        ?.takeIf { it.role == MessageRole.ASSISTANT }
        ?: return null

    val safePartialResponse = partialResponse.toProviderSafeContinuationMessage() ?: return null
    val history = requestMessages.dropLastWhile { it.id == partialResponse.id }
    return history + safePartialResponse
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

internal fun UIMessage.canContinueInterruptedResponse(): Boolean =
    isInterruptedAssistantResponse() && toProviderSafeContinuationMessage() != null

internal fun Conversation.shouldResumeInterruptedResponseAt(message: UIMessage): Boolean {
    if (message.role != MessageRole.ASSISTANT) return false
    val targetNode = getMessageNodeByMessage(message) ?: return false
    if (messageNodes.indexOf(targetNode) != messageNodes.lastIndex) return false
    val currentMessage = currentMessages.lastOrNull() ?: return false
    return currentMessage.id == message.id &&
        currentMessage.canContinueInterruptedResponse()
}

/**
 * Makes automatically started but unfinished tools inert before a continuation. Completed tool
 * outputs and explicit user decisions are untouched. Pending approvals are kept actionable so a
 * cancellation while the approval UI is being handed off cannot become a false denial.
 */
internal fun UIMessage.markInterruptedToolsForContinuation(
    forcePendingApprovals: Boolean = false,
): UIMessage {
    if (role != MessageRole.ASSISTANT) return this

    var changed = false
    val updatedParts = parts.map { part ->
        when {
            part is UIMessagePart.Tool &&
                !part.isExecuted &&
                (part.approvalState is ToolApprovalState.Auto ||
                    (forcePendingApprovals && part.approvalState is ToolApprovalState.Pending)) -> {
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
    val hasExplicitUnfinishedToolDecision = parts.any { part ->
        part is UIMessagePart.Tool &&
            !part.isExecuted &&
            part.approvalState !is ToolApprovalState.Auto
    }
    if (!changed && !wasAlreadyMarked && hasExplicitUnfinishedToolDecision) return this

    return copy(
        parts = updatedParts,
        finishedAt = null,
        interrupted = true,
    ).finishReasoning()
}

@Suppress("DEPRECATION")
private fun UIMessage.toProviderSafeContinuationMessage(): UIMessage? {
    val safeParts = buildList {
        parts.forEach { part ->
            when (part) {
                is UIMessagePart.Text -> addContinuationText(part)
                is UIMessagePart.Reasoning -> Unit
                is UIMessagePart.Tool -> {
                    if (part.isExecuted && !part.isInterruptedTool()) {
                        add(part)
                    }
                }

                is UIMessagePart.ServerTool -> {
                    if (part.status != ServerToolStatus.IN_PROGRESS && !part.isMarkedInterrupted()) {
                        add(part)
                    }
                }

                is UIMessagePart.Image,
                is UIMessagePart.Video,
                is UIMessagePart.Audio,
                is UIMessagePart.Document,
                is UIMessagePart.ToolCall,
                is UIMessagePart.ToolResult,
                UIMessagePart.Search,
                    -> Unit
            }
        }
    }
    if (safeParts.isEmpty()) return null
    return copy(
        parts = safeParts,
        annotations = emptyList(),
        finishedAt = null,
        usage = null,
        interrupted = false,
    )
}

private fun MutableList<UIMessagePart>.addContinuationText(part: UIMessagePart.Text) {
    val previous = lastOrNull()
    if (previous is UIMessagePart.Text && previous.metadata == part.metadata) {
        this[lastIndex] = previous.copy(text = previous.text + part.text)
    } else {
        add(part)
    }
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

private const val INTERRUPTED_TOOL_REASON =
    "Generation interrupted before tool execution completed"
private const val LEGACY_CANCELLED_TOOL_REASON = "Generation cancelled by user"
private const val INTERRUPTED_CLIENT_TOOL_OUTPUT =
    "{\"status\":\"interrupted\",\"error\":\"$INTERRUPTED_TOOL_REASON\"}"
