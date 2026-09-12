package me.rerere.ai.provider

import kotlinx.serialization.json.*
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.providers.google.GoogleProvider
import me.rerere.ai.provider.providers.claude.ClaudeProvider
import me.rerere.ai.provider.providers.openai.ChatCompletionsAPI
import me.rerere.ai.provider.providers.openai.ResponseAPI
import me.rerere.ai.ui.AskUserContract
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.AskUserInteraction
import me.rerere.ai.util.KeyRoulette
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test

class AskUserWireSchemaTest {
    private val tool = Tool("ask_user", AskUserContract.description, parameters = AskUserContract::schema, execute = { emptyList() })
    private val messages = listOf(UIMessage.user("Ask me"))
    private fun params(id: String) = TextGenerationParams(Model(modelId = id, abilities = listOf(ModelAbility.TOOL)), tools = listOf(tool))
    private fun checkSchema(schema: JsonObject) {
        val question = schema["properties"]!!.jsonObject["questions"]!!.jsonObject["items"]!!.jsonObject
        val fields = question["properties"]!!.jsonObject
        assertEquals(8, fields["selection_type"]!!.jsonObject["enum"]!!.jsonArray.size)
        assertEquals(listOf("id", "question"), question["required"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals("array", fields["default_values"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertTrue(fields["visible_if"]!!.jsonObject["properties"]!!.jsonObject.containsKey("all"))
        assertTrue(fields["visible_if"]!!.jsonObject["properties"]!!.jsonObject.containsKey("nodes"))
    }

    @Test fun `Gemini declarations preserve string enums nested condition fields and optional semantics`() {
        val provider = GoogleProvider(OkHttpClient())
        val method = GoogleProvider::class.java.getDeclaredMethod("buildCompletionRequestBody", List::class.java, TextGenerationParams::class.java).apply { isAccessible = true }
        for (id in listOf("gemini-2.5-flash", "gemini-3.8-flash")) {
            val body = method.invoke(provider, messages, params(id)) as JsonObject
            checkSchema(body["tools"]!!.jsonArray.first().jsonObject["functionDeclarations"]!!.jsonArray.single().jsonObject["parameters"]!!.jsonObject)
        }
    }

    @Test fun `Claude declarations retain the canonical contract without forcing strict mode`() {
        val provider = ClaudeProvider(OkHttpClient())
        val method = ClaudeProvider::class.java.getDeclaredMethod("buildMessageRequest", ProviderSetting.Claude::class.java,
            List::class.java, TextGenerationParams::class.java, Boolean::class.javaPrimitiveType).apply { isAccessible = true }
        for (id in listOf("claude-sonnet-4-6", "claude-opus-4-6")) {
            val body = method.invoke(provider, ProviderSetting.Claude(), messages, params(id), false) as JsonObject
            val declaration = body["tools"]!!.jsonArray.first().jsonObject
            checkSchema(declaration["input_schema"]!!.jsonObject)
            assertNull(declaration["strict"])
        }
    }

    @Test fun `OpenAI and compatible Gemini Claude DeepSeek channels keep tool schema optional`() {
        val api = ChatCompletionsAPI(OkHttpClient(), KeyRoulette.default())
        val method = ChatCompletionsAPI::class.java.getDeclaredMethod("buildChatCompletionRequest", List::class.java,
            TextGenerationParams::class.java, ProviderSetting.OpenAI::class.java, Boolean::class.javaPrimitiveType).apply { isAccessible = true }
        for (id in listOf("gpt-4.1", "deepseek-chat", "deepseek-reasoner", "gemini-3.8-flash", "claude-sonnet-4-6")) {
            val body = method.invoke(api, messages, params(id), ProviderSetting.OpenAI(baseUrl = "https://compatible.example/v1"), false) as JsonObject
            checkSchema(body["tools"]!!.jsonArray.first().jsonObject["function"]!!.jsonObject["parameters"]!!.jsonObject)
        }
        val body = ResponseAPI(OkHttpClient()).buildRequestBody(ProviderSetting.OpenAI(), messages, params("gpt-6-astra"), false)
        val function = body["tools"]!!.jsonArray.first().jsonObject
        assertEquals(JsonPrimitive(false), function["strict"])
        checkSchema(function["parameters"]!!.jsonObject)
    }

    @Test fun `Google projection never deletes properties named enum or format`() {
        val input = Json.parseToJsonElement("""{"type":"object","properties":{"enum":{"type":"string","enum":["a","b"]},"format":{"type":"string"},"level":{"type":"integer","enum":[1,2]}}}""")
        val props = input.toGoogleToolSchema().jsonObject["properties"]!!.jsonObject
        assertTrue(props.containsKey("enum")); assertTrue(props.containsKey("format"))
        assertEquals(2, props["enum"]!!.jsonObject["enum"]!!.jsonArray.size)
        assertTrue(props["level"]!!.jsonObject["description"]!!.jsonPrimitive.content.contains("[1,2]"))
    }

    @Test fun `all protocol serializers exclude local draft metadata and include only the submitted tool result`() {
        val source = """{"questions":[{"id":"choice","question":"Choose"}]}"""
        val req = me.rerere.ai.ui.AskUserProtocol.parseRequest(source).getOrThrow()
        val metadata = AskUserInteraction.update(source, req, AskUserInteraction.initial(source, 100),
            buildJsonObject { put("choice", "UNSUBMITTED_PRIVATE_DRAFT") }, emptySet(), AskUserInteraction.Clock(100, 100, 1))
        val answer = """{"answers":{"choice":"submitted-answer"}}"""
        val history = messages + UIMessage.assistant("").copy(
            requestContext = me.rerere.ai.ui.RequestContextSnapshot("local-model", "local-fingerprint", "LOCAL_CONTEXT_SENTINEL", emptyMap(), emptyMap()),
            parts = listOf(UIMessagePart.Tool(
            "call-ask", "ask_user", source, output = listOf(UIMessagePart.Text(answer)),
            approvalState = ToolApprovalState.Answered(answer), metadata = metadata)))
        val google = GoogleProvider::class.java.getDeclaredMethod("buildCompletionRequestBody", List::class.java,
            TextGenerationParams::class.java).apply { isAccessible = true }
            .invoke(GoogleProvider(OkHttpClient()), history, params("gemini-2.5-flash")) as JsonObject
        val claude = ClaudeProvider::class.java.getDeclaredMethod("buildMessageRequest", ProviderSetting.Claude::class.java,
            List::class.java, TextGenerationParams::class.java, Boolean::class.javaPrimitiveType).apply { isAccessible = true }
            .invoke(ClaudeProvider(OkHttpClient()), ProviderSetting.Claude(), history, params("claude-sonnet-4-6"), false) as JsonObject
        val chat = ChatCompletionsAPI::class.java.getDeclaredMethod("buildChatCompletionRequest", List::class.java,
            TextGenerationParams::class.java, ProviderSetting.OpenAI::class.java, Boolean::class.javaPrimitiveType).apply { isAccessible = true }
            .invoke(ChatCompletionsAPI(OkHttpClient(), KeyRoulette.default()), history, params("deepseek-chat"), ProviderSetting.OpenAI(), false) as JsonObject
        val responses = ResponseAPI(OkHttpClient()).buildRequestBody(ProviderSetting.OpenAI(), history, params("gpt-4.1"), false)
        for (body in listOf(google, claude, chat, responses)) {
            assertFalse(body.toString().contains("UNSUBMITTED_PRIVATE_DRAFT"))
            assertFalse(body.toString().contains(AskUserInteraction.KEY))
            assertFalse(body.toString().contains("LOCAL_CONTEXT_SENTINEL"))
            assertTrue(body.toString().contains("submitted-answer"))
        }
    }
}
