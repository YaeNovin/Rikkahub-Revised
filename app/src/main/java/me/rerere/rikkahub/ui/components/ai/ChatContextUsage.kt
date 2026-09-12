package me.rerere.rikkahub.ui.components.ai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.ai.context.RollingContextSummary
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.context.estimateActiveContextTokens
import me.rerere.rikkahub.data.ai.context.coveredMessageCount
import me.rerere.rikkahub.data.ai.context.estimatePartTokens
import me.rerere.rikkahub.data.ai.context.estimateMessageTokens
import me.rerere.rikkahub.data.ai.context.estimateTextTokens
import me.rerere.rikkahub.data.ai.context.estimateRequestCategories
import me.rerere.rikkahub.data.ai.context.contextHistoryFingerprint
import me.rerere.rikkahub.data.ai.context.calibrateRequestCategories
import me.rerere.ai.ui.UIMessagePart
import androidx.compose.runtime.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.TextButton
import me.rerere.rikkahub.ui.components.ui.AppearanceAlertDialog

enum class ContextTokenCategory(val label: String, val color: Color) {
    TEXT("文本", Color(0xFF3984D5)), IMAGE("图片", Color(0xFFCE8A28)),
    VIDEO("视频", Color(0xFF8A68C3)), AUDIO("音频", Color(0xFF279C91)),
    DOCUMENT("文档引用", Color(0xFF9A784A)), REASONING("思考内容", Color(0xFFD16B88)),
    TOOLS("工具调用与结果", Color(0xFF5A9D40)), SUMMARY("滚动摘要", Color(0xFF688697)),
    SYSTEM("系统提示与注入", Color(0xFFB55445)), FRAMING("消息结构", Color(0xFF8A8A8A)),
    TOOL_SCHEMA("工具定义", Color(0xFF398763)),
    OTHER("其他输入及服务端统计差额", Color(0xFF596273)),
}

/** Context usage reflects the rolling request, not the complete locally retained history. */
data class ChatContextUsage(
    val usedTokens: Int,
    val capacityTokens: Int?,
    val isEstimated: Boolean,
    val breakdown: Map<ContextTokenCategory, Int> = emptyMap(),
    val measuredInputTokens: Int? = null,
    val calibrated: Boolean = false,
    val basedOnRequest: Boolean = false,
) {
    val percentage: Int? = capacityTokens?.takeIf { it > 0 }?.let { capacity ->
        ((usedTokens.toLong() * 100) / capacity).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }
    val remainingTokens: Int? = capacityTokens?.takeIf { it > 0 }?.let { capacity ->
        (capacity - usedTokens).coerceAtLeast(0)
    }
}

internal fun calculateChatContextUsage(
    messages: List<UIMessage>,
    rollingContextSummary: RollingContextSummary? = null,
    capacityTokens: Int?,
    rawMessages: List<UIMessage> = messages,
    scopeKey: String? = null,
    modelId: String? = null,
    systemPrompt: String = "",
    includeReasoning: Boolean = false,
): ChatContextUsage {
    val covered = rollingContextSummary?.coveredMessageCount(messages) ?: 0
    val index = rawMessages.indices.reversed().firstOrNull { i ->
        val snapshot = rawMessages[i].requestContext ?: return@firstOrNull false
        i >= covered && (modelId == null || snapshot.modelId == modelId) && snapshot.scopeKey == scopeKey &&
            snapshot.historyFingerprint == contextHistoryFingerprint(rawMessages.take(i)) &&
            (snapshot.responseFingerprint == null || snapshot.responseFingerprint == contextHistoryFingerprint(listOf(rawMessages[i])))
    }
    val snapshot = index?.let { rawMessages[it].requestContext }
    val values = if (snapshot != null && index != null) {
        calibrateRequestCategories(snapshot.estimatedInput, snapshot.measuredPromptTokens).toMutableMap().apply {
            val tail = estimateRequestCategories(messages.drop(index), snapshot.includeReasoning)
            tail.forEach { (key, amount) ->
                val additional = (amount - (snapshot.sentAssistant[key] ?: 0)).coerceAtLeast(0)
                this[key] = ((this[key] ?: 0).toLong() + additional).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            }
        }
    } else estimateRequestCategories(messages.drop(covered), includeReasoning).toMutableMap().apply {
        if (covered > 0) this["SUMMARY"] = estimateTextTokens(rollingContextSummary!!.content)
        if (systemPrompt.isNotBlank()) this["SYSTEM"] = (this["SYSTEM"] ?: 0) + estimateTextTokens(systemPrompt) + 4
    }
    val totals = linkedMapOf<ContextTokenCategory, Int>()
    values.filterValues { it > 0 }.forEach { (key, amount) ->
        val category = runCatching { ContextTokenCategory.valueOf(key) }.getOrDefault(ContextTokenCategory.OTHER)
        totals[category] = ((totals[category] ?: 0).toLong() + amount).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }
    val total = totals.values.sumOf(Int::toLong).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    val measured = rawMessages.mapNotNull { it.requestContext }
        .filter { it.measuredPromptTokens != null && (modelId == null || it.modelId == modelId) }
        .maxByOrNull { it.measuredAt }?.measuredPromptTokens
        ?: rawMessages.asReversed().firstNotNullOfOrNull { message ->
            message.usage?.promptTokens?.takeIf { it > 0 && (modelId == null || message.modelId?.toString() == modelId) }
        }
    return ChatContextUsage(
        usedTokens = total,
        capacityTokens = capacityTokens?.takeIf { it > 0 },
        isEstimated = true,
        breakdown = totals,
        measuredInputTokens = measured,
        calibrated = snapshot?.measuredPromptTokens != null,
        basedOnRequest = snapshot != null,
    )
}

