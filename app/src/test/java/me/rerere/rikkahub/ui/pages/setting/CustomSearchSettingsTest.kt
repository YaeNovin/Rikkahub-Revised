package me.rerere.rikkahub.ui.pages.setting

import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.search.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.reflect.full.primaryConstructor

class CustomSearchSettingsTest {
    @Test fun `add provider factory can instantiate custom search with empty URL and no secret`() {
        val type = SearchServiceOptions.TYPES.keys.single { it == SearchServiceOptions.CustomOptions::class }
        val instance = type.primaryConstructor!!.callBy(emptyMap()) as SearchServiceOptions.CustomOptions
        assertEquals("", instance.baseUrl)
        assertEquals("", instance.apiKey)
        assertFalse(instance.apiKeyEnabled)
        assertFalse(instance.withDedicatedSearchModel().model!!.enabled)
        assertNotNull(SearchService.getService(instance).parameters(instance))
        assertNull(SearchService.getService(instance).scrapingParameters(instance))
    }

    @Test fun `normal settings persistence retains custom drafts toggles and selected service`() {
        val provider = SearchServiceOptions.CustomOptions(name = "自定义样例", baseUrlEnabled = false,
            baseUrl = "https://ignored.example", endpoint = "https://search.example/v1/x_search", kind = CustomSearchKind.X,
            apiKeyEnabled = false, apiKey = "retained-not-sent", model = OptionalSearchValue(false, "saved-search-model"),
            maxResults = OptionalSearchValue(true, "12"),
            allowedXHandles = OptionalSearchValue(false, "draft@"), fromDate = OptionalSearchValue(true, "2026-10-01"),
            extraParameters = listOf(CustomSearchParameter(enabled = true, name = "language", value = "zh")))
        val settings = Settings(searchServices = listOf(SearchServiceOptions.BingLocalOptions(), provider), searchServiceSelected = 1)
        val restored = JsonInstant.decodeFromString<Settings>(JsonInstant.encodeToString(Settings.serializer(), settings))
        assertEquals(1, restored.searchServiceSelected)
        assertEquals(provider, restored.searchServices[1])
        assertEquals(setOf("query", "max_results", "from_date", "language"), buildCustomSearchBody("问题", restored.searchServices[1] as SearchServiceOptions.CustomOptions).keys)
    }

    @Test fun `enabled search model round trips without sharing the chat model selection`() {
        val provider = SearchServiceOptions.CustomOptions(baseUrl = "https://search.example",
            model = OptionalSearchValue(true, "vendor/search-model"))
        val settings = Settings(searchServices = listOf(provider))
        val restored = JsonInstant.decodeFromString<Settings>(JsonInstant.encodeToString(Settings.serializer(), settings))
        val options = restored.searchServices.single() as SearchServiceOptions.CustomOptions
        assertEquals(provider.model, options.model)
        assertEquals("vendor/search-model", buildCustomSearchBody("问题", options)["model"]?.toString()?.trim('"'))
    }
}
