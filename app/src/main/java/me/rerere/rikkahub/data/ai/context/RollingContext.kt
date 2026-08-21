package me.rerere.rikkahub.data.ai.context

import kotlinx.serialization.Serializable
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
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

/** Revalidates a completed compaction against edits or branch changes made while it ran. */
fun RollingContextPlan.isStillApplicableTo(messages: List<UIMessage>): Boolean =
    sourceMessageIds.size <= messages.size &&
        messages.take(sourceMessageIds.size).map(UIMessage::id) == sourceMessageIds

const val MIN_ROLLING_CONTEXT_THRESHOLD_TOKENS = 4_000
const val DEFAULT_ROLLING_CONTEXT_THRESHOLD_TOKENS = 32_000

/**
 * Normalizes legacy settings and keeps automatic compaction below the model's hard input limit.
 * The reserve covers the next answer plus system prompts, tools and provider framing that are not
 * represented by the visible conversation messages.
 */
fun effectiveRollingContextThreshold(
    configuredThresholdTokens: Int,
    modelContextWindowTokens: Int? = null,
    maxOutputTokens: Int? = null,
): Int {
    val configuredThreshold = configuredThresholdTokens.takeIf { it > 0 }
        ?: DEFAULT_ROLLING_CONTEXT_THRESHOLD_TOKENS
    val contextWindow = modelContextWindowTokens?.takeIf { it > 0 } ?: return configuredThreshold
    val protocolReserve = maxOf(MIN_CONTEXT_RESERVE_TOKENS, contextWindow / 10)
    val outputReserve = maxOutputTokens
        ?.coerceAtLeast(0)
        ?.coerceAtMost(contextWindow)
        ?: (contextWindow / 10)
    val safeThreshold = maxOf(
        contextWindow / 2,
        contextWindow - protocolReserve - outputReserve,
    ).coerceAtLeast(1)
    return minOf(configuredThreshold, safeThreshold)
}

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
    val workingTokens = estimateActiveContextTokens(messages, previousSummary)
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

/**
 * Estimates every token-bearing part retained in a message. Provider completion usage is used as
 * a floor because it also captures hidden reasoning and protocol details absent from UI parts.
 */
@Suppress("DEPRECATION")
fun estimateMessageTokens(message: UIMessage): Int {
    val visibleTokens = message.parts.sumOf(::estimatePartTokens)
    val producedTokens = message.usage?.completionTokens ?: 0
    return maxOf(visibleTokens, producedTokens) + MESSAGE_OVERHEAD_TOKENS
}

/**
 * Token estimate for the next provider request. A valid summary replaces its covered prefix; the
 * latest provider prompt usage calibrates system prompts, documents and tool schemas that local
 * tokenization may underestimate.
 */
fun estimateActiveContextTokens(
    messages: List<UIMessage>,
    storedSummary: RollingContextSummary?,
): Int {
    val coveredCount = storedSummary?.coveredMessageCount(messages) ?: 0
    val validSummary = storedSummary?.takeIf { coveredCount > 0 }
    val localEstimate = validSummary.orEmptySummaryTokens() +
        estimateContextTokens(messages.drop(coveredCount))
    val measuredMessageIndex = messages.indexOfLast { message ->
        message.usage?.promptTokens?.let { it > 0 } == true &&
            message.id !in validSummary?.sourceMessageIds.orEmpty()
    }
    val measuredEstimate = measuredMessageIndex.takeIf { it >= 0 }?.let { index ->
        val usage = messages[index].usage ?: return@let null
        usage.promptTokens + usage.completionTokens + estimateContextTokens(messages.drop(index + 1))
    }
    return maxOf(localEstimate, measuredEstimate ?: 0)
}

fun estimateTextTokens(text: String): Int {
    if (text.isBlank()) return 0
    val cjkCharacters = text.count(::isCjkCharacter)
    val otherCharacters = text.length - cjkCharacters
    return cjkCharacters + (otherCharacters + 3) / 4
}