@Composable
fun ContextUsageSummary(
    usage: ChatContextUsage,
    modifier: Modifier = Modifier,
) {
    var detailsOpen by remember { mutableStateOf(false) }
    val used = formatTokenCount(usage.usedTokens)
    val value = when (val capacityTokens = usage.capacityTokens) {
        null -> stringResource(R.string.chat_context_usage_unknown, used)
        else -> stringResource(
            R.string.chat_context_usage_session,
            used,
            formatTokenCount(capacityTokens),
            usage.percentage ?: 0,
            formatTokenCount(usage.remainingTokens ?: 0),
        )
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clickable { detailsOpen = true }
            .padding(horizontal = 24.dp, vertical = 12.dp)
            .semantics { contentDescription = value },
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = stringResource(R.string.setting_provider_page_context_window),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = "当前上下文估算：$value",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        usage.measuredInputTokens?.let { tokens ->
            Text("最近请求输入：${formatTokenCount(tokens)} Token（供应商统计）", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        usage.capacityTokens?.let { capacityTokens ->
            Row(Modifier.fillMaxWidth().height(8.dp).background(MaterialTheme.colorScheme.surfaceContainerHighest)) {
                val denominator = maxOf(capacityTokens, usage.usedTokens, 1)
                usage.breakdown.filterValues { it > 0 }.forEach { (category, tokens) ->
                    Box(Modifier.weight(tokens.toFloat() / denominator).fillMaxHeight().background(category.color))
                }
                val free = (denominator - usage.breakdown.values.sum()).coerceAtLeast(0)
                if (free > 0) Spacer(Modifier.weight(free.toFloat() / denominator))
            }
        }
    }
    if (detailsOpen) AppearanceAlertDialog(onDismissRequest = { detailsOpen = false },
        title = { Text("上下文占用详情") },
        text = { Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(if (usage.calibrated) "根据实际发送的消息、系统注入和工具定义，并使用供应商输入 Token 校准。分类仍为估算；当前回复按可再次发送的内容增加，不累加隐藏思考的计费量。"
                else if (usage.basedOnRequest) "已根据实际发送的消息、系统注入和工具定义估算。供应商未返回输入 Token，暂不能进行服务端校准。"
                else "当前为本地估算，尚无适用于此上下文的完整请求快照。最近请求输入单独显示，不会在压缩、编辑或切换模型后直接当作当前占用。",
                style = MaterialTheme.typography.bodySmall)
            Text("图片、视频、音频消耗取决于模型和媒体处理方式；未发送草稿不计入。分类总数与主统计一致。", style = MaterialTheme.typography.bodySmall)
            usage.breakdown.filterValues { it > 0 }.forEach { (category, tokens) ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Box(Modifier.size(10.dp).background(category.color, androidx.compose.foundation.shape.CircleShape))
                    Text(category.label, Modifier.weight(1f))
                    Text("$tokens Token")
                }
            }
            if (usage.breakdown.isEmpty()) Text("暂无上下文占用")
        } }, confirmButton = { TextButton(onClick = { detailsOpen = false }) { Text("关闭") } })
}

private fun formatTokenCount(tokens: Int): String = java.text.NumberFormat.getIntegerInstance().format(tokens)
