package me.rerere.rikkahub.data.model

import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.BuiltInTools
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.getAssistantById
import java.security.MessageDigest

fun Conversation.suggestionSourceMessage() = suggestionContext().currentMessages.lastOrNull()?.takeIf { message ->
    (message.role == MessageRole.ASSISTANT || suggestionSession.target != null) && !message.interrupted &&
        message.parts.any { it is UIMessagePart.Text && it.text.isNotBlank() } &&
        message.getTools().all { it.isExecuted }
}

/** Local identity of the selected context. Draft metadata, title and usage do not affect it. */
fun Conversation.suggestionContextKey(): String {
    val digest = MessageDigest.getInstance("SHA-256")
    fun add(value: String) { digest.update(value.toByteArray(Charsets.UTF_8)); digest.update(0.toByte()) }
    add(id.toString())
    add(assistantId.toString())
    add(memoryMode.name)
    add(suggestionSession.target?.selectedText.orEmpty())
    suggestionContext().currentMessages.takeLast(8).forEach { message ->
        add(message.id.toString())
        add(message.role.name)
        add(message.toText())
        message.parts.forEach { part ->
            when (part) {
                is UIMessagePart.Image -> add(part.url)
                is UIMessagePart.Video -> add(part.url)
                is UIMessagePart.Audio -> add(part.url)
                is UIMessagePart.Document -> { add(part.fileName); add(part.url) }
                else -> Unit
            }
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
}

/** Use effective conversation memory and the main chat model, not the suggestion model. */
fun Conversation.availableSuggestionActions(settings: Settings): Set<ChatSuggestionAction> {
    val configured = settings.getAssistantById(assistantId) ?: return emptySet()
    val assistant = memoryAssistant(configured, settings)
    val model = settings.findModelById(assistant.chatModelId ?: settings.chatModelId)
    val tools = model?.abilities?.contains(ModelAbility.TOOL) == true
    return ChatSuggestionAction.entries.filterTo(linkedSetOf()) { action ->
        when (action) {
            ChatSuggestionAction.IMAGE_DRAFT -> suggestionConfig(settings).options.imageSuggestions && settings.resolveChatImageModel() != null
            ChatSuggestionAction.SEARCH_WEB -> model?.tools?.contains(BuiltInTools.Search) == true || (tools && assistant.enableWebSearch)
            ChatSuggestionAction.ASK_USER, ChatSuggestionAction.SEARCH_CONVERSATIONS,
            ChatSuggestionAction.WORKSPACE, ChatSuggestionAction.USE_SKILL, ChatSuggestionAction.MCP -> tools && action.isAvailableFor(assistant)
            else -> action.isAvailableFor(assistant)
        }
    }
}
