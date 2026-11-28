package me.rerere.rikkahub.ui.components.richtext

import java.util.Base64
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.parser.MarkdownParser
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import me.rerere.rikkahub.data.render.LinguisticRenderingSettings
import me.rerere.rikkahub.utils.escapeHtml
import me.rerere.rikkahub.utils.webLinkUrl

internal const val ACADEMIC_SOURCE = "data-rikka-academic"
private const val MAX_PROSE_FRAGMENT = 32768
private val academicTags = Regex("""<(ruby|ipa|syntax-tree|vertical|app|choice|note|div)\b[^>]*>""", RegexOption.IGNORE_CASE)
private val inlineIpa = Regex("""(?<![\w/:\\])/(?!/)([^/\r\n<>`]{1,128})/(?![\w/])""")
private val ipaSymbols = Regex("[\u0250-\u02ff\u0300-\u036f\u1d00-\u1d7f]")

internal fun protectProseLiteralHtml(content: String): String {
    if (!content.contains("<code", true) && !content.contains("<samp", true)) return content
    val ranges = proseLiteralRanges(content)
    return Regex("""<(code|samp)\b[^>]*>[^\r\n]*?</\1\s*>""", RegexOption.IGNORE_CASE).replace(content) { match ->
        if (ranges.any { match.range.first in it && it.first != match.range.first }) match.value else {
            val ticks = "`".repeat(maxOf(1, (Regex("`+").findAll(match.value).maxOfOrNull { it.value.length } ?: 0) + 1))
            ticks + match.value + ticks
        }
    }
}

/** Opaque Markdown regions, including destinations and HTML attributes, never become notation. */
internal fun proseLiteralRanges(content: String, protectMath: Boolean = true): List<IntRange> {
    val ranges = mutableListOf<IntRange>()
    val tree = MarkdownParser(GFMFlavourDescriptor()).buildMarkdownTreeFromString(content)
    val opaque = setOf(MarkdownElementTypes.CODE_FENCE, MarkdownElementTypes.CODE_BLOCK, MarkdownElementTypes.CODE_SPAN,
        MarkdownElementTypes.INLINE_LINK, MarkdownElementTypes.FULL_REFERENCE_LINK, MarkdownElementTypes.SHORT_REFERENCE_LINK,
        MarkdownElementTypes.LINK_DEFINITION, MarkdownElementTypes.IMAGE, MarkdownElementTypes.AUTOLINK)
    fun visit(node: org.intellij.markdown.ast.ASTNode) {
        if (node.type in opaque || node.type == MarkdownTokenTypes.HTML_TAG ||
            (protectMath && node.type.toString() in setOf("INLINE_MATH", "BLOCK_MATH"))) {
            ranges += node.startOffset until node.endOffset
        } else node.children.forEach(::visit)
    }
    visit(tree)
    // Inline literal HTML can contain delimiters too. Do not interpret its contents.
    Regex("""<(code|pre|script|style|kbd)\b[^>]*>[\s\S]*?(?:</\1\s*>|$)""", RegexOption.IGNORE_CASE)
        .findAll(content).forEach { ranges += it.range }
    return ranges.sortedBy { it.first }
}

internal fun academicSourceMarker(source: String, block: Boolean): String {
    val encoded = Base64.getEncoder().encodeToString(source.toByteArray(Charsets.UTF_8))
    val tag = if (block) "div" else "span"
    return "<$tag $ACADEMIC_SOURCE=\"$encoded\"></$tag>"
}

/** Recognize complete, explicit academic markup only; never guess analysis or convert bare JSON. */
internal fun protectAcademicProse(content: String): String {
    if ('<' !in content) return content
    val ranges = proseLiteralRanges(content)
    val edits = mutableListOf<Pair<IntRange, String>>()
    var consumed = -1
    academicTags.findAll(content).forEach { opening ->
        val start = opening.range.first
        if (start <= consumed || ranges.any { start in it && it.first != start }) return@forEach
        val tag = opening.groupValues[1].lowercase()
        if (tag == "div" && !Regex("""(?:vertical-rl|vertical-lr)""").containsMatchIn(opening.value)) return@forEach
        val close = Regex("""</$tag\s*>""", RegexOption.IGNORE_CASE).find(content, opening.range.last + 1) ?: return@forEach
        if (close.range.last - start >= MAX_PROSE_FRAGMENT) return@forEach
        val source = content.substring(start, close.range.last + 1)
        edits += (start..close.range.last) to academicSourceMarker(source, tag in setOf("vertical", "div", "syntax-tree"))
        consumed = close.range.last
    }
    return StringBuilder(content).apply { edits.asReversed().forEach { (range, value) -> replace(range.first, range.last + 1, value) } }.toString()
}

