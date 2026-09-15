package me.rerere.rikkahub.ui.pages.extensions

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.data.model.*
import me.rerere.rikkahub.ui.components.ui.AppearanceAlertDialog
import me.rerere.rikkahub.ui.components.ui.Select
import kotlin.uuid.Uuid

@Composable
internal fun LorebookImportPreview(imported: Lorebook, books: List<Lorebook>, onDismiss: () -> Unit,
    onConfirm: (List<Lorebook>, List<Lorebook>, (Result<Unit>) -> Unit) -> Unit) {
    var snapshot by remember(imported.id) { mutableStateOf(books) }
    var action by remember(imported.id) { mutableStateOf("另存为新世界书") }
    var target by remember(imported.id) { mutableStateOf(books.firstOrNull { it.name.equals(imported.name, true) }?.id ?: books.firstOrNull()?.id) }
    val existing = snapshot.find { it.id == target }
    val conflicts = remember(existing, imported) { existing?.let { lorebookMergeConflicts(it, imported) }.orEmpty() }
    val choices = remember(imported.id, target) { mutableStateMapOf<Uuid, LorebookMergeChoice>() }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var replaceConfirmed by remember(action, target) { mutableStateOf(false) }
    var showWarnings by remember { mutableStateOf(false) }
    val stale = existing != null && books.find { it.id == existing.id } != existing
    val merging = action == "合并到已有世界书"
    AppearanceAlertDialog(onDismissRequest = { if (!saving) onDismiss() }, title = { Text("世界书导入预览") }, text = {
        LazyColumn(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Text("${imported.name} · ${imported.entries.size} 个条目")
                Text("同名条目由你选择；无法唯一对应时仅允许保留原项或保留两者。合并会保留来源扫描范围，目标书的预算等设置不变。", style = MaterialTheme.typography.bodySmall)
                Select(options = if (snapshot.isEmpty()) listOf("另存为新世界书") else listOf("另存为新世界书", "合并到已有世界书", "替换已有世界书"), selectedOption = action, enabled = !saving, onOptionSelected = { action = it }, optionToString = { it })
                if (action != "另存为新世界书" && existing != null) {
                    Select(options = snapshot.map { it.id }, selectedOption = existing.id, enabled = !saving, onOptionSelected = { target = it }, optionToString = { id -> snapshot.find { it.id == id }?.name.orEmpty() })
                    Text("目标 ${existing.entries.size} 项 · 待核对同名项 ${conflicts.size} 项")
                    Text("默认扫描：来源 ${imported.defaultScanDepth} → 目标 ${existing.defaultScanDepth}。继承来源的条目会转为显式深度。", style = MaterialTheme.typography.bodySmall)
                    if (action == "替换已有世界书") LorebookToggle("确认替换整本内容（旧版保留在历史中）", replaceConfirmed) { replaceConfirmed = it }
                    if (stale) {
                        Text("目标已发生变化，请刷新后核对。", color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = { snapshot = books; choices.clear(); replaceConfirmed = false }) { Text("刷新预览") }
                    }
                }
                TextButton(onClick = { showWarnings = !showWarnings }) { Text("导入报告（${imported.importWarnings.size} 条）") }
            }
            if (showWarnings) items(imported.importWarnings) { Text(it, color = MaterialTheme.colorScheme.tertiary, style = MaterialTheme.typography.bodySmall) }
            if (merging) items(conflicts, key = { it.incoming.id.toString() }) { conflict ->
                val choice = choices[conflict.incoming.id] ?: if (conflict.ambiguous) LorebookMergeChoice.KEEP_BOTH else LorebookMergeChoice.KEEP_EXISTING
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(conflict.incoming.name, style = MaterialTheme.typography.titleSmall)
                        Text("原有 ${conflict.existing.size} 项${if (conflict.ambiguous) "，名称无法唯一对应" else ""}")
                        conflict.existing.forEach {
                            Text("原：${it.content.take(500)}", maxLines = 5)
                            Text("优先级 ${it.priority} · 顺序 ${it.insertionOrder ?: "原生"} · ${it.position} / ${it.role} / 深度 ${it.injectDepth}\n触发：${it.keywordExpression.ifBlank { it.keywords.joinToString() }.take(250)}", style = MaterialTheme.typography.bodySmall)
                        }
                        Text("导入：${conflict.incoming.content.take(500)}", maxLines = 5)
                        Text("导入优先级 ${conflict.incoming.priority} · 顺序 ${conflict.incoming.insertionOrder ?: "原生"} · ${conflict.incoming.position} / ${conflict.incoming.role} / 深度 ${conflict.incoming.injectDepth}\n触发：${conflict.incoming.keywordExpression.ifBlank { conflict.incoming.keywords.joinToString() }.take(250)}", style = MaterialTheme.typography.bodySmall)
                        Select(options = LorebookMergeChoice.entries.filter { !conflict.ambiguous || it != LorebookMergeChoice.USE_IMPORTED }, selectedOption = choice, enabled = !saving,
                            onOptionSelected = { choices[conflict.incoming.id] = it }, optionToString = { when (it) {
                                LorebookMergeChoice.KEEP_EXISTING -> "保留原项，跳过导入项"
                                LorebookMergeChoice.USE_IMPORTED -> "使用导入内容，保留原 ID"
                                LorebookMergeChoice.KEEP_BOTH -> "保留两者，导入项使用新 ID"
                            } })
                    }
                }
            }
            error?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.error) } }
        }
    }, confirmButton = { TextButton(enabled = !saving && (action == "另存为新世界书" || (existing != null && !stale && (merging || replaceConfirmed))), onClick = {
        val result = when {
            action == "另存为新世界书" -> snapshot + imported
            existing == null -> return@TextButton
            action == "替换已有世界书" -> snapshot.map { if (it.id == existing.id) imported.copy(id = it.id, revisions = it.revisions) else it }
            else -> snapshot.map { if (it.id == existing.id) mergeImportedLorebook(it, imported, choices) else it }
        }
        saving = true; error = null
        onConfirm(snapshot, result) { outcome -> saving = false; outcome.onFailure { error = it.message ?: "导入失败，请重试" } }
    }) { Text(if (saving) "保存中…" else "确认导入") } }, dismissButton = { TextButton(enabled = !saving, onClick = onDismiss) { Text("取消") } })
}
