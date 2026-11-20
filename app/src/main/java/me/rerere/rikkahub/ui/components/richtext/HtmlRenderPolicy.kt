package me.rerere.rikkahub.ui.components.richtext

import me.rerere.rikkahub.ui.components.webview.WEB_VIEW_ASSET_URL
import me.rerere.rikkahub.utils.escapeHtml
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.parser.MarkdownParser
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.util.Base64

internal const val MAX_STATIC_PREVIEW_CHARS = 128 * 1024
internal const val STATIC_PREVIEW_MARKER = "static-safe"

internal fun codePreviewDefaultsToRendered(language: String): Boolean =
    normalizeCodeFenceLanguage(language) != "html"

private val svgPlaceholder = Regex("""<div data-rikka-svg-source="[A-Za-z0-9+/=]*" data-rikka-svg-complete="(?:true|false)"(?: data-rikka-source-language="xml")?></div>""")
internal const val RAW_HTML_SOURCE_ATTRIBUTE = "data-rikka-html-source"

internal fun rawHtmlSource(element: Element): String? = element.attr(RAW_HTML_SOURCE_ATTRIBUTE)
    .takeIf { it.isNotEmpty() }?.let { runCatching { String(Base64.getDecoder().decode(it), Charsets.UTF_8) }.getOrNull() }

private fun restoreProtectedSvg(source: String): String = svgPlaceholder.replace(source) { match ->
    rawSvgSource(Jsoup.parse(match.value).selectFirst("div")!!)?.source ?: match.value
}

private fun htmlSourceBlock(source: String): String {
    val original = restoreProtectedSvg(source)
    val encoded = Base64.getEncoder().encodeToString(original.toByteArray(Charsets.UTF_8))
    return "<pre $RAW_HTML_SOURCE_ATTRIBUTE=\"$encoded\"><code class=\"language-html\">${original.escapeHtml()}</code></pre>\n"
}

/** Preserve plain typographic tag pairs and protect other author HTML as source. */
internal fun protectRawHtml(content: String): String {
    if ('<' !in content) return content
    // A complete document should not be split into independent head/script/body
    // blocks by Markdown. Keep its original source available as one manual preview.
    if (Regex("^[\\r\\n]* {0,3}(?:<!doctype\\s+html\\b|<html(?:\\s|>))", RegexOption.IGNORE_CASE).containsMatchIn(content)) {
        return htmlSourceBlock(content)
    }
    val tree = MarkdownParser(GFMFlavourDescriptor()).buildMarkdownTreeFromString(content)
    val typographyOffsets = plainTypographyTagOffsets(content)
    val replacements = mutableListOf<Pair<IntRange, String>>()
    fun visit(node: ASTNode) {
        if (node.type == MarkdownElementTypes.CODE_SPAN || node.type == MarkdownElementTypes.CODE_FENCE ||
            node.type == MarkdownElementTypes.CODE_BLOCK) return
        if (node.type == MarkdownElementTypes.HTML_BLOCK || node.type == MarkdownTokenTypes.HTML_TAG) {
            if (node.type == MarkdownTokenTypes.HTML_TAG && node.startOffset in typographyOffsets) return
            val source = content.substring(node.startOffset, node.endOffset)
            // SVG is a separate typed renderer; its WebView input is sanitized as well.
            if (svgPlaceholder.containsMatchIn(source) && '<' !in svgPlaceholder.replace(source, "")) return
            val replacement = if (node.type == MarkdownElementTypes.HTML_BLOCK) {
                htmlSourceBlock(source)
            } else restoreProtectedSvg(source).escapeHtml()
            replacements += (node.startOffset until node.endOffset) to replacement
        } else node.children.forEach(::visit)
    }
    visit(tree)
    val output = StringBuilder(content)
    replacements.asReversed().forEach { (range, replacement) ->
        output.replace(range.first, range.last + 1, replacement)
    }
    return output.toString()
}

// An allowlist, not a list of known script spellings. Unknown active/foreign
// elements and all author URLs except same-document references/raster data vanish.
private val staticTags = ("html head body title style div span p br hr pre code blockquote " +
    "h1 h2 h3 h4 h5 h6 ul ol li dl dt dd table thead tbody tfoot tr th td caption colgroup col " +
    "strong b em i u s del ins small sub sup mark abbr q cite samp kbd var a img " +
    "section article header footer main nav aside figure figcaption details summary progress " +
    "svg g defs symbol use image path rect circle ellipse line polyline polygon text tspan textpath " +
    "lineargradient radialgradient stop pattern clippath mask marker filter " +
    "feblend fecolormatrix fecomponenttransfer fecomposite feconvolvematrix fediffuselighting " +
    "fedisplacementmap fedistantlight fedropshadow feflood fefunca fefuncb fefuncg fefuncr " +
    "fegaussianblur feimage femerge femergenode femorphology feoffset fepointlight " +
    "fespecularlighting fespotlight fetile feturbulence animate animatetransform animatemotion mpath set")
    .split(' ').toSet()
