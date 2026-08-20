package me.rerere.rikkahub.data.ai

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart

/**
 * Builds a provider-only continuation request for a partially received plain-text response.
 * The returned user instruction must never be persisted in the conversation.
 */
internal fun buildInterruptedStreamContinuation(
    requestMessages: List<UIMessage>,
    currentMessages: List<UIMessage>,
    hasClientTools: Boolean,
    hasServerTools: Boolean,
): List<UIMessage>? {
    if (hasClientTools || hasServerTools) return null

    val partialResponse = currentMessages.lastOrNull()
        ?.takeIf { it.role == MessageRole.ASSISTANT }
        ?: return null
    if (partialResponse.parts.any { it !is UIMessagePart.Text && it !is UIMessagePart.Reasoning }) {
        return null
    }

    val receivedText = partialResponse.parts
        .filterIsInstance<UIMessagePart.Text>()
        .joinToString(separator = "", transform = UIMessagePart.Text::text)
    if (receivedText.isBlank()) return null

    return requestMessages +
        UIMessage.assistant(receivedText) +
        UIMessage.user(STREAM_CONTINUATION_PROMPT)
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
        "Do not repeat any text already written. Output only the continuation."
