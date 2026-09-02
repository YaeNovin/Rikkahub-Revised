package me.rerere.rikkahub.ui.pages.assistant.detail

import me.rerere.ai.provider.ProviderSetting
import org.junit.Assert.assertEquals
import org.junit.Test

class AssistantParameterRequestRouteTest {
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
