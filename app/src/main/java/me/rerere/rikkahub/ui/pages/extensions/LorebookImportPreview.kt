package me.rerere.rikkahub.ui.pages.extensions

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.data.model.Lorebook
import me.rerere.rikkahub.ui.components.ui.AppearanceAlertDialog
import me.rerere.rikkahub.ui.components.ui.Select

@Composable
internal fun LorebookImportPreview(imported: Lorebook, books: List<Lorebook>, onDismiss: () -> Unit, onConfirm: (List<Lorebook>) -> Unit) {
    var action by remember { mutableStateOf("另存为新世界书") }
    var target by remember { mutableStateOf(books.firstOrNull { it.name.equals(imported.name, true) }?.id ?: books.firstOrNull()?.id) }
    val existing = books.find { it.id == target }
    AppearanceAlertDialog(onDismissRequest = onDismiss, title = { Text("世界书导入预览") }, text = {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                Text("${imported.name} · ${imported.entries.size} 个条目")
                Text("仅预览，确认后才会保存。合并时同名条目采用导入内容并保留原 ID；未重名条目保留。替换及合并均保存旧版本。")
                Select(options = if (books.isEmpty()) listOf("另存为新世界书") else listOf("另存为新世界书", "合并到已有世界书", "替换已有世界书"), selectedOption = action, onOptionSelected = { action = it }, optionToString = { it })
                if (action != "另存为新世界书" && existing != null) {
                    Select(options = books.map { it.id }, selectedOption = existing.id, onOptionSelected = { target = it }, optionToString = { id -> books.find { it.id == id }?.name.orEmpty() })
                    val names = existing.entries.map { it.name.trim().lowercase() }.toSet()
                    Text("目标有 ${existing.entries.size} 项；导入中 ${imported.entries.count { it.name.trim().lowercase() in names }} 项重名。")
                }
                Text(imported.importWarnings.joinToString("\n").ifBlank { "未发现需要提示的导入问题。" }, color = MaterialTheme.colorScheme.tertiary)
            }
        }
    }, confirmButton = { TextButton(onClick = {
        val result = when {
            action == "另存为新世界书" || existing == null -> books + imported
            action == "替换已有世界书" -> books.map { if (it.id == existing.id) imported.copy(id = it.id, revisions = it.revisions) else it }
            else -> {
                val incoming = imported.entries.associateBy { it.name.trim().lowercase() }
                val oldNames = existing.entries.map { it.name.trim().lowercase() }.toSet()
                val merged = existing.entries.map { old -> incoming[old.name.trim().lowercase()]?.copy(id = old.id) ?: old } + imported.entries.filter { it.name.trim().lowercase() !in oldNames }
                books.map { if (it.id == existing.id) it.copy(entries = merged, importWarnings = imported.importWarnings) else it }
            }
        }
        onConfirm(result)
    }) { Text("确认导入") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}
