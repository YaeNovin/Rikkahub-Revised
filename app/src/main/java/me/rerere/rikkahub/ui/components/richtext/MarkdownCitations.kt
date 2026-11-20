package me.rerere.rikkahub.ui.components.richtext

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.ViewConfiguration
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.DpSize
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMElementTypes
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.flavours.gfm.GFMTokenTypes
import org.intellij.markdown.parser.MarkdownParser
import org.jsoup.parser.Parser

internal data class MarkdownCitation(val id: String, val label: String)
private val citationId = Regex("[A-Za-z0-9_-]{1,128}")
private val compactCitation = Regex("""\[citation\s*:\s*([^\r\n\]]+)\]""", RegexOption.IGNORE_CASE)
private val literalHtmlOpening = Regex("""^<\s*(code|pre|samp|kbd|script|style)(?:\s[^>]*)?>$""", RegexOption.IGNORE_CASE)
private val citationParser by lazy { MarkdownParser(GFMFlavourDescriptor()) }
private val citationParserLock = Any()
private val citationLiteralTypes = setOf(MarkdownElementTypes.CODE_FENCE, MarkdownElementTypes.CODE_BLOCK,
    MarkdownElementTypes.CODE_SPAN, MarkdownElementTypes.HTML_BLOCK, MarkdownTokenTypes.HTML_TAG,
    MarkdownElementTypes.INLINE_LINK, MarkdownElementTypes.FULL_REFERENCE_LINK,
    MarkdownElementTypes.LINK_DEFINITION, MarkdownElementTypes.IMAGE, MarkdownElementTypes.AUTOLINK,
    GFMTokenTypes.GFM_AUTOLINK, GFMElementTypes.INLINE_MATH, GFMElementTypes.BLOCK_MATH)

internal fun markdownCitations(label: String, destination: String): List<MarkdownCitation> {
    val name = label.trim()
    if (!name.equals("citation", true) && !name.startsWith("citation,", true)) return emptyList()
    val ids = destination.split(',', '，').map(String::trim).distinct()
    if (ids.isEmpty() || ids.any { !citationId.matches(it) }) return emptyList()
    val domain = name.substringAfter(',', "").trim().take(80)
    return ids.mapIndexed { index, id ->
        MarkdownCitation(id, if (domain.isNotEmpty() && ids.size == 1) domain else (index + 1).toString())
    }
}

/** Translate compact model citations only in prose. Use the real Markdown AST
 * to protect nested/unfinished fences, inline code, escaped links and images. */
internal fun normalizeMarkdownCitations(content: String): String {
    if (!content.contains("[citation", ignoreCase = true)) return content
    val matches = compactCitation.findAll(content).toList()
    if (matches.isEmpty()) return content
    val tree = synchronized(citationParserLock) { citationParser.buildMarkdownTreeFromString(content) }
    val protected = mutableListOf<IntRange>()
    fun visit(node: ASTNode) {
        if (node.type == MarkdownTokenTypes.HTML_TAG) {
            val tag = literalHtmlOpening.matchEntire(content.substring(node.startOffset, node.endOffset))?.groupValues?.get(1)
            if (tag != null) {
                val close = Regex("""</\s*$tag\s*>""", RegexOption.IGNORE_CASE).find(content, node.endOffset)
                protected += node.startOffset until (close?.range?.last?.plus(1) ?: content.length)
            }
        }
        if (node.type in citationLiteralTypes) {
            protected += node.startOffset until node.endOffset
            return
        }
        node.children.forEach(::visit)
    }
    visit(tree)
    val numbers = linkedMapOf<String, Int>()
    return compactCitation.replace(content) { match ->
        var backslashes = 0
        var offset = match.range.first - 1
        while (offset >= 0 && content[offset--] == '\\') backslashes++
        val ids = match.groupValues[1].split(',', '，').map(String::trim).distinct()
        if (backslashes % 2 == 1 || protected.any { match.range.first in it } ||
            ids.isEmpty() || ids.any { !citationId.matches(it) }) {
            match.value
        } else {
            ids.joinToString("") { id ->
                val number = numbers.getOrPut(id) { numbers.size + 1 }
                "[citation,$number]($id)"
            }
        }
    }
}

