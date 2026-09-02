package me.rerere.rikkahub.ui.pages.assistant.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.ai.provider.ClaudeGenerationOptions
import me.rerere.ai.provider.ClaudeInferenceGeo
import me.rerere.ai.provider.ClaudeParallelToolCalls
import me.rerere.ai.provider.ClaudeResponseFormat
import me.rerere.ai.provider.ClaudeServiceTier
import me.rerere.ai.provider.ClaudeThinkingDisplay
import me.rerere.ai.provider.ClaudeToolChoice
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ModelParameterFamily
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.providers.claude.isValidClaudeJsonSchema
import me.rerere.ai.provider.providers.claude.resolveClaudeModelParameterSupport
import me.rerere.ai.provider.resolveParameterFamily
import me.rerere.ai.provider.parameterModelId
import me.rerere.ai.provider.supportsReasoningCapability
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.FormItem
import me.rerere.rikkahub.ui.components.ui.Select
import me.rerere.rikkahub.ui.theme.CustomColors
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
fun AssistantClaudePage(id: String) {
    val vm: AssistantDetailVM = koinViewModel(parameters = { parametersOf(id) })
    val assistant by vm.assistant.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val providers by vm.providers.collectAsStateWithLifecycle()
    val model = providers.findModelById(assistant.chatModelId ?: settings.chatModelId)
    val selectedProvider = model?.findProvider(providers)
    val provider = selectedProvider as? ProviderSetting.Claude
    val openAIProvider = selectedProvider as? ProviderSetting.OpenAI
    val support = resolveClaudeModelParameterSupport(model?.parameterModelId().orEmpty())
    val openAIChatCompatible = openAIProvider != null && !openAIProvider.useResponseApi
    val isClaudeFamily = model?.resolveParameterFamily(selectedProvider) == ModelParameterFamily.CLAUDE
    val enabled = isClaudeFamily && (provider != null || openAIChatCompatible)
    val nativeProtocol = provider != null
    val route = selectedProvider?.parameterRequestRoute()
    val isReasoningModel = support.supportsAdaptiveThinking ||
        support.supportsManualThinking ||
        model?.supportsReasoningCapability() == true
    val unavailableMessage = when {
        model == null -> stringResource(R.string.assistant_claude_unavailable_no_model)
        !isClaudeFamily -> stringResource(R.string.assistant_claude_unavailable_model)
        openAIProvider?.useResponseApi == true -> stringResource(R.string.assistant_compatible_requires_chat_completions)
        provider == null && openAIProvider == null -> stringResource(R.string.assistant_claude_unavailable_protocol)
        else -> null
    }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.assistant_page_tab_claude)) },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        AssistantClaudeContent(
            innerPadding = innerPadding,
            assistant = assistant,
            enabled = enabled,
            isReasoningModel = isReasoningModel,
            supportsThinkingDisplay = nativeProtocol &&
                (support.supportsAdaptiveThinking || support.supportsManualThinking),
            supportsServiceTier = nativeProtocol && support.supportsServiceTier,
            supportsInferenceGeo = nativeProtocol && support.supportsInferenceGeo,
            supportsSampling = nativeProtocol && support.supportsSamplingParameters,
            supportsStructuredOutput = if (nativeProtocol) support.supportsStructuredOutput else enabled,
            route = route,
            openAICompatible = openAIChatCompatible,
            unavailableMessage = unavailableMessage,
            onUpdate = vm::update,
        )
    }
}

