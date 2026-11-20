package me.rerere.rikkahub.ui.components.richtext

import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMElementTypes
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.flavours.gfm.GFMTokenTypes
import org.intellij.markdown.parser.MarkdownParser

private val proseParser = ThreadLocal.withInitial { MarkdownParser(GFMFlavourDescriptor()) }
private val footnoteDefinitionStart = Regex("""(?m)^ {0,3}\[\^[^\]\r\n]+]:[ \t]*""")
private const val MAIL_LOCAL = "[a-z0-9_+\\-]+(?:\\.[a-z0-9_+\\-]+)*"
private const val MAIL_DOMAIN = "(?:[a-z0-9](?:[a-z0-9-]*[a-z0-9])?\\.)+[a-z]{2,63}"
private val mailLink = Regex(
    """(?<![\w.+@/\\<:-])(?:<($MAIL_LOCAL@$MAIL_DOMAIN)>|($MAIL_LOCAL@$MAIL_DOMAIN)(?![\w@>-]|\.[a-z0-9]))""",
    RegexOption.IGNORE_CASE,
)
private val literalInlineHtml = Regex("""<\s*(a|code|pre|samp|kbd|script|style)(?:\s[^>]*)?>""", RegexOption.IGNORE_CASE)
private val typographyTagPair = Regex("""<(sup|sub|kbd)>([^<>\\\x60\r\n]+)</\1>""", RegexOption.IGNORE_CASE)

private val opaqueBlocks = setOf(
    MarkdownElementTypes.CODE_FENCE, MarkdownElementTypes.CODE_BLOCK, MarkdownElementTypes.CODE_SPAN,
    MarkdownElementTypes.HTML_BLOCK, GFMElementTypes.INLINE_MATH, GFMElementTypes.BLOCK_MATH,
)
private val linkNodes = setOf(
    MarkdownElementTypes.INLINE_LINK, MarkdownElementTypes.FULL_REFERENCE_LINK,
    MarkdownElementTypes.SHORT_REFERENCE_LINK, MarkdownElementTypes.LINK_DEFINITION,
    MarkdownElementTypes.IMAGE, MarkdownElementTypes.AUTOLINK, GFMTokenTypes.GFM_AUTOLINK,
)

internal fun plainTypographyTagOffsets(content: String): Set<Int> =
    typographyTagPair.findAll(content).flatMap { match ->
        sequenceOf(match.range.first, match.range.last - match.groupValues[1].length - 2)
    }.toSet()

internal fun normalizeMarkdownLinkDestination(link: String): String =
    if (link.startsWith("www.", ignoreCase = true)) "https://$link" else link

/** Create common Markdown email links without rewriting code, existing links or destinations. */
internal fun normalizeMarkdownProseLinks(content: String, linkEmails: Boolean = true): String {
    val emails = if (linkEmails && '@' in content) mailLink.findAll(content).toList() else emptyList()
    val footnoteDefinitions = if ("[^" in content) footnoteDefinitionStart.findAll(content).toList() else emptyList()
    if (emails.isEmpty() && footnoteDefinitions.isEmpty()) return content

    val blockRanges = mutableListOf<IntRange>()
    val linkRanges = mutableListOf<IntRange>()
    fun visit(node: ASTNode) {
        val range = node.startOffset until node.endOffset
        if (node.type in opaqueBlocks) {
            blockRanges += range
            return
        }
        if (node.type in linkNodes) {
            linkRanges += range
            return
        }
        if (node.type == MarkdownTokenTypes.HTML_TAG) {
            linkRanges += range
            val tag = literalInlineHtml.matchEntire(content.substring(node.startOffset, node.endOffset))
                ?.groupValues?.get(1)
            if (tag != null) {
                val close = content.indexOf("</$tag>", node.endOffset, ignoreCase = true)
                linkRanges += node.startOffset until if (close < 0) content.length else close + tag.length + 3
            }
        }
        node.children.forEach(::visit)
    }
    visit(checkNotNull(proseParser.get()).buildMarkdownTreeFromString(content))

    val edits = mutableListOf<Pair<IntRange, String>>()
    val externalRanges = (blockRanges + linkRanges).sortedBy { it.first }
    var rangeIndex = 0
    for (email in emails) {
        while (rangeIndex < externalRanges.size && externalRanges[rangeIndex].last < email.range.first) rangeIndex++
        if (externalRanges.getOrNull(rangeIndex)?.first?.let { it <= email.range.last } == true) continue
        val address = email.groups[1]?.value ?: email.groupValues[2]
        edits += email.range to "[$address](mailto:$address)"
    }
    // Keep unsupported footnote definitions readable instead of consuming them as link definitions.
    for (definition in footnoteDefinitions) {
        val open = content.indexOf('[', definition.range.first)
        if (blockRanges.none { open in it }) edits += (open..open) to "\\["
    }
    if (edits.isEmpty()) return content
    return StringBuilder(content).apply {
        edits.sortedByDescending { it.first.first }.forEach { (range, replacement) ->
            replace(range.first, range.last + 1, replacement)
        }
    }.toString()
}