internal fun AnnotatedString.Builder.appendCitationBadge(
    citation: MarkdownCitation,
    inlineContents: MutableMap<String, InlineTextContent>,
    colorScheme: ColorScheme,
    onClickCitation: (String) -> Unit,
) {
    val key = "citation:${citation.id}:${citation.label}"
    inlineContents.putIfAbsent(key, InlineTextContent(
        placeholder = Placeholder(
            width = (citation.label.length * 6 + 12).coerceIn(22, 180).sp,
            height = 1.em,
            placeholderVerticalAlign = PlaceholderVerticalAlign.TextTop,
        ),
    ) {
        val longClick = LocalCitationLinkAction.current
        val surface = rememberReadableTextSurface(colorScheme.primary.copy(alpha = .12f), colorScheme.primary)
        val viewConfiguration = LocalViewConfiguration.current
        val inlineConfiguration = remember(viewConfiguration) {
            object : ViewConfiguration by viewConfiguration {
                // Adjacent inline citations must not compete through overlapping
                // expanded 48dp touch regions. Hit-test their actual placeholders.
                override val minimumTouchTargetSize = DpSize.Zero
            }
        }
        CompositionLocalProvider(LocalViewConfiguration provides inlineConfiguration) {
            DisableSelection {
                Box(Modifier.fillMaxSize().clip(RoundedCornerShape(6.dp)).background(surface.container)
                    .combinedClickable(onClick = { onClickCitation(citation.id) },
                        onLongClick = longClick?.let { { it(citation.id) } })
                    .semantics(mergeDescendants = true) { contentDescription = "引用 ${citation.label}" }, contentAlignment = Alignment.Center) {
                    Text(citation.label, color = surface.foreground, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = TextStyle(fontSize = 11.sp, lineHeight = 12.sp, fontWeight = FontWeight.Medium))
                }
            }
        }
    })
    appendInlineContent(key, "[${citation.label}]")
}

private val markdownEntity = Regex("&(?:#[xX][0-9A-Fa-f]+|#[0-9]+|[A-Za-z][A-Za-z0-9]+);")
private val markdownPunctuation = "!\"#$%&'()*+,-./:;<=>?@[\\]^_`{|}~"

internal fun markdownProseText(source: String): String {
    if ('\\' !in source && '&' !in source) return source
    val result = StringBuilder(source.length)
    var index = 0
    while (index < source.length) {
        if (source[index] == '\\' && source.getOrNull(index + 1)?.let { it in markdownPunctuation } == true) {
            result.append(source[index + 1]); index += 2
        } else if (source[index] == '&') {
            val match = markdownEntity.matchAt(source, index)
            if (match != null) {
                result.append(Parser.unescapeEntities(match.value, false)); index = match.range.last + 1
            } else result.append(source[index++])
        } else result.append(source[index++])
    }
    return result.toString()
}

internal fun markdownCodeSpanText(source: String): String {
    val delimiter = source.takeWhile { it == '`' }.length
    if (delimiter == 0 || source.length < delimiter * 2 || !source.endsWith("`".repeat(delimiter))) return source
    val text = source.substring(delimiter, source.length - delimiter).replace("\r\n", " ").replace('\n', ' ').replace('\r', ' ')
    return if (text.startsWith(' ') && text.endsWith(' ') && text.any { it != ' ' }) text.drop(1).dropLast(1) else text
}

/** The bundled parser represents an angle-delimited destination as AUTOLINK
 * rather than LINK_DESTINATION. Read the already parsed direct child only. */
internal fun markdownLinkDestination(node: ASTNode, content: String): String {
    val destination = node.children.firstOrNull { it.type == MarkdownElementTypes.LINK_DESTINATION }
        ?: node.children.firstOrNull { it.type == MarkdownElementTypes.AUTOLINK }
        ?: return ""
    return markdownProseText(content.substring(destination.startOffset, destination.endOffset).removeSurrounding("<", ">"))
}
