package me.rerere.rikkahub

import androidx.test.ext.junit.runners.AndroidJUnit4
import me.rerere.rikkahub.data.model.QuickMessage
import me.rerere.rikkahub.data.model.placeholderNames
import me.rerere.rikkahub.data.model.render
import me.rerere.rikkahub.ui.components.richtext.latexReadableFallback
import me.rerere.rikkahub.ui.components.richtext.latexRenderCandidates
import me.rerere.rikkahub.utils.applyPlaceholders
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidRegexCompatibilityTest {
    @Test
    fun promptPlaceholdersCompileWithAndroidRegex() {
        assertEquals(
            "Today 2026-09-02 at 14:10",
            "Today {date} at {{ time }}".applyPlaceholders(
                "date" to "2026-09-02",
                "time" to "14:10",
            ),
        )
    }

    @Test
    fun ordinaryMessagesBypassPromptRegexOnAndroid() {
        val message = "普通消息不包含任何变量。"

        assertEquals(
            message,
            message.applyPlaceholders("cur_date" to "2026-09-02"),
        )
    }

    @Test
    fun quickMessagePlaceholdersCompileAndRenderWithAndroidRegex() {
        val message = QuickMessage(content = "{{ character }} at {{location}}")

        assertEquals(listOf("character", "location"), message.placeholderNames())
        assertEquals(
            "Alice at Harbor",
            message.render(mapOf("character" to "Alice", "location" to "Harbor")),
        )
    }

    @Test
    fun latexFallbackPatternsCompileAndNormalizeWithAndroidRegex() {
        val fallback = latexRenderCandidates(
            "\\begin{align}x &= 1 \\label{eq:x} \\\\ y &= 2 \\tag{A}\\end{align}"
        ).last()

        assertFalse(fallback.contains("\\label"))
        assertFalse(fallback.contains("\\tag"))
        assertEquals(
            "(α)/(β) ≤ √(x)",
            latexReadableFallback("\\frac{\\alpha}{\\beta} \\le \\sqrt{\\mathrm{x}}"),
        )
    }
}
