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
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.parameterModelId
import me.rerere.ai.provider.QwenGenerationOptions
import me.rerere.ai.provider.QwenOptionalToggle
import me.rerere.ai.provider.QwenResponseFormat
import me.rerere.ai.provider.QwenToolChoice
import me.rerere.ai.provider.providers.openai.isAlibabaModelStudioHost
import me.rerere.ai.provider.providers.openai.isValidQwenJsonSchema
import me.rerere.ai.provider.providers.openai.resolveQwenModelParameterSupport
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.FormItem
import me.rerere.rikkahub.ui.components.ui.Select
import me.rerere.rikkahub.ui.theme.CustomColors
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
fun AssistantQwenPage(id: String) {
    val vm: AssistantDetailVM = koinViewModel(parameters = { parametersOf(id) })
    val assistant by vm.assistant.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val providers by vm.providers.collectAsStateWithLifecycle()
    val model = providers.findModelById(assistant.chatModelId ?: settings.chatModelId)
    val provider = model?.findProvider(providers) as? ProviderSetting.OpenAI
    val support = resolveQwenModelParameterSupport(model?.parameterModelId().orEmpty())
    val enabled = provider != null && support.available
    val isResponses = provider?.useResponseApi == true
    val officialEndpoint = provider?.baseUrl
        ?.toHttpUrlOrNull()
        ?.host
        ?.let(::isAlibabaModelStudioHost) == true
    val route = provider?.parameterRequestRoute(
        endpointOverride = ParameterEndpoint.ALIBABA_MODEL_STUDIO.takeIf { officialEndpoint },
    )
    val unavailableMessage = when {
        model == null -> stringResource(R.string.assistant_qwen_unavailable_no_model)
        provider == null -> stringResource(R.string.assistant_qwen_unavailable_protocol)
        !support.available -> stringResource(R.string.assistant_qwen_unavailable_model)
        else -> null
    }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.assistant_page_tab_qwen)) },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        AssistantQwenContent(
            innerPadding = innerPadding,
            assistant = assistant,
            enabled = enabled,
            isResponses = isResponses,
            route = route,
            supportsJsonSchema = support.supportsJsonSchema,
            supportsToolStream = support.supportsToolStream,
            supportsPreserveThinking = support.supportsPreserveThinking,
            supportsHighResolutionVision = support.supportsHighResolutionVision,
            unavailableMessage = unavailableMessage,
            onUpdate = vm::update,
        )
    }
}

