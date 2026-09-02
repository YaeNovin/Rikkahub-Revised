package me.rerere.rikkahub.data.ai.transformers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptVariableCatalogTest {

    @Test
    fun `catalog keeps variable keys unique`() {
        val keys = PromptVariableCatalog.all.map { it.key }
        assertEquals(keys.size, keys.toSet().size)
    }

    @Test
    fun `editor groups compatibility aliases under canonical variables`() {
        val systemVariables = PromptVariableCatalog.primaryForScope(
            PromptVariableScope.ASSISTANT_SYSTEM,
        )
        val keys = systemVariables.map { it.key }

        assertTrue("assistant_name" in keys)
        assertTrue("char_name" !in keys)
        assertTrue("user" in keys)
        assertTrue("assistant" !in keys)
        assertTrue("character_name" !in keys)
        assertTrue("user_name" !in keys)
        assertEquals(
            setOf("assistant", "char", "char_name", "character_name"),
            PromptVariableCatalog.aliasesFor(
                variable = systemVariables.first { it.key == "assistant_name" },
                scope = PromptVariableScope.ASSISTANT_SYSTEM,
            ).map { it.key }.toSet(),
        )
    }

    @Test
    fun `system variables have a runtime resolver`() {
        val advertised = PromptVariableCatalog.forScope(PromptVariableScope.ASSISTANT_SYSTEM)
            .map { it.key }
        assertTrue(advertised.all { it in DefaultPlaceholderProvider.placeholders })
    }

    @Test
    fun `specialized prompt scopes expose only variables accepted at runtime`() {
        val titleKeys = PromptVariableCatalog.forScope(PromptVariableScope.TITLE_PROMPT)
            .map { it.key }
            .toSet()
        assertTrue(titleKeys.containsAll(setOf("content", "locale", "assistant_name", "workspace_name")))

        val compressionKeys = PromptVariableCatalog.forScope(PromptVariableScope.COMPRESS_PROMPT)
            .map { it.key }
            .toSet()
        assertTrue(
            compressionKeys.containsAll(
                setOf("content", "target_tokens", "additional_context", "locale", "workspace_cwd")
            )
        )
        assertTrue(
            PromptVariableCatalog.forScope(PromptVariableScope.QUICK_MESSAGE)
                .map { it.key }
                .containsAll(listOf("character", "char_name", "user_name", "location", "target", "scene"))
        )
    }

    @Test
    fun `catalog token uses the syntax shown in each editor`() {
        assertEquals(
            "{{message}}",
            PromptVariableCatalog.all.first { it.key == "message" }.token,
        )
        assertEquals(
            "{source_text}",
            PromptVariableCatalog.all.first { it.key == "source_text" }.token,
        )
    }
}
