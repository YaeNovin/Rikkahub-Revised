package me.rerere.rikkahub.ui.components.richtext

import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp
import me.rerere.rikkahub.data.datastore.DisplaySetting
import kotlinx.serialization.json.Json
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.parser.MarkdownParser
import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test

class ChatInlineDecorationsTest {
    private val enabled = ChatInlineOptions(hexSwatches = true, quotedText = true)
    private fun analyze(text: String, options: ChatInlineOptions = enabled) = analyzeChatInline(AnnotatedString(text), options)
    private fun quoteTexts(text: AnnotatedString) = analyzeChatInline(text, enabled).quotes.map { text.text.substring(it.start, it.end) }

    @Test fun `only six digit RGB literals with valid boundaries are decorated`() {
        val text = "颜色#A1b2C3，#000000 #FFFFFF (#aabbcc); #FFF #11223344 #1234567 #12GG56 #AABBCCx x#AABBCC ##AABBCC"
        val plan = analyze(text)
        assertEquals(listOf("#A1b2C3", "#000000", "#FFFFFF", "#aabbcc"), plan.hexSwatches.map { text.substring(it.start, it.end) })
        assertEquals(listOf(0xFFA1B2C3.toInt(), 0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 0xFFAABBCC.toInt()), plan.hexSwatches.map { it.argb })
    }

    @Test fun `double quote forms are supported without treating single quotes and apostrophes as delimiters`() {
        val text = AnnotatedString("\"ASCII\" “中文” ＂全角＂ «English» „Deutsch“ 'single' ‘curly single’ don't `code`")
        assertEquals(listOf("ASCII", "中文", "全角", "English", "Deutsch"), quoteTexts(text))
    }

    @Test fun `escaped empty and unfinished quotes do not paint the rest of a streamed paragraph`() {
        assertTrue(analyze("\\\"escaped\\\" \"\" “未完成").quotes.isEmpty())
        assertEquals(listOf("第一行\n第二行"), quoteTexts(AnnotatedString("“第一行\n第二行”")))
        assertEquals(listOf("outer \"inner\" tail"), quoteTexts(AnnotatedString("“outer \"inner\" tail”")))
    }

    @Test fun `both display options work independently and preserve source text`() {
        val raw = "\"色彩 #123456\""
        assertTrue(analyze(raw, ChatInlineOptions()).let { it.hexSwatches.isEmpty() && it.quotes.isEmpty() })
        assertTrue(analyze(raw, ChatInlineOptions(true, false)).quotes.isEmpty())
        assertEquals(1, analyze(raw, ChatInlineOptions(true, false)).hexSwatches.size)
        assertTrue(analyze(raw, ChatInlineOptions(false, true)).hexSwatches.isEmpty())
        assertEquals(1, analyze(raw, ChatInlineOptions(false, true)).quotes.size)
        assertEquals(raw, AnnotatedString(raw).withHexInlineAnnotations(analyze(raw).hexSwatches).text)
    }

