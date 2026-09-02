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
import me.rerere.ai.provider.GrokGenerationOptions
import me.rerere.ai.provider.GrokParallelToolCalls
import me.rerere.ai.provider.GrokResponseFormat
import me.rerere.ai.provider.GrokServiceTier
import me.rerere.ai.provider.GrokToolChoice
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.supportsReasoningCapability
import me.rerere.ai.provider.providers.openai.isValidGrokJsonSchema
import me.rerere.ai.provider.providers.openai.resolveGrokModelParameterSupport
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
fun AssistantGrokPage(id: String) {
    val vm: AssistantDetailVM = koinViewModel(parameters = { parametersOf(id) })
    val assistant by vm.assistant.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val providers by vm.providers.collectAsStateWithLifecycle()
    val model = providers.findModelById(assistant.chatModelId ?: settings.chatModelId)
    val provider = model?.findProvider(providers) as? ProviderSetting.OpenAI
    val support = resolveGrokModelParameterSupport(model?.modelId.orEmpty())
    val enabled = provider != null && support.available
    val isResponses = provider?.useResponseApi == true
    val isReasoningModel = support.reasoningModel ||
        model?.supportsReasoningCapability() == true
    val route = provider?.parameterRequestRoute()
    val unavailableMessage = when {
        model == null -> stringResource(R.string.assistant_grok_unavailable_no_model)
        provider == null -> stringResource(R.string.assistant_grok_unavailable_protocol)
        !support.available -> stringResource(R.string.assistant_grok_unavailable_model)
        else -> null
    }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.assistant_page_tab_grok)) },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        AssistantGrokContent(
            innerPadding = innerPadding,
            assistant = assistant,
            enabled = enabled,
            isResponses = isResponses,
            isReasoningModel = isReasoningModel,
            supportsPresencePenalty = support.supportsPresencePenalty,
            route = route,
            unavailableMessage = unavailableMessage,
            onUpdate = vm::update,
        )
    }
}

