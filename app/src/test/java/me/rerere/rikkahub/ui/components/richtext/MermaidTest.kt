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
}
