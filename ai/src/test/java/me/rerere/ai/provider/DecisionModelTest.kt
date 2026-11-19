package me.rerere.ai.provider

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test

class DecisionModelTest {
    @Test fun `decision type inference migration and custom instructions retain transport model id`() {
        assertEquals(ModelType.DECISION, inferModelTypeFromId("vendor/decision-model-preview"))
        assertEquals(ModelType.DECISION, Model(modelId = "decision-model-preview").withNameBasedCapabilities().type)
        val alias = Model(modelId = "deployment-123", displayName = "decision-model-preview").withDetectedCapabilities()
        assertEquals(ModelType.DECISION, alias.type)
        val body = buildSuggestionDecisionRequest(alias.modelId, "context", listOf("next"), "Prefer concise follow-ups")
        assertEquals("deployment-123", body["model"]!!.jsonPrimitive.content)
        assertTrue(body["questions"]!!.jsonObject["suggestion_0"]!!.jsonObject["instructions"]!!.jsonPrimitive.content.contains("Prefer concise follow-ups"))
        assertEquals("https://proxy.test/v1/systemone", decisionModelEndpoint("https://proxy.test/v1/systemone"))
    }
    @Test fun `System One sends state and typed questions without chat parameters`() = runBlocking {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            assertEquals("https://workspace.cn-beijing.maas.aliyuncs.com/compatible-mode/v1/systemone", request.url.toString())
            assertEquals("Bearer test", request.header("Authorization"))
            val buffer = Buffer(); request.body!!.writeTo(buffer)
            val body = Json.parseToJsonElement(buffer.readUtf8()).jsonObject
            assertEquals(setOf("model", "state", "questions"), body.keys)
            assertEquals(2, body.getValue("questions").jsonObject.size)
            assertEquals("score", body["questions"]!!.jsonObject["suggestion_0"]!!.jsonObject["type"]!!.jsonPrimitive.content)
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("""{"answers":{"suggestion_0":{"type":"score","score":1.25},"suggestion_1":{"type":"score","score":2.9}},"usage":{"input_tokens":35}}""".toResponseBody()).build()
        }.build()
        val scores = DecisionModelApi(client).scoreSuggestions(
            ProviderSetting.OpenAI(baseUrl = "https://workspace.cn-beijing.maas.aliyuncs.com/compatible-mode/v1", apiKey = "test"),
            Model(modelId = "decision-model-preview"), "conversation", listOf("candidate 0", "candidate 1"),
        )
        assertEquals(mapOf(0 to 1.25, 1 to 2.9), scores)
        assertEquals("https://aihubmix.com/v1/systemone", decisionModelEndpoint("https://aihubmix.com/v1/"))
    }

    @Test fun `malformed or missing judgments cannot reorder candidates`() {
        for (raw in listOf("{}", """{"answers":{}}""", """{"answers":{"suggestion_0":{"type":"score","score":99}}}""",
            """{"answers":{"suggestion_0":{"type":"choice","choice":"execute"}}}""")) {
            assertTrue(runCatching { parseSuggestionDecisionScores(raw, 1) }.isFailure)
        }
        assertEquals(65_536, inferContextWindowTokens("vendor/decision-model-preview"))
        assertTrue(isDecisionModelId("vendor/decision-model-preview"))
        assertFalse(isDecisionModelId("decision-model-preview-imposter"))
    }
}
