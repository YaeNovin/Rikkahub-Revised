package me.rerere.rikkahub.ui.components.ai

import me.rerere.ai.core.MessageRole
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.Lorebook
import me.rerere.rikkahub.data.model.PromptInjection
import me.rerere.rikkahub.data.model.resolveActiveModes
import me.rerere.rikkahub.data.model.LorebookSources
import me.rerere.rikkahub.data.model.resolve

internal fun hasEnabledPromptDiagnostics(
    assistant: Assistant,
    conversation: Conversation,
    modes: List<PromptInjection.ModeInjection>,
    lorebooks: List<Lorebook>,
    sources: LorebookSources = LorebookSources(),
): Boolean {
    val allowConversation = assistant.allowConversationPromptInjection
    val selectedBooks = sources.resolve(assistant, conversation.lorebookIds, conversation.disabledLorebookIds).keys
    if (lorebooks.any { it.enabled && it.id in selectedBooks }) return true
    return resolveActiveModes(
        modeInjections = modes,
        assistantModeIds = assistant.modeInjectionIds,
        conversationModeIds = if (allowConversation) conversation.modeInjectionIds else emptySet(),
        temporaryModes = if (allowConversation) conversation.temporaryModeInjections else emptyMap(),
        currentUserTurn = conversation.currentMessages.count { it.role == MessageRole.USER },
    ).isNotEmpty()
}
