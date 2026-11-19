package me.rerere.ai.provider.providers.google

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.BuiltInTools
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.provider.CustomBody
import me.rerere.ai.ui.UIMessage
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GoogleToolCompositionTest {
    private val provider = GoogleProvider(OkHttpClient())

    @Test
    fun `Gemini 2 keeps local functions when a built in tool would conflict`() {
        val body = buildRequest(
            modelId = "gemini-2.5-flash",
            builtInTools = setOf(BuiltInTools.UrlContext),
        )

        val tools = body.getValue("tools").jsonArray
        assertEquals(1, tools.size)
        assertTrue("functionDeclarations" in tools.single().jsonObject)
        assertFalse(tools.any { "urlContext" in it.jsonObject })
        assertFalse("toolConfig" in body)
    }

    @Test
    fun `Gemini 3 combines local functions and built in tools without overwriting`() {
        val body = buildRequest(
            modelId = "gemini-3-flash-preview",
            builtInTools = setOf(BuiltInTools.Search, BuiltInTools.UrlContext),
        )

        val tools = body.getValue("tools").jsonArray.map { it.jsonObject }
        assertTrue(tools.any { "functionDeclarations" in it })
        assertTrue(tools.any { "googleSearch" in it })
        assertTrue(tools.any { "urlContext" in it })
        assertEquals(true, body.getValue("toolConfig").jsonObject.getValue("includeServerSideToolInvocations").jsonPrimitive.booleanOrNull)
    }

    private fun buildRequest(
        modelId: String,
        builtInTools: Set<BuiltInTools>,
        customBody: List<CustomBody> = emptyList(),
        localTools: Boolean = true,
        abilities: List<ModelAbility> = listOf(ModelAbility.TOOL),
    ): JsonObject {
        val method = GoogleProvider::class.java.getDeclaredMethod(
            "buildCompletionRequestBody",
            List::class.java,
            TextGenerationParams::class.java,
        ).apply { isAccessible = true }
        return method.invoke(
            provider,
            listOf(UIMessage.user("Search for current information")),
            TextGenerationParams(
                model = Model(
                    modelId = modelId,
                    abilities = abilities,
                    tools = builtInTools,
                ),
                customBody = customBody,
                tools = if (localTools) listOf(
                    Tool(
                        name = "search_web",
                        description = "Search the web",
                        parameters = {
                            InputSchema.Obj(
                                properties = buildJsonObject {
                                    put("query", buildJsonObject { put("type", "string") })
                                },
                                required = listOf("query"),
                            )
                        },
                        execute = { emptyList() },
                    )
                ) else emptyList(),
            ),
        ) as JsonObject
    }

    @Test fun `Gemini 4 restored without saved abilities still sends search URL context and functions`() {
        val body = buildRequest("gemini-4-argon", setOf(BuiltInTools.Search, BuiltInTools.UrlContext), abilities = emptyList())
        val tools = body.getValue("tools").jsonArray.map { it.jsonObject }
        assertTrue(tools.any { "googleSearch" in it })
        assertTrue(tools.any { "urlContext" in it })
        assertTrue(tools.any { "functionDeclarations" in it })
        assertEquals("VALIDATED", body.getValue("toolConfig").jsonObject.getValue("functionCallingConfig").jsonObject.getValue("mode").jsonPrimitive.content)
    }

    @Test fun `search plus function calling sends the required flag after all custom overrides`() {
        for (model in listOf("gemini-3.5-flash", "google/gemini-3.5-flash")) {
            val body = buildRequest(model, setOf(BuiltInTools.Search), customBody = listOf(
                CustomBody("tool_config", Json.parseToJsonElement("""{"include_server_side_tool_invocations":false,"functionCallingConfig":{"mode":"AUTO"}}""")),
                CustomBody("toolConfig", Json.parseToJsonElement("""{"includeServerSideToolInvocations":false,"retrievalConfig":{"latLng":{"latitude":1}}}""")),
            ))
            val config = body.getValue("toolConfig").jsonObject
            assertEquals(JsonPrimitive(true), config["includeServerSideToolInvocations"])
            assertEquals("VALIDATED", config.getValue("functionCallingConfig").jsonObject.getValue("mode").jsonPrimitive.content)
            assertTrue("retrievalConfig" in config)
            assertFalse("tool_config" in body)
        }
    }

    @Test fun `custom injected builtin search and function declarations also enable circulation`() {
        val body = buildRequest("gemini-3.5-flash", emptySet(), localTools = false, customBody = listOf(
            CustomBody("tools", Json.parseToJsonElement("""[{"function_declarations":[{"name":"get_session_capabilities"}]},{"google_search":{}}]""")),
        ))
        assertEquals(JsonPrimitive(true), body.getValue("toolConfig").jsonObject["includeServerSideToolInvocations"])
    }

    @Test fun `search only and functions only keep their existing request contracts`() {
        assertFalse("toolConfig" in buildRequest("gemini-3.5-flash", setOf(BuiltInTools.Search), localTools = false))
        assertFalse("toolConfig" in buildRequest("gemini-3.5-flash", emptySet()))
    }

    @Test fun `image and edit bodies with custom functions obey the same mixed tool contract`() {
        val imageModel = Model(modelId = "gemini-3.1-flash-image-preview")
        val constraints = provider.imageGenerationConstraints(me.rerere.ai.provider.ProviderSetting.Google(), imageModel)
        for (parts in listOf(emptyList(), listOf(buildJsonObject { put("text", "reference") }))) {
            val body = buildGoogleImageRequestBody("bird", "1:1", null, customBody = listOf(
                CustomBody("tools", Json.parseToJsonElement("""[{"googleSearch":{}},{"functionDeclarations":[{"name":"check_result"}]}]""")),
                CustomBody("tool_config", Json.parseToJsonElement("""{"include_server_side_tool_invocations":false}""")),
            ), constraints = constraints, imageParts = parts)
            assertEquals(JsonPrimitive(true), body.getValue("toolConfig").jsonObject["includeServerSideToolInvocations"])
            assertEquals("VALIDATED", body.getValue("toolConfig").jsonObject.getValue("functionCallingConfig").jsonObject.getValue("mode").jsonPrimitive.content)
        }
    }
}
