package me.rerere.rikkahub.ui.components.richtext

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import me.rerere.rikkahub.ui.theme.wcagContrastRatio
import org.junit.Assert.*
import org.junit.Test

class ReadableTextSurfaceTest {
    @Test fun `contrast correction never introduces span backgrounds on a mixed wallpaper`() {
        val input = buildAnnotatedString {
            append("正文 ")
            withStyle(SpanStyle(color = Color.Gray)) { append("注释") }
        }
        val actual = input.withReadableSpans(listOf(Color.Black, Color.White), Color.Black)
        assertEquals(input.text, actual.text)
        assertTrue(actual.spanStyles.all { it.item.background == Color.Unspecified })
    }

    @Test fun `links and inline code retain data and formatting while gaining contrast`() {
        val input = buildAnnotatedString {
            append("注释 ")
            withLink(LinkAnnotation.Url("https://example.test")) {
                withStyle(SpanStyle(color = Color(0xFFAAAAFF), textDecoration = TextDecoration.Underline)) { append("链接") }
            }
            withStyle(SpanStyle(background = Color.Black)) {
                withStyle(SpanStyle(color = Color(0xFF333333))) { append(" code") }
            }
        }
        val actual = input.withReadableSpans(listOf(Color.White), Color.Black)
        assertEquals(input.text, actual.text)
        assertEquals(input.getLinkAnnotations(0, input.length), actual.getLinkAnnotations(0, actual.length))
        assertEquals(input.linkTargetAt(3), actual.linkTargetAt(3))
        fun styleAt(text: AnnotatedString, index: Int) = text.spanStyles
            .filter { index >= it.start && index < it.end }.fold(SpanStyle()) { style, span -> style.merge(span.item) }
        val link = styleAt(actual, 3)
        assertEquals(TextDecoration.Underline, link.textDecoration)
        assertTrue(wcagContrastRatio(link.color, Color.White) >= 4.5f)
        val code = styleAt(actual, actual.lastIndex)
        assertEquals(Color.Black, code.background)
        assertTrue(wcagContrastRatio(code.color, code.background) >= 4.5f)
    }
}
