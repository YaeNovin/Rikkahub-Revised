package me.rerere.rikkahub.ui.components.ai

import me.rerere.ai.core.MessageRole
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.Lorebook
import me.rerere.rikkahub.data.model.PromptInjection
import me.rerere.rikkahub.data.model.resolveActiveModes

internal fun hasEnabledPromptDiagnostics(
    assistant: Assistant,
    conversation: Conversation,
    modes: List<PromptInjection.ModeInjection>,
    lorebooks: List<Lorebook>,
): Boolean {
    val allowConversation = assistant.allowConversationPromptInjection
    val selectedBooks = (assistant.lorebookIds + if (allowConversation) conversation.lorebookIds else emptySet()) -
        if (allowConversation) conversation.disabledLorebookIds else emptySet()
    if (lorebooks.any { it.enabled && it.id in selectedBooks }) return true
    return resolveActiveModes(
        modeInjections = modes,
        assistantModeIds = assistant.modeInjectionIds,
        conversationModeIds = if (allowConversation) conversation.modeInjectionIds else emptySet(),
        temporaryModes = if (allowConversation) conversation.temporaryModeInjections else emptyMap(),
        currentUserTurn = conversation.currentMessages.count { it.role == MessageRole.USER },
    ).isNotEmpty()
}