    @Test fun `HEX placeholders retain every character link and existing style for selection and accessibility`() {
        val source = buildAnnotatedString {
            withLink(LinkAnnotation.Url("https://example.test/palette")) {
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append("#A1B2C3") }
            }
        }
        val decorated = source.withHexInlineAnnotations(analyzeChatInline(source, enabled).hexSwatches)
        assertEquals(source.text, decorated.text)
        assertEquals(source.spanStyles, decorated.spanStyles)
        assertEquals(source.getLinkAnnotations(0, source.length), decorated.getLinkAnnotations(0, decorated.length))
        assertEquals(source.linkTargetAt(3), decorated.linkTargetAt(3))
        val marker = decorated.getStringAnnotations(0, decorated.length).single()
        assertEquals(0, marker.start)
        assertEquals(7, marker.end)
        assertTrue(analyzeChatInline(decorated, enabled).hexSwatches.isEmpty())
    }

    @Test fun `URLs and existing inline content are excluded without swallowing a closing quotation mark`() {
        val source = buildAnnotatedString {
            append("“https://example.test/#FF0000” ")
            withChatLiteral { append("\"literal #112233\"") }
            append(" #00FF00")
        }
        val plan = analyzeChatInline(source, enabled)
        assertEquals(listOf("#00FF00"), plan.hexSwatches.map { source.text.substring(it.start, it.end) })
        assertTrue(plan.quotes.isEmpty())
    }

    @Test fun `quotes spanning formatting exclude code islands and do not weaken bold or links`() {
        val source = buildAnnotatedString {
            append("“hello ")
            withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append("bold") }
            append(" ")
            withChatLiteral { append("\"code #112233\"") }
            append(" ")
            withLink(LinkAnnotation.Url("https://example.test")) { append("link") }
            append("”")
        }
        val plan = analyzeChatInline(source, enabled)
        val styled = source.withChatQuoteHighlights(plan.quotes, listOf(Color.Black), Color.Cyan, FontWeight.Normal)
        assertEquals(source.text, styled.text)
        assertEquals(source.getLinkAnnotations(0, source.length), styled.getLinkAnnotations(0, styled.length))
        fun styleAt(index: Int) = styled.spanStyles.filter { index in it.start until it.end }
            .fold(SpanStyle()) { result, span -> result.merge(span.item) }
        assertEquals(FontWeight.Bold, styleAt(source.text.indexOf("bold")).fontWeight)
        assertEquals(FontWeight.SemiBold, styleAt(source.text.indexOf("hello")).fontWeight)
        assertEquals(Color.Unspecified, styleAt(source.text.indexOf("code")).color)
        assertEquals(Color.Unspecified, styleAt(0).color)
    }

    @Test fun `real Markdown inline builder excludes code and formula source`() {
        val raw = "“before **bold** `\"code #112233\"` after” #AABBCC"
        val root = MarkdownParser(GFMFlavourDescriptor()).buildMarkdownTreeFromString(raw)
        val text = buildAnnotatedString {
            appendMarkdownNodeContent(root, raw, inlineContents = mutableMapOf(), colorScheme = darkColorScheme(),
                density = Density(1f), style = TextStyle(fontSize = 16.sp), enableLatexRendering = false)
        }
        val plan = analyzeChatInline(text, enabled)
        assertEquals(listOf("#AABBCC"), plan.hexSwatches.map { text.text.substring(it.start, it.end) })
        assertFalse(quoteTexts(text).joinToString("").contains("code"))
        assertTrue(text.spanStyles.any { it.item.fontWeight == FontWeight.Bold })
    }

    @Test fun `HTML builder excludes code keyboard samples and attributes while retaining prose decorations`() {
        val html = Jsoup.parse("<p>“<b>before</b> <code>\"code #112233\"</code> after” <kbd>#223344</kbd> <samp>#334455</samp> <a href='https://example.test/#445566'>#AABBCC</a></p>").selectFirst("p")!!
        val text = buildAnnotatedString {
            appendHtmlInlineNode(html, darkColorScheme(), mutableMapOf(), Density(1f), TextStyle(fontSize = 16.sp), false, {}, preserveSoftLineBreaks = false)
        }
        val plan = analyzeChatInline(text, enabled)
        assertEquals(listOf("#AABBCC"), plan.hexSwatches.map { text.text.substring(it.start, it.end) })
        assertFalse(quoteTexts(text).joinToString("").contains("code"))
        assertEquals("https://example.test/#445566", (text.getLinkAnnotations(0, text.length).single().item as LinkAnnotation.Url).url)
        val fallback = buildAnnotatedStringFromElement(html) {}
        assertEquals(listOf("#AABBCC"), analyzeChatInline(fallback, enabled).hexSwatches.map { fallback.text.substring(it.start, it.end) })
    }

    @Test fun `quoted text on an explicit HTML background resolves contrast for that background`() {
        val source = buildAnnotatedString {
            withStyle(SpanStyle(background = Color.White)) { append("“light surface”") }
            append(" “dark surface”")
        }
        val plan = analyzeChatInline(source, enabled)
        val styled = source.withChatQuoteHighlights(plan.quotes, listOf(Color.Black), Color.Cyan, null)
        val colors = plan.quotes.map { range -> styled.spanStyles.last { range.start in it.start until it.end }.item.color }
        assertTrue(me.rerere.rikkahub.ui.theme.wcagContrastRatio(colors[0], Color.White) >= 4.5f)
        assertTrue(me.rerere.rikkahub.ui.theme.wcagContrastRatio(colors[1], Color.Black) >= 4.5f)
    }

    @Test fun `legacy settings enable both options and disabled settings survive serialization`() {
        val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
        val legacy = json.decodeFromString<DisplaySetting>("{}")
        assertTrue(legacy.showHexColorSwatches)
        assertTrue(legacy.highlightQuotedText)
        val disabled = legacy.copy(showHexColorSwatches = false, highlightQuotedText = false)
        assertEquals(disabled, json.decodeFromString<DisplaySetting>(json.encodeToString(DisplaySetting.serializer(), disabled)))
    }

    @Test fun `long output is handled without changing offsets or collapsing repeated colors`() {
        val raw = ("段落 😀 “彩色内容” #Ab12Cd\n").repeat(1000)
        assertTrue(raw.length > 16_384)
        val text = AnnotatedString(raw)
        val plan = analyzeChatInline(text, enabled)
        assertEquals(1000, plan.hexSwatches.size)
        assertEquals(1000, plan.quotes.size)
        assertEquals(raw, text.withHexInlineAnnotations(plan.hexSwatches).text)
    }
}
