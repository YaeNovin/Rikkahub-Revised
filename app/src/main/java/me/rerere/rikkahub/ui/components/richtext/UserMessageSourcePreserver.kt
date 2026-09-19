package me.rerere.rikkahub.ui.components.richtext

/** Controls parsing only. The source stored in UIMessage and sent to providers is never changed. */
enum class MarkdownRenderMode {
    STANDARD,
    USER_MESSAGE,
}

internal fun preprocessMarkdownContent(
    content: String,
    renderMode: MarkdownRenderMode,
): String {
    val rawSvgProtected = protectRawSvg(content)
    val sourceProtected = if (renderMode == MarkdownRenderMode.USER_MESSAGE) {
        preserveUserMessageSource(rawSvgProtected)
    } else {
        rawSvgProtected
    }
    return normalizeMarkdownLatex(sourceProtected)
}

/**
 * Keeps probable pasted source code opaque to Markdown, then escapes tag-shaped source constructs
 * such as C++ includes and generic type parameters. This deliberately uses language-neutral syntax
 * signals so new languages do not require another parser-specific branch.
 */
internal fun preserveUserMessageSource(content: String): String {
    if (content.isEmpty()) return content
    return protectUnsupportedHtmlTags(fenceProbableSourceBlocks(content))
}

private val SOURCE_DECLARATION_REGEX = Regex(
    pattern = """^(?:public\s+|private\s+|protected\s+|internal\s+|static\s+|final\s+|abstract\s+|open\s+|export\s+|default\s+|async\s+)*(?:class|interface|enum|record|struct|namespace|module|object|trait|impl|protocol|extension|fun|func|function|def|fn|sub|procedure|proc|macro|type|typedef|using|package|import|from|require|include|use|mod|pub|let|const|var|val|void|int|long|short|float|double|decimal|bool|boolean|string|char|auto|dynamic|local|my|our|param)\b""",
    option = RegexOption.IGNORE_CASE,
)
private val SOURCE_CONTROL_REGEX = Regex(
    pattern = """^(?:if|else\s+if|elif|unless|for|foreach|while|do|when|switch|match|case|try|catch|except|finally|with|defer|guard|return|yield|throw|raise|break|continue|await|go|select)\b""",
    option = RegexOption.IGNORE_CASE,
)
private val SOURCE_PREPROCESSOR_REGEX = Regex(
    pattern = """^#\s*(?:include|define|ifn?def|endif|elif|else|pragma|error|warning|using|region|endregion)\b""",
    option = RegexOption.IGNORE_CASE,
)
private val SOURCE_SQL_REGEX = Regex(
    pattern = """^(?:select|insert\s+into|update|delete\s+from|create\s+(?:table|view|index|database)|alter\s+table|drop\s+(?:table|view|index|database)|with\b|from\b|where\b|group\s+by|order\s+by|having\b|join\b|left\s+join|right\s+join|inner\s+join|outer\s+join|union\b|begin\b|commit\b|rollback\b)""",
    option = RegexOption.IGNORE_CASE,
)
private val SOURCE_ASSIGNMENT_REGEX = Regex(
    pattern = """^(?:(?:const|let|var|val|local|my|our|readonly|static|final)\s+)?(?:[$@%]?[A-Za-z_][\w.$@%\[\]-]*|[A-Za-z_][\w<>,.? :\[\]]*\s+[A-Za-z_][\w$]*)\s*(?:=|:=|<-|\+=|-=|\*=|/=|%=)\s*.+$""",
)
private val SOURCE_CALL_REGEX = Regex(
    pattern = """^(?:await\s+|return\s+|yield\s+)?[A-Za-z_$@][\w.$@:-]*\s*\(.*\)\s*;?$""",
)
private val SOURCE_COMMAND_REGEX = Regex(
    pattern = """^(?:echo|printf|read|export|source|set|unset|alias|cd|mkdir|chmod|curl|wget|grep|sed|awk|Write-[A-Za-z]+|Get-[A-Za-z]+|Set-[A-Za-z]+|New-[A-Za-z]+|Remove-[A-Za-z]+|puts|print|require|load|library|summary|plot)\b""",
    option = RegexOption.IGNORE_CASE,
)
private val SOURCE_LISP_REGEX = Regex(
    pattern = """^\((?:defun|defn|define|lambda|let|setq|require|use-package|ns)\b""",
    option = RegexOption.IGNORE_CASE,
)
private val SOURCE_OPERATOR_REGEX = Regex("""(?:=>|->|::|===|!==|==|!=|<=|>=|&&|\|\||\?\.|!!|\+\+|--|\*\*|\.\.)""")
private val SOURCE_ONLY_DELIMITER_REGEX = Regex(
    """^[{}()\[\]]+[;,]?$|^(?:else|end|then|fi|done|esac)\b.*$""",
    RegexOption.IGNORE_CASE,
)
private val SOURCE_CONTEXT_PREFIX_REGEX = Regex("""^(?://|/\*|\*|#|--|;|<!--|@\w|\[\w|[-+>]\s|\d+[.)]\s)""")
private val SOURCE_FENCE_REGEX = Regex("^ {0,3}(`{3,}|~{3,})(.*)$")
private val SOURCE_BACKTICK_RUN_REGEX = Regex("`+")

