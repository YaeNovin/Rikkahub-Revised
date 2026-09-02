package me.rerere.rikkahub.ui.components.richtext

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MermaidTest {
    @Test
    fun `accepts timeline syntax without changing it`() {
        val timeline = """
            timeline
              2024 : Discovery
              2025 : Delivery
        """.trimIndent()

        assertEquals(timeline, normalizeMermaidCode(timeline))
    }

    @Test
    fun `normalizes the common requirement diagram alias`() {
        val normalized = normalizeMermaidCode("requirement\n  requirement login")

        assertTrue(normalized.startsWith("requirementDiagram"))
    }

    @Test
    fun `generated page uses a mobile viewport and bounded svg`() {
        val html = buildMermaidHtml(
            code = "flowchart LR; A --> B",
            colorScheme = androidx.compose.material3.lightColorScheme(),
        )

        assertTrue(html.contains("width=device-width"))
        assertTrue(html.contains("max-width: 100%"))
        assertTrue(html.contains("pinch-zoom"))
        assertTrue(html.contains("background: transparent"))
        assertTrue(html.contains("preserveAspectRatio"))
        assertTrue(html.contains("removeAttribute('width')"))
    }
}
