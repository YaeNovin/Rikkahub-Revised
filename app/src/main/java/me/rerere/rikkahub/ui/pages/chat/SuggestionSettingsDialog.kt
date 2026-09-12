package me.rerere.rikkahub.ui.pages.chat

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.data.datastore.*
import me.rerere.rikkahub.data.model.*
import me.rerere.rikkahub.ui.components.ai.ModelListSheet
import me.rerere.rikkahub.ui.components.ai.rememberModelListState
import me.rerere.rikkahub.ui.components.ui.AppearanceAlertDialog
import kotlin.math.roundToInt

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SuggestionSettingsDialog(config: ChatSuggestionConfig, providers: List<ProviderSetting>,
    onConfirm: (ChatSuggestionConfig) -> Unit, onDismiss: () -> Unit, onInherit: (() -> Unit)? = null) {
    var draft by remember { mutableStateOf(config.bounded()) }
    val modelState = rememberModelListState(draft.modelId, providers, ModelType.CHAT)
    AppearanceAlertDialog(onDismissRequest = onDismiss, title = { Text("聊天建议设置") }, text = {
        Column(Modifier.fillMaxWidth().heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("生成方式")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SuggestionTrigger.entries.forEach { value -> FilterChip(draft.options.trigger == value,
                    onClick = { draft = draft.copy(options = draft.options.copy(trigger = value)) },
                    label = { Text(when (value) { SuggestionTrigger.AUTOMATIC -> "自动"; SuggestionTrigger.MANUAL -> "手动"; SuggestionTrigger.DISABLED -> "关闭" }) }) }
            }
            TextButton(onClick = { modelState.open() }) { Text("生成模型：${modelState.currentModel?.displayName ?: "使用默认快速模型"}") }
            if (draft.modelId != null) TextButton(onClick = { draft = draft.copy(modelId = null) }) { Text("恢复默认模型") }
            SuggestionSettingSlider("建议数量", draft.count, 1..10) { draft = draft.copy(count = it) }
            SuggestionSettingSlider("文字长度上限", draft.maxLength, 10..200) { draft = draft.copy(maxLength = it) }
            SuggestionSettingSlider("自动生成最短间隔（秒）", draft.options.intervalSeconds, 0..600) { draft = draft.copy(options = draft.options.copy(intervalSeconds = it)) }
            SuggestionSettingSlider("上下文预算（估算 Token）", draft.options.contextBudget, 500..12000) { draft = draft.copy(options = draft.options.copy(contextBudget = it)) }
            Text("预算用于建议读取的上下文；实际 Token 由供应商计数。间隔仅限制自动生成，手动刷新不受影响。", style = MaterialTheme.typography.bodySmall)
            Text("建议风格")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ChatSuggestionStyle.entries.forEach { value -> FilterChip(draft.style == value, { draft = draft.copy(style = value) },
                    label = { Text(when(value) { ChatSuggestionStyle.BALANCED -> "均衡"; ChatSuggestionStyle.FOLLOW_UP -> "追问"; ChatSuggestionStyle.ACTIONABLE -> "行动"; ChatSuggestionStyle.CONCISE -> "简短"; ChatSuggestionStyle.ROLEPLAY -> "角色扮演" }) }) }
            }
            Text("显示形式")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ChatSuggestionDisplayMode.entries.forEach { value -> FilterChip(draft.displayMode == value, { draft = draft.copy(displayMode = value) },
                    label = { Text(when(value) { ChatSuggestionDisplayMode.AUTO -> "自动"; ChatSuggestionDisplayMode.COMPACT -> "单行"; ChatSuggestionDisplayMode.TWO_ROW -> "双行"; ChatSuggestionDisplayMode.RICH -> "卡片" }) }) }
            }
            SuggestionSettingSwitch("优先追加到草稿", draft.insertMode == SuggestionInsertMode.APPEND) { draft = draft.copy(insertMode = if (it) SuggestionInsertMode.APPEND else SuggestionInsertMode.REPLACE) }
            SuggestionSettingSwitch("插入前预览", draft.options.previewBeforeInsert) { draft = draft.copy(options = draft.options.copy(previewBeforeInsert = it)) }
            SuggestionSettingSwitch("均衡分配建议类别", draft.options.balancedCategories) { draft = draft.copy(options = draft.options.copy(balancedCategories = it)) }
            SuggestionSettingSwitch("参考滚动摘要", draft.options.includeSummary) { draft = draft.copy(options = draft.options.copy(includeSummary = it)) }
            SuggestionSettingSwitch("参考当前对话允许的记忆", draft.options.includeMemory) { draft = draft.copy(options = draft.options.copy(includeMemory = it)) }
            Text("记忆关闭时不会读取记忆。针对历史消息生成时，仅使用该消息及之前的对话，避免带入后续内容。", style = MaterialTheme.typography.bodySmall)
            SuggestionSettingSwitch("提供参数化建议", draft.options.parameterForms) { draft = draft.copy(options = draft.options.copy(parameterForms = it)) }
            SuggestionSettingSwitch("提供图片提示词建议", draft.options.imageSuggestions) { draft = draft.copy(options = draft.options.copy(imageSuggestions = it)) }
            Text("图片建议只在配置图片模型后显示，先形成草稿，再由你选择生成。", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(draft.options.goal, { draft = draft.copy(options = draft.options.copy(goal = it.take(2000))) }, label = { Text("当前目标（可选）") }, modifier = Modifier.fillMaxWidth())
            var promptExpanded by remember { mutableStateOf(false) }
            TextButton(onClick = { promptExpanded = !promptExpanded }) { Text("自定义建议提示词") }
            if (promptExpanded) OutlinedTextField(draft.prompt, { draft = draft.copy(prompt = it) }, modifier = Modifier.fillMaxWidth(), minLines = 4)
            if (onInherit != null) TextButton(onClick = { onInherit(); onDismiss() }) { Text("恢复继承上一级设置") }
        }
    }, confirmButton = { TextButton(onClick = { onConfirm(draft.bounded()); onDismiss() }) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
    ModelListSheet(modelState, onSelect = { draft = draft.copy(modelId = it.id) })
}

@Composable private fun SuggestionSettingSlider(label: String, value: Int, range: IntRange, onChange: (Int) -> Unit) {
    Text("$label：$value", style = MaterialTheme.typography.bodyMedium)
    Slider(value.toFloat().coerceIn(range.first.toFloat(), range.last.toFloat()), { onChange(it.roundToInt()) }, valueRange = range.first.toFloat()..range.last.toFloat())
}

@Composable private fun SuggestionSettingSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f)); Switch(checked, onChange)
    }
}
