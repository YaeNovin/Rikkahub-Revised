package me.rerere.rikkahub.ui.pages.extensions

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
internal fun LorebookEditorSheet(book: Lorebook, entertainment: Boolean, onDismiss: () -> Unit, onConfirm: () -> Unit, onEdit: (Lorebook) -> Unit) {
    var query by rememberSaveable(book.id.toString()) { mutableStateOf("") }
    var group by rememberSaveable(book.id.toString()) { mutableStateOf("") }
    var bulkCategory by rememberSaveable(book.id.toString()) { mutableStateOf("") }
    var advanced by rememberSaveable { mutableStateOf(false) }
    var history by rememberSaveable { mutableStateOf(false) }
    var budget by remember(book.id, book.tokenBudget) { mutableStateOf(book.tokenBudget.toString()) }
    var defaultDepth by remember(book.id, book.defaultScanDepth) { mutableStateOf(book.defaultScanDepth.toString()) }
    var editing by remember { mutableStateOf<PromptInjection.RegexInjection?>(null) }
    var deleted by remember { mutableStateOf<PromptInjection.RegexInjection?>(null) }
    var restoreTarget by remember { mutableStateOf<LorebookRevision?>(null) }
    val visible = remember(book.entries, query, group) { book.entries.filter {
        (group.isBlank() || it.category == group) && (query.isBlank() || "${it.name}\n${it.content}\n${it.keywords}\n${it.keywordExpression}".contains(query.trim(), true))
    } }
    AppearanceModalBottomSheet(onDismissRequest = onDismiss,
        sheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden, enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded))) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(.95f).imePadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("编辑世界书", style = MaterialTheme.typography.titleLarge)
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item {
                    OutlinedTextField(book.name, { onEdit(book.copy(name = it)) }, label = { Text("名称") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(book.description, { onEdit(book.copy(description = it)) }, label = { Text("简介") }, modifier = Modifier.fillMaxWidth())
                    LorebookToggle("启用此书", book.enabled) { onEdit(book.copy(enabled = it)) }
                    OutlinedTextField(defaultDepth, { value -> defaultDepth = value; value.toIntOrNull()?.takeIf { it in 0..1000 }?.let { onEdit(book.copy(defaultScanDepth = it)) } }, label = { Text("默认扫描深度（0 不扫描，最大 1000）") }, modifier = Modifier.fillMaxWidth(), isError = defaultDepth.toIntOrNull() !in 0..1000)
                    TextButton(onClick = { advanced = !advanced }) { Text(if (advanced) "收起预算设置" else "预算与超限处理") }
                    if (advanced) {
                        Text(if (entertainment) "按书内优先级采用条目。0 表示不限制本书；仍受跨书总预算约束。" else "单书预算仅娱乐模式生效；跨书总预算在两种模式均生效。", style = MaterialTheme.typography.bodySmall)
                        OutlinedTextField(budget, { budget = it; it.toIntOrNull()?.takeIf { n -> n in 0..1000000 }?.let { n -> onEdit(book.copy(tokenBudget = n)) } },
                            label = { Text("本书 Token 预算（0–1000000）") }, enabled = entertainment, isError = budget.toIntOrNull() !in 0..1000000, modifier = Modifier.fillMaxWidth())
                        Select(options = LorebookOverflowStrategy.entries, selectedOption = book.overflowStrategy, enabled = entertainment, onOptionSelected = { onEdit(book.copy(overflowStrategy = it)) }, optionToString = {
                            when (it) { LorebookOverflowStrategy.DROP_LOW_PRIORITY -> "跳过超预算条目"; LorebookOverflowStrategy.TRUNCATE_LAST -> "截断最后一个条目"; LorebookOverflowStrategy.SKIP_BOOK -> "整本跳过" }
                        })
                    }
                }
                if (book.importWarnings.isNotEmpty()) item {
                    Text("导入报告：${book.entries.size} 个条目，${book.importWarnings.size} 条提示", style = MaterialTheme.typography.titleSmall)
                    Text(book.importWarnings.joinToString("\n"), color = MaterialTheme.colorScheme.tertiary)
                }
                item {
                    Text("条目（${visible.size}/${book.entries.size}）", style = MaterialTheme.typography.titleMedium)
                    OutlinedTextField(query, { query = it }, label = { Text("搜索名称、正文、关键词") }, modifier = Modifier.fillMaxWidth())
                    Select(options = listOf("") + book.entries.map { it.category }.filter { it.isNotBlank() }.distinct(), selectedOption = group,
                        onOptionSelected = { group = it }, optionToString = { it.ifBlank { "全部分类" } })
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { editing = PromptInjection.RegexInjection() }) { Text("新增条目") }
                        TextButton(onClick = { val ids = visible.map { it.id }.toSet(); onEdit(book.copy(entries = book.entries.map { if (it.id in ids) it.copy(enabled = true) else it })) }) { Text("启用筛选结果") }
                        TextButton(onClick = { val ids = visible.map { it.id }.toSet(); onEdit(book.copy(entries = book.entries.map { if (it.id in ids) it.copy(enabled = false) else it })) }) { Text("停用筛选结果") }
                    }
                    OutlinedTextField(bulkCategory, { bulkCategory = it }, label = { Text("批量分类名称（留空可清除）") }, modifier = Modifier.fillMaxWidth())
                    TextButton(onClick = { val ids = visible.map { it.id }.toSet(); onEdit(book.copy(entries = book.entries.map { if (it.id in ids) it.copy(category = bulkCategory.trim()) else it })) }) { Text("应用至 ${visible.size} 个筛选条目") }
                    deleted?.let { removed -> TextButton(onClick = { if (book.entries.none { it.id == removed.id }) onEdit(book.copy(entries = book.entries + removed)); deleted = null }) { Text("撤销删除：${removed.name}") } }
                }
                items(visible, key = { it.id.toString() }) { entry ->
                    CardGroup { item(headlineContent = { Text(entry.name) }, supportingContent = {
                        Column {
                            Text("${if (entry.enabled) "启用" else "停用"} · ${entry.category.ifBlank { "未分类" }} · 优先级 ${entry.priority}")
                            Text(entry.keywordExpression.ifBlank { entry.keywords.joinToString() }, maxLines = 2)
                            Row { TextButton(onClick = { editing = entry }) { Text("编辑") }; TextButton(onClick = { deleted = entry; onEdit(book.copy(entries = book.entries.filterNot { it.id == entry.id })) }) { Text("删除") } }
                        }
                    }) }
                }
                item { TextButton(onClick = { history = !history }) { Text("版本历史（${book.revisions.size}/10）") } }
                if (history) items(book.revisions.reversed()) { revision ->
                    TextButton(onClick = { restoreTarget = revision }) { Text("${java.text.DateFormat.getDateTimeInstance().format(java.util.Date(revision.savedAt))} · ${revision.name} · ${revision.entries.size} 个条目") }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text("取消") }
                TextButton(onClick = onConfirm, enabled = book.name.isNotBlank() && budget.toIntOrNull() in 0..1000000 && defaultDepth.toIntOrNull() in 0..1000) { Text("保存") }
            }
        }
    }
    editing?.let { original -> LorebookEntryEditor(original, entertainment, { editing = null }) { result ->
        onEdit(book.copy(entries = if (book.entries.any { it.id == result.id }) book.entries.map { if (it.id == result.id) result else it } else book.entries + result)); editing = null
    } }
    restoreTarget?.let { revision -> AppearanceAlertDialog(onDismissRequest = { restoreTarget = null }, title = { Text("恢复此版本？") },
        text = { Text("当前 ${book.entries.size} 个条目 → ${revision.entries.size} 个条目。\n${revision.description}\n恢复后仍需点击保存；当前版本会保留在历史中。") },
        confirmButton = { TextButton(onClick = { onEdit(book.restore(revision)); restoreTarget = null }) { Text("恢复") } }, dismissButton = { TextButton(onClick = { restoreTarget = null }) { Text("取消") } }) }
}