private data class SourceSignal(
    val score: Int,
    val context: Boolean,
)

private fun sourceSignal(line: String): SourceSignal {
    if (line.isBlank() || line.contains(RAW_SVG_ATTRIBUTE)) return SourceSignal(0, false)
    val trimmed = line.trim()
    if (trimmed.isEmpty()) return SourceSignal(0, false)

    var score = 0
    if (trimmed.startsWith("#!") || trimmed.startsWith("<?php", ignoreCase = true)) score += 5
    if (SOURCE_PREPROCESSOR_REGEX.containsMatchIn(trimmed)) score += 5
    if (SOURCE_DECLARATION_REGEX.containsMatchIn(trimmed)) score += 3
    if (SOURCE_CONTROL_REGEX.containsMatchIn(trimmed)) score += 2
    if (SOURCE_SQL_REGEX.containsMatchIn(trimmed)) score += 3
    if (SOURCE_ASSIGNMENT_REGEX.containsMatchIn(trimmed)) score += 2
    if (SOURCE_CALL_REGEX.containsMatchIn(trimmed)) score += 3
    if (SOURCE_COMMAND_REGEX.containsMatchIn(trimmed)) score += 3
    if (SOURCE_LISP_REGEX.containsMatchIn(trimmed)) score += 3
    if (SOURCE_OPERATOR_REGEX.containsMatchIn(trimmed)) score += 2
    if (trimmed.endsWith(';')) score += 2
    if (trimmed.startsWith("$", ignoreCase = false) && '=' in trimmed) score += 2
    if (trimmed.startsWith("@") && trimmed.length > 1) score += 1
    if (trimmed.endsWith("{") || SOURCE_ONLY_DELIMITER_REGEX.matches(trimmed)) score += 1

    val context = line.firstOrNull()?.isWhitespace() == true ||
        SOURCE_CONTEXT_PREFIX_REGEX.containsMatchIn(trimmed) ||
        SOURCE_ONLY_DELIMITER_REGEX.matches(trimmed) ||
        trimmed.endsWith(',') ||
        trimmed.endsWith('\\')
    return SourceSignal(score, context)
}

private fun fencedLines(lines: List<String>): BooleanArray {
    val result = BooleanArray(lines.size)
    var fenceChar: Char? = null
    var fenceLength = 0
    lines.forEachIndexed { index, line ->
        val match = SOURCE_FENCE_REGEX.matchEntire(line.trimEnd())
        if (fenceChar != null) result[index] = true
        if (match != null) {
            val marker = match.groupValues[1]
            if (fenceChar == null) {
                fenceChar = marker.first()
                fenceLength = marker.length
                result[index] = true
            } else if (marker.first() == fenceChar && marker.length >= fenceLength && match.groupValues[2].isBlank()) {
                result[index] = true
                fenceChar = null
                fenceLength = 0
            }
        }
    }
    return result
}

