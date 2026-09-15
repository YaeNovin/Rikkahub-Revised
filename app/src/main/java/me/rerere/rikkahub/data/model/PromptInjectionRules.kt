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
    val score: Int = matchedTerms.size,
)

fun PromptInjection.RegexInjection.evaluateKeywords(context: String, resolveTerm: (String) -> String = { it }, budget: LorebookMatchBudget = LorebookMatchBudget()): KeywordExpressionResult = try {
    val v3Regex = sourceFormat == LorebookSourceFormat.CHARACTER_CARD_V3 && useRegex
    if (constantActive && !v3Regex) KeywordExpressionResult(true, emptyList())
    else if (v3Regex || keywordExpression.isBlank() || keywordExpression.trimStart().startsWith("OR \"") && keywords.isNotEmpty()) {
        val matched = keywords.filter { matchesTerm(context, resolveTerm(it), budget) }
        KeywordExpressionResult(matched.isNotEmpty(), matched)
    } else KeywordExpressionParser(keywordExpression) { matchesTerm(context, resolveTerm(it), budget) }.parse()
} catch (e: IllegalArgumentException) {
    KeywordExpressionResult(false, emptyList(), e.message ?: "无效触发条件")
} catch (_: StackOverflowError) {
    KeywordExpressionResult(false, emptyList(), "正则回溯过深，请简化表达式")
}

/** Java regex is synchronous; a coroutine timeout alone cannot stop catastrophic backtracking. */
class LorebookMatchBudget(private val maxReads: Int = 2_000_000) {
    private var reads = 0
    internal fun read() { require(++reads <= maxReads) { "正则匹配超过本次计算上限，请缩小扫描范围或简化规则" } }
}

private class BudgetedText(private val text: String, private val budget: LorebookMatchBudget, private val start: Int = 0, private val end: Int = text.length) : CharSequence {
    override val length get() = end - start
    override fun get(index: Int): Char { budget.read(); return text[start + index] }
    override fun subSequence(startIndex: Int, endIndex: Int): CharSequence = BudgetedText(text, budget, start + startIndex, start + endIndex)
    override fun toString(): String = text.substring(start, end)
}

private val regexCache = object : LinkedHashMap<Pair<String, Set<RegexOption>>, Regex>(64, .75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Pair<String, Set<RegexOption>>, Regex>?) = size > 128
}

/**
 * Finds the closing slash of a JavaScript-style `/pattern/flags` literal.
 * A simple lastIndexOf('/') is incorrect for patterns containing an escaped
 * slash (or a slash inside a character class), and used to truncate imported
 * SillyTavern keys such as `/foo\\/bar/i`.
 */
private fun findRegexLiteralEnd(term: String): Int {
    if (term.length < 2 || term[0] != '/') return -1
    var escaped = false
    var inClass = false
    for (index in 1 until term.length) {
        val ch = term[index]
        if (escaped) {
            escaped = false
            continue
        }
        if (ch == '\\') {
            escaped = true
            continue
        }
        if (ch == '[') {
            inClass = true
            continue
        }
        if (ch == ']' && inClass) {
            inClass = false
            continue
        }
        if (ch == '/' && !inClass) return index
    }
    return -1
}

private fun PromptInjection.RegexInjection.matchesTerm(context: String, term: String, budget: LorebookMatchBudget): Boolean {
    if (term.isBlank()) return false
    var pattern = term
    var options = if (caseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE)
    val slash = findRegexLiteralEnd(term)
    val literal = sourceFormat != LorebookSourceFormat.NATIVE && (sourceFormat != LorebookSourceFormat.CHARACTER_CARD_V3 || useRegex) && term.startsWith('/') && slash > 0
    if (literal) {
        val flags = term.substring(slash + 1)
        require(flags.all { it in "dgimsuvy" } && flags.toSet().size == flags.length) {
            "不支持的正则标志：$flags（Android 支持 i/m/s/u；g 为无状态匹配；y/d/v 暂不支持）"
        }
        require(flags.none { it in "dyv" }) {
            "正则标志 $flags 包含 Android 暂不支持的 y/d/v；请改用 i/m/s/u（g 为无状态匹配）"
        }
        pattern = term.substring(1, slash)
        require(pattern.isNotEmpty()) { "正则表达式不能为空" }
        options = buildSet {
            if ('i' in flags) add(RegexOption.IGNORE_CASE)
            if ('m' in flags) add(RegexOption.MULTILINE)
            if ('s' in flags) add(RegexOption.DOT_MATCHES_ALL)
        }
    } else if (!useRegex) {
        if (!matchWholeWords) return context.contains(term, ignoreCase = !caseSensitive)
        pattern = "(?<![\\p{L}\\p{N}_])${Regex.escape(term)}(?![\\p{L}\\p{N}_])"
    }
    require(pattern.length <= 16384) { "正则表达式过长" }
    val regex = synchronized(regexCache) {
        regexCache.getOrPut(pattern to options) {
            try { Regex(pattern, options) }
            catch (e: IllegalArgumentException) { throw IllegalArgumentException("无效正则「$term」：${e.message}") }
        }
    }
    return regex.containsMatchIn(BudgetedText(context, budget))
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
    private var negated = false
    private var positiveMatches = 0

    fun parse(): KeywordExpressionResult {
        require(tokens.isNotEmpty()) { "表达式不能为空" }
        val matched = parseOr()
        require(index == tokens.size) { "多余的词项：${tokens.getOrNull(index)?.text}" }
        return KeywordExpressionResult(matched, matchedTerms.toList(), score = positiveMatches)
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
            if (operator("NOT")) {
                index++; negated = !negated
                try { return !parseUnary() } finally { negated = !negated }
            }
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
            return matcher(term.text).also { if (it) { matchedTerms += term.text; if (!negated) positiveMatches++ } }
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
    val timingUnit: LorebookTimingUnit = LorebookTimingUnit.USER_TURNS,
    val injectedContent: String? = null,
    val recursionLevel: Int = 0,
    val source: String? = null,
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

internal fun trimToEstimatedTokens(content: String, maxTokens: Int, resolveContent: (String) -> String = { it }): String {
    if (maxTokens <= 0) return ""
    if (estimateTextTokens(resolveContent(content)) <= maxTokens) return content
    var low = 0
    var high = content.length
    while (low < high) {
        val mid = (low + high + 1) / 2
        if (estimateTextTokens(resolveContent(content.take(mid))) <= maxTokens) low = mid else high = mid - 1
    }
    if (low > 0 && low < content.length && content[low - 1].isHighSurrogate() && content[low].isLowSurrogate()) low--
    // Never send half of a placeholder. Prefer a nearby paragraph/sentence boundary.
    val prefix = content.take(low)
    val openBrace = prefix.lastIndexOf('{')
    if (openBrace > prefix.lastIndexOf('}')) low = content.lastIndexOf('{', openBrace - 1).takeIf { it == openBrace - 1 } ?: openBrace
    val boundary = content.take(low).indexOfLast { it == '\n' || it in "。！？.!?" }
    if (boundary >= low * 3 / 4) low = boundary + 1
    return content.take(low).trimEnd()
}