private val urlAttributes = setOf("href", "xlink:href", "src")
private val removedAttributes = setOf("srcdoc", "srcset", "action", "formaction", "target", "ping", "is", "autofocus", "contenteditable", "nonce")
private val visualAnimationAttributes = setOf("x", "y", "x1", "y1", "x2", "y2", "cx", "cy", "r", "rx", "ry", "d", "points", "width", "height", "opacity", "fill", "stroke", "stroke-width", "stroke-dasharray", "stroke-dashoffset", "transform", "offset", "stop-color", "stop-opacity")
private val rasterData = Regex("^data:image/(?:png|jpeg|gif|webp);base64,[A-Za-z0-9+/=\\s]+$", RegexOption.IGNORE_CASE)

internal fun sanitizeStaticHtml(source: String, svgOnly: Boolean = false): String {
    val document = Jsoup.parse(source)
    document.outputSettings().prettyPrint(false)
    document.getAllElements().toList().forEach { element ->
        if (element === document) return@forEach
        val tag = element.normalName()
        if (tag !in staticTags || (tag in setOf("animate", "set", "animatetransform") &&
                element.attr("attributeName").lowercase() !in visualAnimationAttributes)) {
            element.remove()
            return@forEach
        }
        element.attributes().asList().forEach { attr ->
            val name = attr.key.lowercase()
            val value = attr.value.trim()
            if (name.startsWith("on") || name in removedAttributes || name.startsWith("data-rikka") ||
                name in urlAttributes && !(value.startsWith('#') && !value.any { it.isWhitespace() } ||
                    tag in setOf("img", "image", "feimage") && rasterData.matches(value))) {
                element.removeAttr(attr.key)
            }
        }
    }
    return if (svgOnly) document.selectFirst("svg")?.outerHtml().orEmpty() else document.outerHtml()
}

internal fun buildStaticHtmlPreview(source: String, svgOnly: Boolean): String {
    require(source.length <= MAX_STATIC_PREVIEW_CHARS) { "HTML/SVG preview source is too large" }
    val document = Jsoup.parse(sanitizeStaticHtml(source, svgOnly))
    document.outputSettings().prettyPrint(false)
    // HTML without author colors uses the browser's black text. Give it a
    // paper surface instead of exposing the app's dark background through it.
    // Prepend defaults so author styles and inline colors keep precedence.
    if (!svgOnly) document.head().prependElement("style")
        .attr("id", "rikkahub-static-paper")
        .append("html { color:#111111; background:#ffffff; }")
    val assets = "$WEB_VIEW_ASSET_URL/html"
    // Only app-owned code can run. No inline handlers, eval, remote resources or frames.
    val csp = "default-src 'none'; script-src $assets/static-preview.js $assets/fullscreen-pan.js $assets/fullscreen-background.js; style-src 'unsafe-inline'; img-src data:; connect-src 'none'; frame-src 'none'; object-src 'none'; base-uri 'none'; form-action 'none'"
    document.head().prependElement("meta").attr("http-equiv", "Content-Security-Policy").attr("content", csp)
    document.head().prependElement("meta").attr("charset", "utf-8")
    document.head().appendElement("meta").attr("name", "rikkahub-preview").attr("content", STATIC_PREVIEW_MARKER)
    document.head().appendElement("meta").attr("name", "viewport").attr("content", "width=device-width, initial-scale=1.0, minimum-scale=0.25, maximum-scale=8.0, user-scalable=yes")
    document.head().appendElement("style").append("""
        html,body { width:100%; min-height:100%; margin:0; overflow:auto; ${if (svgOnly) "background:transparent;" else ""} }
        img,svg { max-width:100%; height:auto; }
        body > svg { display:block; margin:auto; overflow:visible; }
    """.trimIndent())
    if (svgOnly) document.body().attr("data-rikka-static-svg", "true")
    document.body().appendElement("script").attr("src", "$assets/static-preview.js")
    return document.outerHtml()
}

internal fun isStaticHtmlPreview(content: String): Boolean =
    content.contains(STATIC_PREVIEW_MARKER) &&
        Jsoup.parse(content).head().selectFirst("meta[name=rikkahub-preview][content=$STATIC_PREVIEW_MARKER]") != null