/** Splits compression input without dropping content or exceeding the estimated token budget. */
fun splitTextForTokenBudget(text: String, maxTokens: Int): List<String> {
    require(maxTokens > 0) { "maxTokens must be positive" }
    if (text.isEmpty()) return emptyList()
    if (estimateTextTokens(text) <= maxTokens) return listOf(text)

    val chunks = mutableListOf<String>()
    val current = StringBuilder()
    var currentTokens = 0

    fun flushCurrent() {
        if (current.isEmpty()) return
        chunks += current.toString()
        current.clear()
        currentTokens = 0
    }

    fun appendPiece(piece: String) {
        val pieceTokens = estimateTextTokens(piece)
        if (pieceTokens <= maxTokens) {
            if (currentTokens + pieceTokens > maxTokens) flushCurrent()
            current.append(piece)
            currentTokens += pieceTokens
            return
        }

        flushCurrent()
        var start = 0
        while (start < piece.length) {
            var low = start + 1
            var high = piece.length
            var bestEnd = low
            while (low <= high) {
                val middle = low + (high - low) / 2
                if (estimateTextTokens(piece.substring(start, middle)) <= maxTokens) {
                    bestEnd = middle
                    low = middle + 1
                } else {
                    high = middle - 1
                }
            }
            if (bestEnd < piece.length &&
                bestEnd > start &&
                piece[bestEnd - 1].isHighSurrogate()
            ) {
                bestEnd--
            }
            if (bestEnd <= start) bestEnd = (start + 1).coerceAtMost(piece.length)
            chunks += piece.substring(start, bestEnd)
            start = bestEnd
        }
    }

    var start = 0
    while (start < text.length) {
        val newline = text.indexOf('\n', start)
        val end = if (newline >= 0) newline + 1 else text.length
        appendPiece(text.substring(start, end))
        start = end
    }
    flushCurrent()
    return chunks
}

private fun RollingContextSummary?.orEmptySummaryTokens(): Int = this?.content?.let(::estimateTextTokens) ?: 0

@Suppress("DEPRECATION")
private fun estimatePartTokens(part: UIMessagePart): Int = when (part) {
    is UIMessagePart.Text -> estimateTextTokens(part.text)
    is UIMessagePart.Reasoning -> estimateTextTokens(part.reasoning)
    is UIMessagePart.Tool -> estimateTextTokens(part.toolCallId) +
        estimateTextTokens(part.toolName) +
        estimateTextTokens(part.input) +
        part.output.sumOf(::estimatePartTokens)
    is UIMessagePart.ServerTool -> estimateTextTokens(part.toolCallId) +
        estimateTextTokens(part.toolName) +
        estimateTextTokens(part.input?.toString().orEmpty()) +
        estimateTextTokens(part.output?.toString().orEmpty())
    is UIMessagePart.ToolCall -> estimateTextTokens(part.toolCallId) +
        estimateTextTokens(part.toolName) +
        estimateTextTokens(part.arguments)
    is UIMessagePart.ToolResult -> estimateTextTokens(part.toolCallId) +
        estimateTextTokens(part.toolName) +
        estimateTextTokens(part.content.toString()) +
        estimateTextTokens(part.arguments.toString())
    is UIMessagePart.Image -> IMAGE_TOKEN_ESTIMATE
    is UIMessagePart.Video -> VIDEO_TOKEN_ESTIMATE
    is UIMessagePart.Audio -> AUDIO_TOKEN_ESTIMATE
    is UIMessagePart.Document -> DOCUMENT_REFERENCE_TOKEN_ESTIMATE
    UIMessagePart.Search -> 0
}

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
    // A request window should begin at the next complete user turn. Expanding backwards here can
    // pull a very large document turn back into the request and defeat compression entirely.
    while (startIndex < size && this[startIndex].role != MessageRole.USER) {
        startIndex += 1
    }
    return size - startIndex
}

private fun isCjkCharacter(character: Char): Boolean = character in '\u3040'..'\u30ff' ||
    character in '\u3400'..'\u4dbf' ||
    character in '\u4e00'..'\u9fff'

private const val MESSAGE_OVERHEAD_TOKENS = 4
private const val MIN_CONTEXT_RESERVE_TOKENS = 1_024
private const val DOCUMENT_REFERENCE_TOKEN_ESTIMATE = 32
private const val IMAGE_TOKEN_ESTIMATE = 1_024
private const val AUDIO_TOKEN_ESTIMATE = 4_096
private const val VIDEO_TOKEN_ESTIMATE = 8_192
private const val MIN_ROLLING_CONTEXT_MESSAGES = 1
private const val MIN_RECENT_MESSAGE_COUNT = 1
private const val RECENT_WINDOW_RATIO = 0.55f
private const val SUMMARY_TARGET_DIVISOR = 4
private const val MIN_SUMMARY_TOKENS = 512
private const val MAX_SUMMARY_TOKENS = 8_000