private fun fenceProbableSourceBlocks(content: String): String {
    val normalized = content.replace("\r\n", "\n").replace('\r', '\n')
    val lines = normalized.split('\n')
    if (lines.isEmpty()) return normalized

    val protected = fencedLines(lines)
    val signals = lines.mapIndexed { index, line ->
        if (protected[index]) SourceSignal(0, false) else sourceSignal(line)
    }
    val selected = BooleanArray(lines.size) { index -> !protected[index] && signals[index].score >= 2 }

    // Pull comments, indentation, closing delimiters, and Markdown-shaped source lines into a
    // neighboring source run without treating those ambiguous lines as code on their own.
    repeat(2) {
        signals.indices.forEach { index ->
            if (protected[index] || selected[index] || !signals[index].context) return@forEach
            if (selected.getOrElse(index - 1) { false } || selected.getOrElse(index + 1) { false }) {
                selected[index] = true
            }
        }
    }
    signals.indices.forEach { index ->
        if (protected[index] || lines[index].isNotBlank()) return@forEach
        if (selected.getOrElse(index - 1) { false } && selected.getOrElse(index + 1) { false }) {
            selected[index] = true
        }
    }

    val accepted = BooleanArray(lines.size)
    var index = 0
    while (index < lines.size) {
        if (!selected[index]) {
            index++
            continue
        }
        val start = index
        while (index + 1 < lines.size && selected[index + 1]) index++
        val end = index
        val coreScores = (start..end).map { signals[it].score }.filter { it >= 2 }
        val contextCount = (start..end).count { signals[it].context }
        val confident = coreScores.any { it >= 3 } ||
            coreScores.size >= 2 ||
            (coreScores.isNotEmpty() && contextCount > 0 && end > start)
        if (confident) (start..end).forEach { accepted[it] = true }
        index++
    }

    if (accepted.none { it }) return normalized
    val output = StringBuilder(normalized.length + 64)
    index = 0
    while (index < lines.size) {
        if (!accepted[index]) {
            output.append(lines[index])
            if (index < lines.lastIndex) output.append('\n')
            index++
            continue
        }
        val start = index
        while (index + 1 < lines.size && accepted[index + 1]) index++
        val end = index
        val code = lines.subList(start, end + 1).joinToString("\n")
        val longestBacktickRun = SOURCE_BACKTICK_RUN_REGEX.findAll(code).maxOfOrNull { it.value.length } ?: 0
        val fence = "`".repeat(maxOf(3, longestBacktickRun + 1))
        output.append(fence).append(" plaintext\n")
        output.append(code)
        if (!code.endsWith('\n')) output.append('\n')
        output.append(fence)
        if (end < lines.lastIndex) output.append('\n')
        index++
    }
    return output.toString()
}

private val SUPPORTED_HTML_TAGS = setOf(
    "a", "abbr", "article", "aside", "b", "bdi", "bdo", "blockquote", "br", "caption",
    "cite", "code", "col", "colgroup", "dd", "del", "details", "div", "dl", "dt", "em",
    "fieldset", "figcaption", "figure", "font", "footer", "h1", "h2", "h3", "h4", "h5",
    "h6", "header", "hr", "i", "img", "input", "kbd", "label", "legend", "li", "main",
    "mark", "nav", "ol", "p", "pre", "progress", "q", "s", "section", "small", "span",
    "strike", "strong", "sub", "summary", "sup", "svg", "table", "tbody", "td", "tfoot",
    "th", "thead", "time", "tr", "u", "ul", "var", "wbr",
)
private val BALANCED_INLINE_HTML_TAGS = setOf(
    "a", "abbr", "b", "bdi", "bdo", "cite", "code", "del", "em", "font", "i", "kbd",
    "mark", "q", "s", "small", "span", "strike", "strong", "sub", "sup", "time", "u", "var",
)
private val VOID_HTML_TAGS = setOf("br", "hr", "img", "input")
private val HTML_TAG_NAME_REGEX = Regex("^</?\\s*([A-Za-z][A-Za-z0-9:-]*)")

private data class HtmlToken(
    val start: Int,
    val endExclusive: Int,
    val name: String,
    val closing: Boolean,
    val selfClosing: Boolean,
)

