package me.rerere.rikkahub.ui.pages.extensions

import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.model.*
import org.junit.Assert.*
import org.junit.Test

class LorebookScanDepthGuideTest {
    @Test fun `reference ranges cover allowed depths without gaps or overlaps`() {
        val depths = lorebookScanDepthReferences.flatMap { it.range.toList() }
        assertEquals((0..1000).toList(), depths)
        assertEquals(7, lorebookScanDepthReferences.size)
        assertEquals(0..0, lorebookScanDepthReferences.first().range)
        assertTrue(lorebookScanDepthReferences.single { 4 in it.range }.label.contains("推荐"))
    }

    @Test fun `offline tutorial reuses every field reference and distinguishes recommendations`() {
        val scan = lorebookHelpTopics.single { it.id == "scan" }
        lorebookScanDepthReferences.forEach { reference ->
            assertTrue(scan.steps.any { reference.label in it && reference.usage in it })
        }
        assertTrue(LOREBOOK_SCAN_DEPTH_UNITS in scan.steps)
        assertTrue(LOREBOOK_SCAN_DEPTH_CAVEATS in scan.steps)
        assertTrue(scan.steps.any { "不会" in it && "默认" in it })
    }

    @Test fun `message counting example matches actual source filtering`() {
        val history = listOf(UIMessage.user("A"), UIMessage.assistant("B"), UIMessage.user("C"), UIMessage.assistant("D"), UIMessage.user("E"))
        assertEquals("B\nC\nD\nE", lorebookScanText(history, 4, LorebookScanSource.ALL))
        assertEquals("C\nE", lorebookScanText(history, 2, LorebookScanSource.USER))
        assertEquals("", lorebookScanText(history, 0, LorebookScanSource.ALL))
        val afterReply = history + UIMessage.assistant("F")
        assertEquals("F", lorebookScanText(afterReply, 1, LorebookScanSource.ALL))
        val currentInput = PromptInjection.RegexInjection(scanMode = LorebookScanMode.CURRENT_INPUT)
        assertEquals("E", currentInput.scanText(afterReply, Lorebook()))
    }

    @Test fun `default changes affect only inherited depth and do not change entry values`() {
        val book = Lorebook(defaultScanDepth = 12)
        val custom = PromptInjection.RegexInjection(scanDepth = 2)
        assertEquals(2, custom.resolvedScanDepth(book))
        assertEquals(12, custom.copy(scanMode = LorebookScanMode.INHERIT).resolvedScanDepth(book))
        assertEquals(0, custom.copy(scanMode = LorebookScanMode.NONE).resolvedScanDepth(book))
        assertEquals(1, custom.copy(scanMode = LorebookScanMode.CURRENT_INPUT).resolvedScanDepth(book))
        assertEquals(2, custom.scanDepth)
    }
}
