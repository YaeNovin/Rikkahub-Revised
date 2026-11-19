package me.rerere.highlight

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import me.rerere.highlight.core.HighlightEngine
import me.rerere.highlight.languages.builtinLanguages
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val MAX_CODE_LENGTH = 4096
private const val MAX_EXTENDED_CODE_LENGTH = 32768

internal fun highlightedCodeText(
    code: String,
    language: String,
    colors: HighlightTextColorPalette,
    highlighter: CodeHighlighter,
    maxHighlightLength: Int = MAX_CODE_LENGTH,
): AnnotatedString {
    if (code.length > maxHighlightLength.coerceIn(0, MAX_EXTENDED_CODE_LENGTH)) return AnnotatedString(code)
    return buildAnnotatedString {
        highlighter.highlight(code, language).forEach { token -> buildHighlightText(token, colors) }
    }
}

private data class HighlightResult(
    val code: String, val language: String, val colors: HighlightTextColorPalette, val text: AnnotatedString,
)

private data class HighlightCacheKey(val code: String, val language: String, val colors: HighlightTextColorPalette, val limit: Int)
private val highlightCache = object : LinkedHashMap<HighlightCacheKey, AnnotatedString>(64, .75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<HighlightCacheKey, AnnotatedString>?) = size > 64
}
private val workerHighlighter = ThreadLocal.withInitial { CodeHighlighter() }
private val highlightDispatcher = Dispatchers.Default.limitedParallelism(2)

internal fun cachedHighlightedCode(code: String, language: String, colors: HighlightTextColorPalette, limit: Int): AnnotatedString? =
    synchronized(highlightCache) { highlightCache[HighlightCacheKey(code, language, colors, limit)] }

internal fun prepareHighlightedCode(code: String, language: String, colors: HighlightTextColorPalette, limit: Int): AnnotatedString {
    cachedHighlightedCode(code, language, colors, limit)?.let { return it }
    val result = highlightedCodeText(code, language, colors, checkNotNull(workerHighlighter.get()), limit)
    if (code.length <= limit) synchronized(highlightCache) {
        highlightCache[HighlightCacheKey(code, language, colors, limit)] = result
        var characters = highlightCache.keys.sumOf { it.code.length }
        while (characters > 512 * 1024 && highlightCache.isNotEmpty()) {
            val oldest = highlightCache.keys.first()
            characters -= oldest.code.length
            highlightCache.remove(oldest)
        }
    }
    return result
}

/** Parse the complete block once so line-number layouts do not reset grammar state. */
@Composable
fun rememberHighlightedCode(
    code: String,
    language: String,
    colors: HighlightTextColorPalette,
    maxHighlightLength: Int = MAX_CODE_LENGTH,
): AnnotatedString {
    val limit = maxHighlightLength.coerceIn(0, MAX_EXTENDED_CODE_LENGTH)
    if (code.length > limit || code.isEmpty()) return remember(code) { AnnotatedString(code) }
    val initial = remember(code, language, colors, limit) { cachedHighlightedCode(code, language, colors, limit) }
    val result by produceState<HighlightResult?>(initial?.let { HighlightResult(code, language, colors, it) }, code, language, colors, limit) {
        value = withContext(highlightDispatcher) {
            HighlightResult(code, language, colors, prepareHighlightedCode(code, language, colors, limit))
        }
    }
    return result?.takeIf { it.code == code && it.language == language && it.colors == colors }?.text ?: AnnotatedString(code)
}

/** Preserve span offsets, empty final lines and CRLF display without reparsing each row. */
fun AnnotatedString.highlightedLines(): List<AnnotatedString> = buildList {
    var start = 0
    for (index in text.indices) if (text[index] == '\n') {
        val end = if (index > start && text[index - 1] == '\r') index - 1 else index
        add(subSequence(start, end))
        start = index + 1
    }
    add(subSequence(start, length))
}

val LocalCodeHighlighter = staticCompositionLocalOf { CodeHighlighter() }

/**
 * A pure Kotlin syntax highlighter.
 *
 * Grammars are ported from highlight.js 11.11.1 and run on [HighlightEngine], a port of its mode
 * stack parser. An unsupported language is returned unhighlighted.
 */
class CodeHighlighter {
    private val engine = HighlightEngine(builtinLanguages())

    fun highlight(code: String, language: String): List<HighlightToken> {
        if (code.isEmpty()) return emptyList()
        if (language.trim().lowercase() in TERMINAL_LANGUAGES) return terminalSourceTokens(code)

        return engine.highlight(code, language)
            ?: listOf(HighlightToken.Plain(code))
    }

    fun supports(language: String): Boolean = language.trim().lowercase() in TERMINAL_LANGUAGES || engine.supports(language)
}

private val TERMINAL_LANGUAGES = setOf("ansi", "terminal", "xterm", "console-output")
private val TERMINAL_CONTROL = Regex("""(?:\x1b|\\x1b|\\u001b|\x9b)\[[0-9;:]{0,96}[mKJABCDEFGHfdsu]""", RegexOption.IGNORE_CASE)
private val TERMINAL_SEVERITY = Regex("""\b(?:error|fatal|failed|failure|warn|warning|success|passed|info|debug|trace)\b""", RegexOption.IGNORE_CASE)

/** Source highlighting does not interpret control bytes or alter text used for copying. */
private fun terminalSourceTokens(code: String): List<HighlightToken> = buildList {
    fun appendText(text: String) {
        var cursor = 0
        TERMINAL_SEVERITY.findAll(text).forEach { match ->
            if (match.range.first > cursor) add(HighlightToken.Plain(text.substring(cursor, match.range.first)))
            val scope = when (match.value.lowercase()) {
                "error", "fatal", "failed", "failure" -> "deletion"
                "success", "passed" -> "addition"
                "warn", "warning" -> "keyword"
                "debug", "trace" -> "comment"
                else -> "title.function"
            }
            add(HighlightToken.Styled(match.value, scope))
            cursor = match.range.last + 1
        }
        if (cursor < text.length) add(HighlightToken.Plain(text.substring(cursor)))
    }
    var cursor = 0
    TERMINAL_CONTROL.findAll(code).forEach { match ->
        appendText(code.substring(cursor, match.range.first))
        add(HighlightToken.Styled(match.value, "char.escape"))
        cursor = match.range.last + 1
    }
    appendText(code.substring(cursor))
}

@Composable
fun CodeHighlightText(
    code: String,
    language: String,
    modifier: Modifier = Modifier,
    colors: HighlightTextColorPalette = HighlightTextColorPalette.Default,
    fontSize: TextUnit = 12.sp,
    fontFamily: FontFamily = FontFamily.Monospace,
    fontStyle: FontStyle = FontStyle.Normal,
    fontWeight: FontWeight = FontWeight.Normal,
    lineHeight: TextUnit = TextUnit.Unspecified,
    overflow: TextOverflow = TextOverflow.Clip,
    softWrap: Boolean = true,
    maxLines: Int = Int.MAX_VALUE,
    minLines: Int = 1,
    maxHighlightLength: Int = MAX_CODE_LENGTH,
) {
    val annotatedString = rememberHighlightedCode(code, language, colors, maxHighlightLength)

    Text(
        modifier = modifier,
        text = annotatedString,
        color = colors.fallback,
        fontSize = fontSize,
        fontFamily = fontFamily,
        fontStyle = fontStyle,
        fontWeight = fontWeight,
        lineHeight = lineHeight,
        overflow = overflow,
        softWrap = softWrap,
        maxLines = maxLines,
        minLines = minLines,
    )
}
