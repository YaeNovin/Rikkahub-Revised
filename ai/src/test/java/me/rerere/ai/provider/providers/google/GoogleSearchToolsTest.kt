package me.rerere.ai.provider.providers.google

import kotlinx.serialization.json.*
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.*
import me.rerere.ai.provider.stream.SseEvent
import me.rerere.ai.ui.*
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test

class GoogleSearchToolsTest {
    @Test fun `explicit Gemini 2 built ins take priority over incompatible custom functions`() {
        val body = request(Model(modelId = "gemini-2.5-flash", abilities = listOf(ModelAbility.TOOL)),
            GeminiSearchOptions(enabled = true, urlContext = true), withFunctions = true)
        val tools = body.getValue("tools").jsonArray.map { it.jsonObject }
        assertTrue(tools.any { "googleSearch" in it })
        assertTrue(tools.any { "urlContext" in it })
        assertFalse(tools.any { "functionDeclarations" in it })
    }

    @Test fun `default tool remains backward compatible and explicit search supports dates and image search`() {
        val model = Model(modelId = "gemini-3.1-flash-image-preview")
        assertEquals("""{"googleSearch":{}}""", buildGoogleSearchTool(GeminiSearchOptions(), model).toString())
        val search = buildGoogleSearchTool(GeminiSearchOptions(imageSearch = true,
            startTime = "2026-01-01T00:00:00Z", endTime = "2026-11-12T00:00:00Z"), model)
            .getValue("googleSearch").jsonObject
        assertTrue(search.getValue("searchTypes").jsonObject.containsKey("imageSearch"))
        assertTrue(search.getValue("searchTypes").jsonObject.containsKey("webSearch"))
        assertEquals("2026-01-01T00:00:00Z", search.getValue("timeRangeFilter").jsonObject.getValue("startTime").jsonPrimitive.content)
    }
    @Test fun `unsupported image search falls back to web while invalid intervals fail validation`() {
        val options = GeminiSearchOptions(imageSearch = true, webSearch = false)
        val body = buildGoogleSearchTool(options, Model(modelId = "gemini-4-argon"))
        assertEquals("""{"googleSearch":{}}""", body.toString())
        assertTrue(options.imageSearch)
        assertNotNull(geminiSearchTimeRangeError(GeminiSearchOptions(startTime = "2026-01-01T00:00:00Z")))
        assertNotNull(geminiSearchTimeRangeError(GeminiSearchOptions(startTime = "bad", endTime = "bad")))
        assertNotNull(geminiSearchTimeRangeError(GeminiSearchOptions(startTime = "2026-12-01T00:00:00Z", endTime = "2026-01-01T00:00:00Z")))
        assertNull(geminiSearchTimeRangeError(GeminiSearchOptions()))
    }

    @Test fun `Gemini 4 Argon Carbon and named relay identities preserve complete native search and function combination`() {
        for (model in listOf(Model(modelId = "gemini-4-argon"), Model(modelId = "gemini-4-carbon"),
            Model(modelId = "opaque-relay-id", displayName = "Gemini 4 Carbon"))) {
            val settings = GeminiSearchOptions(enabled = true, urlContext = true,
                startTime = "2026-01-01T00:00:00Z", endTime = "2026-11-12T00:00:00Z")
            val body = request(model.copy(abilities = listOf(ModelAbility.TOOL)), settings, withFunctions = true)
            val tools = body.getValue("tools").jsonArray.map { it.jsonObject }
            assertTrue(tools.any { "functionDeclarations" in it })
            assertTrue(tools.any { "urlContext" in it })
            assertEquals(settings.startTime, tools.first { "googleSearch" in it }.getValue("googleSearch")
                .jsonObject.getValue("timeRangeFilter").jsonObject.getValue("startTime").jsonPrimitive.content)
            assertEquals("VALIDATED", body.getValue("toolConfig").jsonObject.getValue("functionCallingConfig").jsonObject.getValue("mode").jsonPrimitive.content)
            val generation = body.getValue("generationConfig").jsonObject
            assertFalse(generation.containsKey("temperature"))
            assertEquals("medium", generation.getValue("thinkingConfig").jsonObject.getValue("thinkingLevel").jsonPrimitive.content)
            assertTrue(model.supportsReasoningCapability())
            assertEquals(listOf(me.rerere.ai.core.ReasoningLevel.AUTO, me.rerere.ai.core.ReasoningLevel.LOW,
                me.rerere.ai.core.ReasoningLevel.MEDIUM, me.rerere.ai.core.ReasoningLevel.HIGH),
                resolveReasoningLevelSupport(model, ProviderSetting.Google()).levels)
        }
    }

    @Test fun `saved Gemini assistant tools are not sent when Google provider switches to Gemma`() {
        val body = request(Model(modelId = "gemma-4-31b-it"), GeminiSearchOptions(enabled = true, urlContext = true))
        assertFalse(body.containsKey("tools"))
    }