@Composable
private fun AssistantQwenContent(
    innerPadding: PaddingValues,
    assistant: Assistant,
    enabled: Boolean,
    isResponses: Boolean,
    route: ParameterRequestRoute?,
    supportsJsonSchema: Boolean,
    supportsToolStream: Boolean,
    supportsPreserveThinking: Boolean,
    supportsHighResolutionVision: Boolean,
    unavailableMessage: String?,
    onUpdate: (Assistant) -> Unit,
) {
    val options = assistant.qwenOptions

    fun update(transform: (QwenGenerationOptions) -> QwenGenerationOptions) {
        onUpdate(assistant.copy(qwenOptions = transform(options)))
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
                label = { Text(stringResource(R.string.assistant_qwen_experimental_title)) },
                description = {
                    Text(unavailableMessage ?: stringResource(R.string.assistant_qwen_available_desc))
                    if (enabled) route?.DisplayText()
                    Text(stringResource(R.string.assistant_qwen_common_parameters_desc))
                    Text(stringResource(R.string.assistant_qwen_logging_desc))
                    ParameterWarningText(stringResource(R.string.assistant_parameter_experimental_warning))
                    if (enabled && route?.endpoint == ParameterEndpoint.THIRD_PARTY) {
                        ParameterWarningText(stringResource(R.string.assistant_qwen_compatible_warning))
                    }
                },
            )
        }

        Column(
            modifier = Modifier.alpha(if (enabled) 1f else 0.45f),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Card(colors = CustomColors.cardColorsOnSurfaceContainer) {
                QwenSelectItem(
                    title = stringResource(R.string.assistant_qwen_parallel_tools),
                    description = stringResource(R.string.assistant_qwen_parallel_tools_desc),
                    warning = stringResource(
                        if (isResponses) R.string.assistant_qwen_chat_only
                        else R.string.assistant_qwen_tools_warning
                    ),
                    options = QwenOptionalToggle.entries,
                    selected = options.parallelToolCalls,
                    enabled = enabled && !isResponses,
                    label = { it.displayName() },
                    onSelected = { value -> update { it.copy(parallelToolCalls = value) } },
                )
                HorizontalDivider()
                QwenSelectItem(
                    title = stringResource(R.string.assistant_qwen_tool_choice),
                    description = stringResource(R.string.assistant_qwen_tool_choice_desc),
                    warning = stringResource(R.string.assistant_qwen_tool_choice_warning),
                    options = QwenToolChoice.entries,
                    selected = options.toolChoice,
                    enabled = enabled,
                    label = { it.displayName() },
                    onSelected = { value -> update { it.copy(toolChoice = value) } },
                )
                HorizontalDivider()
                QwenSelectItem(
                    title = stringResource(R.string.assistant_qwen_tool_stream),
                    description = stringResource(R.string.assistant_qwen_tool_stream_desc),
                    warning = stringResource(
                        when {
                            isResponses -> R.string.assistant_qwen_chat_only
                            !supportsToolStream -> R.string.assistant_qwen_tool_stream_unsupported
                            else -> R.string.assistant_qwen_tool_stream_warning
                        }
                    ),
                    options = QwenOptionalToggle.entries,
                    selected = options.toolStream,
                    enabled = enabled && !isResponses && supportsToolStream,
                    label = { it.displayName() },
                    onSelected = { value -> update { it.copy(toolStream = value) } },
                )
            }

            Card(colors = CustomColors.cardColorsOnSurfaceContainer) {
                QwenSelectItem(
                    title = stringResource(R.string.assistant_qwen_preserve_thinking),
                    description = stringResource(R.string.assistant_qwen_preserve_thinking_desc),
                    warning = stringResource(
                        when {
                            isResponses -> R.string.assistant_qwen_chat_only
                            !supportsPreserveThinking -> R.string.assistant_qwen_preserve_thinking_unsupported
                            else -> R.string.assistant_qwen_preserve_thinking_warning
                        }
                    ),
                    options = QwenOptionalToggle.entries,
                    selected = options.preserveThinking,
                    enabled = enabled && !isResponses && supportsPreserveThinking,
                    label = { it.displayName() },
                    onSelected = { value -> update { it.copy(preserveThinking = value) } },
                )
                HorizontalDivider()
                QwenSelectItem(
                    title = stringResource(R.string.assistant_qwen_high_resolution_vision),
                    description = stringResource(R.string.assistant_qwen_high_resolution_vision_desc),
                    warning = stringResource(
                        when {
                            isResponses -> R.string.assistant_qwen_chat_only
                            !supportsHighResolutionVision -> R.string.assistant_qwen_high_resolution_vision_unsupported
                            else -> R.string.assistant_qwen_high_resolution_vision_warning
                        }
                    ),
                    options = QwenOptionalToggle.entries,
                    selected = options.highResolutionVision,
                    enabled = enabled && !isResponses && supportsHighResolutionVision,
                    label = { it.displayName() },
                    onSelected = { value -> update { it.copy(highResolutionVision = value) } },
                )
            }

            Card(colors = CustomColors.cardColorsOnSurfaceContainer) {
                QwenSelectItem(
                    title = stringResource(R.string.assistant_qwen_response_format),
                    description = stringResource(R.string.assistant_qwen_response_format_desc),
                    warning = stringResource(
                        when {
                            isResponses -> R.string.assistant_qwen_chat_only
                            options.responseFormat == QwenResponseFormat.JSON_SCHEMA && !supportsJsonSchema ->
                                R.string.assistant_qwen_json_schema_unsupported
                            options.responseFormat == QwenResponseFormat.JSON_OBJECT ->
                                R.string.assistant_qwen_json_object_warning
                            else -> R.string.assistant_qwen_response_format_warning
                        }
                    ),
                    options = QwenResponseFormat.entries,
                    selected = options.responseFormat,
                    enabled = enabled && !isResponses,
                    label = { it.displayName() },
                    onSelected = { value -> update { it.copy(responseFormat = value) } },
                )
                if (options.responseFormat == QwenResponseFormat.JSON_SCHEMA) {
                    HorizontalDivider()
                    QwenSchemaItem(
                        options = options,
                        enabled = enabled && !isResponses && supportsJsonSchema,
                        onUpdate = ::update,
                    )
                }
            }

            Card(colors = CustomColors.cardColorsOnSurfaceContainer) {
                FormItem(
                    modifier = Modifier.padding(12.dp),
                    label = { Text(stringResource(R.string.assistant_qwen_sampling_parameters)) },
                    description = {
                        Text(
                            stringResource(R.string.assistant_qwen_sampling_parameters_desc)
                        )
                        if (isResponses) {
                            ParameterWarningText(stringResource(R.string.assistant_qwen_chat_only))
                        }
                    },
                )
                HorizontalDivider()
                QwenIntegerItem(
                    title = stringResource(R.string.assistant_qwen_top_k),
                    description = stringResource(R.string.assistant_qwen_top_k_desc),
                    warning = stringResource(R.string.assistant_qwen_sampling_warning),
                    value = options.topK,
                    enabled = enabled && !isResponses,
                    valid = { it in 0..100 },
                    onValueChange = { value -> update { it.copy(topK = value) } },
                )
                HorizontalDivider()
                QwenFloatItem(
                    title = stringResource(R.string.assistant_qwen_repetition_penalty),
                    description = stringResource(R.string.assistant_qwen_repetition_penalty_desc),
                    warning = stringResource(R.string.assistant_qwen_repetition_penalty_warning),
                    value = options.repetitionPenalty,
                    enabled = enabled && !isResponses,
                    valid = { it > 0f },
                    onValueChange = { value -> update { it.copy(repetitionPenalty = value) } },
                )
                HorizontalDivider()
                QwenFloatItem(
                    title = stringResource(R.string.assistant_qwen_presence_penalty),
                    description = stringResource(R.string.assistant_qwen_presence_penalty_desc),
                    warning = stringResource(R.string.assistant_qwen_presence_penalty_warning),
                    value = options.presencePenalty,
                    enabled = enabled && !isResponses,
                    valid = { it in -2f..2f },
                    onValueChange = { value -> update { it.copy(presencePenalty = value) } },
                )
                HorizontalDivider()
                QwenLongItem(
                    title = stringResource(R.string.assistant_qwen_seed),
                    description = stringResource(R.string.assistant_qwen_seed_desc),
                    warning = stringResource(R.string.assistant_qwen_seed_warning),
                    value = options.seed,
                    enabled = enabled && !isResponses,
                    onValueChange = { value -> update { it.copy(seed = value) } },
                )
                HorizontalDivider()
                QwenStopSequencesItem(
                    values = options.stopSequences,
                    enabled = enabled && !isResponses,
                    onValueChange = { value -> update { it.copy(stopSequences = value) } },
                )
            }
        }
    }
}

