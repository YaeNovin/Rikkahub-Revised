package me.rerere.rikkahub.ui.components.richtext

private val MARKDOWN_FENCED_CODE_REGEX = Regex(
    pattern = "(?ms)^[ \\t]{0,3}(`{3,}|~{3,})[^\\r\\n]*(?:\\r?\\n|$).*?(?:^[ \\t]{0,3}\\1[ \\t]*(?:\\r?$)|\\z)",
)
private val MARKDOWN_INLINE_CODE_REGEX = Regex("(?s)(`+).+?\\1")
private val MARKDOWN_INDENTED_CODE_REGEX = Regex("(?m)^(?: {4}|\\t).*$")
private val INLINE_LATEX_DELIMITER_REGEX = Regex("(?<!\\\\)\\\\\\(([\\s\\S]+?)(?<!\\\\)\\\\\\)")
private val BLOCK_LATEX_DELIMITER_REGEX = Regex("(?<!\\\\)\\\\\\[([\\s\\S]+?)(?<!\\\\)\\\\\\]")
private val LATEX_DISPLAY_ENVIRONMENT_REGEX = Regex(
    pattern = "\\\\begin\\{(equation\\*?|align\\*?|gather\\*?|multline\\*?|displaymath)\\}([\\s\\S]*?)\\\\end\\{\\1\\}",
    option = RegexOption.IGNORE_CASE,
)
private val BARE_LATEX_LINE_REGEX = Regex(
    pattern = "(?m)^[ \\t]*(\\\\(?:displaystyle\\s+)?(?:d?frac|tfrac|sqrt|sum|prod|int|lim|begin\\{cases\\}|mathbf|mathbb|mathcal|operatorname)\\b[^\\r\\n]*)$",
)
private val INLINE_LATEX_LINE_BREAK_REGEX = Regex("[ \\t]*\\r?\\n[ \\t]*")
// Android uses ICU regex, which rejects quantifying a zero-width boundary (\b?).
// TeX word commands end at a non-letter; punctuation commands need no boundary.
private val LATEX_LAYOUT_COMMAND_REGEX = Regex("\\\\(?:(?:left|right|quad|qquad)(?![A-Za-z])|[,;!])")

internal fun normalizeMarkdownLatex(content: String): String {
    if ('\\' !in content) return content
    val protectedRanges = buildList {
        addAll(proseLiteralRanges(content, protectMath = false))
        Regex("""(?s)(?<!\\)\$\$.*?(?<!\\)\$\$|(?<![\\$])\$(?!\$)[^\r\n$]+(?<!\\)\$""")
            .findAll(content).forEach { add(it.range) }
        MARKDOWN_FENCED_CODE_REGEX.findAll(content).forEach { add(it.range) }
        MARKDOWN_INLINE_CODE_REGEX.findAll(content).forEach { add(it.range) }
        MARKDOWN_INDENTED_CODE_REGEX.findAll(content).forEach { add(it.range) }
    }.sortedBy(IntRange::first).fold(mutableListOf<IntRange>()) { merged, range ->
        val previous = merged.lastOrNull()
        if (previous == null || range.first > previous.last + 1) {
            merged += range
        } else if (range.last > previous.last) {
            merged[merged.lastIndex] = previous.first..range.last
        }
        merged
    }

    val result = StringBuilder(content.length)
    var cursor = 0
    protectedRanges.forEach { range ->
        if (cursor < range.first) {
            result.append(normalizeLatexInMarkdownText(content.substring(cursor, range.first)))
        }
        result.append(content, range.first, range.last + 1)
        cursor = range.last + 1
    }
    if (cursor < content.length) {
        result.append(normalizeLatexInMarkdownText(content.substring(cursor)))
    }
    return result.toString()
}

