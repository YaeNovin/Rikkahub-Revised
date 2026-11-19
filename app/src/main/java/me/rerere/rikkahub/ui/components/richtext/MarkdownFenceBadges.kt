package me.rerere.rikkahub.ui.components.richtext

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import me.rerere.highlight.HighlightTextColorPalette
import me.rerere.rikkahub.ui.theme.JetbrainsMono
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.html.HtmlGenerator
import org.intellij.markdown.parser.MarkdownParser
import org.jsoup.Jsoup
import java.net.URI

internal const val MAX_BADGE_FENCE_CHARS = 64 * 1024
internal const val MAX_BADGES_PER_FENCE = 32
internal fun isMarkdownBadgeFence(language: String): Boolean =
    normalizeCodeFenceLanguage(language) in setOf("markdown", "md", "gfm", "text/markdown")

internal data class MarkdownFenceBadge(val start: Int, val end: Int, val url: String, val alt: String, val link: String?)
internal data class MarkdownBadgeDocument(val source: String, val badges: List<MarkdownFenceBadge>)
private val badgeFenceCache = LinkedHashMap<String, MarkdownBadgeDocument>(16, .75f, true)
private fun cachedBadgeFence(source: String) = synchronized(badgeFenceCache) { badgeFenceCache[source] }
private val angleImageDestination = Regex("""^!\[[\s\S]*]\(\s*<([^<>\r\n]+)>(?:\s+["'][^\r\n]*["'])?\s*\)$""")

/** Parse Markdown only to locate images. All other bytes remain highlighted source, never rendered HTML. */
internal fun parseMarkdownFenceBadges(source: String): MarkdownBadgeDocument {
    cachedBadgeFence(source)?.let { return it }
    if (source.length > MAX_BADGE_FENCE_CHARS || "![" !in source) return MarkdownBadgeDocument(source, emptyList())
    val flavour = GFMFlavourDescriptor()
    val tree = MarkdownParser(flavour).buildMarkdownTreeFromString(source)
    val images = mutableMapOf<Int, ASTNode>()
    val parents = mutableMapOf<ASTNode, ASTNode>()
    fun visit(node: ASTNode) {
        if (node.type in setOf(MarkdownElementTypes.CODE_FENCE, MarkdownElementTypes.CODE_BLOCK,
                MarkdownElementTypes.CODE_SPAN, MarkdownElementTypes.HTML_BLOCK)) return
        if (node.type == MarkdownElementTypes.IMAGE) { images[node.startOffset] = node; return }
        node.children.forEach { parents[it] = node; visit(it) }
    }
    visit(tree)
    val marker = "data-fence-badge-" + java.util.UUID.randomUUID().toString().replace("-", "")
    // Each generator-owned img gets a source offset; raw author HTML is never a candidate.
    val html = HtmlGenerator(source, tree, flavour).generateHtml { node, tag, attributes ->
        if (tag.toString() == "img" && images[node.startOffset] === node) {
            attributes + "$marker=\"${node.startOffset}\""
        } else attributes
    }
    val document = Jsoup.parse(html)
    val badges = document.select("img[$marker]").mapNotNull { image ->
        val offset = image.attr(marker).toIntOrNull() ?: return@mapNotNull null
        val node = images[offset] ?: return@mapNotNull null
        // The bundled generator can confuse an angle-delimited destination with its title.
        // Recover only this unambiguous shape within an already-parsed IMAGE node.
        val url = angleImageDestination.matchEntire(source.substring(node.startOffset, node.endOffset))
            ?.groupValues?.get(1) ?: image.attr("src")
        val uri = runCatching { URI(url) }.getOrNull() ?: return@mapNotNull null
        if (uri.scheme?.lowercase() !in setOf("http", "https") || uri.userInfo != null || !isBadgeImage(url)) return@mapNotNull null
        var start = node.startOffset
        var end = node.endOffset
        val anchor = image.parent()?.takeIf { it.normalName() == "a" }
        // Collapse [![badge](image)](target) as one unit, but never eat text in a mixed link label.
        val label = parents[node]
        val link = label?.let { parents[it] }
        if (anchor != null && label?.type == MarkdownElementTypes.LINK_TEXT && link != null &&
            source.substring(label.startOffset, label.endOffset).removeSurrounding("[", "]") == source.substring(start, end)) {
            start = link.startOffset
            end = link.endOffset
        }
        if ('\n' in source.substring(start, end) || '\r' in source.substring(start, end)) return@mapNotNull null
        MarkdownFenceBadge(start, end, url, image.attr("alt"), safeImageLink(anchor?.attr("href")))
    }.sortedBy { it.start }.take(MAX_BADGES_PER_FENCE)
    return MarkdownBadgeDocument(source, badges).also { result ->
        synchronized(badgeFenceCache) {
            badgeFenceCache[source] = result
            var chars = badgeFenceCache.keys.sumOf(String::length)
            while (badgeFenceCache.size > 24 || chars > 512 * 1024) {
                val oldest = badgeFenceCache.keys.first()
                chars -= oldest.length
                badgeFenceCache.remove(oldest)
            }
        }
    }
}