@Composable
private fun AssistantGrokContent(
    innerPadding: PaddingValues,
    assistant: Assistant,
    enabled: Boolean,
    isResponses: Boolean,
    isReasoningModel: Boolean,
    supportsPresencePenalty: Boolean,
    route: ParameterRequestRoute?,
    unavailableMessage: String?,
    onUpdate: (Assistant) -> Unit,
) {
    val options = assistant.grokOptions

    fun update(transform: (GrokGenerationOptions) -> GrokGenerationOptions) {
        onUpdate(assistant.copy(grokOptions = transform(options)))
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
                label = { Text(stringResource(R.string.assistant_grok_experimental_title)) },
                description = {
                    Text(unavailableMessage ?: stringResource(R.string.assistant_grok_available_desc))
                    route?.DisplayText()
                    Text(stringResource(R.string.assistant_grok_common_parameters_desc))
                    Text(stringResource(R.string.assistant_grok_logging_desc))
                    ParameterWarningText(stringResource(R.string.assistant_parameter_experimental_warning))
                    if (route?.endpoint == ParameterEndpoint.THIRD_PARTY) {
                        ParameterWarningText(stringResource(R.string.assistant_grok_compatible_warning))
                    }
                },
            )
        }

        Column(
            modifier = Modifier.alpha(if (enabled) 1f else 0.45f),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Card(colors = CustomColors.cardColorsOnSurfaceContainer) {
                GrokSelectItem(
                    title = stringResource(R.string.assistant_grok_service_tier),
                    description = stringResource(R.string.assistant_grok_service_tier_desc),
                    warning = stringResource(R.string.assistant_grok_service_tier_warning),
                    options = GrokServiceTier.entries,
                    selected = options.serviceTier,
                    enabled = enabled,
                    label = { it.displayName() },
                    onSelected = { value -> update { it.copy(serviceTier = value) } },
                )
            }

            Card(colors = CustomColors.cardColorsOnSurfaceContainer) {
                GrokSelectItem(
                    title = stringResource(R.string.assistant_grok_parallel_tools),
                    description = stringResource(R.string.assistant_grok_parallel_tools_desc),
                    warning = stringResource(R.string.assistant_grok_tools_warning),
                    options = GrokParallelToolCalls.entries,
                    selected = options.parallelToolCalls,
                    enabled = enabled,
                    label = { it.displayName() },
                    onSelected = { value -> update { it.copy(parallelToolCalls = value) } },
                )
                HorizontalDivider()
                GrokSelectItem(
                    title = stringResource(R.string.assistant_grok_tool_choice),
                    description = stringResource(R.string.assistant_grok_tool_choice_desc),
                    warning = stringResource(R.string.assistant_grok_tools_warning),
                    options = GrokToolChoice.entries,
                    selected = options.toolChoice,
                    enabled = enabled,
                    label = { it.displayName() },
                    onSelected = { value -> update { it.copy(toolChoice = value) } },
                )
                HorizontalDivider()
                GrokIntegerItem(
                    title = stringResource(R.string.assistant_grok_max_turns),
                    description = stringResource(R.string.assistant_grok_max_turns_desc),
                    warning = stringResource(
                        if (isResponses) R.string.assistant_grok_max_turns_warning
                        else R.string.assistant_grok_responses_only
                    ),
                    value = options.maxTurns,
                    enabled = enabled && isResponses,
                    onValueChange = { value -> update { it.copy(maxTurns = value) } },
                )
            }

            Card(colors = CustomColors.cardColorsOnSurfaceContainer) {
                GrokSelectItem(
                    title = stringResource(R.string.assistant_grok_response_format),
                    description = stringResource(R.string.assistant_grok_response_format_desc),
                    warning = stringResource(R.string.assistant_grok_response_format_warning),
                    options = GrokResponseFormat.entries,
                    selected = options.responseFormat,
                    enabled = enabled,
                    label = { it.displayName() },
                    onSelected = { value -> update { it.copy(responseFormat = value) } },
                )
                if (options.responseFormat == GrokResponseFormat.JSON_SCHEMA) {
                    HorizontalDivider()
                    GrokSchemaItem(
                        options = options,
                        enabled = enabled,
                        onUpdate = ::update,
                    )
                }
            }

            Card(colors = CustomColors.cardColorsOnSurfaceContainer) {
                FormItem(
                    modifier = Modifier.padding(12.dp),
                    label = { Text(stringResource(R.string.assistant_grok_chat_parameters)) },
                    description = {
                        Text(
                            stringResource(
                                if (isResponses) R.string.assistant_grok_chat_only
                                else R.string.assistant_grok_chat_parameters_desc
                            )
                        )
                    },
                )
                HorizontalDivider()
                GrokLongItem(
                    title = stringResource(R.string.assistant_grok_seed),
                    description = stringResource(R.string.assistant_grok_seed_desc),
                    warning = stringResource(R.string.assistant_grok_seed_warning),
                    value = options.seed,
                    enabled = enabled && !isResponses,
                    onValueChange = { value -> update { it.copy(seed = value) } },
                )
                HorizontalDivider()
                GrokStopSequencesItem(
                    values = options.stopSequences,
                    enabled = enabled && !isResponses && !isReasoningModel,
                    reasoningModel = isReasoningModel,
                    onValueChange = { value -> update { it.copy(stopSequences = value) } },
                )
                HorizontalDivider()
                GrokPenaltyItem(
                    title = stringResource(R.string.assistant_grok_presence_penalty),
                    description = stringResource(R.string.assistant_grok_presence_penalty_desc),
                    value = options.presencePenalty,
                    enabled = enabled && !isResponses && !isReasoningModel && supportsPresencePenalty,
                    onValueChange = { value -> update { it.copy(presencePenalty = value) } },
                )
                HorizontalDivider()
                GrokPenaltyItem(
                    title = stringResource(R.string.assistant_grok_frequency_penalty),
                    description = stringResource(R.string.assistant_grok_frequency_penalty_desc),
                    value = options.frequencyPenalty,
                    enabled = enabled && !isResponses && !isReasoningModel,
                    onValueChange = { value -> update { it.copy(frequencyPenalty = value) } },
                )
            }

            Card(colors = CustomColors.cardColorsOnSurfaceContainer) {
                FormItem(
                    modifier = Modifier.padding(12.dp),
                    label = { Text(stringResource(R.string.assistant_grok_responses_parameters)) },
                    description = {
                        Text(
                            stringResource(
                                if (isResponses) R.string.assistant_grok_responses_parameters_desc
                                else R.string.assistant_grok_responses_only
                            )
                        )
                    },
                )
                HorizontalDivider()
                GrokFloatItem(
                    title = stringResource(R.string.assistant_grok_min_p),
                    description = stringResource(R.string.assistant_grok_min_p_desc),
                    warning = stringResource(R.string.assistant_grok_sampling_warning),
                    value = options.minP,
                    enabled = enabled && isResponses,
                    valid = { it in 0f..1f },
                    onValueChange = { value -> update { it.copy(minP = value) } },
                )
                HorizontalDivider()
                GrokIntegerItem(
                    title = stringResource(R.string.assistant_grok_top_k),
                    description = stringResource(R.string.assistant_grok_top_k_desc),
                    warning = stringResource(R.string.assistant_grok_sampling_warning),
                    value = options.topK,
                    enabled = enabled && isResponses,
                    onValueChange = { value -> update { it.copy(topK = value) } },
                )
            }
        }
    }
}

