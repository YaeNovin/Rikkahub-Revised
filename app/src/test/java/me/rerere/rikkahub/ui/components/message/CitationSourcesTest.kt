package me.rerere.rikkahub.ui.components.message

import me.rerere.ai.ui.UIMessageAnnotation
import me.rerere.rikkahub.data.db.dao.KnowledgeDocumentPreviewChunk
import org.junit.Assert.*
import org.junit.Test

class CitationSourcesTest {
    private val citation = UIMessageAnnotation.KnowledgeCitation("chunk", "base", "doc", "Document", "file:///source.pdf",
        "引用片段", .9f, 5, 6, "第二章 / 示例")

    @Test fun `web titles stay labels and empty titles fall back to the host`() {
        assertEquals("Example", citationLinkLabel(UIMessageAnnotation.UrlCitation(" Example ", "https://example.com/path")))
        assertEquals("example.com", citationLinkLabel(UIMessageAnnotation.UrlCitation("", "https://example.com/path")))
    }

    @Test fun `locations include exact page section chunk and document character offsets`() {
        val chunk = KnowledgeDocumentPreviewChunk(17, "before 引用片段 after", 8, 8, "更新后的章节", 420, 510)
        assertEquals("第 8 页 · 更新后的章节 · 分块 18 · 文档字符 421–510", knowledgeCitationLocation(citation, chunk))
        assertEquals("第 5–6 页 · 第二章 / 示例", knowledgeCitationLocation(citation))
        assertEquals(7..10, citationExcerptRange("before 引用片段 after", "引用片段"))
    }

    @Test fun `changed or deleted sources never invent a matching location`() {
        assertNull(citationExcerptRange("document changed", citation.excerpt))
        assertNull(citationExcerptRange("abc", ""))
        assertEquals("查看引用原文", knowledgeCitationLocation(citation.copy(pageStart = null, pageEnd = null, sectionPath = "")))
    }

    @Test fun `a citation near the end of a long chunk opens around the actual passage`() {
        val content = "a".repeat(5000) + "引用片段" + "b".repeat(5000)
        val window = citationSourceWindow(content, "引用片段")
        assertTrue(window.startsWith("…\n"))
        assertTrue(window.endsWith("\n…"))
        assertTrue(window.length < 620)
        assertTrue(window.contains("引用片段"))
        assertEquals("before 引用片段 after", citationSourceWindow("before 引用片段 after", "引用片段"))
    }
}
