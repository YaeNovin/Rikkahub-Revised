package me.rerere.rikkahub.data.model

import me.rerere.rikkahub.data.ai.context.estimateTextTokens
import kotlin.uuid.Uuid

enum class ModeActivationScope {
    ASSISTANT_DEFAULT,
    CONVERSATION,
    TEMPORARY,
}

data class ActiveMode(
    val injection: PromptInjection.ModeInjection,
    val scope: ModeActivationScope,
    val remainingTurns: Int? = null,
)

fun resolveActiveModes(
    modeInjections: List<PromptInjection.ModeInjection>,
    assistantModeIds: Set<Uuid>,
    conversationModeIds: Set<Uuid>,
    temporaryModes: Map<Uuid, Int>,
    currentUserTurn: Int,
): List<ActiveMode> {
    val definitions = modeInjections.filter { it.enabled }.associateBy { it.id }
    val active = linkedMapOf<Uuid, ActiveMode>()

    fun applyLayer(ids: Iterable<Uuid>, scope: ModeActivationScope, expires: Map<Uuid, Int> = emptyMap()) {
        val selectedIds = ids.toSet()
        modeInjections.filter { it.id in selectedIds }.mapNotNull { definitions[it.id] }.forEach { injection ->
            val group = injection.exclusiveGroup.trim()
            if (group.isNotEmpty()) {
                active.entries.removeAll { (_, selected) ->
                    selected.injection.exclusiveGroup.trim().equals(group, ignoreCase = true)
                }
            }
            active[injection.id] = ActiveMode(
                injection = injection,
                scope = scope,
                remainingTurns = expires[injection.id]?.let { (it - currentUserTurn).coerceAtLeast(0) },
            )
        }
    }

    applyLayer(assistantModeIds, ModeActivationScope.ASSISTANT_DEFAULT)
    applyLayer(conversationModeIds, ModeActivationScope.CONVERSATION)
    val activeTemporary = temporaryModes.filterValues { it >= currentUserTurn }
    applyLayer(activeTemporary.keys, ModeActivationScope.TEMPORARY, activeTemporary)
    return modeInjections.mapNotNull { active[it.id] }
}

fun selectExclusiveMode(
    selectedIds: Set<Uuid>,
    selectedId: Uuid,
    modeInjections: List<PromptInjection.ModeInjection>,
): Set<Uuid> {
    val selected = modeInjections.firstOrNull { it.id == selectedId } ?: return selectedIds + selectedId
    val group = selected.exclusiveGroup.trim()
    if (group.isEmpty()) return selectedIds + selectedId
    val peers = modeInjections
        .filter { it.exclusiveGroup.trim().equals(group, ignoreCase = true) }
        .mapTo(hashSetOf()) { it.id }
    return (selectedIds - peers) + selectedId
}

data class KeywordExpressionResult(
    val matched: Boolean,
    val matchedTerms: List<String>,
    val error: String? = null,
)

fun PromptInjection.RegexInjection.evaluateKeywords(context: String): KeywordExpressionResult {
    if (constantActive) return KeywordExpressionResult(true, emptyList())
    if (keywordExpression.isBlank()) {
        val matched = keywords.filter { matchesTerm(context, it) }
        return KeywordExpressionResult(matched.isNotEmpty(), matched)
    }
    return runCatching {
        KeywordExpressionParser(keywordExpression) { matchesTerm(context, it) }.parse()
    }.getOrElse { KeywordExpressionResult(false, emptyList(), it.message ?: "Invalid expression") }
}

private fun PromptInjection.RegexInjection.matchesTerm(context: String, term: String): Boolean {
    if (term.isBlank()) return false
    return if (useRegex) {
        runCatching {
            val options = if (caseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE)
            Regex(term, options).containsMatchIn(context)
        }.getOrDefault(false)
    } else {
        context.contains(term, ignoreCase = !caseSensitive)
    }
}

