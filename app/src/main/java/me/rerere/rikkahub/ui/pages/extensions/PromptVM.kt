package me.rerere.rikkahub.ui.pages.extensions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.model.withRevisionOf

class PromptVM(
    private val settingsStore: SettingsStore
) : ViewModel() {
    val settings = settingsStore.settingsFlow
        .stateIn(viewModelScope, SharingStarted.Lazily, Settings.dummy())

    fun updateSettings(before: Settings, edited: Settings) {
        viewModelScope.launch {
            settingsStore.update { current ->
                current.copy(
                    modeInjections = mergeExtensionEdits(before.modeInjections, edited.modeInjections, current.modeInjections) { it.id },
                    lorebooks = mergeExtensionEdits(before.lorebooks, edited.lorebooks, current.lorebooks) { it.id }.map { book ->
                        current.lorebooks.find { it.id == book.id }?.let { latest ->
                            val base = before.lorebooks.find { it.id == book.id }
                            val change = edited.lorebooks.find { it.id == book.id }
                            val merged = if (base != null && change != null) mergeLorebookEdits(base, change, latest) else book
                            merged.withRevisionOf(latest)
                        } ?: book
                    },
                )
            }
        }
    }

    fun setLorebookTotalBudget(value: Int) {
        viewModelScope.launch { settingsStore.update { it.copy(lorebookTotalTokenBudget = value.coerceIn(0, 1000000)) } }
    }

    fun transferEntries(sourceId: kotlin.uuid.Uuid, targetId: kotlin.uuid.Uuid, ids: Set<kotlin.uuid.Uuid>, move: Boolean, onResult: (String) -> Unit) {
        viewModelScope.launch {
            try {
                settingsStore.update { current ->
                    require(sourceId != targetId) { "请选择不同的目标世界书" }
                    val source = current.lorebooks.firstOrNull { it.id == sourceId } ?: error("来源世界书已不存在")
                    val target = current.lorebooks.firstOrNull { it.id == targetId } ?: error("目标世界书已不存在")
                    val entries = source.entries.filter { it.id in ids }.map { it.copy(id = kotlin.uuid.Uuid.random()) }
                    current.copy(lorebooks = current.lorebooks.map { book -> when (book.id) {
                        sourceId -> if (move) source.copy(entries = source.entries.filterNot { it.id in ids }).withRevisionOf(source) else source
                        targetId -> target.copy(entries = target.entries + entries).withRevisionOf(target)
                        else -> book
                    } })
                }
                onResult("已${if (move) "移动" else "复制"}所选条目，源书和目标书的历史均已保留")
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { onResult(e.message ?: "操作失败") }
        }
    }
}

internal fun mergeLorebookEdits(base: me.rerere.rikkahub.data.model.Lorebook, edited: me.rerere.rikkahub.data.model.Lorebook, latest: me.rerere.rikkahub.data.model.Lorebook) = latest.copy(
    name = if (edited.name != base.name) edited.name else latest.name,
    description = if (edited.description != base.description) edited.description else latest.description,
    enabled = if (edited.enabled != base.enabled) edited.enabled else latest.enabled,
    tokenBudget = if (edited.tokenBudget != base.tokenBudget) edited.tokenBudget else latest.tokenBudget,
    defaultScanDepth = if (edited.defaultScanDepth != base.defaultScanDepth) edited.defaultScanDepth else latest.defaultScanDepth,
    overflowStrategy = if (edited.overflowStrategy != base.overflowStrategy) edited.overflowStrategy else latest.overflowStrategy,
    importWarnings = if (edited.importWarnings != base.importWarnings) edited.importWarnings else latest.importWarnings,
    entries = mergeExtensionEdits(base.entries, edited.entries, latest.entries) { it.id },
)

internal fun <T, K> mergeExtensionEdits(before: List<T>, edited: List<T>, current: List<T>, key: (T) -> K): List<T> {
    val original = before.associateBy(key)
    val updated = edited.associateBy(key)
    val deleted = original.keys - updated.keys
    val changed = updated.filter { (id, value) -> original[id] != value }
    val currentIds = current.map(key).toSet()
    val merged = current.filterNot { key(it) in deleted }.map { changed[key(it)] ?: it } +
        changed.filterKeys { it !in currentIds && it !in original }.values
    val sharedIds = original.keys.intersect(updated.keys)
    val reordered = before.map(key).filter { it in sharedIds } != edited.map(key).filter { it in sharedIds }
    if (!reordered) return merged
    val mergedById = merged.associateBy(key)
    return edited.mapNotNull { mergedById[key(it)] } + merged.filter { key(it) !in updated }
}
