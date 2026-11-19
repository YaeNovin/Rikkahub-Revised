package me.rerere.rikkahub.ui.components.richtext

import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withLink
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
}
