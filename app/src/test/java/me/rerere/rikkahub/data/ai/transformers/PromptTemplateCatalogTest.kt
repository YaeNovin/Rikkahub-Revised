package me.rerere.rikkahub.data.ai.transformers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptTemplateCatalogTest {
    @Test
    fun `catalog starts with a no-op choice and exposes practical templates`() {
        assertEquals(PromptTemplateDescriptor.Id.NONE, PromptTemplateCatalog.all.first().id)
        assertTrue(PromptTemplateCatalog.all.any { it.id == PromptTemplateDescriptor.Id.GENERAL_ASSISTANT })
        assertTrue(PromptTemplateCatalog.all.any { it.id == PromptTemplateDescriptor.Id.ROLEPLAY_CHARACTER })
        assertTrue(PromptTemplateCatalog.all.any { it.id == PromptTemplateDescriptor.Id.WORKSPACE_COPILOT })
    }

    @Test
    fun `inserted templates use supported runtime names`() {
        val supported = PromptVariableCatalog.all.map { it.key }.toSet()
        PromptTemplateCatalog.all.drop(1).forEach { template ->
            Regex("\\{\\{\\s*([a-z_]+)\\s*}}")
                .findAll(template.content)
                .forEach { match -> assertTrue(match.groupValues[1] in supported) }
        }
    }
}
