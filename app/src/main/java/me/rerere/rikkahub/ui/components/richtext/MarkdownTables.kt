package me.rerere.rikkahub.ui.components.richtext

import androidx.compose.ui.text.style.TextAlign
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMElementTypes
import org.intellij.markdown.flavours.gfm.GFMTokenTypes

internal data class MarkdownTableData(
    val headers: List<String>,
    val rows: List<List<String>>,
    val alignments: List<TextAlign>,
)

internal fun markdownTableAlignment(value: String): TextAlign = when (value.trim().lowercase()) {
    "center" -> TextAlign.Center
    "right" -> TextAlign.Right
    else -> TextAlign.Left
}

internal fun markdownTableData(node: ASTNode, source: String): MarkdownTableData {
    fun ASTNode.text() = source.substring(startOffset, endOffset).trim()
    fun ASTNode.cells() = children.filter { it.type == GFMTokenTypes.CELL }.map { it.text() }
    val headers = node.children.firstOrNull { it.type == GFMElementTypes.HEADER }?.cells().orEmpty()
    val separator = node.children.firstOrNull { it.type == GFMTokenTypes.TABLE_SEPARATOR }?.text().orEmpty()
    val delimiters = separator.trimStart(' ', '\t', '>').removePrefix("|").removeSuffix("|").split('|').map(String::trim)
    val alignments = List(headers.size) { column ->
        val delimiter = delimiters.getOrNull(column).orEmpty()
        when {
            delimiter.startsWith(':') && delimiter.endsWith(':') -> TextAlign.Center
            delimiter.endsWith(':') -> TextAlign.Right
            else -> TextAlign.Left
        }
    }
    // GFM pads short rows and ignores excess cells beyond the header width.
    val rows = node.children.filter { it.type == GFMElementTypes.ROW }.map { row ->
        val cells = row.cells()
        List(headers.size) { cells.getOrElse(it) { "" } }
    }
    return MarkdownTableData(headers, rows, alignments)
}
