package me.rerere.rikkahub.web.dto

import kotlinx.serialization.json.*
import me.rerere.ai.provider.*
import me.rerere.rikkahub.data.ai.mcp.*
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.web.BadRequestException
import me.rerere.rikkahub.web.ConflictException
import me.rerere.search.SearchServiceOptions
import org.junit.Assert.*
import org.junit.Test

class WebConfigurationTest {
    @Test fun `model discovery resolves redacted draft credentials without persisting draft`() {
        val s = settings()
        val public = configurationDocument(s, "providers")["values"]!!.jsonObject["providers"]!!.jsonArray.single().jsonObject
        val draft = JsonObject(public + ("baseUrl" to JsonPrimitive("https://new.example/v1")))
        val resolved = configurationProviderDraft(s, draft) as ProviderSetting.OpenAI
        assertEquals("private-sentinel", resolved.apiKey)
        assertEquals("https://new.example/v1", resolved.baseUrl)
        assertEquals(provider.baseUrl, (s.providers.single() as ProviderSetting.OpenAI).baseUrl)
        assertThrows(BadRequestException::class.java) { configurationProviderDraft(s, JsonObject(draft + ("baseUrl" to JsonPrimitive("file:///tmp")))) }
    }
    private val chat = Model(modelId = "chat")
    private val image = Model(modelId = "image", type = ModelType.IMAGE)
    private val video = Model(modelId = "video", type = ModelType.VIDEO)
    private val provider = ProviderSetting.OpenAI(name = "Test", apiKey = "private-sentinel", builtIn = true, models = listOf(chat, image, video))
    private val assistant = Assistant(name = "A", chatModelId = chat.id)
    private fun settings() = Settings(providers = listOf(provider), assistants = listOf(assistant), assistantId = assistant.id, chatModelId = chat.id, webServerJwtSecret = "signing-secret")
    private fun request(s: Settings, group: String, values: JsonObject, id: String? = null) = ConfigurationUpdate(configurationRevision(configurationRaw(s, group, id)), values)

    @Test fun `credentials are not returned and excluded models survive provider editing`() {
        val s = settings()
        val doc = configurationDocument(s, "providers")
        assertFalse(doc.toString().contains("private-sentinel"))
        val values = doc.getValue("values").jsonObject
        val publicProvider = values.getValue("providers").jsonArray.single().jsonObject
        assertEquals(2, publicProvider.getValue("models").jsonArray.size)
        val changed = JsonObject(publicProvider + ("name" to JsonPrimitive("Changed")))
        val next = applyConfiguration(s, "providers", request(s, "providers", buildJsonObject { put("providers", JsonArray(listOf(changed))) }))
        val saved = next.providers.single() as ProviderSetting.OpenAI
        assertEquals("private-sentinel", saved.apiKey)
        assertEquals(listOf(chat.id, image.id, video.id), saved.models.map { it.id })
        assertTrue(saved.builtIn)
    }

    @Test fun `write-only credentials explicitly replace and clear`() {
        val s = settings()
        val p = configurationDocument(s, "providers").getValue("values").jsonObject.getValue("providers").jsonArray.single().jsonObject
        for (value in listOf("new-secret", "")) {
            val body = buildJsonObject { put("providers", JsonArray(listOf(JsonObject(p + ("apiKey" to JsonPrimitive(value)))))) }
            assertEquals(value, (applyConfiguration(s, "providers", request(s, "providers", body)).providers.single() as ProviderSetting.OpenAI).apiKey)
        }
    }

    @Test fun `conflicting revision fails and unknown fields are rejected`() {
        val s = settings()
        val values = buildJsonObject { put("titleMaxLength", 36) }
        val request = request(s, "conversations", values)
        assertThrows(ConflictException::class.java) { applyConfiguration(s.copy(titleMaxLength = 40), "conversations", request) }
        assertThrows(BadRequestException::class.java) { applyConfiguration(s, "preferences", request(s, "preferences", buildJsonObject { put("webServerJwtSecret", "overwrite") })) }
    }

    @Test fun `conversation and shortcut changes preserve all unrelated objects and secrets`() {
        val s = settings()
        val values = buildJsonObject { put("titleMaxLength", 36); put("titlePrompt", "Custom prompt") }
        val next = applyConfiguration(s, "conversations", request(s, "conversations", values))
        assertEquals(36, next.titleMaxLength)
        assertSame(s.providers, next.providers)
        assertSame(s.assistants, next.assistants)
        assertEquals(s.webServerJwtSecret, next.webServerJwtSecret)
        val shortcut = applyConfiguration(next, "preferences", request(next, "preferences", buildJsonObject { put("sendOnEnter", false) }))
        assertFalse(shortcut.displaySetting.sendOnEnter)
        assertEquals(next.displaySetting.copy(sendOnEnter = false), shortcut.displaySetting)
    }