@Composable
internal fun rememberMarkdownFenceBadges(source: String, enabled: Boolean): MarkdownBadgeDocument? {
    val eligible = enabled && source.length <= MAX_BADGE_FENCE_CHARS && "![" in source
    val result by produceState(if (eligible) cachedBadgeFence(source) else null, source, eligible) {
        value = if (!eligible) null else withContext(RichTextParsingDispatcher) {
            try { parseMarkdownFenceBadges(source) } catch (error: Exception) {
                if (error is CancellationException) throw error
                me.rerere.rikkahub.data.render.RenderDiagnostics.report("Markdown badges", "parse", error.javaClass.simpleName, error)
                MarkdownBadgeDocument(source, emptyList())
            }
        }
    }
    val current = result?.takeIf { eligible && it.source == source }
    me.rerere.rikkahub.ui.components.ui.AwaitExportRender(eligible && current == null)
    return current
}

internal data class MarkdownBadgeRow(val start: Int, val end: Int, val badges: List<MarkdownFenceBadge>)
internal fun MarkdownBadgeDocument.rows(): List<MarkdownBadgeRow> = buildList {
    var start = 0
    var badgeIndex = 0
    while (start <= source.length) {
        val newline = source.indexOf('\n', start).takeIf { it >= 0 } ?: source.length
        val end = if (newline > start && source[newline - 1] == '\r') newline - 1 else newline
        val rowBadges = mutableListOf<MarkdownFenceBadge>()
        while (badgeIndex < badges.size && badges[badgeIndex].start < newline) {
            val badge = badges[badgeIndex++]
            if (badge.start >= start && badge.end <= end) rowBadges += badge
        }
        add(MarkdownBadgeRow(start, end, rowBadges))
        if (newline == source.length) break
        start = newline + 1
    }
}

@Composable
internal fun MarkdownBadgeCodeRows(document: MarkdownBadgeDocument, highlighted: AnnotatedString, maxLines: Int,
    showLineNumbers: Boolean, autoWrap: Boolean, scrollState: ScrollState, textStyle: TextStyle, palette: HighlightTextColorPalette) {
    val rows = remember(document, maxLines) { document.rows().take(maxLines) }
    val lineNumberWidth = rows.size.toString().length
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val badgeWidth = maxWidth.value.coerceAtMost(360f)
        SelectionContainer {
            Column(Modifier.then(if (autoWrap) Modifier.fillMaxWidth() else Modifier.horizontalScroll(scrollState))) {
                rows.forEachIndexed { index, row ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (showLineNumbers) Text((index + 1).toString().padStart(lineNumberWidth, ' '),
                            style = textStyle, fontFamily = JetbrainsMono, color = palette.comment,
                            softWrap = false, modifier = Modifier.padding(end = 8.dp))
                        val contents: @Composable () -> Unit = {
                            var offset = row.start
                            row.badges.forEach { badge ->
                                if (offset < badge.start) BadgeSourceText(highlighted.subSequence(offset, badge.start), textStyle, palette, autoWrap)
                                ZoomableAsyncImage(badge.url, badge.alt, respectIntrinsicSize = true, linkUrl = badge.link,
                                    maxDisplayWidthDp = badgeWidth, maxDisplayHeightDp = 40f)
                                offset = badge.end
                            }
                            if (offset < row.end || row.badges.isEmpty()) BadgeSourceText(highlighted.subSequence(offset, row.end), textStyle, palette, autoWrap)
                        }
                        if (autoWrap) FlowRow(Modifier.weight(1f), verticalArrangement = Arrangement.Center) { contents() }
                        else Row(verticalAlignment = Alignment.CenterVertically) { contents() }
                    }
                }
            }
        }
    }
}

@Composable
private fun BadgeSourceText(text: AnnotatedString, style: TextStyle, palette: HighlightTextColorPalette, wrap: Boolean) {
    Text(text, style = style, color = palette.fallback, fontFamily = JetbrainsMono, softWrap = wrap, overflow = TextOverflow.Visible)
}
