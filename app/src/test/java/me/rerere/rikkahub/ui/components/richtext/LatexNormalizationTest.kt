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
}
