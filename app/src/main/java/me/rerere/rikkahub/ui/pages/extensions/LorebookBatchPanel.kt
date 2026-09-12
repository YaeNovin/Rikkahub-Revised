package me.rerere.rikkahub.ui.pages.extensions

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import me.rerere.rikkahub.data.model.Lorebook
import me.rerere.rikkahub.data.export.LorebookSerializer
import me.rerere.rikkahub.data.export.rememberExporter
import me.rerere.rikkahub.ui.components.ui.Select
import me.rerere.rikkahub.ui.components.ui.ExportDialog
import me.rerere.rikkahub.ui.components.ui.AppearanceAlertDialog

@Composable
internal fun LorebookBatchPanel(book: Lorebook, books: List<Lorebook>, vm: PromptVM) {
    var expanded by remember { mutableStateOf(false) }
    var category by remember { mutableStateOf("") }
    var destination by remember { mutableStateOf(books.firstOrNull { it.id != book.id }?.id) }
    var export by remember { mutableStateOf(false) }
    var confirmMove by remember { mutableStateOf<Boolean?>(null) }
    var result by remember { mutableStateOf<String?>(null) }
    val selected = book.entries.filter { category.isBlank() || it.category == category }
    val targets = books.filter { it.id != book.id }
    val exporter = rememberExporter(book.copy(entries = selected, revisions = emptyList()), LorebookSerializer)
    TextButton(onClick = { expanded = !expanded }) { Text("分类批量导出、复制和移动") }
    if (expanded) Column {
        Select(options = listOf("") + book.entries.map { it.category }.filter { it.isNotBlank() }.distinct(), selectedOption = category, onOptionSelected = { category = it }, optionToString = { it.ifBlank { "全部条目" } })
        Text("当前选择 ${selected.size} 个已保存条目")
        TextButton(enabled = selected.isNotEmpty(), onClick = { export = true }) { Text("导出所选条目") }
        if (targets.isNotEmpty()) {
            Select(options = targets.map { it.id }, selectedOption = destination?.takeIf { id -> targets.any { it.id == id } } ?: targets.first().id, onOptionSelected = { destination = it }, optionToString = { id -> targets.find { it.id == id }?.name.orEmpty() })
            Row { TextButton(enabled = selected.isNotEmpty(), onClick = { confirmMove = false }) { Text("复制至目标书") }; TextButton(enabled = selected.isNotEmpty(), onClick = { confirmMove = true }) { Text("移动至目标书") } }
        }
        result?.let { Text(it) }
    }
    if (export) ExportDialog(exporter, onDismiss = { export = false })
    confirmMove?.let { move -> AppearanceAlertDialog(onDismissRequest = { confirmMove = null }, title = { Text("${if (move) "移动" else "复制"} ${selected.size} 个条目？") },
        text = { Text("目标采用追加方式，不覆盖同名内容。${if (move) "来源条目将移除，可通过版本历史恢复。" else "来源条目保留。"}") },
        confirmButton = { TextButton(onClick = { val target = destination?.takeIf { id -> targets.any { it.id == id } } ?: targets.first().id; vm.transferEntries(book.id, target, selected.map { it.id }.toSet(), move) { result = it }; confirmMove = null }) { Text("确认") } },
        dismissButton = { TextButton(onClick = { confirmMove = null }) { Text("取消") } }) }
}
