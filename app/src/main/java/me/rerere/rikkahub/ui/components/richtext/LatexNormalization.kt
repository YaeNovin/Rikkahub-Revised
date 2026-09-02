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

internal fun normalizeMarkdownLatex(content: String): String {
    if ('\\' !in content) return content
    val protectedRanges = buildList {
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

private fun normalizeLatexInMarkdownText(text: String): String {
    var normalized = LATEX_DISPLAY_ENVIRONMENT_REGEX.replace(text) { match ->
        "$$${match.value}$$"
    }
    normalized = BLOCK_LATEX_DELIMITER_REGEX.replace(normalized) { match ->
        "$$${match.groupValues[1].trim()}$$"
    }
    normalized = INLINE_LATEX_DELIMITER_REGEX.replace(normalized) { match ->
        "$${match.groupValues[1].trim().replace(INLINE_LATEX_LINE_BREAK_REGEX, " ")}$"
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
        .replace(Regex("\\\\(?:left|right|,|;|!|quad|qquad)\\b?"), " ")
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