    @Test fun `grounding supports retain cited text and source byte ranges`() {
        val candidate = Json.parseToJsonElement("""{"groundingMetadata":{
          "groundingChunks":[{"web":{"uri":"https://example.com","title":"Example"}}],
          "groundingSupports":[{"groundingChunkIndices":[0],"segment":{"text":"已验证来源","partIndex":1,"startIndex":4,"endIndex":19}}]}}""").jsonObject
        val citation = parseGoogleGroundingAnnotations(candidate).filterIsInstance<UIMessageAnnotation.UrlCitation>().single()
        assertEquals(UIMessageAnnotation.GroundingSegment("已验证来源", 1, 4, 19), citation.groundingSegments.single())
        assertEquals(citation, Json.decodeFromString<UIMessageAnnotation.UrlCitation>(Json.encodeToString(citation)))
    }
    @Test fun `assistant search and url switches work without model tools and preserve custom functions on Gemini 3`() {
        val body = request(Model(modelId = "gemini-3.8-flash", abilities = listOf(ModelAbility.TOOL)),
            GeminiSearchOptions(enabled = true, urlContext = true), withFunctions = true)
        val tools = body.getValue("tools").jsonArray.map { it.jsonObject }
        assertTrue(tools.any { "googleSearch" in it })
        assertTrue(tools.any { "urlContext" in it })
        assertTrue(tools.any { "functionDeclarations" in it })
        assertTrue(body.getValue("toolConfig").jsonObject.getValue("includeServerSideToolInvocations").jsonPrimitive.boolean)
        val disabled = request(Model(modelId = "gemini-3.8-flash", tools = setOf(BuiltInTools.Search, BuiltInTools.UrlContext)),
            GeminiSearchOptions(enabled = false, urlContext = false))
        assertFalse(disabled.containsKey("tools"))
    }
    @Test fun `metadata only streaming event preserves images search suggestions and every url retrieval status`() {
        val candidate = Json.parseToJsonElement("""{
            "finishReason":"STOP",
            "groundingMetadata":{"groundingChunks":[{"web":{"uri":"https://example.com/page","title":"Page"}},
              {"image":{"sourceUri":"https://example.com/source","imageUri":"https://example.com/image.jpg","title":"Image"}}],
              "searchEntryPoint":{"renderedContent":"<div>Google Search</div>"},"webSearchQueries":["query"],"imageSearchQueries":["visual reference"]},
            "urlContextMetadata":{"urlMetadata":[{"retrievedUrl":"https://example.com/a","urlRetrievalStatus":"URL_RETRIEVAL_STATUS_SUCCESS"},
              {"retrievedUrl":"https://example.com/b","urlRetrievalStatus":"URL_RETRIEVAL_STATUS_PAYWALL"}]}
        }""").jsonObject
        val expected = parseGoogleGroundingAnnotations(candidate)
        val decoder = GoogleStreamDecoder("id", "gemini-3.8-flash")
        val event = buildJsonObject { put("candidates", JsonArray(listOf(candidate))) }.toString()
        val actual = decoder.accept(SseEvent(data = event)).chunks.filterIsInstance<StreamChunk.Annotations>().flatMap { it.annotations }
        assertEquals(expected, actual)
        val citations = actual.filterIsInstance<UIMessageAnnotation.UrlCitation>()
        assertEquals("https://example.com/image.jpg", citations.first { it.title == "Image" }.imageUrl)
        assertEquals(2, citations.count { it.retrievalStatus != null })
        assertEquals(listOf("visual reference"), actual.filterIsInstance<UIMessageAnnotation.GoogleSearchSuggestions>().single().imageQueries)
        decoder.onClosed()
    }
    @Test fun `old saved configuration keeps default tools and new options round trip`() {
        assertNull(Json.decodeFromString<GeminiGenerationOptions>("{}").search.enabled)
        val options = GeminiGenerationOptions(search = GeminiSearchOptions(enabled = true, urlContext = true, imageSearch = true))
        assertEquals(options, Json.decodeFromString<GeminiGenerationOptions>(Json.encodeToString(options)))
    }

    private fun request(model: Model, search: GeminiSearchOptions, withFunctions: Boolean = false): JsonObject {
        val provider = GoogleProvider(OkHttpClient())
        val method = GoogleProvider::class.java.getDeclaredMethod("buildCompletionRequestBody", List::class.java, TextGenerationParams::class.java)
            .apply { isAccessible = true }
        return method.invoke(provider, listOf(UIMessage.user("Read https://example.com/manual.pdf and search for images")),
            TextGenerationParams(model = model, geminiOptions = GeminiGenerationOptions(search = search),
                tools = if (withFunctions) listOf(Tool(name = "local", description = "Local tool",
                    parameters = { InputSchema.Obj(properties = buildJsonObject {}) }, execute = { emptyList() })) else emptyList())) as JsonObject
    }
}
