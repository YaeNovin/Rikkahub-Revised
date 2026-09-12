package me.rerere.rikkahub.ui.pages.chat

import androidx.compose.material3.*
import androidx.compose.runtime.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.memory.MemoryDataCounts
import me.rerere.rikkahub.service.ChatService
import me.rerere.rikkahub.ui.components.ui.AppearanceAlertDialog
import org.koin.compose.koinInject

@Composable
fun ClearAllMemoryButton() {
    val service = koinInject<ChatService>()
    val scope = rememberCoroutineScope()
    var counts by remember { mutableStateOf<MemoryDataCounts?>(null) }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    TextButton(enabled = !busy, onClick = {
        busy = true
        scope.launch {
            try { counts = service.memoryDataCounts(); result = null }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { result = "无法读取记忆数量，请重试" }
            finally { busy = false }
        }
    }) { Text("删除全部记忆与对话索引", color = MaterialTheme.colorScheme.error) }
    result?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    counts?.let { total -> AppearanceAlertDialog(onDismissRequest = { if (!busy) counts = null }, title = { Text("清理全应用的记忆数据？") },
        text = { Text("将删除所有助手的 ${total.memories} 条事实/情景记忆，以及 ${total.chunks} 条对话片段和对应向量。聊天原文、知识库和设置会保留。后续消息仍可按设置形成新记忆。") },
        confirmButton = { TextButton(enabled = !busy && total.memories + total.chunks > 0, onClick = {
            busy = true
            scope.launch {
                try { val removed = service.clearAllMemoryData(); result = "已清理 ${removed.memories} 条记忆和 ${removed.chunks} 条对话索引"; counts = null }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { result = "清理失败，请重试"; counts = null }
                finally { busy = false }
            }
        }) { Text(if (busy) "正在清理…" else "删除全部") } },
        dismissButton = { TextButton(enabled = !busy, onClick = { counts = null }) { Text("取消") } }) }
}
