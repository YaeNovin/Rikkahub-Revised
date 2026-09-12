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

fun PromptInjection.RegexInjection.evaluateKeywords(context: String): KeywordExpressionResult = try {
    if (constantActive) KeywordExpressionResult(true, emptyList())
    else if (keywordExpression.isBlank()) {
        val matched = keywords.filter { matchesTerm(context, it) }
        KeywordExpressionResult(matched.isNotEmpty(), matched)
    } else KeywordExpressionParser(keywordExpression) { matchesTerm(context, it) }.parse()
} catch (e: IllegalArgumentException) {
    KeywordExpressionResult(false, emptyList(), e.message ?: "无效触发条件")
}

private val regexCache = object : LinkedHashMap<Pair<String, Boolean>, Regex>(64, .75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Pair<String, Boolean>, Regex>?) = size > 128
}

private fun PromptInjection.RegexInjection.matchesTerm(context: String, term: String): Boolean {
    if (term.isBlank()) return false
    if (!useRegex) return context.contains(term, ignoreCase = !caseSensitive)
    val regex = synchronized(regexCache) {
        regexCache.getOrPut(term to caseSensitive) {
            try { Regex(term, if (caseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE)) }
            catch (e: IllegalArgumentException) { throw IllegalArgumentException("无效正则「$term」：${e.message}") }
        }
    }
    return regex.containsMatchIn(context)
}

internal fun quoteLorebookKeyword(value: String): String =
    "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

private data class KeywordToken(val text: String, val literal: Boolean = false)

private val expressionCache = object : LinkedHashMap<String, List<KeywordToken>>(64, .75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<KeywordToken>>?) = size > 128
}

private class KeywordExpressionParser(expression: String, private val matcher: (String) -> Boolean) {
    private val tokens = synchronized(expressionCache) { expressionCache.getOrPut(expression) { tokenize(expression) } }
    private var index = 0
    private var depth = 0
    private val matchedTerms = linkedSetOf<String>()

    fun parse(): KeywordExpressionResult {
        require(tokens.isNotEmpty()) { "表达式不能为空" }
        val matched = parseOr()
        require(index == tokens.size) { "多余的词项：${tokens.getOrNull(index)?.text}" }
        return KeywordExpressionResult(matched, matchedTerms.toList())
    }

    private fun operator(value: String): Boolean = tokens.getOrNull(index)?.let {
        !it.literal && it.text.equals(value, true)
    } == true

    private fun parseOr(): Boolean {
        var value = parseAnd()
        while (operator("OR")) { index++; val right = parseAnd(); value = value || right }
        return value
    }

    private fun parseAnd(): Boolean {
        var value = parseUnary()
        while (operator("AND")) { index++; val right = parseUnary(); value = value && right }
        return value
    }

    private fun parseUnary(): Boolean {
        require(++depth <= 64) { "条件嵌套不能超过 64 层" }
        try {
            if (operator("NOT")) { index++; return !parseUnary() }
            if (operator("(")) {
                index++
                val value = parseOr()
                require(operator(")")) { "缺少右括号" }
                index++
                return value
            }
            val term = tokens.getOrNull(index) ?: throw IllegalArgumentException("缺少关键词")
            require(term.literal || term.text.uppercase() !in listOf(")", "AND", "OR")) { "此处需要关键词：${term.text}" }
            require(term.text.isNotBlank()) { "关键词不能为空" }
            index++
            return matcher(term.text).also { if (it) matchedTerms += term.text }
        } finally { depth-- }
    }

    private fun tokenize(value: String): List<KeywordToken> {
        require(value.length <= 16384) { "表达式过长" }
        val result = mutableListOf<KeywordToken>()
        var cursor = 0
        while (cursor < value.length) {
            when {
                value[cursor].isWhitespace() -> cursor++
                value[cursor] in "()" -> result += KeywordToken(value[cursor++].toString())
                value[cursor] == '"' -> {
                    cursor++
                    val text = StringBuilder()
                    var closed = false
                    while (cursor < value.length) {
                        val ch = value[cursor++]
                        if (ch == '"') { closed = true; break }
                        if (ch == '\\' && cursor < value.length && value[cursor] in "\\\"") text.append(value[cursor++])
                        else text.append(ch)
                    }
                    require(closed) { "关键词引号未闭合" }
                    result += KeywordToken(text.toString(), true)
                }
                else -> {
                    val start = cursor
                    while (cursor < value.length && !value[cursor].isWhitespace() && value[cursor] !in "()") cursor++
                    result += KeywordToken(value.substring(start, cursor))
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
    val remainingActiveTurns: Int = 0,
    val remainingCooldownTurns: Int = 0,
    val injectedContent: String? = null,
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

internal fun passesDeterministicProbability(entryId: Uuid, userTurn: Int, probability: Int, seed: String = ""): Boolean {
    if (probability >= 100) return true
    if (probability <= 0) return false
    val bucket = kotlin.random.Random("$seed:$entryId:$userTurn".hashCode()).nextInt(100)
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
    if (low > 0 && low < content.length && content[low - 1].isHighSurrogate() && content[low].isLowSurrogate()) low--
    return content.take(low).trimEnd()
}
