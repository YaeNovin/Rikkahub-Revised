package me.rerere.rikkahub.ui.components.richtext

import org.junit.Assert.*
import org.junit.Test

class StreamingHtmlReuseTest {
    @Test fun `completed blocks retain identity while open text and resolved references update`() {
        val first = htmlRenderSnapshot(buildMarkdownDocument("## Fixed\n\nCompleted text.\n\nGrowing"))
        val next = htmlRenderSnapshot(buildMarkdownDocument("## Fixed\n\nCompleted text.\n\nGrowing longer."), first.blocks)
        assertSame(first.blocks[0], next.blocks[0])
        assertSame(first.blocks[1], next.blocks[1])
        assertNull(next.blocks[0].node.parent())
        assertNotSame(first.blocks.last(), next.blocks.last())
        assertEquals("Growing", first.document.select("p").last()!!.text())
        val unresolved = htmlRenderSnapshot(buildMarkdownDocument("[link][r]"))
        val resolved = htmlRenderSnapshot(buildMarkdownDocument("[link][r]\n\n[r]: https://example.com"), unresolved.blocks)
        assertNotSame(unresolved.blocks.first(), resolved.blocks.first())
        assertEquals("https://example.com", resolved.document.selectFirst("a")!!.attr("href"))
    }

    @Test fun `closing graphical fence invalidates block without mutating active document`() {
        val open = htmlRenderSnapshot(buildMarkdownDocument("```mermaid\nflowchart LR; A-->B\n"))
        val closed = htmlRenderSnapshot(buildMarkdownDocument("```mermaid\nflowchart LR; A-->B\n```\n"), open.blocks)
        assertNotSame(open.blocks.first(), closed.blocks.first())
        assertEquals("false", open.document.selectFirst("pre")!!.attr("data-rikka-complete"))
        assertFalse(closed.document.selectFirst("pre")!!.hasAttr("data-rikka-complete"))
    }
}