/** Preserve formulas before Markdown consumes TeX backslashes or tight delimiters. */
internal fun protectMarkdownMath(content: String): String {
    if ('$' !in content && !content.contains("\\ce{") && !content.contains("\\pu{")) return content
    val ranges = proseLiteralRanges(content, protectMath = false)
    val edits = mutableListOf<Triple<Int, Int, String>>()
    var index = 0
    var rangeIndex = 0
    while (index < content.length) {
        while (rangeIndex < ranges.size && ranges[rangeIndex].last < index) rangeIndex++
        val opaque = ranges.getOrNull(rangeIndex)
        if (opaque != null && index in opaque) { index = opaque.last + 1; continue }
        if (content[index] == '$' && (index == 0 || content[index - 1] != '\\')) {
            val block = content.startsWith("$$", index)
            val marker = if (block) "$$" else "$"
            val start = index + marker.length
            var end = start
            while (end < content.length && end - start <= 8000 && (block || content[end] != '\n')) {
                if (content.startsWith(marker, end) && content.getOrNull(end - 1) != '\\') break
                end++
            }
            if (end < content.length && end - start in 1..8000 && content.startsWith(marker, end) &&
                (block || content.getOrNull(end + 1)?.isDigit() != true) &&
                (block || !content.substring(start, end).all { it.isDigit() || it in " ,." })) {
                val formula = content.substring(start, end)
                if (ranges.none { it.first < end && it.last >= start }) {
                    edits += Triple(index, end + marker.length, mathSourceSpan(formula, !block))
                    index = end + marker.length; continue
                }
            }
        } else if ((content.startsWith("\\ce{", index) || content.startsWith("\\pu{", index)) &&
            (index == 0 || content[index - 1] != '\\')) {
            var depth = 1
            var end = index + 4
            while (end < content.length && depth > 0 && end - index <= 8000) {
                if (content[end] == '{') depth++ else if (content[end] == '}') depth--
                end++
            }
            if (depth == 0 && ranges.none { it.first < end && it.last >= index }) {
                edits += Triple(index, end, mathSourceSpan(content.substring(index, end), true))
                index = end; continue
            }
        }
        index++
    }
    return StringBuilder(content).apply { edits.asReversed().forEach { (start, end, value) -> replace(start, end, value) } }.toString()
}

private fun mathSourceSpan(formula: String, inline: Boolean): String =
    academicSourceMarker("<span class=\"math\" inline=\"$inline\">${formula.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")}</span>", false)

private fun normalizeLatexInMarkdownText(text: String): String {
    var normalized = BLOCK_LATEX_DELIMITER_REGEX.replace(text) { match ->
        "$$${match.groupValues[1].trim()}$$"
    }
    normalized = INLINE_LATEX_DELIMITER_REGEX.replace(normalized) { match ->
        "$${match.groupValues[1].trim().replace(INLINE_LATEX_LINE_BREAK_REGEX, " ")}$"
    }
    val delimited = Regex("""(?s)\$\$.*?\$\$|\$[^\r\n$]+\$""").findAll(normalized).map { it.range }.toList()
    normalized = LATEX_DISPLAY_ENVIRONMENT_REGEX.replace(normalized) { match ->
        if (delimited.any { match.range.first in it }) match.value else "$$${match.value}$$"
    }
    return BARE_LATEX_LINE_REGEX.replace(normalized) { match ->
        "$$${match.groupValues[1].trim()}$$"
    }
}

