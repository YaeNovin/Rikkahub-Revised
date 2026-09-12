package me.rerere.rikkahub.ui.pages.extensions

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.transformers.evaluateInjections
import me.rerere.rikkahub.data.ai.transformers.applyInjections
import me.rerere.rikkahub.data.model.*
import kotlin.uuid.Uuid

@Composable
internal fun LorebookSimulator(book: Lorebook, allBooks: List<Lorebook>, entertainment: Boolean, totalBudget: Int) {
    var text by rememberSaveable(book.id.toString()) { mutableStateOf("") }
    var assistantText by rememberSaveable(book.id.toString()) { mutableStateOf("") }
    var seed by rememberSaveable { mutableStateOf("测试场景 1") }
    var includeOthers by rememberSaveable { mutableStateOf(false) }
    var history by remember(book.id, seed, includeOthers, entertainment, allBooks, totalBudget) { mutableStateOf<List<UIMessage>>(emptyList()) }
    var runtime by remember(book.id, seed, includeOthers, entertainment, allBooks, totalBudget) { mutableStateOf<Map<Uuid, LorebookEntryRuntimeState>>(emptyMap()) }
    var evaluation by remember(book.id, seed, includeOthers, entertainment, allBooks, totalBudget) { mutableStateOf<PromptInjectionEvaluation?>(null) }
    var preview by remember(book.id, seed, includeOthers, entertainment, allBooks, totalBudget) { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("世界书场景模拟", style = MaterialTheme.typography.titleMedium)
        Text("使用聊天同一评估器，不调用模型、不写入真实对话。每次推进一轮可验证持续激活和冷却；修改配置或种子会重置模拟。", style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(seed, { if (!busy) seed = it }, label = { Text("场景种子：相同种子与轮次可复现") }, modifier = Modifier.fillMaxWidth(), enabled = !busy)
        LorebookToggle("同时模拟其他已启用世界书（检查总预算）", includeOthers) { if (!busy) includeOthers = it }
        OutlinedTextField(text, { text = it }, label = { Text("本轮用户消息") }, minLines = 3, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(assistantText, { assistantText = it }, label = { Text("此前助手回复（可选，先于本轮用户消息加入）") }, modifier = Modifier.fillMaxWidth())
        Row {
            TextButton(enabled = !busy && history.size < 100, onClick = {
                busy = true; error = null
                val next = history + (if (assistantText.isNotBlank()) listOf(UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text(assistantText)))) else emptyList()) + UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text(text)))
                val books = if (includeOthers) allBooks else listOf(book)
                val previous = runtime
                scope.launch {
                    try {
                        val result = withContext(Dispatchers.Default) {
                            evaluateInjections(next, Assistant(lorebookIds = books.map { it.id }.toSet()), emptyList(), books,
                                runtimeStates = previous, entertainmentMode = entertainment, randomSeed = seed, totalTokenBudget = totalBudget,
                                currentUserTurn = next.count { it.role == MessageRole.USER })
                        }
                        val rendered = applyInjections(next, result.injections.sortedByDescending { it.priority }.groupBy { it.position })
                        history = next; runtime = result.runtimeStates; evaluation = result
                        preview = rendered.joinToString("\n\n") { "[${it.role}]\n${it.toText()}" }
                    } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                    catch (e: Exception) { error = e.message ?: "模拟失败" }
                    finally { busy = false }
                }
            }) { Text(if (busy) "模拟中…" else "推进到第 ${history.count { it.role == MessageRole.USER } + 1} 轮") }
            TextButton(enabled = !busy, onClick = { history = emptyList(); runtime = emptyMap(); evaluation = null; preview = "" }) { Text("重置") }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        evaluation?.let { result ->
            Text("采用 ${result.injections.size} 项 · 估算 ${result.injections.sumOf { me.rerere.rikkahub.data.ai.context.estimateTextTokens(it.content) }} Token")
            if (!entertainment) Text("普通模式不启用概率与冷却；下方为实际注入预览。")
            result.diagnostics.entries.forEach { entry ->
                val state = result.runtimeStates[entry.entryId]
                Text("${entry.lorebookName} / ${entry.entryName}：${entry.statusLabel()}\n命中：${entry.matchedTerms.joinToString()} · ${entry.estimatedTokens} Token · ${entry.position}\n${entry.detail.orEmpty()}" +
                    if (state != null) "\n持续至第 ${state.activeUntilTurn} 轮，冷却至第 ${state.cooldownUntilTurn} 轮" else "")
            }
            Text("最终消息预览", style = MaterialTheme.typography.titleSmall)
            androidx.compose.foundation.text.selection.SelectionContainer { Text(preview) }
        }
    }
}

private fun PromptInjectionDiagnosticEntry.statusLabel() = when(status) {
    LorebookEntryStatus.USED -> "已采用"
    LorebookEntryStatus.ACTIVE_FROM_PREVIOUS_TURN -> "持续激活"
    LorebookEntryStatus.NOT_MATCHED -> "未采用"
    LorebookEntryStatus.PROBABILITY_MISSED -> "概率未命中"
    LorebookEntryStatus.COOLDOWN -> "冷却中"
    LorebookEntryStatus.BUDGET_EXCEEDED -> "预算不足"
    LorebookEntryStatus.INVALID_EXPRESSION -> "条件错误"
}
