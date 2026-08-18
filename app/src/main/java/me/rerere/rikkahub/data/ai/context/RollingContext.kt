package me.rerere.rikkahub.data.ai.context

import kotlinx.serialization.Serializable
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import kotlin.uuid.Uuid

/** A persisted summary of a stable prefix of the active conversation branch. */
@Serializable
data class RollingContextSummary(
    val content: String,
    val sourceMessageIds: List<Uuid>,
    val updatedAtMillis: Long,
)

data class RollingContextPlan(
    val previousSummary: RollingContextSummary?,
    val messagesToSummarize: List<UIMessage>,
    val sourceMessageIds: List<Uuid>,
    val targetTokens: Int,
)

const val MIN_ROLLING_CONTEXT_THRESHOLD_TOKENS = 4_000
const val DEFAULT_ROLLING_CONTEXT_THRESHOLD_TOKENS = 32_000

/** Normalizes legacy disabled settings now that rolling context is always active. */
fun effectiveRollingContextThreshold(configuredThresholdTokens: Int): Int =
    configuredThresholdTokens.takeIf { it > 0 } ?: DEFAULT_ROLLING_CONTEXT_THRESHOLD_TOKENS

/**
 * The persisted summary is valid only when it still covers the exact current branch prefix.
 * This makes edits, deletions, and branch changes rebuild the summary from the new branch.
 */
fun RollingContextSummary.coveredMessageCount(messages: List<UIMessage>): Int {
    if (sourceMessageIds.isEmpty() || sourceMessageIds.size > messages.size) return 0
    return sourceMessageIds.size.takeIf { count ->
        messages.take(count).map(UIMessage::id) == sourceMessageIds
    } ?: 0
}

fun createRollingContextPlan(
    messages: List<UIMessage>,
    storedSummary: RollingContextSummary?,
    thresholdTokens: Int,
    force: Boolean = false,
    targetTokensOverride: Int? = null,
): RollingContextPlan? {
    if (messages.size <= MIN_ROLLING_CONTEXT_MESSAGES) return null

    val effectiveThreshold = effectiveRollingContextThreshold(thresholdTokens)

    val coveredCount = storedSummary?.coveredMessageCount(messages) ?: 0
    val previousSummary = storedSummary?.takeIf { coveredCount > 0 }
    val unsummarizedMessages = messages.drop(coveredCount)
    val workingTokens = estimateContextTokens(unsummarizedMessages) +
        previousSummary.orEmptySummaryTokens()
    if (!force && workingTokens < effectiveThreshold) return null

    val keepCount = unsummarizedMessages.recentWindowCount(
        tokenBudget = if (force) 0 else (effectiveThreshold * RECENT_WINDOW_RATIO).toInt(),
    )
    val messagesToSummarize = unsummarizedMessages.dropLast(keepCount)
    if (messagesToSummarize.isEmpty()) return null

    return RollingContextPlan(
        previousSummary = previousSummary,
        messagesToSummarize = messagesToSummarize,
        sourceMessageIds = messages.take(coveredCount + messagesToSummarize.size).map(UIMessage::id),
        targetTokens = targetTokensOverride ?: (effectiveThreshold / SUMMARY_TARGET_DIVISOR)
            .coerceIn(MIN_SUMMARY_TOKENS, MAX_SUMMARY_TOKENS),
    )
}

fun estimateContextTokens(messages: List<UIMessage>): Int = messages.sumOf(::estimateMessageTokens)

fun estimateMessageTokens(message: UIMessage): Int = estimateTextTokens(message.toText()) + MESSAGE_OVERHEAD_TOKENS

fun estimateTextTokens(text: String): Int {
    if (text.isBlank()) return 0
    val cjkCharacters = text.count(::isCjkCharacter)
    val otherCharacters = text.length - cjkCharacters
    return cjkCharacters + (otherCharacters + 3) / 4
}

private fun RollingContextSummary?.orEmptySummaryTokens(): Int = this?.content?.let(::estimateTextTokens) ?: 0

/**
 * Returns the first message index retained when a summary cannot be refreshed.
 * The caller retains its local history, but sends only this recent window to the model.
 */
fun rollingContextWindowStartIndex(
    messages: List<UIMessage>,
    thresholdTokens: Int,
): Int {
    val tokenBudget = (effectiveRollingContextThreshold(thresholdTokens) * RECENT_WINDOW_RATIO).toInt()
    return messages.size - messages.recentWindowCount(tokenBudget)
}

private fun List<UIMessage>.recentWindowCount(tokenBudget: Int): Int {
    var count = 0
    var tokens = 0
    for (index in lastIndex downTo 0) {
        val nextTokens = estimateMessageTokens(this[index])
        if (count >= MIN_RECENT_MESSAGE_COUNT && tokens + nextTokens > tokenBudget) break
        count += 1
        tokens += nextTokens
    }

    var startIndex = (size - count).coerceAtLeast(0)
    // Tool outputs must retain the user turn that initiated their call.
    while (startIndex > 0 && this[startIndex].role != MessageRole.USER) {
        startIndex -= 1
    }
    return size - startIndex
}

private fun isCjkCharacter(character: Char): Boolean = character in '\u3040'..'\u30ff' ||
    character in '\u3400'..'\u4dbf' ||
    character in '\u4e00'..'\u9fff'

private const val MESSAGE_OVERHEAD_TOKENS = 4
private const val MIN_ROLLING_CONTEXT_MESSAGES = 4
private const val MIN_RECENT_MESSAGE_COUNT = 4
private const val RECENT_WINDOW_RATIO = 0.55f
private const val SUMMARY_TARGET_DIVISOR = 4
private const val MIN_SUMMARY_TOKENS = 512
private const val MAX_SUMMARY_TOKENS = 8_000
