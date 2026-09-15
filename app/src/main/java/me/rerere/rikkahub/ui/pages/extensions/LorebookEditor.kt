package me.rerere.rikkahub.ui.pages.extensions

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import me.rerere.ai.core.MessageRole
import me.rerere.rikkahub.data.model.*
import me.rerere.rikkahub.ui.components.ui.AppearanceModalBottomSheet
import me.rerere.rikkahub.ui.components.ui.AppearanceAlertDialog
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.Select

@Composable
internal fun LorebookEditorSheet(session: LorebookEditorSession, entertainment: Boolean, onDismiss: () -> Unit, onConfirm: () -> Unit, onSaveCopy: () -> Unit) {
    val book = session.draft
    val onEdit: (Lorebook) -> Unit = { if (!session.saving) session.draft = it }
    val advancedEnabled = entertainment || book.entries.any { it.sourceFormat != LorebookSourceFormat.NATIVE }
    var discard by rememberSaveable(book.id.toString()) { mutableStateOf(false) }
    val dismiss = { if (!session.saving) { if (session.dirty) discard = true else onDismiss() } }
    var query by rememberSaveable(book.id.toString()) { mutableStateOf("") }
    var tab by rememberSaveable(book.id.toString()) { mutableIntStateOf(if (book.name.isBlank()) 1 else 0) }
    val scrollStates = List(3) { rememberLazyListState() }
    var help by rememberSaveable { mutableStateOf(false) }
    var bulk by rememberSaveable { mutableStateOf(false) }
    var report by rememberSaveable { mutableStateOf(false) }
    var group by rememberSaveable(book.id.toString()) { mutableStateOf("") }
    var bulkCategory by rememberSaveable(book.id.toString()) { mutableStateOf("") }
    var advanced by rememberSaveable { mutableStateOf(false) }
    var scanning by rememberSaveable(book.id.toString()) { mutableStateOf(false) }
    var maxRecursionSteps by session::maxRecursionSteps
    var minActivations by session::minActivations
    var maxScanDepth by session::maxScanDepth
    var history by rememberSaveable { mutableStateOf(false) }
    val budget = session.budget
    val defaultDepth = session.defaultDepth
    var deleted by remember { mutableStateOf<PromptInjection.RegexInjection?>(null) }
    var restoreTarget by remember { mutableStateOf<LorebookRevision?>(null) }
    val visible = remember(book.entries, query, group) { book.entries.filter {
        (group.isBlank() || it.category == group) && (query.isBlank() || "${it.name}\n${it.content}\n${it.keywords}\n${it.keywordExpression}".contains(query.trim(), true))
    } }
    AppearanceModalBottomSheet(onDismissRequest = dismiss,
        sheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden, enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded))) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(.95f).imePadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(book.name.ifBlank { "新建世界书" }, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    LorebookHint("${book.entries.size} 个条目 · ${book.entries.count { it.enabled }} 启用 · ${lorebookSourceLabel(book.sourceFormat)}")
                }
                TextButton(onClick = { help = true }) { Text("教程") }
            }
            me.rerere.rikkahub.ui.components.ui.AppearancePrimaryTabRow(tab) { listOf("条目", "设置", "检查").forEachIndexed { index, title -> Tab(tab == index, { tab = index }, text = { Text(title) }) } }
            LazyColumn(Modifier.weight(1f), state = scrollStates[tab], verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (tab == 1) item {
                    OutlinedTextField(book.name, { onEdit(book.copy(name = it)) }, label = { Text("名称") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(book.description, { onEdit(book.copy(description = it)) }, label = { Text("简介") }, modifier = Modifier.fillMaxWidth())
                    LorebookToggle("启用此书", book.enabled, "启用不等于对所有对话生效，还需在助手或当前对话中绑定。") { onEdit(book.copy(enabled = it)) }
                    OutlinedTextField(defaultDepth, { value -> session.defaultDepth = value; value.toIntOrNull()?.takeIf { it in 0..1000 }?.let { onEdit(book.copy(defaultScanDepth = it)) } }, label = { Text("默认扫描深度（0 不扫描，最大 1000）") }, modifier = Modifier.fillMaxWidth(), isError = defaultDepth.toIntOrNull() !in 0..1000)
                    LorebookHint(LOREBOOK_SCAN_DEPTH_START)
                    LorebookScanDepthReference()
                    if (advancedEnabled) {
                        TextButton(onClick = { scanning = !scanning }) { Text(if (scanning) "收起扫描与递归" else "扫描与递归设置") }
                        if (scanning) {
                            LorebookToggle("启用递归扫描", book.recursiveScanning) { onEdit(book.copy(recursiveScanning = it)) }
                            Text("扫描深度为 0 时不会扫描初始聊天；启用递归后，已注入条目的正文仍可触发后续条目。", style = MaterialTheme.typography.bodySmall)
                            OutlinedTextField(maxRecursionSteps, { value -> maxRecursionSteps = value; value.toIntOrNull()?.takeIf { it in 0..1000 }?.let { onEdit(book.copy(maxRecursionSteps = it)) } }, label = { Text("最大递归步数（0 = 预算内自动停止）") }, modifier = Modifier.fillMaxWidth(), isError = maxRecursionSteps.toIntOrNull() !in 0..1000)
                            OutlinedTextField(minActivations, { value -> minActivations = value; value.toIntOrNull()?.takeIf { it in 0..10000 }?.let { onEdit(book.copy(minActivations = it)) } }, label = { Text("最少激活条目（0 = 不额外回溯）") }, modifier = Modifier.fillMaxWidth(), isError = minActivations.toIntOrNull() !in 0..10000)
                            OutlinedTextField(maxScanDepth, { value -> maxScanDepth = value; value.toIntOrNull()?.takeIf { it in 0..1000 }?.let { onEdit(book.copy(maxScanDepth = it)) } }, label = { Text("额外回溯最大深度（0 = 不限制）") }, modifier = Modifier.fillMaxWidth(), isError = maxScanDepth.toIntOrNull() !in 0..1000)
                            LorebookHint("最少激活逐条扩大聊天窗口，达到目标或预算上限即停止。仅递归激活的条目按级别逐步放行，递归始终有安全上限。")
                            LorebookToggle("扫描时包含参与者名称", book.includeNames, "添加用户与助手名称前缀；导入条目的扫描文本带有 SillyTavern 的 \\x01 消息分隔符。") { onEdit(book.copy(includeNames = it)) }
                            LorebookToggle("按命中关键词数量进行组评分", book.useGroupScoring, "NOT 条件不加分。组内按正向命中数筛选候选；跨书复杂竞争仍建议模拟验证。") { onEdit(book.copy(useGroupScoring = it)) }
                            LorebookToggle("关键词区分大小写（默认）", book.defaultCaseSensitive, "未显式指定此选项的导入条目继承本值；原生条目使用自己的开关。") { onEdit(book.copy(defaultCaseSensitive = it)) }
                            LorebookToggle("完整词匹配（默认）", book.defaultMatchWholeWords) { onEdit(book.copy(defaultMatchWholeWords = it)) }
                            LorebookToggle("预算超限时显示提醒", book.alertOnOverflow) { onEdit(book.copy(alertOnOverflow = it)) }
                            Text("最少激活与最大递归步数在 SillyTavern 中互斥；同时填写时优先执行递归上限。", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    TextButton(onClick = { advanced = !advanced }) { Text(if (advanced) "收起预算设置" else "预算与超限处理") }
                    if (advanced) {
                        Text(if (advancedEnabled) "优先级决定预算保留，插入顺序决定排列。Token 按变量展开后的文本估算；0 表示不限制本书，仍受总预算约束。" else "原生单书预算仅娱乐模式生效；导入兼容规则和跨书总预算在两种模式均生效。", style = MaterialTheme.typography.bodySmall)
                        OutlinedTextField(budget, { session.budget = it; it.toIntOrNull()?.takeIf { n -> n in 0..1000000 }?.let { n -> onEdit(book.copy(tokenBudget = n)) } },
                            label = { Text("本书 Token 预算（0–1000000）") }, enabled = advancedEnabled, isError = budget.toIntOrNull() !in 0..1000000, modifier = Modifier.fillMaxWidth())
                        Select(options = LorebookOverflowStrategy.entries, selectedOption = book.overflowStrategy, enabled = advancedEnabled, onOptionSelected = { onEdit(book.copy(overflowStrategy = it)) }, optionToString = {
                            when (it) { LorebookOverflowStrategy.DROP_LOW_PRIORITY -> "跳过超预算条目"; LorebookOverflowStrategy.TRUNCATE_LAST -> "截断最后一个条目"; LorebookOverflowStrategy.SKIP_BOOK -> "整本跳过" }
                        })
                    }
                }
                if (tab == 2 && book.importWarnings.isNotEmpty()) item {
                    TextButton(onClick = { report = !report }) { Text("${if (report) "收起" else "展开"}导入报告（${book.importWarnings.size} 条提示）") }
                }
                if (tab == 2 && report) items(book.importWarnings) { LorebookHint(it) }
                if (tab == 2 && (book.sourceData != null || book.entries.any { it.sourceData != null })) item {
                    Text("来源：${book.sourceFormat}。原始字段随原生导出保留；未支持的行为见导入报告，原始字段不会直接执行。", style = MaterialTheme.typography.bodySmall)
                }
                val bookErrors = if (tab == 2) book.validationErrors() else emptyList()
                if (tab == 2 && bookErrors.isEmpty()) item { LorebookHint("当前未发现配置错误。完整场景模拟位于世界书详情页；条目编辑器内也可单独测试触发条件。") }
                if (tab == 2 && bookErrors.isNotEmpty()) item {
                    Text("检查结果", style = MaterialTheme.typography.titleSmall)
                    bookErrors.take(8).forEach { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    if (bookErrors.size > 8) Text("还有 ${bookErrors.size - 8} 个问题，请逐项检查条目。", style = MaterialTheme.typography.bodySmall)
                }
                if (tab == 0) item {
                    Text("条目（${visible.size}/${book.entries.size}）", style = MaterialTheme.typography.titleMedium)
                    OutlinedTextField(query, { query = it }, label = { Text("搜索名称、正文、关键词") }, modifier = Modifier.fillMaxWidth())
                    Row {
                        TextButton(onClick = { session.entry = LorebookEntryDraft(PromptInjection.RegexInjection()) }) { Text("新增条目") }
                        TextButton(onClick = { bulk = !bulk }) { Text(if (bulk) "收起批量操作" else "分类与批量操作") }
                    }
                    if (bulk) {
                    Select(options = listOf("") + book.entries.map { it.category }.filter { it.isNotBlank() }.distinct(), selectedOption = group,
                        onOptionSelected = { group = it }, optionToString = { it.ifBlank { "全部分类" } })
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { val ids = visible.map { it.id }.toSet(); onEdit(book.copy(entries = book.entries.map { if (it.id in ids) it.copy(enabled = true) else it })) }) { Text("启用筛选结果") }
                        TextButton(onClick = { val ids = visible.map { it.id }.toSet(); onEdit(book.copy(entries = book.entries.map { if (it.id in ids) it.copy(enabled = false) else it })) }) { Text("停用筛选结果") }
                    }
                    OutlinedTextField(bulkCategory, { bulkCategory = it }, label = { Text("批量分类名称（留空可清除）") }, modifier = Modifier.fillMaxWidth())
                    TextButton(onClick = { val ids = visible.map { it.id }.toSet(); onEdit(book.copy(entries = book.entries.map { if (it.id in ids) it.copy(category = bulkCategory.trim()) else it })) }) { Text("应用至 ${visible.size} 个筛选条目") }
                    }
                    deleted?.let { removed -> TextButton(onClick = { if (book.entries.none { it.id == removed.id }) onEdit(book.copy(entries = book.entries + removed)); deleted = null }) { Text("撤销删除：${removed.name}") } }
                }
                if (tab == 0 && visible.isEmpty()) item { LorebookHint(if (book.entries.isEmpty()) "先新增一个条目，填写正文与关键词，再测试命中。" else "没有符合条件的条目，可清空搜索并恢复全部分类。") }
                if (tab == 0) items(visible, key = { it.id.toString() }) { entry ->
                    CardGroup { item(headlineContent = { Text(entry.name) }, supportingContent = {
                        Column {
                            LorebookHint("${if (entry.enabled) "启用" else "停用"} · ${entry.category.ifBlank { "未分类" }} · ${lorebookPositionLabel(entry.position)}")
                            Text(entry.keywordExpression.ifBlank { entry.keywords.take(3).joinToString(" · ") }.ifBlank { if (entry.constantActive) "常驻：无需关键词" else "尚未设置触发条件" }, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                            if (entry.unsupportedPosition != null) Text("待映射位置：${entry.unsupportedPosition}（未注入）", color = MaterialTheme.colorScheme.error)
                            Row { TextButton(onClick = { session.entry = LorebookEntryDraft(entry) }) { Text("编辑") }; TextButton(onClick = { deleted = entry; onEdit(book.copy(entries = book.entries.filterNot { it.id == entry.id })) }) { Text("删除") } }
                        }
                    }) }
                }
                if (tab == 2) item { TextButton(onClick = { history = !history }) { Text("版本历史（${book.revisions.size}/10）") } }
                if (tab == 2 && history) items(book.revisions.reversed()) { revision ->
                    TextButton(onClick = { restoreTarget = revision }) { Text("${java.text.DateFormat.getDateTimeInstance().format(java.util.Date(revision.savedAt))} · ${revision.name} · ${revision.entries.size} 个条目") }
                }
            }
            if (!session.validNumbers) TextButton(onClick = { tab = 1 }) { Text("数值未填写或超出范围，前往设置检查") }
            LorebookHint("条目修改先暂存到此草稿，保存世界书后生效。")
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = dismiss, enabled = !session.saving) { Text("取消") }
                TextButton(onClick = onConfirm, enabled = !session.saving && book.name.isNotBlank() && budget.toIntOrNull() in 0..1000000 && defaultDepth.toIntOrNull() in 0..1000 && maxRecursionSteps.toIntOrNull() in 0..1000 && minActivations.toIntOrNull() in 0..10000 && maxScanDepth.toIntOrNull() in 0..1000) { Text(if (session.saving) "保存中…" else "保存") }
            }
            session.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error)
                TextButton(onClick = onSaveCopy, enabled = !session.saving && book.name.isNotBlank() && budget.toIntOrNull() in 0..1000000 && defaultDepth.toIntOrNull() in 0..1000 && maxRecursionSteps.toIntOrNull() in 0..1000 && minActivations.toIntOrNull() in 0..10000 && maxScanDepth.toIntOrNull() in 0..1000) { Text("将草稿另存为新世界书") }
            }
        }
    }
    session.entry?.let { entryDraft -> LorebookEntryEditor(entryDraft, entertainment || entryDraft.original.sourceFormat != LorebookSourceFormat.NATIVE, { session.entry = null }, book) { result ->
        onEdit(book.copy(entries = if (book.entries.any { it.id == result.id }) book.entries.map { if (it.id == result.id) result else it } else book.entries + result)); session.entry = null
    } }
    if (help) LorebookHelpSheet { help = false }
    if (discard) AppearanceAlertDialog(onDismissRequest = { discard = false }, title = { Text("放弃未保存的修改？") }, text = { Text("当前世界书草稿尚未保存。") }, confirmButton = { TextButton(onClick = { discard = false; onDismiss() }) { Text("放弃修改") } }, dismissButton = { TextButton(onClick = { discard = false }) { Text("继续编辑") } })
    restoreTarget?.let { revision -> AppearanceAlertDialog(onDismissRequest = { restoreTarget = null }, title = { Text("恢复此版本？") },
        text = { Text("当前 ${book.entries.size} 个条目 → ${revision.entries.size} 个条目。\n${revision.description}\n恢复后仍需点击保存；当前版本会保留在历史中。") },
        confirmButton = { TextButton(onClick = { onEdit(book.restore(revision)); session.budget = revision.tokenBudget.toString(); session.defaultDepth = revision.defaultScanDepth.toString(); maxRecursionSteps = revision.maxRecursionSteps.toString(); minActivations = revision.minActivations.toString(); maxScanDepth = revision.maxScanDepth.toString(); restoreTarget = null }) { Text("恢复") } }, dismissButton = { TextButton(onClick = { restoreTarget = null }) { Text("取消") } }) }
}

@Composable
internal fun LorebookToggle(label: String, value: Boolean, description: String? = null, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { Text(label); description?.let { LorebookHint(it) } }
        Switch(value, onChange, enabled = enabled)
    }
}
