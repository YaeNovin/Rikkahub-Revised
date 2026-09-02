package me.rerere.rikkahub.data.model

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import kotlin.time.Clock
import kotlin.uuid.Uuid

/** Copies only the fields owned by the assistant prompt editor. */
fun Assistant.withPromptSettingsFrom(draft: Assistant): Assistant = copy(
    systemPrompt = draft.systemPrompt,
    allowConversationSystemPrompt = draft.allowConversationSystemPrompt,
    allowConversationPromptInjection = draft.allowConversationPromptInjection,
    messageTemplate = draft.messageTemplate,
    presetMessages = normalizePresetMessages(draft.presetMessages),
    regexes = normalizeAssistantRegexes(draft.regexes),
)

/**
 * Repairs editor data without changing the order or the message content.
 *
 * Old imports can contain duplicate ids. Compose uses ids as list keys and the
 * duplicate would crash the editor, so only the later occurrences receive a
 * new identity. Runtime fields are not part of a preset and are cleared when
 * the setting is persisted.
 */
fun normalizePresetMessages(messages: List<UIMessage>): List<UIMessage> {
    val usedIds = HashSet<Uuid>(messages.size)
    return messages.map { message ->
        val id = if (usedIds.add(message.id)) {
            message.id
        } else {
            generateUnusedUuid(usedIds)
        }
        message.copy(
            id = id,
            annotations = emptyList(),
            finishedAt = null,
            modelId = null,
            usage = null,
            translation = null,
            interrupted = false,
        )
    }
}

/** Repairs duplicate regex ids while preserving rule order and values. */
fun normalizeAssistantRegexes(regexes: List<AssistantRegex>): List<AssistantRegex> {
    val usedIds = HashSet<Uuid>(regexes.size)
    return regexes.map { regex ->
        val id = if (usedIds.add(regex.id)) {
            regex.id
        } else {
            generateUnusedUuid(usedIds)
        }
        regex.copy(id = id)
    }
}

private fun generateUnusedUuid(usedIds: MutableSet<Uuid>): Uuid {
    var candidate: Uuid
    do {
        candidate = Uuid.random()
    } while (!usedIds.add(candidate))
    return candidate
}

/** Updates the editable text while retaining attachments and other parts. */
fun UIMessage.withPresetText(text: String): UIMessage {
    val firstTextIndex = parts.indexOfFirst { it is UIMessagePart.Text }
    val nextParts = if (firstTextIndex < 0) {
        listOf(UIMessagePart.Text(text)) + parts
    } else {
        buildList {
            parts.forEachIndexed { index, part ->
                when {
                    index == firstTextIndex -> {
                        val metadata = (part as UIMessagePart.Text).metadata
                        add(UIMessagePart.Text(text, metadata))
                    }

                    part is UIMessagePart.Text -> Unit
                    else -> add(part)
                }
            }
        }
    }
    return copy(parts = nextParts)
}

/** Creates conversation-local preset messages without carrying editor/runtime identity. */
fun instantiatePresetMessages(
    presetMessages: List<UIMessage>,
    createdAt: LocalDateTime = Clock.System.now()
        .toLocalDateTime(TimeZone.currentSystemDefault()),
): List<UIMessage> = normalizePresetMessages(presetMessages)
    // A newly added but unfinished card must not create an invalid provider
    // message. The editor keeps it visible so the user can finish it later.
    .filter(UIMessage::isValidToUpload)
    .map { preset ->
    preset.copy(
        id = Uuid.random(),
        annotations = emptyList(),
        createdAt = createdAt,
        finishedAt = null,
        modelId = null,
        usage = null,
        translation = null,
        interrupted = false,
    )
}
