package me.rerere.rikkahub.data.ai.transformers

import android.content.Context
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.context.estimateTextTokens
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.db.entity.WorkspaceEntity
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.instantiatePresetMessages

data class AssistantPromptPreview(
    val messages: List<UIMessage>,
    val estimatedTokens: Int,
)

/**
 * Builds the static part of the request with the same transformer order used by generation.
 * Runtime-only memory, tools, workspace reminders, attachments and conversation overrides are
 * intentionally excluded because they do not exist in the assistant editor.
 */
suspend fun buildAssistantPromptPreview(
    context: Context,
    model: Model,
    assistant: Assistant,
    settings: Settings,
    sampleUserInput: String,
    templateTransformer: TemplateTransformer,
    workspace: WorkspaceEntity? = null,
): AssistantPromptPreview {
    val contextMessages = buildList {
        if (assistant.systemPrompt.isNotBlank()) {
            add(UIMessage.system(assistant.systemPrompt))
        }
        addAll(instantiatePresetMessages(assistant.presetMessages))
        add(UIMessage.user(sampleUserInput))
    }
    val transformerContext = TransformerContext(
        context = context,
        model = model,
        assistant = assistant,
        settings = settings,
    )
    val promptVariables = PromptVariableResolutionContext(
        settings = settings,
        model = model,
        assistant = assistant,
        workspace = workspace,
        context = context,
    ).resolvePromptVariables()
    val transformed = templateTransformer.transformWithTemplate(
        templateSource = assistant.messageTemplate,
        messages = PlaceholderTransformer.transform(
            ctx = transformerContext,
            messages = PromptInjectionTransformer.transform(
                ctx = transformerContext,
                messages = contextMessages,
            ),
        ),
        variables = promptVariables,
    )
    val estimatedTokens = transformed.sumOf { message ->
        message.parts.sumOf { part ->
            when (part) {
                is UIMessagePart.Text -> estimateTextTokens(part.text)
                is UIMessagePart.Reasoning -> estimateTextTokens(part.reasoning)
                else -> 0
            }
        }
    }
    return AssistantPromptPreview(
        messages = transformed,
        estimatedTokens = estimatedTokens,
    )
}
