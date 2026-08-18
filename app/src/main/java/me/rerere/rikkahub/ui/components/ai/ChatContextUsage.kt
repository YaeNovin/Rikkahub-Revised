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
import me.rerere.rikkahub.data.ai.context.createRollingContextPlan
import me.rerere.rikkahub.data.ai.context.estimateTextTokens
import me.rerere.rikkahub.data.ai.context.effectiveRollingContextThreshold
import me.rerere.rikkahub.data.ai.context.coveredMessageCount
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.context.estimateContextTokens

/** Context usage reflects the rolling request, not the complete locally retained history. */
data class ChatContextUsage(
    val usedTokens: Int,
    val capacityTokens: Int?,
    val isEstimated: Boolean,
) {
    val percentage: Int? = capacityTokens?.let { capacity ->
        ((usedTokens.toLong() * 100) / capacity).toInt()
    }
    val remainingTokens: Int? = capacityTokens?.let { capacity ->
        (capacity - usedTokens).coerceAtLeast(0)
    }
}

internal fun calculateChatContextUsage(
    messages: List<UIMessage>,
    rollingContextSummary: RollingContextSummary? = null,
    rollingContextThresholdTokens: Int = 0,
    capacityTokens: Int?,
): ChatContextUsage {
    val coveredCount = rollingContextSummary?.coveredMessageCount(messages) ?: 0
    val validSummary = rollingContextSummary?.takeIf { coveredCount > 0 }
    val plannedRefresh = createRollingContextPlan(
        messages = messages,
        storedSummary = validSummary,
        thresholdTokens = effectiveRollingContextThreshold(rollingContextThresholdTokens),
    )
    val summaryTokens = when {
        plannedRefresh != null -> plannedRefresh.targetTokens
        validSummary != null -> estimateTextTokens(validSummary.content)
        else -> 0
    }
    val windowMessages = when {
        plannedRefresh != null -> messages.drop(plannedRefresh.sourceMessageIds.size)
        else -> messages.drop(coveredCount)
    }
    return ChatContextUsage(
        usedTokens = summaryTokens + estimateContextTokens(windowMessages),
        capacityTokens = capacityTokens?.takeIf { it > 0 },
        isEstimated = true,
    )
}

@Composable
fun ContextUsageSummary(
    usage: ChatContextUsage,
    modifier: Modifier = Modifier,
) {
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
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        usage.capacityTokens?.let { capacityTokens ->
            LinearProgressIndicator(
                progress = { (usage.usedTokens.toFloat() / capacityTokens).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

private fun formatTokenCount(tokens: Int): String = when {
    tokens < 1_000 -> tokens.toString()
    tokens < 10_000 -> "${tokens / 1_000}.${(tokens % 1_000) / 100}k"
    else -> "${tokens / 1_000}k"
}
