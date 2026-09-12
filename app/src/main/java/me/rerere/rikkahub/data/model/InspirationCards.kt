package me.rerere.rikkahub.data.model

import kotlinx.serialization.Serializable
import me.rerere.rikkahub.data.datastore.ExtensionManagementMode
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.getAssistantById
import kotlin.random.Random
import kotlin.uuid.Uuid

@Serializable
enum class InspirationAudience { ALL, NORMAL, ENTERTAINMENT }

@Serializable
data class InspirationCard(
    val id: String = "custom:${Uuid.random()}",
    val title: String = "",
    val prompt: String = "",
    val audience: InspirationAudience = InspirationAudience.ALL,
) {
    fun template() = QuickMessage(title = title, content = prompt)
    fun matches(mode: ExtensionManagementMode) = audience == InspirationAudience.ALL || audience.name == mode.name
}

@Serializable
data class InspirationSettings(
    val cardCount: Int = 4,
    val includeBuiltIns: Boolean = true,
    val customCards: List<InspirationCard> = emptyList(),
    val pinnedIds: List<String> = emptyList(),
) {
    fun normalized() = copy(
        cardCount = cardCount.coerceIn(1, 8),
        customCards = customCards.filter { it.id.startsWith("custom:") && it.title.isNotBlank() && it.prompt.isNotBlank() }
            .distinctBy { it.id }.take(40).map { it.copy(title = it.title.trim().take(80), prompt = it.prompt.take(8000)) },
        pinnedIds = pinnedIds.distinct().take(40),
    )

    fun togglePin(id: String) = copy(pinnedIds = if (id in pinnedIds) pinnedIds - id else pinnedIds + id).normalized()
    fun removeCard(id: String) = copy(customCards = customCards.filterNot { it.id == id }, pinnedIds = pinnedIds - id)
}

@Serializable
data class InspirationAppearance(
    val followRichContent: Boolean = true,
    val surfaceOpacity: Float = .62f,
    val borderOpacity: Float = .34f,
    val cornerRadius: Float = 12f,
) {
    fun normalized() = copy(
        surfaceOpacity = surfaceOpacity.safeIn(.2f, 1f, .62f),
        borderOpacity = borderOpacity.safeIn(0f, 1f, .34f),
        cornerRadius = cornerRadius.safeIn(0f, 28f, 12f),
    )
}

private fun Float.safeIn(min: Float, max: Float, fallback: Float) = if (isFinite()) coerceIn(min, max) else fallback

fun Settings.inspirationSettings(assistantId: Uuid?) =
    (assistantId?.let { getAssistantById(it)?.inspirationSettings } ?: displaySetting.inspirationSettings).normalized()

/** Resolve from the latest settings; editing one assistant must not overwrite other preferences. */
fun Settings.withInspirationSettings(assistantId: Uuid?, transform: (InspirationSettings) -> InspirationSettings): Settings {
    val updated = transform(inspirationSettings(assistantId)).normalized()
    return if (assistantId == null) copy(displaySetting = displaySetting.copy(inspirationSettings = updated))
    else copy(assistants = assistants.map { if (it.id == assistantId) it.copy(inspirationSettings = updated) else it })
}

internal fun inspirationPool(builtIns: List<InspirationCard>, config: InspirationSettings, mode: ExtensionManagementMode) =
    ((if (config.includeBuiltIns) builtIns else emptyList()) + config.normalized().customCards)
        .filter { it.matches(mode) }.distinctBy { it.id }

/** Rotate only the unpinned portion; the same conversation and revision give a stable batch. */
internal fun inspirationBatch(cards: List<InspirationCard>, config: InspirationSettings, seed: Int, revision: Int): List<InspirationCard> {
    val count = config.cardCount.coerceIn(1, 8)
    val byId = cards.associateBy { it.id }
    val pinned = config.pinnedIds.distinct().mapNotNull(byId::get).take(count)
    val rest = cards.distinctBy { it.id }.filterNot { it.id in config.pinnedIds }.shuffled(Random(seed))
    val slots = (count - pinned.size).coerceAtMost(rest.size)
    if (slots == 0) return pinned
    val offset = ((revision.toLong().coerceAtLeast(0) * slots) % rest.size).toInt()
    return pinned + List(slots) { rest[(offset + it) % rest.size] }
}

internal fun inspirationColumns(widthDp: Float, fontScale: Float): Int = when {
    widthDp < 360f || fontScale >= 1.3f -> 1
    widthDp >= 840f -> 3
    else -> 2
}

internal enum class InspirationInsert { AUTO, APPEND, REPLACE }

/** AUTO cannot overwrite a draft. Explicit replace is tied to the draft the user reviewed. */
internal fun inspirationDraftEdit(before: String, prompt: String, mode: InspirationInsert, reviewedDraft: String = before): SuggestionDraftEdit? {
    if (prompt.isBlank() || (mode == InspirationInsert.AUTO && before.isNotBlank()) ||
        (mode == InspirationInsert.REPLACE && before != reviewedDraft)) return null
    return prepareSuggestionInsertion(before, prompt,
        if (mode == InspirationInsert.APPEND) SuggestionInsertLocation.APPEND else SuggestionInsertLocation.REPLACE)
}
