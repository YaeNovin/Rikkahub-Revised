package me.rerere.rikkahub.data.ai.transformers

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.AssistantAffectScope
import me.rerere.rikkahub.data.model.replaceRegexes
import org.koin.core.component.KoinComponent

object RegexOutputTransformer : OutputMessageTransformer, KoinComponent {
    override suspend fun visualTransform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> = transformActualAssistantContent(ctx.assistant, messages)

    override suspend fun onGenerationFinish(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> = transformActualAssistantContent(ctx.assistant, messages)
}

internal fun transformActualAssistantContent(
    assistant: me.rerere.rikkahub.data.model.Assistant,
    messages: List<UIMessage>,
): List<UIMessage> {
    if (assistant.regexes.isEmpty()) return messages
    val targetIndex = messages.indexOfLast { it.role == MessageRole.ASSISTANT }
    if (targetIndex < 0) return messages
    return messages.mapIndexed { index, message ->
        if (index != targetIndex) return@mapIndexed message
        val scope = when (message.role) {
            MessageRole.ASSISTANT -> AssistantAffectScope.ASSISTANT
            else -> return@mapIndexed message
        }
        message.copy(
            parts = message.parts.map { part ->
                when (part) {
                    is UIMessagePart.Text -> {
                        part.copy(text = part.text.replaceRegexes(assistant, scope, visual = false))
                    }

                    is UIMessagePart.Reasoning -> {
                        part.copy(reasoning = part.reasoning.replaceRegexes(assistant, scope, visual = false))
                    }

                    else -> part
                }
            }
        )
    }
}
