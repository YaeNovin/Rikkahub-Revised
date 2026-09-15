package me.rerere.rikkahub.data.model

import kotlin.uuid.Uuid

enum class LorebookMergeChoice { KEEP_EXISTING, USE_IMPORTED, KEEP_BOTH }

data class LorebookMergeConflict(
    val incoming: PromptInjection.RegexInjection,
    val existing: List<PromptInjection.RegexInjection>,
    val ambiguous: Boolean,
)

fun lorebookMergeConflicts(target: Lorebook, incoming: Lorebook): List<LorebookMergeConflict> {
    fun String.key() = trim().lowercase(java.util.Locale.ROOT)
    val existingByName = target.entries.groupBy { it.name.key() }
    val incomingByName = incoming.entries.groupBy { it.name.key() }
    return incoming.entries.mapNotNull { entry ->
        val matches = existingByName[entry.name.key()].orEmpty()
        if (matches.isEmpty() && incomingByName.getValue(entry.name.key()).size == 1) null
        else LorebookMergeConflict(entry, matches, matches.size != 1 || incomingByName.getValue(entry.name.key()).size != 1)
    }
}

/** Every ambiguous entry is retained by default; never collapse duplicates with associateBy(name). */
fun mergeImportedLorebook(target: Lorebook, incoming: Lorebook, choices: Map<Uuid, LorebookMergeChoice>): Lorebook {
    val conflicts = lorebookMergeConflicts(target, incoming).associateBy { it.incoming.id }
    val result = target.entries.toMutableList()
    incoming.entries.forEach { entry ->
        val conflict = conflicts[entry.id]
        val choice = choices[entry.id] ?: if (conflict == null || conflict.ambiguous) LorebookMergeChoice.KEEP_BOTH else LorebookMergeChoice.KEEP_EXISTING
        when (choice) {
            LorebookMergeChoice.KEEP_EXISTING -> Unit
            LorebookMergeChoice.KEEP_BOTH -> result += entry.forTransferFrom(incoming).copy(id = Uuid.random())
            LorebookMergeChoice.USE_IMPORTED -> {
                require(conflict != null && !conflict.ambiguous) { "同名条目不唯一，请保留两者后手动整理" }
                val old = conflict.existing.single()
                result[result.indexOfFirst { it.id == old.id }] = entry.forTransferFrom(incoming).copy(id = old.id)
            }
        }
    }
    return target.copy(entries = result, importWarnings = (target.importWarnings + incoming.importWarnings).distinct())
}
