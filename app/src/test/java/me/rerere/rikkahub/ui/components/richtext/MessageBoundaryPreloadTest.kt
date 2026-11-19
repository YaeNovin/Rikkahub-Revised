package me.rerere.rikkahub.ui.components.richtext

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class MessageBoundaryPreloadTest {
    @Test fun `table fragments do not evict neighbouring message documents`() = runBlocking {
        val marker = UUID.randomUUID().toString()
        val neighbours = (1..20).map { "Message $marker $it\n\nHistory ready for scrolling." }
        preloadMarkdownContents(neighbours, 256 * 1024)
        val table = "| A | B |\n| --- | --- |\n" + (1..30).joinToString("\n") { "| $marker left $it | right $it |" }
        preloadMarkdownContents(listOf(table), 256 * 1024)
        assertTrue(isMarkdownContentPreloaded(table))
        assertTrue(neighbours.all(::isMarkdownContentPreloaded))
    }
}
