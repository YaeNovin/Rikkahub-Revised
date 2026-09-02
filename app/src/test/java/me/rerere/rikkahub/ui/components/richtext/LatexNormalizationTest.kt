package me.rerere.rikkahub.ui.components.richtext

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LatexNormalizationTest {
    @Test
    fun `strips delimiters and normalizes unicode operators`() {
        assertEquals("a\\le b\\times c", latexRenderCandidates("\$a≤b×c\$").first())
    }

    @Test
    fun `offers JLatexMath compatible alternatives for common macros`() {
        val candidates = latexRenderCandidates("\\(\\dfrac{a}{b} + \\operatorname{sin}(x)\\)")

        assertEquals("\\dfrac{a}{b} + \\operatorname{sin}(x)", candidates.first())
        assertTrue("\\frac{a}{b} + \\mathrm{sin}(x)" in candidates)
    }

    @Test
    fun `removes unsupported equation metadata from environment fallback`() {
        val candidates = latexRenderCandidates(
            "\\begin{align}x &= 1 \\label{eq:x} \\\\ y &= 2 \\tag{A}\\end{align}"
        )

        assertEquals("x = 1   \\quad  y = 2", candidates.last())
    }

    @Test
    fun `normalizes multiline delimiters and display environments outside code`() {
        val markdown = """
            Before \(a +
            b\) after.

            \begin{align}
            x &= 1 \\
            y &= 2
            \end{align}

            ```kotlin
            val literal = "\(not math\)"
            ```
        """.trimIndent()
        val normalized = normalizeMarkdownLatex(markdown)

        assertTrue(normalized.contains("Before ${'$'}a + b${'$'} after."))
        assertTrue(normalized.contains("$$\\begin{align}"))
        assertTrue(normalized.contains("val literal = \"\\(not math\\)\""))
    }

    @Test
    fun `wraps a standalone latex macro line but leaves prose alone`() {
        assertEquals("$$\\frac{a}{b}$$", normalizeMarkdownLatex("\\frac{a}{b}"))
        assertEquals("Use \\frac in documentation", normalizeMarkdownLatex("Use \\frac in documentation"))
    }

    @Test
    fun `failed formulas have a readable fallback without raw macro names`() {
        val readable = latexReadableFallback("\\dfrac{\\alpha}{\\beta} \\le \\sqrt{\\mathrm{x}}")

        assertEquals("(α)/(β) ≤ √(x)", readable)
    }
}