@Composable
private fun <T> GrokSelectItem(
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
private fun GrokSchemaItem(
    options: GrokGenerationOptions,
    enabled: Boolean,
    onUpdate: ((GrokGenerationOptions) -> GrokGenerationOptions) -> Unit,
) {
    val validSchema = isValidGrokJsonSchema(options.responseJsonSchema)
    FormItem(
        modifier = Modifier.padding(12.dp),
        label = { Text(stringResource(R.string.assistant_grok_json_schema)) },
        description = {
            Text(stringResource(R.string.assistant_grok_json_schema_desc))
            if (!validSchema) {
                ParameterWarningText(stringResource(R.string.assistant_grok_json_schema_invalid))
            }
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = options.responseSchemaName,
                onValueChange = { raw ->
                    if (raw.length <= 64 && raw.all { it.isLetterOrDigit() || it == '_' || it == '-' }) {
                        onUpdate { it.copy(responseSchemaName = raw) }
                    }
                },
                enabled = enabled,
                singleLine = true,
                label = { Text(stringResource(R.string.assistant_grok_schema_name)) },
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = options.responseJsonSchema,
                onValueChange = { raw -> onUpdate { it.copy(responseJsonSchema = raw) } },
                enabled = enabled,
                minLines = 5,
                isError = !validSchema,
                label = { Text(stringResource(R.string.assistant_grok_json_schema)) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun GrokStopSequencesItem(
    values: List<String>,
    enabled: Boolean,
    reasoningModel: Boolean,
    onValueChange: (List<String>) -> Unit,
) {
    FormItem(
        modifier = Modifier.padding(12.dp),
        label = { Text(stringResource(R.string.assistant_grok_stop_sequences)) },
        description = {
            Text(stringResource(R.string.assistant_grok_stop_sequences_desc))
            ParameterWarningText(
                stringResource(
                    if (reasoningModel) R.string.assistant_grok_reasoning_unsupported
                    else R.string.assistant_grok_stop_sequences_warning
                )
            )
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
private fun GrokPenaltyItem(
    title: String,
    description: String,
    value: Float?,
    enabled: Boolean,
    onValueChange: (Float?) -> Unit,
) = GrokFloatItem(
    title = title,
    description = description,
    warning = stringResource(R.string.assistant_grok_penalty_warning),
    value = value,
    enabled = enabled,
    valid = { it in -2f..2f },
    onValueChange = onValueChange,
)

@Composable
private fun GrokFloatItem(
    title: String,
    description: String,
    warning: String,
    value: Float?,
    enabled: Boolean,
    valid: (Float) -> Boolean,
    onValueChange: (Float?) -> Unit,
) {
    var rawValue by remember(value) { mutableStateOf(value?.toString().orEmpty()) }
    val parsedValue = rawValue.toFloatOrNull()
    val hasInvalidValue = rawValue.isNotBlank() && (parsedValue == null || !valid(parsedValue))
    GrokTextItem(title, description, warning) {
        OutlinedTextField(
            value = rawValue,
            onValueChange = { raw ->
                rawValue = raw
                if (raw.isBlank()) onValueChange(null)
                else raw.toFloatOrNull()?.takeIf(valid)?.let(onValueChange)
            },
            enabled = enabled,
            singleLine = true,
            isError = hasInvalidValue,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
            placeholder = { Text(stringResource(R.string.assistant_grok_option_auto)) },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun GrokIntegerItem(
    title: String,
    description: String,
    warning: String,
    value: Int?,
    enabled: Boolean,
    onValueChange: (Int?) -> Unit,
) {
    GrokTextItem(title, description, warning) {
        OutlinedTextField(
            value = value?.toString().orEmpty(),
            onValueChange = { raw ->
                if (raw.isBlank()) onValueChange(null)
                else raw.toIntOrNull()?.takeIf { it > 0 }?.let(onValueChange)
            },
            enabled = enabled,
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            placeholder = { Text(stringResource(R.string.assistant_grok_option_auto)) },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun GrokLongItem(
    title: String,
    description: String,
    warning: String,
    value: Long?,
    enabled: Boolean,
    onValueChange: (Long?) -> Unit,
) {
    GrokTextItem(title, description, warning) {
        OutlinedTextField(
            value = value?.toString().orEmpty(),
            onValueChange = { raw ->
                if (raw.isBlank()) onValueChange(null)
                else raw.toLongOrNull()?.let(onValueChange)
            },
            enabled = enabled,
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            placeholder = { Text(stringResource(R.string.assistant_grok_option_auto)) },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun GrokTextItem(
    title: String,
    description: String,
    warning: String,
    content: @Composable () -> Unit,
) {
    FormItem(
        modifier = Modifier.padding(12.dp),
        label = { Text(title) },
        description = { Text(description); ParameterWarningText(warning) },
    ) {
        content()
    }
}

@Composable
private fun GrokServiceTier.displayName(): String = stringResource(
    when (this) {
        GrokServiceTier.AUTO -> R.string.assistant_grok_option_auto
        GrokServiceTier.DEFAULT -> R.string.assistant_grok_tier_default
        GrokServiceTier.PRIORITY -> R.string.assistant_grok_tier_priority
    }
)

@Composable
private fun GrokParallelToolCalls.displayName(): String = stringResource(
    when (this) {
        GrokParallelToolCalls.AUTO -> R.string.assistant_grok_option_auto
        GrokParallelToolCalls.ENABLED -> R.string.assistant_grok_option_enabled
        GrokParallelToolCalls.DISABLED -> R.string.assistant_grok_option_disabled
    }
)

@Composable
private fun GrokToolChoice.displayName(): String = stringResource(
    when (this) {
        GrokToolChoice.DEFAULT -> R.string.assistant_grok_option_default
        GrokToolChoice.AUTO -> R.string.assistant_grok_tool_auto
        GrokToolChoice.NONE -> R.string.assistant_grok_tool_none
        GrokToolChoice.REQUIRED -> R.string.assistant_grok_tool_required
    }
)

@Composable
private fun GrokResponseFormat.displayName(): String = stringResource(
    when (this) {
        GrokResponseFormat.AUTO -> R.string.assistant_grok_option_auto
        GrokResponseFormat.TEXT -> R.string.assistant_grok_format_text
        GrokResponseFormat.JSON_OBJECT -> R.string.assistant_grok_format_json_object
        GrokResponseFormat.JSON_SCHEMA -> R.string.assistant_grok_format_json_schema
    }
)
