package me.rerere.rikkahub.ui.components.richtext

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
        .replace("\\dfrac", "\\frac")
        .replace("\\tfrac", "\\frac")
        .replace("\\dbinom", "\\binom")
        .replace("\\tbinom", "\\binom")
        .replace("\\operatorname", "\\mathrm")
        .replace("\\text", "\\mathrm")
        .replace("\\boldsymbol", "\\mathbf")
        .replace("\\bm", "\\mathbf")
        .replace("\\mathbbm", "\\mathbb")
        .replace("\\pmb", "\\mathbf")

    return listOf(normalized, compatibilityFallback)
        .filter(String::isNotBlank)
        .distinct()
}

private fun String.stripLatexDelimiters(): String = when {
    startsWith("$$") && endsWith("$$") && length >= 4 -> substring(2, length - 2).trim()
    startsWith("$") && endsWith("$") && length >= 2 -> substring(1, length - 1).trim()
    startsWith("\\[") && endsWith("\\]") && length >= 4 -> substring(2, length - 2).trim()
    startsWith("\\(") && endsWith("\\)") && length >= 4 -> substring(2, length - 2).trim()
    else -> this
}
