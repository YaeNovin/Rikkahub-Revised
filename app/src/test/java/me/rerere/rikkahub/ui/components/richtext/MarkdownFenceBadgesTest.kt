package me.rerere.rikkahub.ui.components.richtext

import org.junit.Assert.*
import org.junit.Test

class MarkdownFenceBadgesTest {
    private val url = "https://img.shields.io/badge/Android-3DDC84?style=flat&logo=android&logoColor=white"
    private val image = "![Android]($url)"

    @Test fun `only explicit markdown fence aliases opt in`() {
        listOf("markdown", "md", "GFM", "language-markdown", "{.md}", "text/markdown").forEach { assertTrue(it, isMarkdownBadgeFence(it)) }
        listOf("", "text", "html", "xml", "javascript").forEach { assertFalse(it, isMarkdownBadgeFence(it)) }
    }

    @Test fun `screenshot comments and all other source stay byte for byte`() {
        val source = "<!-- 基础圆角扁平风格（Flat） -->\n$image\n# title stays source\n![Photo](https://example.com/photo.png)\n"
        val result = parseMarkdownFenceBadges(source)
        assertEquals(source, result.source)
        assertEquals(1, result.badges.size)
        val badge = result.badges.single()
        assertEquals(image, source.substring(badge.start, badge.end))
        assertEquals(url, badge.url)
        assertEquals("Android", badge.alt)
        assertEquals(5, result.rows().size)
        assertEquals(1, result.rows()[1].badges.size)
        assertTrue(result.rows()[3].badges.isEmpty())
    }

    @Test fun `linked badge includes only its wrapper and retains navigation target`() {
        val linked = "[$image](https://example.com/project)"
        val source = "before $linked after"
        val badge = parseMarkdownFenceBadges(source).badges.single()
        assertEquals(linked, source.substring(badge.start, badge.end))
        assertEquals("before ", source.take(badge.start))
        assertEquals(" after", source.drop(badge.end))
        assertEquals("https://example.com/project", badge.link)
    }

    @Test fun `reference links resolve without removing definition lines`() {
        val source = "[![CI][badge]][project]\n\n[badge]: $url\n[project]: https://example.com/project"
        val result = parseMarkdownFenceBadges(source)
        val badge = result.badges.single()
        assertEquals("[![CI][badge]][project]", source.substring(badge.start, badge.end))
        assertEquals("https://example.com/project", badge.link)
        assertEquals(url, badge.url)
        assertTrue(result.rows().drop(1).all { it.badges.isEmpty() })
    }

    @Test fun `inline code escaping inner fences and comments remain literal`() {
        val source = "`$image`\n\\$image\n\n~~~md\n$image\n~~~\n\n<!-- $image -->\n\n    $image\n"
        assertTrue(parseMarkdownFenceBadges(source).badges.isEmpty())
    }

    @Test fun `raw HTML is never opted in even with spoofed marker`() {
        val source = "<img src=\"$url\" data-fence-badge-offset=\"0\" onerror=\"alert(1)\"/>\n\n$image"
        val badges = parseMarkdownFenceBadges(source).badges
        assertEquals(1, badges.size)
        assertEquals(source.lastIndexOf(image), badges.single().start)
    }

    @Test fun `fake hosts credentials local and script URLs stay source`() {
        listOf("https://img.shields.io.evil.test/badge/a", "file://img.shields.io/badge/a", "https://key@img.shields.io/badge/a",
            "javascript:alert(1)", "data:image/svg+xml,abc").forEach {
            assertTrue(it, parseMarkdownFenceBadges("![x]($it)").badges.isEmpty())
        }
    }

    @Test fun `same line badges preserve spacing and mixed link labels`() {
        val source = "start $image  [text $image](https://example.com) end"
        val badges = parseMarkdownFenceBadges(source).badges
        assertEquals(2, badges.size)
        assertEquals("  [text ", source.substring(badges[0].end, badges[1].start))
        assertEquals("](https://example.com) end", source.drop(badges[1].end))
    }

    @Test fun `CRLF empty lines titles and escaped query entities work`() {
        val source = "a\r\n\r\n![Android](<$url> \"title\")\r\n"
        val result = parseMarkdownFenceBadges(source)
        assertEquals(4, result.rows().size)
        assertEquals(buildMarkdownDocument(source).body().html(), 1, result.badges.size)
        assertEquals(url, result.badges.single().url)
        assertEquals(1, result.rows()[2].badges.size)
        assertEquals("", source.substring(result.rows()[1].start, result.rows()[1].end))
    }

    @Test fun `resource limits fall back to complete source not truncation`() {
        val source = (1..40).joinToString("\n") { image }
        val result = parseMarkdownFenceBadges(source)
        assertEquals(MAX_BADGES_PER_FENCE, result.badges.size)
        assertEquals(source, result.source)
        assertEquals(40, result.rows().size)
        assertTrue(parseMarkdownFenceBadges("x".repeat(MAX_BADGE_FENCE_CHARS) + image).badges.isEmpty())
    }

    @Test fun `outer markdown fence is still a single code block with original content`() {
        val source = "<!-- Flat -->\n$image\n```kotlin\nval x = 1\n```"
        val document = buildMarkdownDocument("~~~~markdown\n$source\n~~~~")
        assertEquals(1, document.select("pre > code").size)
        assertEquals(source, document.selectFirst("pre > code")!!.wholeText().trimEnd('\n'))
        assertTrue(document.select("img").isEmpty())
    }
}
