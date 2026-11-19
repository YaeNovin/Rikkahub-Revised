package me.rerere.rikkahub.data.datastore

import me.rerere.ai.provider.*
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.ui.pages.assistant.detail.showGeminiToolsSettings
import org.junit.Assert.*
import org.junit.Test

class NativeSearchSettingsTest {
    @Test fun `native search button changes only the active assistant and preserves legacy model defaults`() {
        val model = Model(modelId = "gemini-4-argon", tools = setOf(BuiltInTools.Search, BuiltInTools.UrlContext))
        val first = Assistant(chatModelId = model.id)
        val second = Assistant(chatModelId = model.id)
        val settings = Settings(assistantId = first.id, assistants = listOf(first, second),
            providers = listOf(ProviderSetting.Google(models = listOf(model))))
        assertTrue(settings.isBuiltInSearchEnabled(model))
        val off = settings.withChatSearchMode(builtIn = false, external = false)
        assertFalse(off.isBuiltInSearchEnabled(model))
        assertEquals(settings.providers, off.providers)
        assertEquals(second, off.assistants[1])
        assertEquals(true, off.assistants[0].geminiOptions.search.urlContext)
        assertTrue(off.copy(assistantId = second.id).isBuiltInSearchEnabled(model))
        val local = off.withChatSearchMode(builtIn = false, external = true)
        assertTrue(local.getCurrentAssistant().enableWebSearch)
        assertFalse(local.isBuiltInSearchEnabled(model))
    }

    @Test fun `native search overrides do not activate on Gemma or an OpenAI relay`() {
        val model = Model(modelId = "gemma-4-31b-it")
        val assistant = Assistant(geminiOptions = GeminiGenerationOptions(search = GeminiSearchOptions(enabled = true)))
        val settings = Settings(assistantId = assistant.id, assistants = listOf(assistant), chatModelId = model.id,
            providers = listOf(ProviderSetting.Google(models = listOf(model))))
        assertFalse(settings.isBuiltInSearchEnabled(model))
        assertFalse(settings.copy(providers = listOf(ProviderSetting.OpenAI(models = listOf(model.copy(modelId = "gemini-4-argon")))))
            .isBuiltInSearchEnabled(model.copy(modelId = "gemini-4-argon")))
    }

    @Test fun `only Gemini identity shows the assistant tool entry including opaque ids with model names`() {
        for (name in listOf("Gemini 4 Argon", "Gemini 4 Carbon", "Gemini 3.8 Flash")) {
            assertTrue(name, showGeminiToolsSettings(Model(modelId = "opaque", displayName = name)))
        }
        for (id in listOf("gpt-6.1-sol", "gemma-4-31b-it", "deepseek-v4", "unknown")) {
            assertFalse(id, showGeminiToolsSettings(Model(modelId = id)))
        }
        assertFalse(showGeminiToolsSettings(null))
    }

    @Test fun `compatible provider search still uses its original model route`() {
        val model = Model(modelId = "gpt-6.1-sol")
        val assistant = Assistant(chatModelId = model.id)
        val settings = Settings(assistantId = assistant.id, assistants = listOf(assistant),
            providers = listOf(ProviderSetting.OpenAI(useResponseApi = true, models = listOf(model))))
        val enabled = settings.withChatSearchMode(builtIn = true, external = false)
        val updated = enabled.providers.single().models.single()
        assertTrue(BuiltInTools.Search in updated.tools)
        assertEquals(assistant.geminiOptions, enabled.assistants.single().geminiOptions)
    }
}
