package me.rerere.rikkahub.data.model

import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

internal val lorebookMatchingSourceLabels = linkedMapOf(
    "matchCharacterDescription" to "角色描述", "matchCharacterPersonality" to "角色性格",
    "matchScenario" to "场景", "matchPersonaDescription" to "当前 Persona 描述",
    "matchCharacterDepthPrompt" to "角色备注", "matchCreatorNotes" to "创作者备注",
)

@Serializable
enum class LorebookInsertionStrategy { LEGACY, SORTED_EVENLY, CHARACTER_FIRST, GLOBAL_FIRST }

/** Explicit opt-in bindings. Import/source labels never activate a book by themselves. */
@Serializable
data class LorebookSources(
    val globalIds: Set<Uuid> = emptySet(),
    val personas: List<LorebookPersona> = emptyList(),
    val activePersonaId: Uuid? = null,
    val insertionStrategy: LorebookInsertionStrategy = LorebookInsertionStrategy.LEGACY,
    val vectorEnabled: Boolean = false,
    val embeddingModelId: Uuid? = null,
    val vectorQueryMessages: Int = 3,
    val vectorMaxEntries: Int = 5,
    val vectorMinScore: Float = 0.5f,
)

@Serializable
data class LorebookPersona(
    val id: Uuid = Uuid.random(),
    val name: String = "",
    val description: String = "",
    val lorebookIds: Set<Uuid> = emptySet(),
)

fun LorebookSources.resolve(
    assistant: Assistant, conversationIds: Set<Uuid> = emptySet(), disabledIds: Set<Uuid> = emptySet(),
): Map<Uuid, LorebookSourceScope> = buildMap {
    if (assistant.useGlobalLorebooks) globalIds.forEach { put(it, LorebookSourceScope.GLOBAL) }
    assistant.lorebookIds.forEach { put(it, LorebookSourceScope.CHARACTER) }
    if (assistant.usePersonaLorebooks) personas.firstOrNull { it.id == activePersonaId }?.lorebookIds
        ?.forEach { put(it, LorebookSourceScope.PERSONA) }
    if (assistant.allowConversationPromptInjection) {
        conversationIds.forEach { put(it, LorebookSourceScope.CHAT) }
        disabledIds.forEach(::remove)
    }
}

internal fun LorebookSourceScope.insertionRank(strategy: LorebookInsertionStrategy): Int = when {
    strategy == LorebookInsertionStrategy.LEGACY -> 0
    this == LorebookSourceScope.CHAT -> 0
    this == LorebookSourceScope.PERSONA -> 1
    strategy == LorebookInsertionStrategy.SORTED_EVENLY -> 2
    strategy == LorebookInsertionStrategy.GLOBAL_FIRST -> if (this == LorebookSourceScope.GLOBAL) 2 else 3
    else -> if (this == LorebookSourceScope.CHARACTER) 2 else 3
}

internal fun LorebookSources.withoutDeletedBooks(ids: Set<Uuid>) = copy(
    globalIds = globalIds.intersect(ids), personas = personas.map { it.copy(lorebookIds = it.lorebookIds.intersect(ids)) },
    activePersonaId = activePersonaId?.takeIf { id -> personas.any { it.id == id } },
)
