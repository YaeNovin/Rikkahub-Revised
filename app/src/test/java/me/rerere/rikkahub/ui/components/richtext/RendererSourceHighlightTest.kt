package me.rerere.rikkahub.ui.components.richtext

import me.rerere.highlight.CodeHighlighter
import me.rerere.highlight.HighlightToken
import me.rerere.rikkahub.data.render.RendererCatalog
import org.junit.Assert.*
import org.junit.Test

class RendererSourceHighlightTest {
    @Test fun `all new typed renderer aliases highlight as JSON without changing renderer identity`() {
        for (id in listOf("math_plot", "sigil", "pattern")) {
            val spec = RendererCatalog.find(id)!!
            for (alias in spec.aliases) {
                for (language in listOf(alias, "language-${alias.uppercase()}", "{.$alias}")) {
                    assertEquals("json", rendererSourceHighlightLanguage(language))
                    assertEquals(32768, rendererSourceHighlightLimit(language))
                    assertEquals(id, geometryRenderer(language))
                }
            }
            val tokens = CodeHighlighter().highlight(spec.example, rendererSourceHighlightLanguage(id))
            assertEquals(spec.example, tokens.joinToString("") { it.content })
            assertTrue(tokens.any { it is HighlightToken.Styled && it.type == "attr" })
            assertTrue(tokens.any { it is HighlightToken.Styled && it.type == "string" })
        }
    }
    @Test fun `normal code retains its language and opts into bounded background highlighting`() {
        listOf("python", "json", "javascript", "html", "terminal", "ansi", "xterm", "unknown", "p5").forEach {
            assertEquals(it, rendererSourceHighlightLanguage(it))
            assertEquals(32768, rendererSourceHighlightLimit(it))
        }
        assertEquals(32768, rendererSourceHighlightLimit("pattern", 32768))
        assertEquals(0, rendererSourceHighlightLimit("pattern", 32769))
        assertEquals(0, rendererSourceHighlightLimit("kotlin", 32769))
    }
}
