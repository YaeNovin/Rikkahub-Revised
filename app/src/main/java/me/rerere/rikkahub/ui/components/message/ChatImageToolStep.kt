package me.rerere.rikkahub.ui.components.message

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.ChatImageToolCoordinator
import me.rerere.rikkahub.data.ai.tools.*
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.model.ImageGenerationRequestState
import me.rerere.rikkahub.data.model.constrained
import me.rerere.rikkahub.ui.components.ui.ChainOfThoughtScope
import me.rerere.rikkahub.ui.components.ui.AppearanceAlertDialog
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.utils.JsonInstant
import org.koin.compose.koinInject
import kotlin.uuid.Uuid

class ChatImageToolActions(
    val conversationId: Uuid,
    val confirm: (callId: String, taskId: String, modelId: Uuid, options: ImageGenerationRequestState) -> Unit,
    val stop: () -> Unit,
    val resume: (messageId: String) -> Unit,
    val modelId: Uuid?,
    val options: ImageGenerationRequestState,
    val openSettings: () -> Unit,
    val canResume: (messageId: String) -> Boolean,
)

val LocalChatImageToolActions = staticCompositionLocalOf<ChatImageToolActions?> { null }

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChainOfThoughtScope.ChatImageToolStep(
    tool: UIMessagePart.Tool,
    loading: Boolean,
    onToolCancel: ((String, String) -> Unit)?,
) {
    val taskId = tool.metadata?.get(CHAT_IMAGE_TOOL_TASK_KEY)?.jsonPrimitive?.contentOrNull ?: return
    val coordinator: ChatImageToolCoordinator = koinInject()
    val providers: ProviderManager = koinInject()
    val actions = LocalChatImageToolActions.current
    val observed by remember(taskId) { coordinator.observe(taskId) }.collectAsStateWithLifecycle(null)
    val task = observed?.takeIf {
        it.toolCallId == tool.toolCallId && it.inputHash == imageToolHash(tool.input) &&
            (actions == null || it.conversationId == actions.conversationId.toString())
    }
    if (task == null) {
        ChainOfThoughtStep(label = { Text("图片生成任务") }, content = { Text("任务加载中或原记录已移除") })
        return
    }
    val request = remember(task.requestJson) { JsonInstant.decodeFromString<ChatImageToolRequest>(task.requestJson) }
    val settings = LocalSettings.current
    var prompt by rememberSaveable(task.id) { mutableStateOf(request.options.prompt) }
    var promptMode by rememberSaveable(task.id) { mutableStateOf("proposed") }
    var showSnapshot by remember { mutableStateOf(false) }
    var expanded by rememberSaveable(task.id) { mutableStateOf(true) }
    var submitting by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val pending = task.status == "pending" && tool.isPending
    val model = settings.findModelById(actions?.modelId ?: runCatching { Uuid.parse(request.modelId) }.getOrNull())
    val provider = model?.findProvider(settings.providers)
    val constraints = remember(provider, model) {
        if (provider != null && model != null) providers.imageGenerationConstraints(provider, model).forChatImageTool() else null
    }
    val options = (actions?.options ?: request.options).copy(prompt = prompt).let {
        if (constraints != null) it.constrained(constraints) else it
    }
    LaunchedEffect(task.status, loading) { if (pending && !loading) submitting = false }
    val images = remember(task.imagesJson) { JsonInstant.decodeFromString<List<String>>(task.imagesJson) }
    val title = when (task.status) {
        "pending" -> "确认生成图片"
        "approved" -> "已确认，等待执行"
        "running" -> "图片生成中"
        "succeeded" -> "图片已生成"
        "partial" -> "已保存部分图片"
        "unknown" -> "生成结果未知"
        "cancelled" -> "已取消生图"
        "expired" -> "生图确认已失效"
        else -> "图片生成未完成"
    }
    ControlledChainOfThoughtStep(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        label = { Text(title, style = MaterialTheme.typography.titleSmall) },
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                if (pending) {
                    TextButton(onClick = { actions?.openSettings?.invoke() }, enabled = actions != null) {
                        Text("图片模型：${model?.displayName?.ifBlank { model.modelId } ?: "选择模型"}")
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(selected = promptMode == "original", onClick = {
                            promptMode = "original"; prompt = task.originalPrompt
                        }, label = { Text("用户原文") })
                        FilterChip(selected = promptMode == "proposed", onClick = {
                            promptMode = "proposed"; prompt = task.proposedPrompt
                        }, label = { Text("模型建议") })
                        FilterChip(selected = promptMode == "custom", onClick = { promptMode = "custom" }, label = { Text("手动修改") })
                    }
                    OutlinedTextField(value = prompt, onValueChange = {
                        promptMode = "custom"; prompt = it
                    }, label = { Text("实际发送给图片模型的提示词") }, modifier = Modifier.fillMaxWidth(), minLines = 3, maxLines = 8)
                    Text("${options.count} 张 · ${options.size}" + (options.quality?.let { " · $it" } ?: ""))
                    TextButton(onClick = { actions?.openSettings?.invoke() }, enabled = actions != null) { Text("打开手动生图参数设置") }
                    Text("确认后将固定本次参数，可能产生图片生成费用。不会发送整段聊天历史或世界书。",
                        style = MaterialTheme.typography.bodySmall)
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { onToolCancel?.invoke(tool.toolCallId, "本次不生成图片，继续对话") }, enabled = !submitting && actions != null) { Text("本次不生成") }
                        Button(enabled = !submitting && actions != null && provider?.enabled == true && constraints?.supportsGeneration == true && options.prompt.isNotBlank(), onClick = {
                            try {
                                val selected = requireNotNull(model)
                                val confirmed = coordinator.makeRequest(settings, selected.id, options)
                                error = null; submitting = true
                                actions?.confirm?.invoke(tool.toolCallId, task.id, selected.id, confirmed.options)
                            } catch (failure: Exception) { error = failure.message ?: "请检查生图参数" }
                        }) { Text(if (submitting) "提交中…" else "确认并生成") }
                    }
                } else {
                    Text("${request.modelName} · ${request.providerName}", style = MaterialTheme.typography.labelMedium)
                    if (request.automatic) Text("模型自行调用 · 使用用户手动参数", style = MaterialTheme.typography.labelSmall)
                    TextButton(onClick = { showSnapshot = true }) { Text("查看本次提示词与参数") }
                    if (task.status in setOf("approved", "running")) {
                        if (task.status == "running") ImageGenerationSkeleton()
                        Text(if (task.status == "approved") "请求已确认；若还有其他待回答的问题，完成后开始生成。"
                            else "本轮回复将在图片返回后继续。可以切换对话或编辑草稿；停止等待不保证供应商停止计费。",
                            style = MaterialTheme.typography.bodySmall)
                        if (loading && actions != null) TextButton(onClick = actions.stop) { Text("停止等待") }
                        if (!loading && task.status == "approved" && onToolCancel != null) {
                            TextButton(onClick = { onToolCancel(tool.toolCallId, "用户取消尚未执行的生图任务") }) { Text("取消生图") }
                        }
                    }
                    task.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    if (task.status in setOf("unknown", "partial", "failed")) {
                        Text("不会自动重复生图。如需再次生成，请发送新的请求并重新确认。", style = MaterialTheme.typography.bodySmall)
                    }
                    if (!loading && task.status in setOf("succeeded", "partial", "unknown", "failed") && actions?.canResume?.invoke(task.messageId) == true) {
                        TextButton(onClick = { actions.resume(task.messageId) }) { Text("继续文字回复") }
                    }
                }
                if (images.isNotEmpty()) {
                    images.forEach { url -> GeneratedChatImage(url) }
                }
            }
        },
    )
    if (showSnapshot) AppearanceAlertDialog(onDismissRequest = { showSnapshot = false }, title = { Text("本次生图参数") },
        text = {
            Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("已确认的提示词", style = MaterialTheme.typography.titleSmall)
                SelectionContainer { Text(request.options.prompt) }
                Text("参数（只读）", style = MaterialTheme.typography.titleSmall)
                SelectionContainer {
                    Text(kotlinx.serialization.json.Json { prettyPrint = true; encodeDefaults = true }.encodeToString(request.options))
                }
            }
        },
        confirmButton = { TextButton(onClick = { showSnapshot = false }) { Text("关闭") } })
}