@Composable
private fun <T> QwenSelectItem(
    title: String,
    description: String,
    warning: String,
    options: List<T>,
    selected: T,
    enabled: Boolean,
    label: @Composable (T) -> String,
    onSelected: (T) -> Unit,
) {
    QwenTextItem(title, description, warning) {
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
private fun QwenSchemaItem(
    options: QwenGenerationOptions,
    enabled: Boolean,
    onUpdate: ((QwenGenerationOptions) -> QwenGenerationOptions) -> Unit,
) {
    val validSchema = isValidQwenJsonSchema(options.responseJsonSchema)
    FormItem(
        modifier = Modifier.padding(12.dp),
        label = { Text(stringResource(R.string.assistant_qwen_json_schema)) },
        description = {
            Text(stringResource(R.string.assistant_qwen_json_schema_desc))
            if (!validSchema) {
                ParameterWarningText(stringResource(R.string.assistant_qwen_json_schema_invalid))
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
                label = { Text(stringResource(R.string.assistant_qwen_schema_name)) },
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = options.responseJsonSchema,
                onValueChange = { raw -> onUpdate { it.copy(responseJsonSchema = raw) } },
                enabled = enabled,
                minLines = 5,
                isError = !validSchema,
                label = { Text(stringResource(R.string.assistant_qwen_json_schema)) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun QwenStopSequencesItem(
    values: List<String>,
    enabled: Boolean,
    onValueChange: (List<String>) -> Unit,
) {
    QwenTextItem(
        title = stringResource(R.string.assistant_qwen_stop_sequences),
        description = stringResource(R.string.assistant_qwen_stop_sequences_desc),
        warning = stringResource(R.string.assistant_qwen_stop_sequences_warning),
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
private fun QwenFloatItem(
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
    val invalid = rawValue.isNotBlank() && (parsedValue == null || !valid(parsedValue))
    QwenTextItem(title, description, warning) {
        OutlinedTextField(
            value = rawValue,
            onValueChange = { raw ->
                rawValue = raw
                if (raw.isBlank()) onValueChange(null)
                else raw.toFloatOrNull()?.takeIf(valid)?.let(onValueChange)
            },
            enabled = enabled,
            singleLine = true,
            isError = invalid,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
            placeholder = { Text(stringResource(R.string.assistant_qwen_option_auto)) },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun QwenIntegerItem(
    title: String,
    description: String,
    warning: String,
    value: Int?,
    enabled: Boolean,
    valid: (Int) -> Boolean,
    onValueChange: (Int?) -> Unit,
) {
    var rawValue by remember(value) { mutableStateOf(value?.toString().orEmpty()) }
    val parsedValue = rawValue.toIntOrNull()
    val invalid = rawValue.isNotBlank() && (parsedValue == null || !valid(parsedValue))
    QwenTextItem(title, description, warning) {
        OutlinedTextField(
            value = rawValue,
            onValueChange = { raw ->
                rawValue = raw
                if (raw.isBlank()) onValueChange(null)
                else raw.toIntOrNull()?.takeIf(valid)?.let(onValueChange)
            },
            enabled = enabled,
            singleLine = true,
            isError = invalid,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            placeholder = { Text(stringResource(R.string.assistant_qwen_option_auto)) },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun QwenLongItem(
    title: String,
    description: String,
    warning: String,
    value: Long?,
    enabled: Boolean,
    onValueChange: (Long?) -> Unit,
) {
    var rawValue by remember(value) { mutableStateOf(value?.toString().orEmpty()) }
    val parsedValue = rawValue.toLongOrNull()
    val invalid = rawValue.isNotBlank() && (parsedValue == null || parsedValue !in 0..Int.MAX_VALUE.toLong())
    QwenTextItem(title, description, warning) {
        OutlinedTextField(
            value = rawValue,
            onValueChange = { raw ->
                rawValue = raw
                if (raw.isBlank()) onValueChange(null)
                else raw.toLongOrNull()
                    ?.takeIf { it in 0..Int.MAX_VALUE.toLong() }
                    ?.let(onValueChange)
            },
            enabled = enabled,
            singleLine = true,
            isError = invalid,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            placeholder = { Text(stringResource(R.string.assistant_qwen_option_auto)) },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun QwenTextItem(
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
private fun QwenOptionalToggle.displayName(): String = stringResource(
    when (this) {
        QwenOptionalToggle.DEFAULT -> R.string.assistant_qwen_option_auto
        QwenOptionalToggle.ENABLED -> R.string.assistant_qwen_option_enabled
        QwenOptionalToggle.DISABLED -> R.string.assistant_qwen_option_disabled
    }
)

@Composable
private fun QwenToolChoice.displayName(): String = stringResource(
    when (this) {
        QwenToolChoice.DEFAULT -> R.string.assistant_qwen_option_default
        QwenToolChoice.AUTO -> R.string.assistant_qwen_tool_auto
        QwenToolChoice.NONE -> R.string.assistant_qwen_tool_none
        QwenToolChoice.REQUIRED -> R.string.assistant_qwen_tool_required
    }
)

@Composable
private fun QwenResponseFormat.displayName(): String = stringResource(
    when (this) {
        QwenResponseFormat.AUTO -> R.string.assistant_qwen_option_auto
        QwenResponseFormat.TEXT -> R.string.assistant_qwen_format_text
        QwenResponseFormat.JSON_OBJECT -> R.string.assistant_qwen_format_json_object
        QwenResponseFormat.JSON_SCHEMA -> R.string.assistant_qwen_format_json_schema
    }
)
