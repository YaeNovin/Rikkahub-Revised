package me.rerere.rikkahub.ui.components.richtext

import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withLink
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.tools.SearchWebCitation
import me.rerere.rikkahub.data.ai.tools.searchWebCitations
import me.rerere.rikkahub.data.ai.tools.withSearchWebCitationMetadata
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.rikkahub.utils.webLinkUrl
import org.junit.Assert.*
import org.junit.Test

class LinkActionsTest {
    @Test fun `hyperlink target keeps destination and visible label separate`() {
        val text = buildAnnotatedString {
            append("See ")
            withLink(LinkAnnotation.Url("https://example.com/path?q=1")) { append("documentation") }
            append(" here")
        }
        assertNull(text.linkTargetAt(0))
        assertEquals(LinkTarget("https://example.com/path?q=1", "documentation"), text.linkTargetAt(5))
        assertNull(text.linkTargetAt(text.length))
    }

    @Test fun `model actions only accept web links and never claim the page was fetched`() {
        assertFalse(LinkTarget("mailto:user@example.com").isWeb)
        assertFalse(LinkTarget("javascript:alert(1)").isWeb)
        assertFalse(LinkTarget("file:///private.txt").isWeb)
        assertFalse(LinkTarget("https:").isWeb)
        val target = LinkTarget("https://example.com/page?x=1&y=2", "source")
        assertTrue(target.isWeb)
        assertTrue(linkQuestionDraft(target, true).endsWith(target.url))
        assertTrue(linkQuestionDraft(target, true).contains("无法读取网页"))
        assertTrue(runCatching { linkQuestionDraft(LinkTarget("file:///private.txt"), true) }.isFailure)
    }

    @Test fun `browser and preview destinations use the same normalized web address`() {
        assertEquals("https://www.example.com/a%20b", webLinkUrl(" www.example.com/a b "))
        assertEquals("https://example.com/path?q=1#source", webLinkUrl("https://example.com/path?q=1#source"))
        assertNull(webLinkUrl("https://account:secret@example.com/"))
        assertNull(webLinkUrl("intent://example.com/"))
    }

    @Test fun `search citations survive tool output truncation and reload`() {
        val full = listOf(UIMessagePart.Text(
            """{"items":[null,{"id":"broken","url":{}},{"id":"abc123","url":"https://example.com","title":"100% coverage"}]}"""
        ))
        val expected = listOf(SearchWebCitation("abc123", "https://example.com", "100% coverage"))
        val original = UIMessagePart.Tool("tool1", "search_web", "{}", output = full)
        assertEquals(expected, original.searchWebCitations())
        val truncated = original.copy(output = listOf(UIMessagePart.Text("[Tool output truncated]")))
        assertTrue(truncated.searchWebCitations().isEmpty())
        val stored = truncated.withSearchWebCitationMetadata(full)
        val reloaded = JsonInstant.decodeFromString<UIMessagePart.Tool>(JsonInstant.encodeToString(stored))
        assertEquals(truncated.output, reloaded.output)
        assertEquals(expected, reloaded.searchWebCitations())
    }
}