/** IPA auto-detection requires distinctive IPA/combining characters. ASCII transcriptions use <ipa>. */
internal fun decorateInlineIpa(content: String): String {
    if ('/' !in content || !ipaSymbols.containsMatchIn(content)) return content
    val ranges = proseLiteralRanges(content)
    return inlineIpa.replace(content) { match ->
        if (ranges.any { match.range.first in it } || !ipaSymbols.containsMatchIn(match.groupValues[1]) ||
            match.groupValues[1].any { it in "=:{}[]" }) match.value
        else academicSourceMarker("<ipa>${match.groupValues[1].escapeHtml()}</ipa>", false)
    }
}

internal fun academicSource(element: Element): String? = element.attr(ACADEMIC_SOURCE).takeIf { it.length in 1..(MAX_PROSE_FRAGMENT * 4) }?.let {
    runCatching { String(Base64.getDecoder().decode(it), Charsets.UTF_8) }.getOrNull()?.takeIf { text -> text.length <= MAX_PROSE_FRAGMENT }
}

internal fun hasAcademicProse(element: Element): Boolean = element.hasAttr(ACADEMIC_SOURCE) ||
    element.select("[$ACADEMIC_SOURCE], span.math").isNotEmpty()

/** Rebuild an allowlisted DOM. Original style, URLs, event handlers and namespace declarations never survive. */
internal fun academicProseHtml(nodes: List<Node>, settings: LinguisticRenderingSettings, math: Boolean,
    decorations: ChatInlineOptions = ChatInlineOptions()): String {
    val output = Element("div")
    var count = 0
    fun append(node: Node, parent: Element, depth: Int) {
        if (++count > 4096 || depth > 32) { parent.appendText("[内容过长，请拆分段落]"); return }
        if (node is TextNode) {
            val value = node.wholeText
            if ((!decorations.hexSwatches && !decorations.quotedText) || parent.closest("code,kbd,ruby,rt,a,.ipa") != null) {
                parent.appendText(value); return
            }
            val plan = analyzeChatInline(androidx.compose.ui.text.AnnotatedString(value), decorations)
            val marks = plan.quotes.flatMap { listOf(it.start, it.end) } + plan.hexSwatches.flatMap { listOf(it.start, it.end) }
            var cursor = 0
            (marks + value.length).distinct().sorted().forEach { end ->
                if (end <= cursor) return@forEach
                val literal = value.substring(cursor, end)
                val target = if (plan.quotes.any { cursor >= it.start && end <= it.end }) parent.appendElement("span").addClass("quote-accent") else parent
                val swatch = plan.hexSwatches.firstOrNull { it.start == cursor && it.end == end }
                if (swatch != null) target.appendElement("span").addClass("hex-swatch").attr("aria-hidden", "true")
                    .attr("style", "background:$literal")
                target.appendText(literal)
                cursor = end
            }
            return
        }
        if (node !is Element) return
        academicSource(node)?.let { source ->
            Jsoup.parseBodyFragment(source).body().childNodes().forEach { append(it, parent, depth + 1) }
            return
        }
        val tag = node.normalName()
        val lang = node.attr("lang").takeIf { Regex("[A-Za-z0-9-]{1,48}").matches(it) }
        fun children(target: Element) { node.childNodes().forEach { append(it, target, depth + 1) } }
        fun literal() { parent.appendText(node.outerHtml()) }
        when {
            tag == "ipa" || tag == "span" && node.hasClass("ipa") -> {
                if (!settings.ipa) { parent.appendText("/" + node.text().trim().trim('/', '[', ']') + "/"); return }
                parent.appendElement("span").addClass("ipa").attr("lang", lang ?: "und-fonipa")
                    .text("/" + node.text().trim().trim('/', '[', ']') + "/")
            }
            tag == "ruby" -> {
                if (!settings.ruby) { literal(); return }
                val ruby = parent.appendElement("ruby")
                val position = node.attr("data-position").ifBlank { node.attr("ruby-position") }
                ruby.addClass(when (position) { "under" -> "ruby-under"; "inter-character" -> "ruby-inter"; else -> "ruby-over" })
                if (lang != null) ruby.attr("lang", lang)
                children(ruby)
            }
            tag in setOf("rt", "rp", "rb") -> children(parent.appendElement(tag))
            tag == "vertical" || tag == "div" && (node.hasClass("vertical-rl") || node.hasClass("vertical-lr") || node.attr("style").contains("vertical-")) -> {
                if (!settings.literary) { literal(); return }
                val wrap = parent.appendElement("div").addClass("vertical-scroll")
                val vertical = wrap.appendElement("div").addClass(if (node.hasClass("vertical-lr") || node.attr("writing-mode") == "vertical-lr") "vertical-lr" else "vertical-rl")
                vertical.attr("lang", lang ?: "ja")
                children(vertical)
            }
            tag == "app" -> {
                if (!settings.literary) { literal(); return }
                val details = parent.appendElement("details").addClass("apparatus")
                val lemma = node.children().firstOrNull { it.normalName() == "lem" }
                val summary = details.appendElement("summary")
                if (lemma == null) summary.appendText("校勘注") else append(lemma, summary, depth + 1)
                summary.appendElement("sup").text("〔校〕")
                node.children().filter { it !== lemma }.forEach { child ->
                    val line = details.appendElement("div").addClass("reading")
                    val witness = child.attr("wit").trim().take(240)
                    if (witness.isNotBlank()) line.appendElement("b").text(witness.replace("#", "") + "：")
                    append(child, line, depth + 1)
                }
            }
            tag == "choice" -> {
                if (!settings.literary) { literal(); return }
                val details = parent.appendElement("details").addClass("apparatus")
                val preferred = node.children().firstOrNull { it.normalName() in setOf("corr", "reg", "expan") }
                    ?: node.children().firstOrNull()
                val summary = details.appendElement("summary")
                preferred?.let { append(it, summary, depth + 1) }
                summary.appendElement("sup").text("〔异〕")
                node.children().forEach { child ->
                    val line = details.appendElement("div"); line.appendElement("b").text(child.normalName() + "：")
                    append(child, line, depth + 1)
                }
            }
            tag == "note" -> {
                if (!settings.literary) { literal(); return }
                val details = parent.appendElement("details").addClass("apparatus")
                details.appendElement("summary").text("〔" + node.attr("n").take(40).ifBlank { "注" } + "〕")
                children(details.appendElement("div"))
            }
            tag == "syntax-tree" -> {
                if (!settings.syntaxTree) { literal(); return }
                val data = runCatching { me.rerere.rikkahub.data.render.compileLinguisticSpec("syntax_tree", node.wholeText()) }.getOrNull()
                if (data == null) parent.appendText("[句法树格式不正确] " + node.text())
                else parent.appendElement("div").addClass("syntax-tree").attr("data-spec", data.toString())
            }
            tag == "span" && node.hasClass("math") -> {
                if (!math) { parent.appendText(node.wholeText()); return }
                parent.appendElement("span").addClass(if (node.attr("inline") == "true") "prose-math" else "prose-math display-math")
                    .text(node.wholeText().trim().removeSurrounding("$$").removeSurrounding("$"))
            }
            tag == "a" -> {
                val citations = markdownCitations(node.text(), node.attr("href"))
                if (citations.isNotEmpty()) {
                    citations.forEach { citation -> parent.appendElement("a").attr("href", "rikka-citation:" + citation.id)
                        .appendElement("sup").text("[${citation.label}]") }
                    return
                }
                val href = webLinkUrl(node.attr("href"))
                if (href == null) children(parent) else children(parent.appendElement("a").attr("href", href))
            }
            tag == "lb" -> parent.appendElement("br")
            tag == "gap" -> parent.appendElement("span").text("〔缺文" + node.attr("extent").take(24) + "〕")
            tag == "supplied" -> { parent.appendText("〔"); children(parent); parent.appendText("〕") }
            tag == "unclear" -> children(parent.appendElement("span").addClass("unclear"))
            tag == "tcy" -> children(parent.appendElement("span").addClass("tcy"))
            // HTML's <p> cannot contain <details>; a prose div preserves inline
            // apparatus instead of the browser silently splitting the sentence.
            tag == "p" -> children(parent.appendElement("div").addClass("prose-paragraph"))
            tag in setOf("div", "span", "strong", "b", "em", "i", "s", "del", "sub", "sup", "br", "code", "kbd") -> children(parent.appendElement(tag))
            tag in setOf("lem", "rdg", "sic", "corr", "orig", "reg", "abbr", "expan") -> children(parent)
            else -> literal()
        }
    }
    nodes.forEach { append(it, output, 0) }
    return output.html()
}
