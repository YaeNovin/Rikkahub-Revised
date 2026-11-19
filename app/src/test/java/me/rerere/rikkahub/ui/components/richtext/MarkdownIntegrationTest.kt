package me.rerere.rikkahub.ui.components.richtext

import org.junit.Assert.*
import org.junit.Test

class MarkdownIntegrationTest {
    @Test fun `both render modes keep grouped citations and literal code independent`() {
        val source = "Conclusion[citation:source_a,source_b]\n\n`[citation:literal]`"
        for (mode in MarkdownRenderMode.entries) {
            val document = buildMarkdownDocument(source, mode)
            assertEquals(listOf("source_a", "source_b"), document.select("a").map { it.attr("href") })
            assertEquals("[citation:literal]", document.selectFirst("code")!!.wholeText())
        }
    }

    @Test fun `mode cache isolation preserves source safety and bounded snapshot reuse`() {
        val source = "#include <iostream>\nstd::vector<int> values;"
        val standard = preloadMarkdownDocument(source, MarkdownRenderMode.STANDARD)
        val user = preloadMarkdownDocument(source, MarkdownRenderMode.USER_MESSAGE)
        assertNotSame(standard, user)
        assertTrue(user.body().wholeText().contains("#include <iostream>"))
        assertTrue(user.body().wholeText().contains("std::vector<int>"))
        assertSame(standard, preloadMarkdownDocument(source, MarkdownRenderMode.STANDARD))
        val first = htmlRenderSnapshot(user)
        val second = htmlRenderSnapshot(buildMarkdownDocument(source, MarkdownRenderMode.USER_MESSAGE), first.blocks)
        assertSame(first.blocks.first(), second.blocks.first())
        assertNull(first.blocks.first().node.parent())
    }
}
