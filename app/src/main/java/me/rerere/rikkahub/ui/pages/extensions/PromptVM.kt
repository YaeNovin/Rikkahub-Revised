package me.rerere.rikkahub.ui.pages.extensions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.compose.runtime.*
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.model.withRevisionOf
import me.rerere.rikkahub.data.model.forTransferFrom

class PromptVM(
    private val settingsStore: SettingsStore
) : ViewModel() {
    val settings = settingsStore.settingsFlow
        .stateIn(viewModelScope, SharingStarted.Lazily, Settings.dummy())

    internal var lorebookEditor by mutableStateOf<LorebookEditorSession?>(null)
        private set
    internal var pendingImport by mutableStateOf<me.rerere.rikkahub.data.model.Lorebook?>(null)
    var saveError by mutableStateOf<String?>(null)
        private set

    internal fun openLorebook(book: me.rerere.rikkahub.data.model.Lorebook) {
        lorebookEditor = LorebookEditorSession(settingsStore.settingsFlow.value, book)
    }

    internal fun dismissLorebook() { if (lorebookEditor?.saving != true) lorebookEditor = null }

    internal fun saveLorebookCopy() {
        val editor = lorebookEditor?.takeUnless { it.saving } ?: return
        val copy = editor.draft.copy(id = kotlin.uuid.Uuid.random(), name = "${editor.draft.name}（副本）", revisions = emptyList(),
            entries = editor.draft.entries.map { it.copy(id = kotlin.uuid.Uuid.random()) })
        lorebookEditor = LorebookEditorSession(settingsStore.settingsFlow.value, copy)
        saveLorebook()
    }

    internal fun saveLorebook() {
        val editor = lorebookEditor ?: return
        if (editor.saving) return
        editor.saving = true
        editor.error = null
        val books = editor.base.lorebooks
        val edited = if (books.any { it.id == editor.draft.id }) books.map { if (it.id == editor.draft.id) editor.draft else it } else books + editor.draft
        updateSettings(editor.base, editor.base.copy(lorebooks = edited)) { result ->
            editor.saving = false
            result.onSuccess { if (lorebookEditor === editor) lorebookEditor = null }
                .onFailure { editor.error = it.message ?: "保存失败，草稿已保留" }
        }
    }

    internal fun importLorebooks(before: List<me.rerere.rikkahub.data.model.Lorebook>, edited: List<me.rerere.rikkahub.data.model.Lorebook>, done: (Result<Unit>) -> Unit) {
        val snapshot = settingsStore.settingsFlow.value
        updateSettings(snapshot.copy(lorebooks = before), snapshot.copy(lorebooks = edited), done)
    }

    fun updateSettings(before: Settings, edited: Settings, onResult: (Result<Unit>) -> Unit = {}) {
        viewModelScope.launch {
          try {
            settingsStore.updatePromptExtensions { current ->
                before.lorebooks.filter { old -> edited.lorebooks.find { it.id == old.id } != old }.forEach { old ->
                    val change = edited.lorebooks.find { it.id == old.id }
                    val latest = current.lorebooks.find { it.id == old.id }
                    require(latest != null || change == null) { "世界书已被删除，草稿仍保留，请另存或重新打开" }
                    if (change == null) require(latest == null || latest == old) { "世界书已更新，删除前请重新检查" }
                }
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
            saveError = null
            onResult(Result.success(Unit))
          } catch (e: kotlinx.coroutines.CancellationException) { throw e }
          catch (e: Exception) { saveError = e.message ?: "保存失败"; onResult(Result.failure(e)) }
        }
    }

    fun setLorebookTotalBudget(value: Int) {
        viewModelScope.launch {
            try { settingsStore.updatePromptExtensions { it.copy(lorebookTotalTokenBudget = value.coerceIn(0, 1000000)) }; saveError = null }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { saveError = e.message ?: "总预算保存失败" }
        }
    }

    internal fun updateLorebookSources(change: (me.rerere.rikkahub.data.model.LorebookSources) -> me.rerere.rikkahub.data.model.LorebookSources) {
        viewModelScope.launch {
            try { settingsStore.updatePromptExtensions { it.copy(lorebookSources = change(it.lorebookSources)) }; saveError = null }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { saveError = e.message ?: "来源设置保存失败" }
        }
    }

    internal fun updateSourceAssistant(id: kotlin.uuid.Uuid, change: (me.rerere.rikkahub.data.model.Assistant) -> me.rerere.rikkahub.data.model.Assistant) {
        viewModelScope.launch {
            try { settingsStore.updateAssistantConfig(id, change); saveError = null }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { saveError = e.message ?: "助手绑定保存失败" }
        }
    }

    fun transferEntries(sourceId: kotlin.uuid.Uuid, targetId: kotlin.uuid.Uuid, ids: Set<kotlin.uuid.Uuid>, move: Boolean, onResult: (String) -> Unit) {
        viewModelScope.launch {
            try {
                settingsStore.updatePromptExtensions { current ->
                    require(sourceId != targetId) { "请选择不同的目标世界书" }
                    val source = current.lorebooks.firstOrNull { it.id == sourceId } ?: error("来源世界书已不存在")
                    val target = current.lorebooks.firstOrNull { it.id == targetId } ?: error("目标世界书已不存在")
                    val entries = source.entries.filter { it.id in ids }.map { it.forTransferFrom(source).copy(id = kotlin.uuid.Uuid.random()) }
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

internal fun mergeLorebookEdits(base: me.rerere.rikkahub.data.model.Lorebook, edited: me.rerere.rikkahub.data.model.Lorebook, latest: me.rerere.rikkahub.data.model.Lorebook): me.rerere.rikkahub.data.model.Lorebook {
    fun <T> check(old: T, change: T, current: T) { require(change == old || current == old || change == current) { "此世界书的同一字段已被其他操作修改，草稿已保留，请重新核对" } }
    check(base.name, edited.name, latest.name); check(base.description, edited.description, latest.description)
    check(base.enabled, edited.enabled, latest.enabled); check(base.tokenBudget, edited.tokenBudget, latest.tokenBudget)
    check(base.defaultScanDepth, edited.defaultScanDepth, latest.defaultScanDepth); check(base.overflowStrategy, edited.overflowStrategy, latest.overflowStrategy)
    check(base.sourceData, edited.sourceData, latest.sourceData); check(base.sourceFormat, edited.sourceFormat, latest.sourceFormat)
    fun <T> merge(old: T, change: T, current: T): T { check(old, change, current); return if (change != old) change else current }
    val changedEntries = edited.entries.associateBy { it.id }
    val latestEntries = latest.entries.associateBy { it.id }
    base.entries.forEach { old -> check(old, changedEntries[old.id], latestEntries[old.id]) }
    return latest.copy(
    name = if (edited.name != base.name) edited.name else latest.name,
    description = if (edited.description != base.description) edited.description else latest.description,
    enabled = if (edited.enabled != base.enabled) edited.enabled else latest.enabled,
    tokenBudget = if (edited.tokenBudget != base.tokenBudget) edited.tokenBudget else latest.tokenBudget,
    defaultScanDepth = if (edited.defaultScanDepth != base.defaultScanDepth) edited.defaultScanDepth else latest.defaultScanDepth,
    overflowStrategy = if (edited.overflowStrategy != base.overflowStrategy) edited.overflowStrategy else latest.overflowStrategy,
    importWarnings = if (edited.importWarnings != base.importWarnings) edited.importWarnings else latest.importWarnings,
    sourceFormat = if (edited.sourceFormat != base.sourceFormat) edited.sourceFormat else latest.sourceFormat,
    sourceData = if (edited.sourceData != base.sourceData) edited.sourceData else latest.sourceData,
    recursiveScanning = merge(base.recursiveScanning, edited.recursiveScanning, latest.recursiveScanning),
    maxRecursionSteps = merge(base.maxRecursionSteps, edited.maxRecursionSteps, latest.maxRecursionSteps),
    minActivations = merge(base.minActivations, edited.minActivations, latest.minActivations),
    maxScanDepth = merge(base.maxScanDepth, edited.maxScanDepth, latest.maxScanDepth),
    includeNames = merge(base.includeNames, edited.includeNames, latest.includeNames),
    useGroupScoring = merge(base.useGroupScoring, edited.useGroupScoring, latest.useGroupScoring),
    defaultCaseSensitive = merge(base.defaultCaseSensitive, edited.defaultCaseSensitive, latest.defaultCaseSensitive),
    defaultMatchWholeWords = merge(base.defaultMatchWholeWords, edited.defaultMatchWholeWords, latest.defaultMatchWholeWords),
    alertOnOverflow = merge(base.alertOnOverflow, edited.alertOnOverflow, latest.alertOnOverflow),
    sourceScope = merge(base.sourceScope, edited.sourceScope, latest.sourceScope),
    entries = mergeExtensionEdits(base.entries, edited.entries, latest.entries) { it.id },
)
}

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
