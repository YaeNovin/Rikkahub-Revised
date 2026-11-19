package me.rerere.rikkahub.data.datastore

import me.rerere.ai.provider.*
import me.rerere.rikkahub.data.model.Assistant
import org.junit.Assert.*
import org.junit.Test

class SearchAvailabilityTest {
    @Test fun `relay model names and orphan built-in flags cannot suppress external functions`() {
        for (name in listOf("GPT 6.1 Sol", "Claude Opus 5.5", "Gemini 4 Argon")) {
            val model = Model(modelId = "deployment-42", displayName = name, tools = setOf(BuiltInTools.Search))
            val state = searchAvailability(Assistant(enableWebSearch = true), model,
                ProviderSetting.OpenAI(baseUrl = "https://relay.example/v1"))
            assertTrue(name, state.external)
            assertFalse(name, state.native)
            assertTrue(name, model.supportsFunctionCalling())
            assertTrue(state.prompt().contains("search_web"))
        }
    }

    @Test fun `Google native search is reflected in capability hint even with external search off`() {
        val assistant = Assistant(enableWebSearch = false,
            geminiOptions = GeminiGenerationOptions(search = GeminiSearchOptions(enabled = true, urlContext = true)))
        val model = Model(modelId = "gemini-4-argon")
        val state = searchAvailability(assistant, model, ProviderSetting.Google())
        assertTrue(state.native)
        assertTrue(state.urlContext)
        assertFalse(state.external)
        assertTrue(state.prompt().contains("does not need to be enabled again"))
    }

    @Test fun `Gemini 2 external search gets function tools while URL preference stays saved`() {
        val assistant = Assistant(enableWebSearch = true,
            geminiOptions = GeminiGenerationOptions(search = GeminiSearchOptions(enabled = false, urlContext = true)))
        val model = Model(modelId = "gemini-2.5-flash")
        val options = effectiveGoogleSearchOptions(assistant, model, ProviderSetting.Google())
        assertEquals(false, options.enabled)
        assertEquals(false, options.urlContext)
        assertEquals(true, assistant.geminiOptions.search.urlContext)
        assertEquals(true, effectiveGoogleSearchOptions(assistant, model.copy(modelId = "gemini-4-carbon"), ProviderSetting.Google()).urlContext)
    }

    @Test fun `off clears orphan fallback and Responses uses its native search`() {
        val model = Model(modelId = "gpt-6.1-sol", tools = setOf(BuiltInTools.Search))
        val assistant = Assistant(chatModelId = model.id)
        val settings = Settings(assistantId = assistant.id, assistants = listOf(assistant),
            providers = listOf(ProviderSetting.OpenAI(models = listOf(model))))
        assertTrue(settings.isExternalSearchEnabled(model))
        val off = settings.withChatSearchMode(false, false)
        assertFalse(off.isExternalSearchEnabled(off.providers.single().models.single()))
        assertTrue(searchAvailability(assistant, model, ProviderSetting.OpenAI(useResponseApi = true)).native)
    }
}
