package me.rerere.rikkahub.ui.pages.assistant.detail

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import me.rerere.ai.provider.ProviderRequestChannel
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.providers.claude.requestChannel as claudeRequestChannel
import me.rerere.ai.provider.providers.google.requestChannel as googleRequestChannel
import me.rerere.ai.provider.providers.openai.requestChannel as openAIRequestChannel
import me.rerere.rikkahub.R

internal enum class ParameterWireProtocol {
    OPENAI_CHAT_COMPLETIONS,
    OPENAI_RESPONSES,
    GOOGLE_GENERATE_CONTENT,
    ANTHROPIC_MESSAGES,
}

internal enum class ParameterEndpoint {
    OPENAI,
    XAI,
    GOOGLE_AI_STUDIO,
    VERTEX_AI,
    ANTHROPIC,
    ALIBABA_MODEL_STUDIO,
    DEEPSEEK,
    THIRD_PARTY,
}

internal data class ParameterRequestRoute(
    val protocol: ParameterWireProtocol,
    val endpoint: ParameterEndpoint,
)

internal fun ProviderSetting.parameterRequestRoute(
    endpointOverride: ParameterEndpoint? = null,
): ParameterRequestRoute {
    val protocol = when (this) {
        is ProviderSetting.OpenAI -> if (useResponseApi) {
            ParameterWireProtocol.OPENAI_RESPONSES
        } else {
            ParameterWireProtocol.OPENAI_CHAT_COMPLETIONS
        }
        is ProviderSetting.Google -> ParameterWireProtocol.GOOGLE_GENERATE_CONTENT
        is ProviderSetting.Claude -> ParameterWireProtocol.ANTHROPIC_MESSAGES
    }
    val endpoint = endpointOverride ?: when (this) {
        is ProviderSetting.OpenAI -> when (openAIRequestChannel()) {
            ProviderRequestChannel.OPENAI_API -> ParameterEndpoint.OPENAI
            ProviderRequestChannel.XAI_API -> ParameterEndpoint.XAI
            else -> ParameterEndpoint.THIRD_PARTY
        }
        is ProviderSetting.Google -> when (googleRequestChannel()) {
            ProviderRequestChannel.GOOGLE_AI_STUDIO -> ParameterEndpoint.GOOGLE_AI_STUDIO
            ProviderRequestChannel.VERTEX_AI -> ParameterEndpoint.VERTEX_AI
            else -> ParameterEndpoint.THIRD_PARTY
        }
        is ProviderSetting.Claude -> when (claudeRequestChannel()) {
            ProviderRequestChannel.ANTHROPIC_API -> ParameterEndpoint.ANTHROPIC
            else -> ParameterEndpoint.THIRD_PARTY
        }
    }
    return ParameterRequestRoute(protocol = protocol, endpoint = endpoint)
}

@Composable
internal fun ParameterRequestRoute.DisplayText() {
    Text(
        stringResource(
            R.string.assistant_parameter_current_route,
            protocol.displayName(),
            endpoint.displayName(),
        )
    )
}

@Composable
internal fun ParameterWarningText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
    )
}

@Composable
private fun ParameterWireProtocol.displayName(): String = stringResource(
    when (this) {
        ParameterWireProtocol.OPENAI_CHAT_COMPLETIONS -> R.string.assistant_parameter_protocol_openai_chat
        ParameterWireProtocol.OPENAI_RESPONSES -> R.string.assistant_parameter_protocol_openai_responses
        ParameterWireProtocol.GOOGLE_GENERATE_CONTENT -> R.string.assistant_parameter_protocol_google_generate_content
        ParameterWireProtocol.ANTHROPIC_MESSAGES -> R.string.assistant_parameter_protocol_anthropic_messages
    }
)

@Composable
private fun ParameterEndpoint.displayName(): String = stringResource(
    when (this) {
        ParameterEndpoint.OPENAI -> R.string.assistant_parameter_endpoint_openai
        ParameterEndpoint.XAI -> R.string.assistant_parameter_endpoint_xai
        ParameterEndpoint.GOOGLE_AI_STUDIO -> R.string.assistant_parameter_endpoint_google_ai_studio
        ParameterEndpoint.VERTEX_AI -> R.string.assistant_parameter_endpoint_vertex_ai
        ParameterEndpoint.ANTHROPIC -> R.string.assistant_parameter_endpoint_anthropic
        ParameterEndpoint.ALIBABA_MODEL_STUDIO -> R.string.assistant_parameter_endpoint_alibaba
        ParameterEndpoint.DEEPSEEK -> R.string.assistant_parameter_endpoint_deepseek
        ParameterEndpoint.THIRD_PARTY -> R.string.assistant_parameter_endpoint_third_party
    }
)
