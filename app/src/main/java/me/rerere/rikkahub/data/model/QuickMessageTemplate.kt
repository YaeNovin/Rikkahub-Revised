package me.rerere.rikkahub.data.model

import me.rerere.rikkahub.data.datastore.QuickMessageSortMode
import me.rerere.rikkahub.data.datastore.Settings
import java.util.Locale

private val QUICK_MESSAGE_PLACEHOLDER = Regex("""\{\{\s*([^{}\r\n]+?)\s*\}\}""")

fun QuickMessage.placeholderNames(): List<String> = QUICK_MESSAGE_PLACEHOLDER
    .findAll(content)
    .map { it.groupValues[1].trim() }
    .filter { it.isNotEmpty() }
    .distinct()
    .toList()

fun QuickMessage.render(values: Map<String, String>): String =
    QUICK_MESSAGE_PLACEHOLDER.replace(content) { match ->
        values[match.groupValues[1].trim()] ?: match.value
    }

/** Values that do not require user input when inserting a quick message. */
fun automaticQuickMessageValues(
    settings: Settings,
    assistant: Assistant,
): Map<String, String> {
    val assistantName = assistant.name.ifBlank { "assistant" }
    val userName = settings.displaySetting.userNickname.ifBlank { "user" }
    val mode = settings.extensionManagementMode.name.lowercase(Locale.ROOT)
    return mapOf(
        "assistant_name" to assistantName,
        "assistant" to assistantName,
        "char" to assistantName,
        "char_name" to assistantName,
        "character_name" to assistantName,
        "user" to userName,
        "user_name" to userName,
        "nickname" to userName,
        "player_name" to userName,
        "app_mode" to mode,
        "roleplay_mode" to (mode == "entertainment").toString(),
    )
}

fun QuickMessage.matchesQuery(query: String): Boolean {
    val normalized = query.trim().lowercase()
    if (normalized.isEmpty()) return true
    return sequenceOf(title, content, category)
        .plus(tags.asSequence())
        .any { it.lowercase().contains(normalized) }
}

fun List<QuickMessage>.sortedForDisplay(mode: QuickMessageSortMode): List<QuickMessage> {
    val indexed = withIndex().toList()
    val comparator = when (mode) {
        QuickMessageSortMode.DEFAULT -> compareByDescending<IndexedValue<QuickMessage>> { it.value.favorite }
            .thenBy { it.index }

        QuickMessageSortMode.RECENT -> compareByDescending<IndexedValue<QuickMessage>> { it.value.favorite }
            .thenByDescending { it.value.lastUsedAt }
            .thenBy { it.index }

        QuickMessageSortMode.FREQUENT -> compareByDescending<IndexedValue<QuickMessage>> { it.value.favorite }
            .thenByDescending { it.value.useCount }
            .thenByDescending { it.value.lastUsedAt }
            .thenBy { it.index }
    }
    return indexed.sortedWith(comparator).map { it.value }
}

fun normalizeQuickMessageTags(tags: Iterable<String>): List<String> = tags
    .map { it.trim() }
    .filter { it.isNotEmpty() }
    .distinctBy { it.lowercase() }

fun Assistant.upsertQuickMessageGroup(group: QuickMessageGroup): Assistant {
    val normalized = group.copy(
        name = group.name.trim(),
        quickMessageIds = group.quickMessageIds.intersect(quickMessageIds),
    )
    val withoutSelectedMessages = quickMessageGroups
        .filterNot { it.id == normalized.id }
        .map { existing ->
            existing.copy(
                quickMessageIds = existing.quickMessageIds - normalized.quickMessageIds
            )
        }
    val existingIndex = quickMessageGroups.indexOfFirst { it.id == normalized.id }
    val updated = if (existingIndex >= 0) {
        withoutSelectedMessages.toMutableList().apply {
            add(existingIndex.coerceAtMost(size), normalized)
        }
    } else {
        withoutSelectedMessages + normalized
    }
    return copy(quickMessageGroups = updated)
}

fun Assistant.removeQuickMessageGroup(groupId: kotlin.uuid.Uuid): Assistant = copy(
    quickMessageGroups = quickMessageGroups.filterNot { it.id == groupId }
)
