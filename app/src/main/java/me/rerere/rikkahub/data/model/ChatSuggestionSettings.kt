package me.rerere.rikkahub.data.model

import kotlinx.serialization.Serializable
import me.rerere.ai.core.TokenUsage
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_SUGGESTION_PROMPT
import me.rerere.rikkahub.data.datastore.*
import java.util.Locale
import kotlin.uuid.Uuid

@Serializable enum class SuggestionTrigger { AUTOMATIC, MANUAL, DISABLED }
@Serializable enum class SuggestionFeedbackReason { IRRELEVANT, REPETITIVE, TOO_LONG, OUT_OF_CHARACTER }

@Serializable data class SuggestionOptions(
    val trigger: SuggestionTrigger = SuggestionTrigger.AUTOMATIC,
    val intervalSeconds: Int = 0,
    val contextBudget: Int = 3000,
    val includeSummary: Boolean = true,
    val includeMemory: Boolean = false,
    val goal: String = "",
    val balancedCategories: Boolean = true,
    val previewBeforeInsert: Boolean = true,
    val imageSuggestions: Boolean = false,
    val parameterForms: Boolean = true,
)

@Serializable data class ChatSuggestionConfig(
    val modelId: Uuid? = null,
    val count: Int = 5,
    val maxLength: Int = 80,
    val style: ChatSuggestionStyle = ChatSuggestionStyle.BALANCED,
    val insertMode: SuggestionInsertMode = SuggestionInsertMode.REPLACE,
    val displayMode: ChatSuggestionDisplayMode = ChatSuggestionDisplayMode.AUTO,
    val prompt: String = DEFAULT_SUGGESTION_PROMPT,
    val options: SuggestionOptions = SuggestionOptions(),
) {
    fun bounded() = copy(count = count.coerceIn(1, 10), maxLength = maxLength.coerceIn(10, 200),
        options = options.copy(intervalSeconds = options.intervalSeconds.coerceIn(0, 3600),
            contextBudget = options.contextBudget.coerceIn(500, 12000), goal = options.goal.take(2000)))
}

@Serializable data class SuggestionTarget(val messageId: Uuid, val selectedText: String = "")
@Serializable data class SuggestionRunInfo(
    val modelName: String, val modelId: String, val completedAt: Long, val elapsedMillis: Long,
    val usage: TokenUsage? = null, val contextSources: List<String> = emptyList(), val logId: String? = null,
    val error: String? = null,
)
@Serializable data class SuggestionBatch(
    val items: List<ChatSuggestionItem> = emptyList(), val target: SuggestionTarget? = null,
    val info: SuggestionRunInfo? = null,
)
@Serializable data class SuggestionSession(
    val settings: ChatSuggestionConfig? = null,
    val paused: Boolean = false,
    val collapsed: Boolean = false,
    val pinnedIds: Set<String> = emptySet(),
    val target: SuggestionTarget? = null,
    val previous: SuggestionBatch? = null,
    val lastAttemptAt: Long = 0,
    val info: SuggestionRunInfo? = null,
)
@Serializable data class SuggestionFeedback(
    val text: String, val reason: SuggestionFeedbackReason, val category: ChatSuggestionCategory,
)

fun Settings.defaultSuggestionConfig() = ChatSuggestionConfig(suggestionModelId, suggestionCount, suggestionMaxLength,
    suggestionStyle, suggestionInsertMode, suggestionDisplayMode, suggestionPrompt,
    suggestionOptions.copy(trigger = if (enableSuggestion) suggestionOptions.trigger else SuggestionTrigger.DISABLED)).bounded()

fun Settings.withSuggestionConfig(config: ChatSuggestionConfig): Settings = config.bounded().let { value -> copy(
    enableSuggestion = value.options.trigger != SuggestionTrigger.DISABLED,
    suggestionModelId = value.modelId, suggestionCount = value.count, suggestionMaxLength = value.maxLength,
    suggestionStyle = value.style, suggestionInsertMode = value.insertMode, suggestionDisplayMode = value.displayMode,
    suggestionPrompt = value.prompt, suggestionOptions = value.options,
) }

fun Conversation.suggestionConfig(settings: Settings): ChatSuggestionConfig =
    (suggestionSession.settings ?: settings.getAssistantById(assistantId)?.suggestionSettings ?: settings.defaultSuggestionConfig()).bounded()

fun Conversation.suggestionContext(): Conversation {
    val target = suggestionSession.target ?: return this
    val index = messageNodes.indexOfFirst { it.currentMessage.id == target.messageId }
    return copy(messageNodes = if (index < 0) emptyList() else messageNodes.take(index + 1))
}

fun suggestionTextKey(text: String): String = text.lowercase(Locale.ROOT).let { lower ->
    lower.filter { it.isLetterOrDigit() }.ifBlank { lower.trim() }
}

fun Conversation.canAutomaticallySuggest(config: ChatSuggestionConfig, now: Long): Boolean =
    config.options.trigger == SuggestionTrigger.AUTOMATIC && !suggestionSession.paused &&
        (now >= suggestionSession.lastAttemptAt && now - suggestionSession.lastAttemptAt >= config.options.intervalSeconds * 1000L)

fun suggestionsSimilar(first: ChatSuggestionItem, second: ChatSuggestionItem): Boolean {
    if (first.action != second.action) return false
    val a = suggestionTextKey(first.payload.ifBlank { first.text })
    val b = suggestionTextKey(second.payload.ifBlank { second.text })
    if (a == b) return true
    if (a.length < 8 || b.length < 8) return false
    val left = a.windowed(2).toSet()
    val right = b.windowed(2).toSet()
    return left.intersect(right).size.toDouble() / left.union(right).size.coerceAtLeast(1) >= 0.86
}

fun selectSuggestionBatch(candidates: List<ChatSuggestionItem>, retained: List<ChatSuggestionItem>, count: Int,
    balanced: Boolean, feedback: List<SuggestionFeedback>): List<ChatSuggestionItem> {
    val result = retained.take(10).toMutableList()
    val pool = candidates.filterNot { candidate -> feedback.any {
        suggestionTextKey(it.text) == suggestionTextKey(candidate.payload.ifBlank { candidate.text })
    } }.toMutableList()
    while (pool.isNotEmpty() && result.size < count.coerceIn(1, 10)) {
        val next = if (balanced) pool.minBy { item -> result.count { it.category == item.category } } else pool.first()
        pool.remove(next)
        if (result.none { suggestionsSimilar(it, next) }) result.add(next)
    }
    return result
}
