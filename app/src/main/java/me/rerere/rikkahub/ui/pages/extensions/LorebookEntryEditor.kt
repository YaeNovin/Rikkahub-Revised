package me.rerere.rikkahub.ui.pages.extensions

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.ai.core.MessageRole
import me.rerere.rikkahub.data.model.*
import me.rerere.rikkahub.ui.components.ui.AppearanceAlertDialog
import me.rerere.rikkahub.ui.components.ui.AppearanceModalBottomSheet
import me.rerere.rikkahub.ui.components.ui.Select

@Composable
internal fun LorebookEntryEditor(session: LorebookEntryDraft, entertainment: Boolean, onDismiss: () -> Unit, bookDefaults: Lorebook? = null, onSave: (PromptInjection.RegexInjection) -> Unit) {
    var draft by session::draft
    var tab by rememberSaveable(session.original.id.toString()) { mutableIntStateOf(0) }
    val scrollStates = List(5) { rememberLazyListState() }
    var help by rememberSaveable { mutableStateOf(false) }
    var discard by rememberSaveable { mutableStateOf(false) }
    var visual by rememberSaveable { mutableStateOf(false) }
    var expressionReplacement by remember { mutableStateOf<String?>(null) }
    val candidate = session.candidate()
    val effectiveMatching = bookDefaults?.let { candidate.withLorebookMatchingDefaults(it) } ?: candidate
    val numericErrors = session.numberErrors(entertainment)
    // Validation can compile imported regexes. Keep it off the UI thread and never save a
    // candidate whose validation result belongs to an older edit.
    val validated by produceState<Pair<PromptInjection.RegexInjection, List<String>>?>(null, candidate, entertainment) {
        value = withContext(Dispatchers.Default) { candidate to candidate.validationErrors(entertainment) }
    }
    val checked = validated?.first == candidate
    val errors = if (checked) (validated!!.second + numericErrors).distinct() else numericErrors
    var testResult by remember { mutableStateOf<Pair<PromptInjection.RegexInjection, KeywordExpressionResult>?>(null) }
    var testedText by remember { mutableStateOf("") }
    var testing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val dismiss = { if (session.dirty) discard = true else onDismiss() }
    val v3Regex = draft.sourceFormat == LorebookSourceFormat.CHARACTER_CARD_V3 && draft.useRegex
    val conditionsEnabled = !draft.constantActive
    AppearanceModalBottomSheet(onDismissRequest = dismiss) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(.95f).imePadding().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("编辑世界书条目", style = MaterialTheme.typography.titleLarge)
                    LorebookHint("${lorebookSourceLabel(draft.sourceFormat)} · 条目先暂存，整本保存后生效")
                }
                TextButton(onClick = { help = true }) { Text("教程") }
            }
            ScrollableTabRow(tab, edgePadding = 0.dp, containerColor = androidx.compose.ui.graphics.Color.Transparent) {
                listOf("内容", "触发", "注入", "高级", "测试").forEachIndexed { index, title -> Tab(tab == index, { tab = index }, text = { Text(title) }) }
            }
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = scrollStates[tab], contentPadding = PaddingValues(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when (tab) {
                    0 -> {
                        item { LorebookSectionTitle("模型需要知道什么？", "只有正文会作为设定注入；名称和分类用于管理，不会自动发送给模型。") }
                        item { OutlinedTextField(draft.name, { draft = draft.copy(name = it) }, label = { Text("条目名称") }, modifier = Modifier.fillMaxWidth(), singleLine = true, isError = draft.name.isBlank()) }
                        item { OutlinedTextField(draft.category, { draft = draft.copy(category = it) }, label = { Text("分类，例如人物、地点、事件") }, modifier = Modifier.fillMaxWidth(), singleLine = true) }
                        item { LorebookToggle("启用条目", draft.enabled, "停用会保留内容，但不参与注入。") { draft = draft.copy(enabled = it) } }
                        item { OutlinedTextField(draft.content, { draft = draft.copy(content = it) }, label = { Text("注入正文") }, modifier = Modifier.fillMaxWidth(), minLines = 8, maxLines = 18, isError = draft.content.isBlank(), supportingText = { Text("${draft.content.length} 字符 · 写成独立完整的描述；修改界面不会自动改写原设定。") }) }
                    }
                    1 -> {
                        item { LorebookSectionTitle("什么时候使用这段设定？", "先使用少量精确关键词。复杂条件可使用辅助生成器，再到测试页验证。") }
                        item { LorebookToggle("常驻", draft.constantActive, if (v3Regex) "V3 正则模式忽略常驻与辅助关键词。" else "跳过关键词匹配，仍受绑定、预算和事件规则限制。", !v3Regex) { draft = draft.copy(constantActive = it) } }
                        item { OutlinedTextField(session.keywords, { session.keywords = it }, label = { Text("关键词：每行一个") }, modifier = Modifier.fillMaxWidth(), minLines = 3, maxLines = 8, enabled = conditionsEnabled && (draft.keywordExpression.isBlank() || v3Regex || !entertainment), supportingText = { Text(if (draft.keywordExpression.isNotBlank() && !v3Regex && entertainment) "当前表达式替代关键词条件；清空表达式可恢复这些关键词。" else "每行是一个完整关键词。不要拆开短语或正则中的逗号。") }) }
                        if (entertainment) {
                            item { OutlinedTextField(draft.keywordExpression, { draft = draft.copy(keywordExpression = it) }, label = { Text("组合表达式（可选）") }, modifier = Modifier.fillMaxWidth(), minLines = 2, maxLines = 7, enabled = conditionsEnabled && !v3Regex,
                                supportingText = { Text("AND 同时满足 · OR 任一满足 · NOT 排除。填写后替代上方关键词条件。") }) }
                            item { TextButton(onClick = { visual = !visual }, enabled = conditionsEnabled && !v3Regex) { Text(if (visual) "收起条件辅助" else "不会写表达式？使用条件辅助") } }
                            if (visual) item {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedTextField(session.allWords, { session.allWords = it }, label = { Text("全部满足（每行一个）") }, modifier = Modifier.fillMaxWidth())
                                    OutlinedTextField(session.anyWords, { session.anyWords = it }, label = { Text("任一满足（每行一个）") }, modifier = Modifier.fillMaxWidth())
                                    OutlinedTextField(session.notWords, { session.notWords = it }, label = { Text("排除词（每行一个）") }, modifier = Modifier.fillMaxWidth())
                                    TextButton(enabled = conditionsEnabled && !v3Regex, onClick = {
                                        val expression = buildLorebookExpression(session.allWords, session.anyWords, session.notWords)
                                        if (draft.keywordExpression.isNotBlank()) expressionReplacement = expression else draft = draft.copy(keywordExpression = expression)
                                    }) { Text("生成表达式") }
                                }
                            }
                        } else item { LorebookHint("普通模式下原生组合条件与事件不运行；导入兼容条目不受该模式限制。") }
                        item { LorebookToggle("所有关键词按正则处理", draft.useRegex, if (draft.sourceFormat == LorebookSourceFormat.NATIVE) "原生模式填写 Android／Java 正则，不使用 /…/ 包裹。" else "SillyTavern／V2 的 /正则/i 已自动识别，普通关键词无需开启此开关。V3 开启后按规范忽略常驻和辅助条件。", conditionsEnabled || v3Regex) { value ->
                            // Do not erase dormant source conditions when toggling regex mode.
                            draft = draft.copy(useRegex = value, constantActive = if (value && draft.sourceFormat == LorebookSourceFormat.CHARACTER_CARD_V3) false else draft.constantActive)
                        } }
                        item { LorebookToggle("区分大小写", effectiveMatching.caseSensitive, "显示当前有效值；修改后成为条目独立设置。/…/i 由自己的标志控制。", conditionsEnabled) { draft = draft.withExplicitLorebookMatching(caseSensitive = it) } }
                        item { LorebookToggle("完整词匹配", effectiveMatching.matchWholeWords, "防止英文 cat 命中 catalog；中文、日文通常关闭。修改后成为独立设置。", conditionsEnabled && !draft.useRegex) { draft = draft.withExplicitLorebookMatching(wholeWords = it) } }
                        item { LorebookSectionTitle("扫描哪里？", "扫描深度是查找关键词的消息范围，与注入位置的深度不同。$LOREBOOK_SCAN_DEPTH_START") }
                        item { LorebookScanDepthReference() }
                        item { Select(LorebookScanMode.entries, draft.scanMode, onOptionSelected = { draft = draft.copy(scanMode = it) }, enabled = conditionsEnabled, optionToString = { when (it) { LorebookScanMode.CUSTOM -> "自定义深度"; LorebookScanMode.INHERIT -> "继承本书默认"; LorebookScanMode.CURRENT_INPUT -> "仅当前用户输入"; LorebookScanMode.NONE -> "不扫描聊天" } }) }
                        if (draft.scanMode == LorebookScanMode.CUSTOM) item { LorebookNumberField("扫描深度（条消息）", session.numbers.getValue("扫描深度"), 0..1000, "0 不扫描聊天，不代表常驻。", conditionsEnabled) { session.numbers["扫描深度"] = it } }
                        if (draft.scanMode != LorebookScanMode.CURRENT_INPUT) item { Select(LorebookScanSource.entries, draft.scanSource, onOptionSelected = { draft = draft.copy(scanSource = it) }, enabled = conditionsEnabled && draft.scanMode != LorebookScanMode.NONE, optionToString = { when (it) { LorebookScanSource.ALL -> "全部非系统消息文本"; LorebookScanSource.USER -> "仅用户消息"; LorebookScanSource.ASSISTANT -> "仅助手消息"; LorebookScanSource.TEXT_ONLY -> "仅文本" } }) }
                    }
                    2 -> {
                        item { LorebookSectionTitle("把设定放在哪里？", "预算优先级决定保留谁；插入位置与顺序决定设定排列。先使用系统提示词之后，需要时再调整。") }
                        draft.unsupportedPosition?.let { source -> item {
                            Text("来源位置 $source 尚未映射，条目暂停注入。", color = MaterialTheme.colorScheme.error)
                            TextButton(onClick = { draft = draft.copy(unsupportedPosition = null) }) { Text("确认采用当前选择的位置") }
                        } }
                        item { Select(InjectionPosition.entries, draft.position, onOptionSelected = { draft = draft.copy(position = it, unsupportedPosition = null) }, optionToString = { lorebookPositionLabel(it) }) }
                        if (draft.position == InjectionPosition.OUTLET) item { OutlinedTextField(draft.outletName, { draft = draft.copy(outletName = it) }, label = { Text("插槽名称") }, modifier = Modifier.fillMaxWidth(), supportingText = { Text("不自动注入；在提示词中使用 {{outlet::名称}} 引用，区分大小写。") }) }
                        if (draft.position == InjectionPosition.AT_DEPTH) item { LorebookNumberField("插入深度", session.numbers.getValue("插入深度"), 0..1000, "0 位于提示末尾，数字增加则向更早的消息移动。") { session.numbers["插入深度"] = it } }
                        item { Select(listOf(MessageRole.SYSTEM, MessageRole.USER, MessageRole.ASSISTANT), draft.role, onOptionSelected = { draft = draft.copy(role = it) }, optionToString = { when (it) { MessageRole.SYSTEM -> "系统消息"; MessageRole.USER -> "用户消息"; else -> "助手消息" } }); LorebookHint("角色只在独立消息插入位置生效。") }
                        item { LorebookNumberField("预算优先级", session.numbers.getValue("优先级"), null, "越高越先参与预算保留，可使用负数。") { session.numbers["优先级"] = it } }
                        item { LorebookNumberField("插入顺序（可留空）", session.numbers.getValue("插入顺序"), null, "显式顺序越小越靠前；留空沿用原生优先级排序。", optional = true) { session.numbers["插入顺序"] = it } }
                    }
                    3 -> {
                        if (!entertainment) item { LorebookHint("当前是普通模式的原生条目，高级事件不运行。已保存的设置仍保留，不会因隐藏而清空。") }
                        else {
                            item { LorebookSectionTitle("随机事件与有效期", "先用概率 100%、无冷却测试，再逐步增加随机性。不同计数单位的边界不同，见教程。") }
                            item { LorebookToggle("允许向量语义触发", draft.vectorized, "需在来源与绑定页开启并配置向量模型。可与关键词并用；仍受概率、角色、分组和预算约束。") { draft = draft.copy(vectorized = it) } }
                            item { LorebookNumberField("触发概率（%）", session.numbers.getValue("概率"), 0..100, "100 每次通过概率；0 不通过。相同种子与轮次可以复现。") { session.numbers["概率"] = it } }
                            item { Select(LorebookTimingUnit.entries, draft.timingUnit, onOptionSelected = { draft = draft.copy(timingUnit = it); if (it == LorebookTimingUnit.USER_TURNS && session.numbers["持续计数"] == "0") session.numbers["持续计数"] = "1" }, optionToString = { if (it == LorebookTimingUnit.MESSAGES) "按消息计数（导入兼容）" else "按用户轮次计数（原生）" }) }
                            item { LorebookNumberField("持续计数", session.numbers.getValue("持续计数"), (if (draft.timingUnit == LorebookTimingUnit.MESSAGES) 0 else 1)..10000, "持续期间无需再次命中关键词。按消息计数时 0 表示不延续。") { session.numbers["持续计数"] = it } }
                            item { LorebookNumberField("冷却计数", session.numbers.getValue("冷却计数"), 0..10000, "持续结束后，多少条消息或轮次内不能再激活；0 关闭。") { session.numbers["冷却计数"] = it } }
                            item { LorebookNumberField("延迟消息数", session.numbers.getValue("延迟消息数"), 0..10000, "历史达到此消息数后才可激活，与计数单位开关无关。") { session.numbers["延迟消息数"] = it } }
                            item { LorebookSectionTitle("分组与适用范围", "多值字段可用逗号或换行分隔，编辑期间不会自动删除分隔符。") }
                            item {
                                LorebookSectionTitle("额外匹配来源", "仅作为触发检索文本，不直接注入。角色字段可在来源与绑定页编辑，Persona 使用当前选中描述。")
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    lorebookMatchingSourceLabels.forEach { (key, label) -> FilterChip(key in draft.additionalMatchingSources,
                                        onClick = { draft = draft.copy(additionalMatchingSources = if (key in draft.additionalMatchingSources) draft.additionalMatchingSources - key else draft.additionalMatchingSources + key) }, label = { Text(label) }) }
                                }
                            }
                            item { OutlinedTextField(session.groups, { session.groups = it }, label = { Text("分组名称") }, modifier = Modifier.fillMaxWidth(), supportingText = { Text("组内选择存在兼容边界；重要互斥先使用单组验证。") }) }
                            item { LorebookToggle("组内优先选择", draft.groupOverride || draft.prioritizeInclusion, "优先候选按插入顺序（Order）选择；没有显式顺序时沿用优先级。") { draft = draft.copy(groupOverride = it, prioritizeInclusion = it) } }
                            item { LorebookNumberField("随机权重", session.numbers.getValue("随机权重"), 0..10000, "组中有正权重时，权重越大越容易选中，0 不参与随机。") { session.numbers["随机权重"] = it } }
                            item { LorebookToggle("参与组评分", draft.useGroupScoring, "按命中词数比较；NOT 条件不加分；跨书复杂竞争仍建议模拟验证。") { draft = draft.copy(useGroupScoring = it) } }
                            item { OutlinedTextField(session.characters, { session.characters = it }, label = { Text("助手／角色名称过滤") }, modifier = Modifier.fillMaxWidth()) }
                            item { OutlinedTextField(session.characterTags, { session.characterTags = it }, label = { Text("助手标签过滤") }, modifier = Modifier.fillMaxWidth()) }
                            item { LorebookToggle("排除上述角色或标签", draft.characterFilterExclude, "留空不限制；当前名称或标签任一匹配即满足筛选。") { draft = draft.copy(characterFilterExclude = it) } }
                            item {
                                LorebookSectionTitle("生成触发类型", "全不选代表所有类型。静默类型仅适用于经过世界书评估器的后台请求。")
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    LorebookGenerationTrigger.entries.forEach { trigger -> FilterChip(trigger in draft.generationTriggers,
                                        onClick = { draft = draft.copy(generationTriggers = if (trigger in draft.generationTriggers) draft.generationTriggers - trigger else draft.generationTriggers + trigger) }, label = { Text(lorebookTriggerLabel(trigger)) }) }
                                }
                                if (draft.generationTriggers.isNotEmpty()) TextButton(onClick = { draft = draft.copy(generationTriggers = emptySet()) }) { Text("恢复不限类型") }
                            }
                            if (draft.sourceFormat != LorebookSourceFormat.NATIVE) {
                                item { LorebookSectionTitle("递归控制", "需要先在本书设置启用递归。低级别没有新增命中后，才放行下一层延迟候选。") }
                                item { LorebookToggle("阻止继续递归", draft.preventRecursion, "本条可以被选中，但正文不触发其他条目。") { draft = draft.copy(preventRecursion = it) } }
                                item { LorebookToggle("递归阶段排除此条目", draft.excludeRecursion, "允许被聊天触发，不允许被其他条目正文触发。") { draft = draft.copy(excludeRecursion = it) } }
                                item { LorebookToggle("仅递归阶段激活", draft.delayUntilRecursion, "不参加初始聊天扫描；与递归阶段排除同时启用将无法匹配。") { draft = draft.copy(delayUntilRecursion = it) } }
                                item { LorebookNumberField("递归级别", session.numbers.getValue("递归级别"), 0..1000, "仅递归激活时按级别逐步放行；0 与 1 为最早延迟层。非延迟条目仍按具体扫描轮限定。") { session.numbers["递归级别"] = it } }
                            }
                            item { LorebookToggle("忽略本书和跨书预算", draft.ignoreBudget, "高风险：可能挤占聊天上下文。建议保持关闭。") { draft = draft.copy(ignoreBudget = it) } }
                            item { OutlinedTextField(session.settingKeys, { session.settingKeys = it }, label = { Text("设定键（用于冲突提示）") }, modifier = Modifier.fillMaxWidth(), supportingText = { Text("如 character.age；同键说明可能修改同一设定，不会自动判断语义矛盾。") }) }
                        }
                    }
                    4 -> {
                        item { LorebookSectionTitle("测试当前条件", "仅测试常驻／关键词／表达式；不扫描真实历史、不校验预算和事件，不调用模型。完整测试请使用世界书详情页的场景模拟。") }
                        item { OutlinedTextField(session.testText, { session.testText = it }, label = { Text("用于匹配的文本") }, minLines = 4, maxLines = 9, modifier = Modifier.fillMaxWidth()) }
                        item { Button(enabled = !testing && checked && numericErrors.isEmpty(), onClick = {
                            testing = true
                            val snapshot = candidate
                            val input = session.testText
                            scope.launch {
                                try {
                                    val matching = bookDefaults?.let { snapshot.withLorebookMatchingDefaults(it) } ?: snapshot
                                    val rule = if (entertainment) matching else matching.copy(keywordExpression = "")
                                    val result = withContext(Dispatchers.Default) { rule.evaluateKeywords(input) }
                                    testResult = snapshot to result; testedText = input
                                } finally { testing = false }
                            }
                        }) { Text(if (testing) "测试中…" else "测试条件") } }
                        testResult?.let { (tested, result) -> item {
                            if (tested != candidate || testedText != session.testText) LorebookHint("输入已改变，以下是上一次测试结果，请重新测试。")
                            Text(result.error ?: if (result.matched) "条件命中" else "条件未命中", color = if (result.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                            LorebookHint("命中词项：${result.matchedTerms.joinToString("、").ifBlank { if (tested.constantActive) "常驻（不依赖词项）" else "无" }}")
                        } }
                        item { LorebookSectionTitle("保存前检查", "校验结果随草稿更新；停用功能的输入仍保留，便于以后恢复。") }
                        item { if (!checked) LorebookHint("校验中…") else if (errors.isEmpty()) LorebookHint("未发现配置校验错误。") else errors.forEach { Text(it, color = MaterialTheme.colorScheme.error) } }
                    }
                }
            }
            if (checked && errors.isNotEmpty()) TextButton(onClick = { tab = 4 }) { Text("${errors.size} 个问题，前往测试页检查") }
            HorizontalDivider()
            Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = dismiss) { Text("取消") }
                Button(onClick = { onSave(candidate) }, enabled = checked && errors.isEmpty()) { Text("暂存条目") }
            }
        }
    }
    if (help) LorebookHelpSheet { help = false }
    expressionReplacement?.let { expression -> AppearanceAlertDialog(onDismissRequest = { expressionReplacement = null }, title = { Text("替换现有表达式？") }, text = { Text(expression.ifBlank { "辅助条件为空，将清空表达式并恢复普通关键词。" }) }, confirmButton = { TextButton(onClick = { draft = draft.copy(keywordExpression = expression); expressionReplacement = null }) { Text("替换") } }, dismissButton = { TextButton(onClick = { expressionReplacement = null }) { Text("取消") } }) }
    if (discard) AppearanceAlertDialog(onDismissRequest = { discard = false }, title = { Text("放弃条目修改？") }, text = { Text("尚未暂存的输入将被放弃。") }, confirmButton = { TextButton(onClick = { discard = false; onDismiss() }) { Text("放弃修改") } }, dismissButton = { TextButton(onClick = { discard = false }) { Text("继续编辑") } })
}