@Composable
private fun AssistantClaudeContent(
    innerPadding: PaddingValues,
    assistant: Assistant,
    enabled: Boolean,
    isReasoningModel: Boolean,
    supportsThinkingDisplay: Boolean,
    supportsServiceTier: Boolean,
    supportsInferenceGeo: Boolean,
    supportsSampling: Boolean,
    supportsStructuredOutput: Boolean,
    route: ParameterRequestRoute?,
    openAICompatible: Boolean,
    unavailableMessage: String?,
    onUpdate: (Assistant) -> Unit,
) {
    val options = assistant.claudeOptions
    val reasoningEnabled = isReasoningModel && assistant.reasoningLevel.isEnabled

    fun update(transform: (ClaudeGenerationOptions) -> ClaudeGenerationOptions) {
        onUpdate(assistant.copy(claudeOptions = transform(options)))
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(innerPadding)
            .imePadding(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Card(colors = CustomColors.cardColorsOnSurfaceContainer) {
            FormItem(
                modifier = Modifier.padding(12.dp),
                label = { Text(stringResource(R.string.assistant_claude_experimental_title)) },
                description = {
                    Text(unavailableMessage ?: stringResource(R.string.assistant_claude_available_desc))
                    route?.DisplayText()
                    Text(stringResource(R.string.assistant_claude_common_parameters_desc))
                    Text(stringResource(R.string.assistant_claude_logging_desc))
                    ParameterWarningText(stringResource(R.string.assistant_parameter_experimental_warning))
                    if (openAICompatible) {
                        ParameterWarningText(stringResource(R.string.assistant_claude_openai_mapping_desc))
                    }
                    if (route?.endpoint == ParameterEndpoint.THIRD_PARTY) {
                        ParameterWarningText(stringResource(R.string.assistant_claude_compatible_warning))
                    }
                },
            )
        }

        Column(
            modifier = Modifier.alpha(if (enabled) 1f else 0.45f),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Card(colors = CustomColors.cardColorsOnSurfaceContainer) {
                ClaudeSelectItem(
                    title = stringResource(R.string.assistant_claude_service_tier),
                    description = stringResource(R.string.assistant_claude_service_tier_desc),
                    warning = stringResource(
                        if (supportsServiceTier) R.string.assistant_claude_service_tier_warning
                        else R.string.assistant_claude_service_tier_unsupported
                    ),
                    options = ClaudeServiceTier.entries,
                    selected = options.serviceTier,
                    enabled = enabled && supportsServiceTier,
                    label = { it.displayName() },
                    onSelected = { value -> update { it.copy(serviceTier = value) } },
                )
                HorizontalDivider()
                ClaudeSelectItem(
                    title = stringResource(R.string.assistant_claude_inference_geo),
                    description = stringResource(R.string.assistant_claude_inference_geo_desc),
                    warning = stringResource(
                        if (supportsInferenceGeo) R.string.assistant_claude_inference_geo_warning
                        else R.string.assistant_claude_inference_geo_unsupported
                    ),
                    options = ClaudeInferenceGeo.entries,
                    selected = options.inferenceGeo,
                    enabled = enabled && supportsInferenceGeo,
                    label = { it.displayName() },
                    onSelected = { value -> update { it.copy(inferenceGeo = value) } },
                )
            }

            Card(colors = CustomColors.cardColorsOnSurfaceContainer) {
                ClaudeSelectItem(
                    title = stringResource(R.string.assistant_claude_tool_choice),
                    description = stringResource(R.string.assistant_claude_tool_choice_desc),
                    warning = stringResource(R.string.assistant_claude_tools_warning),
                    options = ClaudeToolChoice.entries,
                    selected = options.toolChoice,
                    enabled = enabled,
                    label = { it.displayName() },
                    onSelected = { value -> update { it.copy(toolChoice = value) } },
                )
                HorizontalDivider()
                ClaudeSelectItem(
                    title = stringResource(R.string.assistant_claude_parallel_tools),
                    description = stringResource(R.string.assistant_claude_parallel_tools_desc),
                    warning = stringResource(R.string.assistant_claude_tools_warning),
                    options = ClaudeParallelToolCalls.entries,
                    selected = options.parallelToolCalls,
                    enabled = enabled,
                    label = { it.displayName() },
                    onSelected = { value -> update { it.copy(parallelToolCalls = value) } },
                )
            }

            Card(colors = CustomColors.cardColorsOnSurfaceContainer) {
                ClaudeSelectItem(
                    title = stringResource(R.string.assistant_claude_thinking_display),
                    description = stringResource(R.string.assistant_claude_thinking_display_desc),
                    warning = stringResource(
                        if (isReasoningModel && supportsThinkingDisplay) {
                            R.string.assistant_claude_thinking_display_warning
                        } else {
                            R.string.assistant_claude_parameter_unsupported
                        }
                    ),
                    options = ClaudeThinkingDisplay.entries,
                    selected = options.thinkingDisplay,
                    enabled = enabled && isReasoningModel && supportsThinkingDisplay,
                    label = { it.displayName() },
                    onSelected = { value -> update { it.copy(thinkingDisplay = value) } },
                )
            }

            Card(colors = CustomColors.cardColorsOnSurfaceContainer) {
                ClaudeStopSequencesItem(
                    values = options.stopSequences,
                    enabled = enabled,
                    onValueChange = { value -> update { it.copy(stopSequences = value) } },
                )
                HorizontalDivider()
                ClaudeIntegerItem(
                    title = stringResource(R.string.assistant_claude_top_k),
                    description = stringResource(R.string.assistant_claude_top_k_desc),
                    warning = stringResource(
                        if (supportsSampling && !reasoningEnabled) {
                            R.string.assistant_claude_top_k_warning
                        } else {
                            R.string.assistant_claude_sampling_unsupported
                        }
                    ),
                    value = options.topK,
                    enabled = enabled && supportsSampling && !reasoningEnabled,
                    onValueChange = { value -> update { it.copy(topK = value) } },
                )
            }

            Card(colors = CustomColors.cardColorsOnSurfaceContainer) {
                ClaudeSelectItem(
                    title = stringResource(R.string.assistant_claude_response_format),
                    description = stringResource(R.string.assistant_claude_response_format_desc),
                    warning = stringResource(
                        if (supportsStructuredOutput) {
                            R.string.assistant_claude_response_format_warning
                        } else {
                            R.string.assistant_claude_structured_unsupported
                        }
                    ),
                    options = ClaudeResponseFormat.entries,
                    selected = options.responseFormat,
                    enabled = enabled && supportsStructuredOutput,
                    label = { it.displayName() },
                    onSelected = { value -> update { it.copy(responseFormat = value) } },
                )
                if (options.responseFormat == ClaudeResponseFormat.JSON_SCHEMA) {
                    HorizontalDivider()
                    ClaudeSchemaItem(
                        options = options,
                        enabled = enabled && supportsStructuredOutput,
                        onUpdate = ::update,
                    )
                }
            }
        }
    }
}

@Composable
private fun <T> ClaudeSelectItem(
    title: String,
    description: String,
    warning: String,
    options: List<T>,
    selected: T,
    enabled: Boolean,
    label: @Composable (T) -> String,
    onSelected: (T) -> Unit,
) {
    FormItem(
        modifier = Modifier.padding(12.dp),
        label = { Text(title) },
        description = { Text(description); ParameterWarningText(warning) },
    ) {
        Select(
            options = options,
            selectedOption = selected,
            onOptionSelected = onSelected,
            enabled = enabled,
            optionToString = label,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun ClaudeStopSequencesItem(
    values: List<String>,
    enabled: Boolean,
    onValueChange: (List<String>) -> Unit,
) {
    FormItem(
        modifier = Modifier.padding(12.dp),
        label = { Text(stringResource(R.string.assistant_claude_stop_sequences)) },
        description = {
            Text(stringResource(R.string.assistant_claude_stop_sequences_desc))
            ParameterWarningText(stringResource(R.string.assistant_claude_stop_sequences_warning))
        },
    ) {
        OutlinedTextField(
            value = values.joinToString("\n"),
            onValueChange = { raw -> onValueChange(raw.lines().take(4)) },
            enabled = enabled,
            minLines = 3,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun ClaudeIntegerItem(
    title: String,
    description: String,
    warning: String,
    value: Int?,
    enabled: Boolean,
    onValueChange: (Int?) -> Unit,
) {
    var rawValue by remember(value) { mutableStateOf(value?.toString().orEmpty()) }
    val invalid = rawValue.isNotBlank() && (rawValue.toIntOrNull()?.let { it > 0 } != true)
    FormItem(
        modifier = Modifier.padding(12.dp),
        label = { Text(title) },
        description = { Text(description); ParameterWarningText(warning) },
    ) {
        OutlinedTextField(
            value = rawValue,
            onValueChange = { raw ->
                rawValue = raw
                if (raw.isBlank()) onValueChange(null)
                else raw.toIntOrNull()?.takeIf { it > 0 }?.let(onValueChange)
            },
            enabled = enabled,
            singleLine = true,
            isError = invalid,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            placeholder = { Text(stringResource(R.string.assistant_claude_option_default)) },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun ClaudeSchemaItem(
    options: ClaudeGenerationOptions,
    enabled: Boolean,
    onUpdate: ((ClaudeGenerationOptions) -> ClaudeGenerationOptions) -> Unit,
) {
    val validSchema = isValidClaudeJsonSchema(options.responseJsonSchema)
    FormItem(
        modifier = Modifier.padding(12.dp),
        label = { Text(stringResource(R.string.assistant_claude_json_schema)) },
        description = {
            Text(stringResource(R.string.assistant_claude_json_schema_desc))
            if (!validSchema) {
                ParameterWarningText(stringResource(R.string.assistant_claude_json_schema_invalid))
            }
        },
    ) {
        OutlinedTextField(
            value = options.responseJsonSchema,
            onValueChange = { raw -> onUpdate { it.copy(responseJsonSchema = raw) } },
            enabled = enabled,
            minLines = 6,
            isError = !validSchema,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun ClaudeServiceTier.displayName(): String = stringResource(
    when (this) {
        ClaudeServiceTier.DEFAULT -> R.string.assistant_claude_option_default
        ClaudeServiceTier.AUTO -> R.string.assistant_claude_tier_auto
        ClaudeServiceTier.STANDARD_ONLY -> R.string.assistant_claude_tier_standard_only
    }
)

@Composable
private fun ClaudeInferenceGeo.displayName(): String = stringResource(
    when (this) {
        ClaudeInferenceGeo.DEFAULT -> R.string.assistant_claude_geo_default
        ClaudeInferenceGeo.GLOBAL -> R.string.assistant_claude_geo_global
        ClaudeInferenceGeo.US -> R.string.assistant_claude_geo_us
    }
)

@Composable
private fun ClaudeParallelToolCalls.displayName(): String = stringResource(
    when (this) {
        ClaudeParallelToolCalls.AUTO -> R.string.assistant_claude_option_default
        ClaudeParallelToolCalls.ENABLED -> R.string.assistant_claude_option_enabled
        ClaudeParallelToolCalls.DISABLED -> R.string.assistant_claude_option_disabled
    }
)

@Composable
private fun ClaudeToolChoice.displayName(): String = stringResource(
    when (this) {
        ClaudeToolChoice.DEFAULT -> R.string.assistant_claude_option_default
        ClaudeToolChoice.AUTO -> R.string.assistant_claude_tool_auto
        ClaudeToolChoice.ANY -> R.string.assistant_claude_tool_any
        ClaudeToolChoice.NONE -> R.string.assistant_claude_tool_none
    }
)

@Composable
private fun ClaudeThinkingDisplay.displayName(): String = stringResource(
    when (this) {
        ClaudeThinkingDisplay.DEFAULT -> R.string.assistant_claude_option_default
        ClaudeThinkingDisplay.SUMMARIZED -> R.string.assistant_claude_thinking_summarized
        ClaudeThinkingDisplay.OMITTED -> R.string.assistant_claude_thinking_omitted
    }
)

@Composable
private fun ClaudeResponseFormat.displayName(): String = stringResource(
    when (this) {
        ClaudeResponseFormat.AUTO -> R.string.assistant_claude_format_auto
        ClaudeResponseFormat.JSON_SCHEMA -> R.string.assistant_claude_format_json_schema
    }
)
