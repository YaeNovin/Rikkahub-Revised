package me.rerere.rikkahub.data.ai.transformers

import io.pebbletemplates.pebble.PebbleEngine
import io.pebbletemplates.pebble.loader.StringLoader
import me.rerere.ai.ui.UIMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TemplateTransformerTest {
    private val transformer = run {
        System.setProperty(
            "slf4j.provider",
            "org.slf4j.helpers.NOP_FallbackServiceProvider",
        )
        TemplateTransformer(
            PebbleEngine.Builder()
                .loader(StringLoader())
                .autoEscaping(false)
                .build()
        )
    }

    @Test
    fun `validation accepts pebble whitespace variants and indirect message use`() {
        assertTrue(transformer.validate("[{{message}}]").isValid)
        assertTrue(
            transformer.validate("{% set body = message %}{{ body }}").isValid
        )
    }

    @Test
    fun `validation rejects syntax errors and templates that discard content`() {
        assertFalse(transformer.validate("{{ message").isValid)
        assertFalse(transformer.validate("fixed text").isValid)
    }

    @Test
    fun `literal draft rendering does not depend on persisted assistant cache`() {
        val rendered = transformer.transformWithTemplate(
            templateSource = "{{ role }}: {{ message }}",
            messages = listOf(UIMessage.user("hello")),
        )

        assertEquals("user: hello", rendered.single().toText())
    }

    @Test
    fun `literal draft rendering accepts resolved runtime variables`() {
        val rendered = transformer.transformWithTemplate(
            templateSource = "{{ assistant_name }} / {{ workspace_name }} / {{ message }}",
            messages = listOf(UIMessage.user("hello")),
            variables = mapOf(
                "assistant_name" to "Ari",
                "workspace_name" to "Novel",
            ),
        )

        assertEquals("Ari / Novel / hello", rendered.single().toText())
    }
}
