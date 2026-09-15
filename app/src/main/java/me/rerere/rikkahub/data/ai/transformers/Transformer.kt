package me.rerere.rikkahub.data.ai.transformers

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.LorebookEntryRuntimeState
import me.rerere.rikkahub.data.model.PromptInjectionEvaluation
import me.rerere.rikkahub.data.model.WorkspaceFileOperationMode
import me.rerere.rikkahub.data.model.LorebookGenerationTrigger
import kotlin.uuid.Uuid

class TransformerContext(
    val context: Context,
    val model: Model,
    val assistant: Assistant,
    val settings: Settings,
    val conversationModeInjectionIds: Set<Uuid> = emptySet(),
    val conversationLorebookIds: Set<Uuid> = emptySet(),
    val disabledLorebookIds: Set<Uuid> = emptySet(),
    val allowLorebookNetwork: Boolean = false,
    val temporaryModeInjections: Map<Uuid, Int> = emptyMap(),
    val lorebookRuntimeStates: Map<Uuid, LorebookEntryRuntimeState> = emptyMap(),
    val conversationUserTurn: Int? = null,
    val onPromptInjectionEvaluation: ((PromptInjectionEvaluation) -> Unit)? = null,
    val processingStatus: MutableStateFlow<String?> = MutableStateFlow(null),
    val workspaceCwd: String? = null,
    val workspaceFileOperationMode: WorkspaceFileOperationMode = WorkspaceFileOperationMode.TOOLS,
    val conversationId: Uuid? = null,
    val conversationMessages: List<UIMessage> = emptyList(),
    val userName: String = "user",
    val assistantName: String = assistant.name.ifBlank { "assistant" },
    val generationTrigger: LorebookGenerationTrigger = LorebookGenerationTrigger.NORMAL,
    val assistantTagNames: Set<String> = settings.assistantTags.filter { it.id in assistant.tags }.map { it.name }.toSet(),
) {
    // One snapshot for keyword expansion, worldbook budgeting and later message interpolation.
    internal var promptVariableValues: Map<String, String>? = null
}

interface MessageTransformer {
    /**
     * 消息转换器，用于对消息进行转换
     *
     * 对于输入消息，消息会转换被提供给API模块
     *
     * 对于输出消息，会对消息输出chunk进行转换
     */
    suspend fun transform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        return messages
    }
}

interface InputMessageTransformer : MessageTransformer

interface OutputMessageTransformer : MessageTransformer {
    /**
     * 一个视觉的转换，例如转换think tag为reasoning parts
     * 但是不实际转换消息，因为流式输出需要处理消息delta chunk
     * 不能还没结束生成就transform，因此提供一个visualTransform
     */
    suspend fun visualTransform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        return messages
    }

    /**
     * 消息生成完成后调用
     */
    suspend fun onGenerationFinish(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        return messages
    }
}

suspend fun List<UIMessage>.transforms(
    transformers: List<MessageTransformer>,
    context: Context,
    model: Model,
    assistant: Assistant,
    settings: Settings,
    conversationModeInjectionIds: Set<Uuid> = emptySet(),
    conversationLorebookIds: Set<Uuid> = emptySet(),
    disabledLorebookIds: Set<Uuid> = emptySet(),
    allowLorebookNetwork: Boolean = false,
    temporaryModeInjections: Map<Uuid, Int> = emptyMap(),
    lorebookRuntimeStates: Map<Uuid, LorebookEntryRuntimeState> = emptyMap(),
    conversationUserTurn: Int? = null,
    onPromptInjectionEvaluation: ((PromptInjectionEvaluation) -> Unit)? = null,
    processingStatus: MutableStateFlow<String?> = MutableStateFlow(null),
    workspaceCwd: String? = null,
    workspaceFileOperationMode: WorkspaceFileOperationMode = WorkspaceFileOperationMode.TOOLS,
    conversationId: Uuid? = null,
    conversationMessages: List<UIMessage> = emptyList(),
    userName: String = "user",
    assistantName: String = assistant.name.ifBlank { "assistant" },
    generationTrigger: LorebookGenerationTrigger = LorebookGenerationTrigger.NORMAL,
    assistantTagNames: Set<String> = settings.assistantTags.filter { it.id in assistant.tags }.map { it.name }.toSet(),
): List<UIMessage> {
    val ctx = TransformerContext(
        context = context,
        model = model,
        assistant = assistant,
        settings = settings,
        conversationModeInjectionIds = conversationModeInjectionIds,
        conversationLorebookIds = conversationLorebookIds,
        disabledLorebookIds = disabledLorebookIds,
        allowLorebookNetwork = allowLorebookNetwork,
        temporaryModeInjections = temporaryModeInjections,
        lorebookRuntimeStates = lorebookRuntimeStates,
        conversationUserTurn = conversationUserTurn,
        onPromptInjectionEvaluation = onPromptInjectionEvaluation,
        processingStatus = processingStatus,
        workspaceCwd = workspaceCwd,
        workspaceFileOperationMode = workspaceFileOperationMode,
        conversationId = conversationId,
        conversationMessages = conversationMessages,
        userName = userName,
        assistantName = assistantName,
        generationTrigger = generationTrigger,
        assistantTagNames = assistantTagNames,
    )
    return transformers.fold(this) { acc, transformer ->
        transformer.transform(ctx, acc)
    }
}

suspend fun List<UIMessage>.visualTransforms(
    transformers: List<MessageTransformer>,
    context: Context,
    model: Model,
    assistant: Assistant,
    settings: Settings,
): List<UIMessage> {
    val ctx = TransformerContext(context, model, assistant, settings)
    return transformers.fold(this) { acc, transformer ->
        if (transformer is OutputMessageTransformer) {
            transformer.visualTransform(ctx, acc)
        } else {
            acc
        }
    }
}

suspend fun List<UIMessage>.onGenerationFinish(
    transformers: List<MessageTransformer>,
    context: Context,
    model: Model,
    assistant: Assistant,
    settings: Settings,
): List<UIMessage> {
    val ctx = TransformerContext(context, model, assistant, settings)
    return transformers.fold(this) { acc, transformer ->
        if (transformer is OutputMessageTransformer) {
            transformer.onGenerationFinish(ctx, acc)
        } else {
            acc
        }
    }
}
