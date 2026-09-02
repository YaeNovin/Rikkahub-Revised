package me.rerere.ai.provider.providers.google

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.BuiltInTools
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.TextGenerationParams
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
    }

    private fun buildRequest(
        modelId: String,
        builtInTools: Set<BuiltInTools>,
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
                    abilities = listOf(ModelAbility.TOOL),
                    tools = builtInTools,
                ),
                tools = listOf(
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
                ),
            ),
        ) as JsonObject
    }
}