internal fun latexRenderCandidates(latex: String): List<String> {
    val normalized = latex
        .trim()
        .stripLatexDelimiters()
        .replace("\uFEFF", "")
        .replace("\u200B", "")
        .replace("−", "-")
        .replace("×", "\\times ")
        .replace("÷", "\\div ")
        .replace("≤", "\\le ")
        .replace("≥", "\\ge ")
        .replace("≠", "\\ne ")
        .replace("±", "\\pm ")
        .replace("∓", "\\mp ")
        .replace("∞", "\\infty ")
        .replace("⋅", "\\cdot ")

    val compatibilityFallback = normalized
        .replace("\\operatorname*", "\\mathrm")
        .replace("\\dfrac", "\\frac")
        .replace("\\tfrac", "\\frac")
        .replace("\\dbinom", "\\binom")
        .replace("\\tbinom", "\\binom")
        .replace("\\operatorname", "\\mathrm")
        .replace("\\textnormal", "\\mathrm")
        .replace("\\textrm", "\\mathrm")
        .replace("\\texttt", "\\mathtt")
        .replace("\\text", "\\mathrm")
        .replace("\\boldsymbol", "\\mathbf")
        .replace("\\bm", "\\mathbf")
        .replace("\\mathbbm", "\\mathbb")
        .replace("\\pmb", "\\mathbf")

    val environmentFallback = compatibilityFallback
        .replace(
            Regex("\\\\begin\\{(?:equation\\*?|align\\*?|gather\\*?|multline\\*?|displaymath)\\}", RegexOption.IGNORE_CASE),
            "",
        )
        .replace(
            Regex("\\\\end\\{(?:equation\\*?|align\\*?|gather\\*?|multline\\*?|displaymath)\\}", RegexOption.IGNORE_CASE),
            "",
        )
        .replace(Regex("\\\\(?:label|tag)\\{[^{}]*\\}"), "")
        .replace(Regex("\\\\(?:notag|nonumber|displaystyle|textstyle)\\b"), "")
        .replace("&", "")
        .replace("\\\\", " \\quad ")

    return listOf(normalized, compatibilityFallback, environmentFallback.trim())
        .filter(String::isNotBlank)
        .distinct()
}

internal fun latexReadableFallback(latex: String): String {
    var readable = latexRenderCandidates(latex).lastOrNull().orEmpty()
    repeat(4) {
        readable = readable
            .replace(Regex("\\\\(?:dfrac|tfrac|frac)\\{([^{}]*)\\}\\{([^{}]*)\\}"), "($1)/($2)")
            .replace(Regex("\\\\sqrt\\{([^{}]*)\\}"), "√($1)")
            .replace(Regex("\\\\(?:mathrm|mathbf|mathbb|mathcal|mathtt)\\{([^{}]*)\\}"), "$1")
    }
    val symbols = linkedMapOf(
        "alpha" to "α", "beta" to "β", "gamma" to "γ", "delta" to "δ",
        "epsilon" to "ε", "theta" to "θ", "lambda" to "λ", "mu" to "μ",
        "pi" to "π", "rho" to "ρ", "sigma" to "σ", "phi" to "φ", "omega" to "ω",
        "Gamma" to "Γ", "Delta" to "Δ", "Theta" to "Θ", "Lambda" to "Λ",
        "Sigma" to "Σ", "Phi" to "Φ", "Omega" to "Ω",
        "times" to "×", "div" to "÷", "cdot" to "·", "pm" to "±", "mp" to "∓",
        "le" to "≤", "leq" to "≤", "ge" to "≥", "geq" to "≥", "ne" to "≠",
        "infty" to "∞", "to" to "→", "rightarrow" to "→", "leftarrow" to "←",
        "sum" to "Σ", "prod" to "Π", "int" to "∫",
    )
    symbols.forEach { (macro, symbol) ->
        readable = readable.replace(Regex("\\\\$macro\\b"), symbol)
    }
    return readable
        .replace(LATEX_LAYOUT_COMMAND_REGEX, " ")
        .replace(Regex("\\\\([A-Za-z]+)\\*?"), "$1")
        .replace("\\{", "{")
        .replace("\\}", "}")
        .replace("{", "")
        .replace("}", "")
        .replace("\\", "")
        .replace(Regex("[ \\t]{2,}"), " ")
        .trim()
        .ifBlank { latex.trim().trim('$') }
}

private fun String.stripLatexDelimiters(): String = when {
    startsWith("$$") && endsWith("$$") && length >= 4 -> substring(2, length - 2).trim()
    startsWith("$") && endsWith("$") && length >= 2 -> substring(1, length - 1).trim()
    startsWith("\\[") && endsWith("\\]") && length >= 4 -> substring(2, length - 2).trim()
    startsWith("\\(") && endsWith("\\)") && length >= 4 -> substring(2, length - 2).trim()
    else -> this
}