@Composable
internal fun LorebookToggle(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) { Text(label, Modifier.weight(1f)); Switch(value, onChange) }
}

@Composable
private fun LorebookEntryEditor(original: PromptInjection.RegexInjection, entertainment: Boolean, onDismiss: () -> Unit, onSave: (PromptInjection.RegexInjection) -> Unit) {
    var draft by remember(original.id) { mutableStateOf(original) }
    var keywords by remember { mutableStateOf(original.keywords.joinToString("\n")) }
    var settingsKeys by remember { mutableStateOf(original.settingKeys.joinToString(", ")) }
    var advanced by rememberSaveable { mutableStateOf(false) }
    var visual by rememberSaveable { mutableStateOf(false) }
    var allWords by rememberSaveable { mutableStateOf("") }; var anyWords by rememberSaveable { mutableStateOf("") }; var notWords by rememberSaveable { mutableStateOf("") }
    val numbers = remember { mutableStateMapOf("扫描深度" to original.scanDepth.toString(), "优先级" to original.priority.toString(), "插入深度" to original.injectDepth.toString(), "概率" to original.triggerProbability.toString(), "持续轮数" to original.stickyTurns.toString(), "冷却轮数" to original.cooldownTurns.toString(), "随机权重" to original.selectionWeight.toString()) }
    val candidate = draft.copy(keywords = keywords.lines().map(String::trim).filter(String::isNotEmpty).distinct(), settingKeys = settingsKeys.split(',', '，').map(String::trim).filter(String::isNotEmpty).distinct(),
        scanDepth = numbers["扫描深度"]?.toIntOrNull() ?: 0, priority = numbers["优先级"]?.toIntOrNull() ?: 0, injectDepth = numbers["插入深度"]?.toIntOrNull() ?: 0,
        triggerProbability = numbers["概率"]?.toIntOrNull() ?: 0, stickyTurns = numbers["持续轮数"]?.toIntOrNull() ?: 0, cooldownTurns = numbers["冷却轮数"]?.toIntOrNull() ?: 0, selectionWeight = numbers["随机权重"]?.toIntOrNull() ?: 0)
    val errors = candidate.validationErrors(entertainment) + numbers.filterValues { it.toIntOrNull() == null }.keys.map { "$it 请填写整数" }
    AppearanceAlertDialog(onDismissRequest = onDismiss, title = { Text("世界书条目") }, text = {
        LazyColumn(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item { Text("基本内容", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(draft.name, { draft = draft.copy(name = it) }, label = { Text("名称") })
                OutlinedTextField(draft.category, { draft = draft.copy(category = it) }, label = { Text("分类，如人物、地点、事件") })
                LorebookToggle("启用条目", draft.enabled) { draft = draft.copy(enabled = it) }
                OutlinedTextField(draft.content, { draft = draft.copy(content = it) }, label = { Text("注入正文") }, minLines = 4)
            }
            item { Text("触发条件", style = MaterialTheme.typography.titleMedium)
                LorebookToggle("常驻（无需关键词，但仍受预算约束）", draft.constantActive) { draft = draft.copy(constantActive = it) }
                OutlinedTextField(keywords, { keywords = it }, label = { Text("普通关键词，每行一个") }, minLines = 2)
                if (entertainment) {
                    OutlinedTextField(draft.keywordExpression, { draft = draft.copy(keywordExpression = it) }, label = { Text("AND / OR / NOT 表达式（填写后替代普通关键词）") }, minLines = 2)
                    TextButton(onClick = { visual = !visual }) { Text("可视化条件辅助") }
                    if (visual) {
                        OutlinedTextField(allWords, { allWords = it }, label = { Text("全部满足，每行一个") }); OutlinedTextField(anyWords, { anyWords = it }, label = { Text("任一满足，每行一个") }); OutlinedTextField(notWords, { notWords = it }, label = { Text("排除词，每行一个") })
                        TextButton(onClick = {
                            fun terms(text: String, op: String) = text.lines().filter { it.isNotBlank() }.joinToString(" $op ") { quoteLorebookKeyword(it.trim()) }
                            draft = draft.copy(keywordExpression = listOf(terms(allWords, "AND").takeIf { it.isNotBlank() }?.let { "($it)" }, terms(anyWords, "OR").takeIf { it.isNotBlank() }?.let { "($it)" }, terms(notWords, "OR").takeIf { it.isNotBlank() }?.let { "NOT ($it)" }).filterNotNull().joinToString(" AND "))
                        }) { Text("生成并替换表达式") }
                    }
                } else Text("普通模式按普通关键词匹配；组合表达式、概率、冷却和互斥仅娱乐模式使用。", style = MaterialTheme.typography.bodySmall)
                LorebookToggle("正则匹配", draft.useRegex) { draft = draft.copy(useRegex = it) }; LorebookToggle("区分大小写", draft.caseSensitive) { draft = draft.copy(caseSensitive = it) }
                Select(options = LorebookScanSource.entries, selectedOption = draft.scanSource, onOptionSelected = { draft = draft.copy(scanSource = it) }, optionToString = { when(it) { LorebookScanSource.ALL -> "全部非系统消息"; LorebookScanSource.USER -> "仅用户消息"; LorebookScanSource.ASSISTANT -> "仅助手消息"; LorebookScanSource.TEXT_ONLY -> "仅文本，排除工具结果等" } })
                Select(options = LorebookScanMode.entries, selectedOption = draft.scanMode, onOptionSelected = { draft = draft.copy(scanMode = it) }, enabled = !draft.constantActive, optionToString = { when(it) { LorebookScanMode.CUSTOM -> "自定义深度（0 不扫描）"; LorebookScanMode.INHERIT -> "继承本书默认深度"; LorebookScanMode.CURRENT_INPUT -> "仅当前用户输入"; LorebookScanMode.NONE -> "不扫描消息" } })
                Text(if (draft.constantActive) "常驻无需扫描；只在已绑定本书的助手或对话中生效，仍受预算与互斥约束。" else "深度 0 不扫描，也不会自动常驻。仅当前输入是独立选项，不受扫描来源筛选影响。持续激活无需再次匹配。", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(numbers.getValue("扫描深度"), { numbers["扫描深度"] = it }, label = { Text("扫描深度：0–1000 条消息") }, enabled = !draft.constantActive && draft.scanMode == LorebookScanMode.CUSTOM)
                TextButton(onClick = { advanced = !advanced }) { Text(if (advanced) "收起高级设置" else "注入位置、优先级与事件设置") }
            }
            if (advanced) {
                item {
                    Select(options = InjectionPosition.entries, selectedOption = draft.position, onOptionSelected = { draft = draft.copy(position = it) }, optionToString = { when(it) { InjectionPosition.BEFORE_SYSTEM_PROMPT -> "系统提示词之前"; InjectionPosition.AFTER_SYSTEM_PROMPT -> "系统提示词之后"; InjectionPosition.TOP_OF_CHAT -> "聊天开头"; InjectionPosition.BOTTOM_OF_CHAT -> "最新消息之前"; InjectionPosition.AT_DEPTH -> "指定深度" } })
                    Select(options = listOf(MessageRole.USER, MessageRole.ASSISTANT), selectedOption = draft.role, onOptionSelected = { draft = draft.copy(role = it) }, optionToString = { if (it == MessageRole.USER) "用户消息" else "助手消息" })
                    Text("角色仅在独立消息插入位置生效。数字可清空重填，保存时统一校验。", style = MaterialTheme.typography.bodySmall)
                }
                items(numbers.keys.filter { it != "扫描深度" && (entertainment || it in listOf("优先级", "插入深度")) }) { key -> OutlinedTextField(numbers.getValue(key), { numbers[key] = it }, label = { Text(key) }) }
                if (entertainment) item {
                    OutlinedTextField(draft.exclusiveGroup, { draft = draft.copy(exclusiveGroup = it) }, label = { Text("互斥组：同书同组最多采用一项") })
                    LorebookToggle("组内优先覆盖（高于加权随机）", draft.groupOverride) { draft = draft.copy(groupOverride = it) }
                    Text("组内权重全为 0 时按优先级；存在正权重时按权重随机，权重 0 不参与。", style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(settingsKeys, { settingsKeys = it }, label = { Text("设定键，逗号分隔（用于冲突提示）") })
                }
            }
            item { errors.distinct().forEach { Text(it, color = MaterialTheme.colorScheme.error) } }
        }
    }, confirmButton = { TextButton(onClick = { onSave(candidate) }, enabled = errors.isEmpty()) { Text("保存条目") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}