private fun protectUnsupportedHtmlTags(content: String): String {
    if ('<' !in content) return content
    val tokens = collectHtmlTokens(content)
    if (tokens.isEmpty()) return content

    val matched = BooleanArray(tokens.size)
    val openings = mutableMapOf<String, ArrayDeque<Int>>()
    tokens.forEachIndexed { tokenIndex, token ->
        if (token.name !in BALANCED_INLINE_HTML_TAGS || token.selfClosing) return@forEachIndexed
        if (!token.closing) {
            openings.getOrPut(token.name) { ArrayDeque() }.addLast(tokenIndex)
        } else {
            val opening = openings[token.name]?.removeLastOrNull() ?: return@forEachIndexed
            matched[opening] = true
            matched[tokenIndex] = true
        }
    }

    val output = StringBuilder(content.length + 32)
    var cursor = 0
    tokens.forEachIndexed { tokenIndex, token ->
        output.append(content, cursor, token.start)
        val tokenText = content.substring(token.start, token.endExclusive)
        val supported = token.name in SUPPORTED_HTML_TAGS || isMarkdownAutolink(tokenText)
        val balanced = token.name !in BALANCED_INLINE_HTML_TAGS || token.selfClosing || matched[tokenIndex]
        if (supported && balanced) {
            output.append(content, token.start, token.endExclusive)
        } else {
            output.append(
                content.substring(token.start, token.endExclusive)
                    .replace("&", "&amp;")
                    .replace("<", "&lt;")
                    .replace(">", "&gt;"),
            )
        }
        cursor = token.endExclusive
    }
    output.append(content, cursor, content.length)
    return output.toString()
}

private val MARKDOWN_AUTOLINK_REGEX = Regex(
    "^<(?:(?:https?|ftp)://|mailto:|[^\\s<>@]+@)[^<>]+>$",
    RegexOption.IGNORE_CASE,
)

private fun isMarkdownAutolink(token: String): Boolean = MARKDOWN_AUTOLINK_REGEX.matches(token)

private fun collectHtmlTokens(content: String): List<HtmlToken> {
    val tokens = mutableListOf<HtmlToken>()
    var index = 0
    var fenceChar: Char? = null
    var fenceLength = 0
    while (index < content.length) {
        if (index == 0 || content[index - 1] == '\n') {
            val lineEnd = content.indexOf('\n', index).let { if (it < 0) content.length else it }
            val line = content.substring(index, lineEnd).trimEnd('\r')
            val fence = SOURCE_FENCE_REGEX.matchEntire(line)
            if (fence != null) {
                val marker = fence.groupValues[1]
                if (fenceChar == null) {
                    fenceChar = marker.first()
                    fenceLength = marker.length
                } else if (marker.first() == fenceChar && marker.length >= fenceLength && fence.groupValues[2].isBlank()) {
                    fenceChar = null
                    fenceLength = 0
                }
                index = lineEnd
                continue
            }
            if (fenceChar != null || line.startsWith("    ") || line.startsWith('\t')) {
                index = lineEnd
                continue
            }
        }
        if (content[index] == '`') {
            var markerEnd = index
            while (markerEnd < content.length && content[markerEnd] == '`') markerEnd++
            val marker = content.substring(index, markerEnd)
            val close = content.indexOf(marker, markerEnd)
            if (close >= 0) {
                index = close + marker.length
                continue
            }
        }
        if (content[index] != '<') {
            index++
            continue
        }
        if (content.startsWith("<!--", index)) {
            val end = content.indexOf("-->", index + 4)
            index = if (end >= 0) end + 3 else content.length
            continue
        }
        val end = htmlTokenEnd(content, index) ?: run {
            index++
            continue
        }
        val tokenText = content.substring(index, end)
        val name = HTML_TAG_NAME_REGEX.find(tokenText)?.groupValues?.get(1)?.lowercase()
        if (name != null) {
            tokens += HtmlToken(
                start = index,
                endExclusive = end,
                name = name,
                closing = tokenText.startsWith("</"),
                selfClosing = name in VOID_HTML_TAGS || tokenText.trimEnd().endsWith("/>"),
            )
        }
        index = end
    }
    return tokens
}

private fun htmlTokenEnd(content: String, start: Int): Int? {
    var quote: Char? = null
    for (index in start + 1 until content.length) {
        val char = content[index]
        if (quote != null) {
            if (char == quote) quote = null
        } else if (char == '\'' || char == '"') {
            quote = char
        } else if (char == '>') {
            return index + 1
        } else if (char == '\n' || char == '\r') {
            return null
        }
    }
    return null
}
