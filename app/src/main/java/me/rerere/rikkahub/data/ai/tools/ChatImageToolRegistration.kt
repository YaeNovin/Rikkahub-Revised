package me.rerere.rikkahub.data.ai.tools

import me.rerere.ai.core.Tool
import me.rerere.ai.provider.ImageGenerationConstraints
import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.ui.AskUserProtocol
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.tools.local.buildAskUserTool
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.resolveChatImageModel

/** One registration boundary for the request manifest, capability discovery and HITL support. */
internal fun List<Tool>.withChatImageTools(
    settings: Settings,
    assistant: Assistant,
    chatModel: Model,
    hasConversation: Boolean,
    constraintsFor: (ProviderSetting, Model) -> ImageGenerationConstraints,
    inspect: suspend (String) -> List<UIMessagePart>,
): List<Tool> {
    // These names belong to the app's task-bound tools, not a second independent registration.
    val base = filterNot { it.name == CHAT_IMAGE_TOOL_NAME || it.name == INSPECT_GENERATED_IMAGE_TOOL_NAME }
    if (!hasConversation || !assistant.enableImageGenerationTool || ModelAbility.TOOL !in chatModel.abilities) return base
    val imageModel = settings.resolveChatImageModel() ?: return base
    if (imageModel.modelId.isBlank()) return base
    if (imageModel.findProvider(settings.providers, checkOverwrite = false)?.enabled != true) return base
    val provider = imageModel.findProvider(settings.providers) ?: return base
    if (!constraintsFor(provider, imageModel).supportsGeneration) return base

    val inspectionAvailable = Modality.IMAGE in chatModel.inputModalities
    return buildList {
        addAll(base)
        add(chatImageToolDefinition(assistant.autoApproveImageGeneration, inspectionAvailable))
        // Keep an explicitly enabled generic ask_user unchanged, and never inject it when
        // image generation is unavailable. Its schema has no image-generation options.
        if (none { it.name == AskUserProtocol.TOOL_NAME }) add(buildAskUserTool())
        if (inspectionAvailable) add(inspectGeneratedImageTool(inspect))
    }
}
