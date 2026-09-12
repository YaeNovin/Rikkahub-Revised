package me.rerere.rikkahub.ui.pages.chat

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import me.rerere.common.android.Logging
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.getAssistantById
import me.rerere.rikkahub.data.model.*
import me.rerere.rikkahub.service.SuggestionGenerationState
import me.rerere.rikkahub.ui.components.ui.AppearanceAlertDialog
import me.rerere.rikkahub.ui.components.ui.AppearanceModalBottomSheet
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class SuggestionUiActions(
    val changeSession: (SuggestionSession) -> Unit = {},
    val refreshOne: (String) -> Unit = {},
    val pin: (String) -> Unit = {},
    val undoBatch: () -> Unit = {},
    val resetTarget: () -> Unit = {},
    val feedback: (ChatSuggestionItem, SuggestionFeedbackReason) -> Unit = { _, _ -> },
    val clearFeedback: () -> Unit = {},
    val canUndoDraft: Boolean = false,
    val undoDraft: () -> Unit = {},
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SuggestionControlPanel(conversation: Conversation, settings: Settings, state: SuggestionGenerationState,
    actions: SuggestionUiActions, onRefresh: () -> Unit, onClear: () -> Unit,
    onAction: (ChatSuggestionItem) -> Unit, onDismiss: () -> Unit, mainGenerating: Boolean = false) {
    val config = conversation.suggestionConfig(settings)
    val session = conversation.suggestionSession
    val items = conversation.currentChatSuggestions()
    val allowed = conversation.availableSuggestionActions(settings)
    var editSettings by remember { mutableStateOf(false) }
    var feedbackItem by remember { mutableStateOf<ChatSuggestionItem?>(null) }
    var showLog by remember { mutableStateOf(false) }
    AppearanceModalBottomSheet(onDismissRequest = onDismiss) {
        // Keep one scroll container for the entire sheet. A nested LazyColumn made the
        // gesture region small and prevented the sheet from scrolling as a whole.
        Column(Modifier.fillMaxWidth()
            .heightIn(max = (LocalConfiguration.current.screenHeightDp * .88f).dp)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("聊天建议", style = MaterialTheme.typography.titleLarge)
            Text(if (session.settings != null) "使用本次对话设置" else if (settings.getAssistantById(conversation.assistantId)?.suggestionSettings != null)
                "继承助手设置" else "继承全局设置", style = MaterialTheme.typography.bodySmall)
            if (session.target != null) {
                val source = conversation.currentMessages.firstOrNull { it.id == session.target.messageId }
                Text("针对：" + session.target.selectedText.ifBlank { source?.toText().orEmpty() }.take(120), style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = actions.resetTarget) { Text("返回最新对话") }
            }
            if (state == SuggestionGenerationState.GENERATING) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (state == SuggestionGenerationState.FAILED || (state == SuggestionGenerationState.IDLE && session.info?.error != null)) Text("刷新失败，已保留上次有效建议。可以重试或检查模型设置。", color = MaterialTheme.colorScheme.error)
            if (items.isEmpty() && state == SuggestionGenerationState.IDLE) Text("暂无建议。手动生成不会发送聊天消息。", style = MaterialTheme.typography.bodySmall)
            if (mainGenerating || conversation.suggestionSourceMessage() == null) Text("当前消息尚未完成或没有可用文本，完成后可生成建议。", style = MaterialTheme.typography.bodySmall)
            if (items.isNotEmpty() && items.count { it.id in session.pinnedIds } >= config.count) Text("固定项已达到数量上限，取消部分固定后可以换新。", style = MaterialTheme.typography.bodySmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onRefresh, enabled = !mainGenerating && conversation.suggestionSourceMessage() != null && state != SuggestionGenerationState.GENERATING && !session.paused &&
                    config.options.trigger != SuggestionTrigger.DISABLED && items.count { it.id in session.pinnedIds } < config.count) { Text("生成 / 换一批") }
                TextButton(onClick = { actions.changeSession(session.copy(collapsed = !session.collapsed)); onDismiss() }) { Text(if (session.collapsed) "展开建议" else "暂时收起") }
                TextButton(onClick = { actions.changeSession(session.copy(paused = !session.paused)) }) { Text(if (session.paused) "恢复本次对话" else "暂停本次对话") }
                TextButton(onClick = onClear) { Text("清除本组") }
                TextButton(onClick = actions.undoBatch, enabled = session.previous != null) { Text("撤销上次刷新") }
                TextButton(onClick = actions.undoDraft, enabled = actions.canUndoDraft) { Text("撤销上次插入") }
                TextButton(onClick = { editSettings = true }) { Text("本次对话设置") }
            }
            if (items.isNotEmpty()) {
                items.forEach { item ->
                    ChatSuggestionSurface {
                        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text((if (item.id in session.pinnedIds) "📌 " else "") + item.text, style = MaterialTheme.typography.titleSmall)
                            Text(when(item.category) {
                                ChatSuggestionCategory.DIALOGUE -> "对白"; ChatSuggestionCategory.ROLE_ACTION -> "动作";
                                ChatSuggestionCategory.INNER_THOUGHT -> "心理"; ChatSuggestionCategory.PLOT -> "剧情方向";
                                ChatSuggestionCategory.ACTION -> "行动"; ChatSuggestionCategory.FOLLOW_UP -> "追问"; ChatSuggestionCategory.DIRECTION -> "方向"
                            }, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                            if (item.description.isNotBlank()) Text(item.description, style = MaterialTheme.typography.bodySmall)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                TextButton(onClick = { onAction(item); onDismiss() }, enabled = item.action in allowed) { Text("预览 / 使用") }
                                TextButton(onClick = { actions.pin(item.id) }) { Text(if (item.id in session.pinnedIds) "取消固定" else "固定") }
                                TextButton(onClick = { actions.refreshOne(item.id) }, enabled = !mainGenerating && state != SuggestionGenerationState.GENERATING && item.id !in session.pinnedIds && !session.paused && config.options.trigger != SuggestionTrigger.DISABLED) { Text("单条换新") }
                                TextButton(onClick = { feedbackItem = item }) { Text("反馈") }
                                TextButton(onClick = { onAction(item.copy(action = ChatSuggestionAction.COPY_TEXT)); onDismiss() }) { Text("复制") }
                                TextButton(onClick = { onAction(item.copy(action = ChatSuggestionAction.SAVE_QUICK_MESSAGE)); onDismiss() }) { Text("保存快捷消息") }
                                TextButton(onClick = { onAction(item.copy(action = ChatSuggestionAction.CREATE_BRANCH)); onDismiss() }) { Text("创建分支") }
                            }
                        }
                    }
                }
            }
            session.info?.let { info ->
                Text("最近生成：${info.modelName} · ${info.elapsedMillis} ms", style = MaterialTheme.typography.bodySmall)
                Text("${SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(Date(info.completedAt))} · 参考：${info.contextSources.joinToString("、")}", style = MaterialTheme.typography.bodySmall)
                info.usage?.let { Text("输入 ${it.promptTokens} / 输出 ${it.completionTokens} / 缓存 ${it.cachedTokens} Token", style = MaterialTheme.typography.bodySmall) }
                TextButton(onClick = { showLog = true }) { Text("本次运行日志") }
            }
            val feedbackCount = settings.getAssistantById(conversation.assistantId)?.suggestionFeedback?.size ?: 0
            if (feedbackCount > 0) TextButton(onClick = actions.clearFeedback) { Text("重置本地反馈偏好（$feedbackCount）") }
        }
    }
    if (editSettings) SuggestionSettingsDialog(config, settings.providers,
        { actions.changeSession(session.copy(settings = it)) }, { editSettings = false },
        { actions.changeSession(session.copy(settings = null)) })
    feedbackItem?.let { item -> AppearanceAlertDialog(onDismissRequest = { feedbackItem = null }, title = { Text("这条建议有什么问题？") },
        text = { Column { SuggestionFeedbackReason.entries.forEach { reason ->
            TextButton(onClick = { actions.feedback(item, reason); feedbackItem = null }) { Text(when(reason) {
                SuggestionFeedbackReason.IRRELEVANT -> "不相关"; SuggestionFeedbackReason.REPETITIVE -> "重复";
                SuggestionFeedbackReason.TOO_LONG -> "太长"; SuggestionFeedbackReason.OUT_OF_CHARACTER -> "角色不符"
            }) }
        } } }, confirmButton = { TextButton(onClick = { feedbackItem = null }) { Text("取消") } }) }
    if (showLog) AppearanceAlertDialog(onDismissRequest = { showLog = false }, title = { Text("聊天建议运行记录") }, text = {
        val info = session.info
        val entry = Logging.getRecentLogs().firstOrNull { it.id.toString() == info?.logId }
        androidx.compose.foundation.text.selection.SelectionContainer {
            Text(entry?.toString() ?: "日志已过期；保存的运行摘要：\n$info", style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()))
        }
    }, confirmButton = { TextButton(onClick = { showLog = false }) { Text("关闭") } })
}
