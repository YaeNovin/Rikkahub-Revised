package me.rerere.rikkahub.ui.pages.assistant.detail

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.provider.CustomBody
import me.rerere.ai.provider.CustomHeader
import me.rerere.ai.provider.ProviderSetting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantCustomRequestSupportTest {
    @Test
    fun `preview redacts secrets nested inside arrays`() {
        val value = kotlinx.serialization.json.Json.parseToJsonElement(
            """[{"api_key":"private"},[{"password":"private"}],{"name":"visible"}]"""
        )
        val preview = effectiveCustomBody(listOf(CustomBody("items", value)), emptyList())
        assertFalse(preview.toString().contains("private"))
        assertTrue(preview.toString().contains("visible"))
    }

    @Test
    fun `Anthropic messages are managed fields but not a protocol mismatch`() {
        val issues = analyzeCustomRequest(
            assistantHeaders = emptyList(),
            assistantBodies = listOf(CustomBody("messages", JsonPrimitive("override"))),
            modelHeaders = emptyList(),
            modelBodies = emptyList(),
            route = ParameterRequestRoute(
                ParameterWireProtocol.ANTHROPIC_MESSAGES,
                ParameterEndpoint.ANTHROPIC,
            ),
        )
        assertTrue(issues.any { it.kind == CustomRequestIssueKind.APP_MANAGED_BODY })
        assertFalse(issues.any { it.kind == CustomRequestIssueKind.PROTOCOL_MISMATCH })
    }

    @Test
    fun `request route uses current model family and official endpoint`() {
        val qwen = ProviderSetting.OpenAI(
            baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1",
        ).parameterRequestRouteForModel("qwen3.8-flash")
        val deepSeek = ProviderSetting.OpenAI(
            baseUrl = "https://api.deepseek.com/v1",
        ).parameterRequestRouteForModel("deepseek-v4-pro")

        assertEquals(ParameterEndpoint.ALIBABA_MODEL_STUDIO, qwen.endpoint)
        assertEquals(ParameterWireProtocol.OPENAI_CHAT_COMPLETIONS, qwen.protocol)
        assertEquals(ParameterEndpoint.DEEPSEEK, deepSeek.endpoint)
    }

    @Test
    fun `official templates are filtered by route and start disabled`() {
        val qwenRoute = ProviderSetting.OpenAI(
            baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1",
        ).parameterRequestRouteForModel("qwen3.8-flash")
        val qwenPresets = customRequestPresets(qwenRoute, "qwen3.8-flash")
        val thirdPartyRoute = ProviderSetting.OpenAI(
            baseUrl = "https://gateway.example.com/v1",
        ).parameterRequestRouteForModel("qwen3.8-flash")

        assertEquals(listOf(CustomRequestPresetId.QWEN_WEB_SEARCH), qwenPresets.map { it.id })
        assertTrue(qwenPresets.single().bodies.all { !it.enabled })
        assertTrue(customRequestPresets(thirdPartyRoute, "qwen3.8-flash").isEmpty())
    }

    @Test
    fun `analysis reports invalid duplicates priority and protocol conflicts`() {
        val route = ParameterRequestRoute(
            protocol = ParameterWireProtocol.GOOGLE_GENERATE_CONTENT,
            endpoint = ParameterEndpoint.GOOGLE_AI_STUDIO,
        )
        val issues = analyzeCustomRequest(
            assistantHeaders = listOf(
                CustomHeader("X Project", "invalid"),
                CustomHeader("Authorization", "secret"),
                CustomHeader("X-Mode", "one"),
                CustomHeader("x-mode", "two"),
            ),
            assistantBodies = listOf(
                CustomBody("contents", JsonPrimitive("broken")),
                CustomBody("response_format", JsonPrimitive("wrong protocol")),
                CustomBody("custom", JsonPrimitive(1)),
            ),
            modelHeaders = listOf(CustomHeader("X-Mode", "model")),
            modelBodies = listOf(CustomBody("custom", JsonPrimitive(2))),
            route = route,
        )

        val kinds = issues.mapTo(hashSetOf()) { it.kind }
        assertTrue(CustomRequestIssueKind.INVALID_HEADER in kinds)
        assertTrue(CustomRequestIssueKind.AUTH_HEADER in kinds)
        assertTrue(CustomRequestIssueKind.DUPLICATE_HEADER in kinds)
        assertTrue(CustomRequestIssueKind.MODEL_OVERRIDES_HEADER in kinds)
        assertTrue(CustomRequestIssueKind.APP_MANAGED_BODY in kinds)
        assertTrue(CustomRequestIssueKind.PROTOCOL_MISMATCH in kinds)
        assertTrue(CustomRequestIssueKind.MODEL_OVERRIDES_BODY in kinds)
    }

    @Test
    fun `import replaces top level keys and effective preview masks secrets`() {
        val imported = importCustomBodyObject(
            existing = listOf(
                CustomBody("keep", JsonPrimitive(1)),
                CustomBody("replace", JsonPrimitive("old")),
            ),
            imported = buildJsonObject {
                put("replace", "new")
                put("credentials", buildJsonObject { put("api_key", "private") })
            },
        )
        val preview = effectiveCustomBody(imported, emptyList())
        val headers = effectiveCustomHeaders(
            assistantHeaders = listOf(CustomHeader("Authorization", "Bearer private")),
            modelHeaders = listOf(CustomHeader("X-Mode", "model")),
        )

        assertEquals(3, imported.size)
        assertEquals("new", preview["replace"]?.jsonPrimitive?.content)
        assertEquals(
            "******",
            preview["credentials"]?.jsonObject?.get("api_key")?.jsonPrimitive?.content,
        )
        assertEquals("******", headers["Authorization"])
        assertEquals("model", headers["X-Mode"])
        assertFalse(headers.values.any { "private" in it })
    }
}