    @Test fun `invalid bounds and empty auth password cannot be saved`() {
        val s = settings()
        for ((key, value) in listOf("titleMaxLength" to 81, "generationRetryMaxRetries" to 8, "generationRetryInitialIntervalSeconds" to 31, "generationRetryMaxDurationSeconds" to 301)) {
            assertThrows(BadRequestException::class.java) { applyConfiguration(s, "conversations", request(s, "conversations", buildJsonObject { put(key, value) })) }
        }
        assertThrows(BadRequestException::class.java) { applyConfiguration(s, "security", request(s, "security", buildJsonObject { put("webServerJwtEnabled", true); put("webServerAccessPassword", "") })) }
    }

    @Test fun `image and video deletion modification and model reference removal fail`() {
        val s = settings()
        assertThrows(BadRequestException::class.java) { applyConfiguration(s, "providers", request(s, "providers", buildJsonObject { put("providers", JsonArray(emptyList())) })) }
        val p = configurationDocument(s, "providers").getValue("values").jsonObject.getValue("providers").jsonArray.single().jsonObject
        assertThrows(BadRequestException::class.java) { applyConfiguration(s, "providers", request(s, "providers", buildJsonObject { put("providers", JsonArray(listOf(JsonObject(p + ("models" to JsonArray(emptyList())))))) })) }
        val editedImage = buildJsonObject { put("id", image.id.toString()); put("modelId", "hidden"); put("type", "CHAT") }
        val invalidModels = JsonObject(p + ("models" to JsonArray(listOf(editedImage))))
        val invalidBody = buildJsonObject { put("providers", JsonArray(listOf(invalidModels))) }
        assertThrows(BadRequestException::class.java) { applyConfiguration(s, "providers", request(s, "providers", invalidBody)) }
    }

    @Test fun `search secrets and MCP oauth survive unrelated edits`() {
        val service = SearchServiceOptions.TavilyOptions(apiKey = "search-secret")
        val server = McpServerConfig.SseTransportServer(url = "https://example.com/sse", commonOptions = McpCommonOptions(name = "Tools", headers = listOf("Authorization" to "header-secret"), oauth = McpOAuthState(accessToken = "oauth-secret")))
        val s = settings().copy(searchServices = listOf(service), mcpServers = listOf(server))
        for (group in listOf("search", "mcp")) {
            val doc = configurationDocument(s, group)
            assertFalse(doc.toString().contains("-secret"))
            val next = applyConfiguration(s, group, request(s, group, doc.getValue("values").jsonObject))
            assertEquals(s.searchServices, next.searchServices)
            assertEquals(s.mcpServers, next.mcpServers)
        }
    }

    @Test fun `assistant compression uses native minimum and only updates the selected assistant`() {
        val s = settings().copy(assistants = listOf(assistant, Assistant(name = "B")))
        val id = assistant.id.toString()
        val values = buildJsonObject { put("enableRollingContextCompression", false); put("rollingContextCompressionThresholdTokens", 5000) }
        val next = applyConfiguration(s, "assistant", request(s, "assistant", values, id), id)
        assertFalse(next.assistants.first().enableRollingContextCompression)
        assertEquals(s.assistants[1], next.assistants[1])
        assertThrows(BadRequestException::class.java) { applyConfiguration(s, "assistant", request(s, "assistant", buildJsonObject { put("rollingContextCompressionThresholdTokens", 3999) }, id), id) }
    }

    @Test fun `image suggestion setting is excluded and retained`() {
        val s = settings().copy(suggestionOptions = settings().suggestionOptions.copy(imageSuggestions = true))
        val doc = configurationDocument(s, "conversations")
        assertFalse(doc.toString().contains("imageSuggestions"))
        val next = applyConfiguration(s, "conversations", request(s, "conversations", doc.getValue("values").jsonObject))
        assertTrue(next.suggestionOptions.imageSuggestions)
    }

    @Test fun `catalog includes all native provider and search kinds without credentials`() {
        val catalog = configurationTemplates()
        assertEquals(3, catalog.getValue("providers").jsonArray.size)
        assertEquals(20, catalog.getValue("searchServices").jsonArray.size)
        assertEquals(2, catalog.getValue("mcpServers").jsonArray.size)
        assertEquals(listOf("CHAT", "EMBEDDING", "IMAGE"), catalog.getValue("models").jsonArray.map { it.jsonObject.getValue("type").jsonPrimitive.content })
    }
}
