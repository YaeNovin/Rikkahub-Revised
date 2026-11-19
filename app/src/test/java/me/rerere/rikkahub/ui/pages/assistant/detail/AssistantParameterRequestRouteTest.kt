package me.rerere.rikkahub.ui.pages.assistant.detail

import me.rerere.ai.provider.ProviderSetting
import org.junit.Assert.assertEquals
import org.junit.Test

class AssistantParameterRequestRouteTest {
    @Test fun `native vendor labels do not change their selected protocol`() {
        for ((host, endpoint) in listOf("api.xiaomimimo.com" to ParameterEndpoint.MIMO,
            "api.minimax.io" to ParameterEndpoint.MINIMAX, "api.moonshot.ai" to ParameterEndpoint.KIMI,
            "open.bigmodel.cn" to ParameterEndpoint.GLM)) {
            assertEquals(ParameterRequestRoute(ParameterWireProtocol.OPENAI_RESPONSES, endpoint),
                ProviderSetting.OpenAI(baseUrl = "https://$host/v1", useResponseApi = true).parameterRequestRoute())
            assertEquals(ParameterRequestRoute(ParameterWireProtocol.ANTHROPIC_MESSAGES, endpoint),
                ProviderSetting.Claude(baseUrl = "https://$host/anthropic/v1").parameterRequestRoute())
        }
    }
    @Test
    fun `experimental Google route and official GPT62 automatic route match requests`() {
        assertEquals(ParameterWireProtocol.GOOGLE_INTERACTIONS,
            ProviderSetting.Google(useInteractionsApi = true).parameterRequestRoute().protocol)
        assertEquals(ParameterWireProtocol.GOOGLE_GENERATE_CONTENT,
            ProviderSetting.Google(useInteractionsApi = true, vertexAI = true).parameterRequestRoute().protocol)
        assertEquals(ParameterWireProtocol.OPENAI_RESPONSES,
            ProviderSetting.OpenAI().parameterRequestRouteForModel("gpt-6.2-sol").protocol)
        assertEquals(ParameterWireProtocol.OPENAI_CHAT_COMPLETIONS,
            ProviderSetting.OpenAI(baseUrl = "https://proxy.example/v1").parameterRequestRouteForModel("gpt-6.2-sol").protocol)
    }
    @Test
    fun `custom endpoints retain their configured wire protocol`() {
        assertEquals(
            ParameterRequestRoute(
                ParameterWireProtocol.GOOGLE_GENERATE_CONTENT,
                ParameterEndpoint.THIRD_PARTY,
            ),
            ProviderSetting.Google(baseUrl = "https://gemini-proxy.example/v1beta")
                .parameterRequestRoute(),
        )
        assertEquals(
            ParameterRequestRoute(
                ParameterWireProtocol.ANTHROPIC_MESSAGES,
                ParameterEndpoint.THIRD_PARTY,
            ),
            ProviderSetting.Claude(baseUrl = "https://claude-proxy.example/v1")
                .parameterRequestRoute(),
        )
        assertEquals(
            ParameterRequestRoute(
                ParameterWireProtocol.OPENAI_CHAT_COMPLETIONS,
                ParameterEndpoint.THIRD_PARTY,
            ),
            ProviderSetting.OpenAI(baseUrl = "https://openai-proxy.example/v1")
                .parameterRequestRoute(),
        )
    }

    @Test
    fun `official endpoint and responses protocol are represented independently`() {
        assertEquals(
            ParameterRequestRoute(
                ParameterWireProtocol.GOOGLE_GENERATE_CONTENT,
                ParameterEndpoint.GOOGLE_AI_STUDIO,
            ),
            ProviderSetting.Google().parameterRequestRoute(),
        )
        assertEquals(
            ParameterRequestRoute(
                ParameterWireProtocol.OPENAI_RESPONSES,
                ParameterEndpoint.OPENAI,
            ),
            ProviderSetting.OpenAI(useResponseApi = true).parameterRequestRoute(),
        )
        assertEquals(
            ParameterRequestRoute(
                ParameterWireProtocol.ANTHROPIC_MESSAGES,
                ParameterEndpoint.ANTHROPIC,
            ),
            ProviderSetting.Claude().parameterRequestRoute(),
        )
    }

    @Test
    fun `vendor endpoint override does not alter OpenAI wire protocol`() {
        assertEquals(
            ParameterRequestRoute(
                ParameterWireProtocol.OPENAI_CHAT_COMPLETIONS,
                ParameterEndpoint.ALIBABA_MODEL_STUDIO,
            ),
            ProviderSetting.OpenAI(baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1")
                .parameterRequestRoute(ParameterEndpoint.ALIBABA_MODEL_STUDIO),
        )
    }
}