private class KeywordExpressionParser(
    expression: String,
    private val matcher: (String) -> Boolean,
) {
    private val tokens = tokenize(expression)
    private var index = 0
    private val matchedTerms = linkedSetOf<String>()

    fun parse(): KeywordExpressionResult {
        require(tokens.isNotEmpty()) { "Expression is empty" }
        val matched = parseOr()
        require(index == tokens.size) { "Unexpected token: ${tokens[index]}" }
        return KeywordExpressionResult(matched, matchedTerms.toList())
    }

    private fun parseOr(): Boolean {
        var value = parseAnd()
        while (peekOperator("OR")) {
            index++
            val right = parseAnd()
            value = value || right
        }
        return value
    }

    private fun parseAnd(): Boolean {
        var value = parseUnary()
        while (peekOperator("AND")) {
            index++
            val right = parseUnary()
            value = value && right
        }
        return value
    }

    private fun parseUnary(): Boolean {
        if (peekOperator("NOT")) {
            index++
            return !parseUnary()
        }
        if (tokens.getOrNull(index) == "(") {
            index++
            val value = parseOr()
            require(tokens.getOrNull(index) == ")") { "Missing closing parenthesis" }
            index++
            return value
        }
        val term = tokens.getOrNull(index) ?: error("Missing keyword")
        require(term !in listOf(")", "AND", "OR")) { "Missing keyword before $term" }
        index++
        return matcher(term).also { if (it) matchedTerms += term }
    }

    private fun peekOperator(value: String): Boolean =
        tokens.getOrNull(index)?.equals(value, ignoreCase = true) == true

    private fun tokenize(value: String): List<String> {
        val result = mutableListOf<String>()
        var cursor = 0
        while (cursor < value.length) {
            when {
                value[cursor].isWhitespace() -> cursor++
                value[cursor] == '(' || value[cursor] == ')' -> result += value[cursor++].toString()
                value[cursor] == '"' -> {
                    val end = value.indexOf('"', cursor + 1)
                    require(end >= 0) { "Unclosed quoted keyword" }
                    result += value.substring(cursor + 1, end)
                    cursor = end + 1
                }
                else -> {
                    val start = cursor
                    while (cursor < value.length && !value[cursor].isWhitespace() && value[cursor] !in "()") cursor++
                    result += value.substring(start, cursor)
                }
            }
        }
        return result
    }
}

data class PromptInjectionDiagnosticEntry(
    val lorebookId: Uuid,
    val lorebookName: String,
    val entryId: Uuid,
    val entryName: String,
    val matchedTerms: List<String>,
    val status: LorebookEntryStatus,
    val position: InjectionPosition,
    val estimatedTokens: Int,
    val detail: String? = null,
)

enum class LorebookEntryStatus {
    USED,
    ACTIVE_FROM_PREVIOUS_TURN,
    NOT_MATCHED,
    PROBABILITY_MISSED,
    COOLDOWN,
    BUDGET_EXCEEDED,
    INVALID_EXPRESSION,
}

data class PromptInjectionDiagnostics(
    val userTurn: Int,
    val entries: List<PromptInjectionDiagnosticEntry>,
    val totalEstimatedTokens: Int,
)

data class PromptInjectionEvaluation(
    val injections: List<PromptInjection>,
    val runtimeStates: Map<Uuid, LorebookEntryRuntimeState>,
    val diagnostics: PromptInjectionDiagnostics,
)

internal fun passesDeterministicProbability(entryId: Uuid, userTurn: Int, probability: Int): Boolean {
    if (probability >= 100) return true
    if (probability <= 0) return false
    val bucket = ((entryId.hashCode() * 31L + userTurn * 17L) and Long.MAX_VALUE) % 100
    return bucket < probability
}

internal fun trimToEstimatedTokens(content: String, maxTokens: Int): String {
    if (maxTokens <= 0) return ""
    if (estimateTextTokens(content) <= maxTokens) return content
    var low = 0
    var high = content.length
    while (low < high) {
        val mid = (low + high + 1) / 2
        if (estimateTextTokens(content.take(mid)) <= maxTokens) low = mid else high = mid - 1
    }
    return content.take(low).trimEnd()
}
