package me.rerere.rikkahub.ui.components.richtext

import androidx.compose.ui.text.style.TextAlign
import me.rerere.rikkahub.ui.components.table.stretchTableColumnWidths
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMElementTypes
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.parser.MarkdownParser
import org.junit.Assert.*
import org.junit.Test

class MarkdownTableTest {
    private fun table(source: String): MarkdownTableData {
        val root = MarkdownParser(GFMFlavourDescriptor()).buildMarkdownTreeFromString(source)
        fun find(node: ASTNode): ASTNode? = if (node.type == GFMElementTypes.TABLE) node else node.children.firstNotNullOfOrNull(::find)
        return markdownTableData(requireNotNull(find(root)), source)
    }

    @Test fun `GFM delimiter alignment applies to each column`() {
        val data = table("| Left | Center | Right |\n| :--- | :---: | ---: |\n| a | b | c |")
        assertEquals(listOf(TextAlign.Left, TextAlign.Center, TextAlign.Right), data.alignments)
        assertEquals(listOf("Left", "Center", "Right"), data.headers)
    }

    @Test fun `alignment works without outer pipes and in nested block quotes`() {
        for (source in listOf("A | B\n:---: | ---:\na | b", "> | A | B |\n> | :---: | ---: |\n> | a | b |")) {
            assertEquals(source, listOf(TextAlign.Center, TextAlign.Right), table(source).alignments)
        }
    }

    @Test fun `GFM row width pads missing cells and ignores extras`() {
        val data = table("| A | B |\n| --- | --- |\n| one |\n| one | two | ignored |")
        assertEquals(listOf(listOf("one", ""), listOf("one", "two")), data.rows)
    }

    @Test fun `escaped pipes and inline markup are not lost during extraction`() {
        val data = table("| A | B |\n| --- | --- |\n| a\\|b | **bold** `code` [citation:abc123] |")
        assertEquals(2, data.rows.single().size)
        assertEquals("a\\|b", data.rows.single()[0])
        assertTrue(data.rows.single()[1].contains("**bold**"))
        assertTrue(data.rows.single()[1].contains("[citation:abc123]"))
    }

    @Test fun `streaming unfinished row is padded without index errors`() {
        val data = table("| A | B |\n| --- | --- |\n| partial")
        assertEquals(listOf("partial", ""), data.rows.single())
    }

    @Test fun `HTML generation preserves matching table alignment`() {
        val doc = buildMarkdownDocument("| A | B | C |\n| :--- | :---: | ---: |\n| a | b | c |")
        val alignments = doc.select("thead th").map { markdownTableAlignment(it.attr("align")) }
        assertEquals(listOf(TextAlign.Left, TextAlign.Center, TextAlign.Right), alignments)
    }

    @Test fun `column stretching respects each maximum and redistributes unused room`() {
        assertArrayEquals(intArrayOf(100, 200), stretchTableColumnWidths(intArrayOf(80, 80), intArrayOf(100, 200), 300))
        assertArrayEquals(intArrayOf(100, 200), stretchTableColumnWidths(intArrayOf(80, 80), intArrayOf(100, 200), 1000))
        assertArrayEquals(intArrayOf(200, 200), stretchTableColumnWidths(intArrayOf(200, 200), intArrayOf(240, 240), 300))
    }

    @Test fun `empty columns can stretch and large dimensions do not overflow arithmetic`() {
        assertArrayEquals(intArrayOf(50, 50), stretchTableColumnWidths(intArrayOf(0, 0), intArrayOf(100, 100), 100))
        assertArrayEquals(intArrayOf(Int.MAX_VALUE, Int.MAX_VALUE), stretchTableColumnWidths(intArrayOf(Int.MAX_VALUE, Int.MAX_VALUE), intArrayOf(Int.MAX_VALUE, Int.MAX_VALUE), 100))
    }
}
