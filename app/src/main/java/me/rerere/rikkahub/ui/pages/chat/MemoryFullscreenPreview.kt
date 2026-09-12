package me.rerere.rikkahub.ui.pages.chat

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import kotlinx.coroutines.launch

internal data class MemoryPreviewContent(
    val title: String, val content: String, val id: String, val hash: String,
    val source: String?, val vector: ByteArray? = null, val modelId: String? = null, val dimension: Int? = null,
    val identity: String = "", val sources: List<me.rerere.rikkahub.data.db.entity.MemorySourceEntity> = emptyList(),
)

internal fun memoryIdentityDescription(record: me.rerere.rikkahub.data.repository.MemorySearchRecord): String =
    "稳定 UUID：${record.memory.uid}\n版本：${record.memory.revision}\n作用域：${record.memory.scopeType} / ${record.memory.scopeId}\n替代记录：${record.memory.supersededByUid ?: "无"}\n向量配置指纹：${record.embeddingKey ?: "未建立"}\n提取任务：${record.createdByRun?.id ?: "旧记录或手动创建"}\n任务状态：${record.createdByRun?.status ?: "无"}"

@Composable
internal fun MemoryFullscreenPreview(value: MemoryPreviewContent, onOpenSource: ((String, String) -> Unit)? = null,
    rebuildIndex: (suspend () -> Unit)? = null, onDismiss: () -> Unit) {
    var showIndex by remember(value.id) { mutableStateOf(false) }
    var rebuilding by remember(value.id) { mutableStateOf(false) }
    var rebuildResult by remember(value.id) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val vector = remember(value.id, value.vector) {
        value.vector?.takeIf { it.size % Float.SIZE_BYTES == 0 }?.let { bytes ->
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            List(bytes.size / Float.SIZE_BYTES) { buffer.float }
        }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        me.rerere.rikkahub.ui.components.ui.IsolatedOverlaySurface(Modifier.fillMaxSize(), shape = androidx.compose.ui.graphics.RectangleShape) {
            Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text(value.title, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
                    TextButton(onClick = onDismiss) { Text("关闭") }
                }
                LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    item {
                        SelectionContainer { Text(value.content, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface) }
                    }
                    item { TextButton(onClick = { showIndex = !showIndex }) { Text(if (showIndex) "收起索引信息" else "查看标识与向量索引") } }
                    if (showIndex) {
                        if (rebuildIndex != null) item {
                            TextButton(enabled = !rebuilding, onClick = {
                                rebuilding = true
                                scope.launch {
                                    try { rebuildIndex(); rebuildResult = "索引已更新" }
                                    catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                                    catch (error: Exception) { rebuildResult = error.message ?: "索引重建失败" }
                                    finally { rebuilding = false }
                                }
                            }) { Text(if (rebuilding) "正在重建索引…" else "重建向量索引") }
                            Text("将调用已配置的向量模型。", style = MaterialTheme.typography.bodySmall)
                            rebuildResult?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                        }
                        item {
                            SelectionContainer { Text("记录 ID：${value.id}\n内容 SHA-256：${value.hash}\n来源对话/消息：${value.source ?: "未关联"}\n向量模型：${value.modelId ?: "未建立向量"}\n维度：${value.dimension ?: vector?.size ?: 0}\n${value.identity}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface) }
                        }
                        if (value.sources.isNotEmpty()) item { Text("提取时参考的消息（不代表逐条事实证据）", style = MaterialTheme.typography.labelMedium) }
                        items(value.sources, key = { it.conversationId + ":" + it.messageId }) { source ->
                            TextButton(onClick = { onOpenSource?.invoke(source.conversationId, source.messageId) }, enabled = onOpenSource != null) {
                                Text(if (source.messageId.isBlank()) "查看来源对话" else "查看来源消息 · ${source.messageId.take(8)}")
                            }
                        }
                        if (vector != null) items(vector.chunked(12).withIndex().toList(), key = { it.index }) { row ->
                            SelectionContainer { Text(row.value.mapIndexed { index, number ->
                                "${row.index * 12 + index}: ${String.format(Locale.ROOT, "%.6g", number)}"
                            }.joinToString("\n"), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
                        }
                    }
                }
            }
        }
    }
}
