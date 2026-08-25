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
import me.rerere.ai.provider.DeepSeekGenerationOptions
import me.rerere.ai.provider.DeepSeekImageDetail
import me.rerere.ai.provider.DeepSeekOptionalToggle
import me.rerere.ai.provider.DeepSeekResponseFormat
import me.rerere.ai.provider.DeepSeekToolChoice
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.providers.openai.isOfficialDeepSeekHost
import me.rerere.ai.provider.providers.openai.resolveDeepSeekModelParameterSupport
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
fun AssistantDeepSeekPage(id: String) {
    val vm: AssistantDetailVM = koinViewModel(parameters = { parametersOf(id) })
    val assistant by vm.assistant.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val providers by vm.providers.collectAsStateWithLifecycle()
    val model = providers.findModelById(assistant.chatModelId ?: settings.chatModelId)
    val provider = model?.findProvider(providers)
    val openAIProvider = provider as? ProviderSetting.OpenAI
    val claudeProvider = provider as? ProviderSetting.Claude
    val support = resolveDeepSeekModelParameterSupport(model?.modelId.orEmpty())
    val supportedProtocol = openAIProvider != null || claudeProvider != null
    val enabled = supportedProtocol && support.available
    val isAnthropic = claudeProvider != null
    val isResponses = openAIProvider?.useResponseApi == true
    val officialEndpoint = (openAIProvider?.baseUrl ?: claudeProvider?.baseUrl)
        ?.toHttpUrlOrNull()
        ?.host
        ?.let(::isOfficialDeepSeekHost) == true
    val unavailableMessage = when {
        model == null -> stringResource(R.string.assistant_deepseek_unavailable_no_model)
        !supportedProtocol -> stringResource(R.string.assistant_deepseek_unavailable_protocol)
        !support.available -> stringResource(R.string.assistant_deepseek_unavailable_model)
        else -> null
    }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.assistant_page_tab_deepseek)) },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        AssistantDeepSeekContent(
            innerPadding = innerPadding,
            assistant = assistant,
            enabled = enabled,
            isResponses = isResponses,
            isAnthropic = isAnthropic,
            officialEndpoint = officialEndpoint,
            supportsVision = support.supportsVision,
            unavailableMessage = unavailableMessage,
            onUpdate = vm::update,
        )
    }
}

@Composable
private fun AssistantDeepSeekContent(
    innerPadding: PaddingValues,
    assistant: Assistant,
    enabled: Boolean,
    isResponses: Boolean,
    isAnthropic: Boolean,
    officialEndpoint: Boolean,
    supportsVision: Boolean,
    unavailableMessage: String?,
    onUpdate: (Assistant) -> Unit,
) {
    val options = assistant.deepSeekOptions

    fun update(transform: (DeepSeekGenerationOptions) -> DeepSeekGenerationOptions) {
        onUpdate(assistant.copy(deepSeekOptions = transform(options)))
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
                label = { Text(stringResource(R.string.assistant_deepseek_experimental_title)) },
                description = {
                    Text(unavailableMessage ?: stringResource(R.string.assistant_deepseek_available_desc))
                    if (enabled) {
                        Text(
                            stringResource(
                                R.string.assistant_deepseek_current_channel,
                                stringResource(
                                    if (officialEndpoint) R.string.assistant_deepseek_channel_official
                                    else R.string.assistant_deepseek_channel_compatible
                                ),
                                when {
                                    isAnthropic -> "Anthropic Messages"
                                    isResponses -> "Responses"
                                    else -> "Chat Completions"
                                },
                            )
                        )
                    }
                    Text(stringResource(R.string.assistant_deepseek_common_parameters_desc))
                    Text(stringResource(R.string.assistant_deepseek_logging_desc))
                    if (enabled && !officialEndpoint) {
                        Text(stringResource(R.string.assistant_deepseek_compatible_warning))
                    }
                },
            )
        }

        Column(
            modifier = Modifier.alpha(if (enabled) 1f else 0.45f),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Card(colors = CustomColors.cardColorsOnSurfaceContainer) {
                DeepSeekSelectItem(
                    title = stringResource(R.string.assistant_deepseek_tool_choice),
                    description = stringResource(R.string.assistant_deepseek_tool_choice_desc),
                    warning = stringResource(R.string.assistant_deepseek_tool_choice_warning),
                    options = DeepSeekToolChoice.entries,
                    selected = options.toolChoice,
                    enabled = enabled,
                    label = { it.displayName() },
                    onSelected = { value -> update { it.copy(toolChoice = value) } },
                )
                HorizontalDivider()
                DeepSeekSelectItem(
                    title = stringResource(R.string.assistant_deepseek_response_format),
                    description = stringResource(R.string.assistant_deepseek_response_format_desc),
                    warning = stringResource(
                        if (isAnthropic) {
                            R.string.assistant_deepseek_response_format_anthropic
                        } else if (options.responseFormat == DeepSeekResponseFormat.JSON_OBJECT) {
                            R.string.assistant_deepseek_json_warning
                        } else {
                            R.string.assistant_deepseek_response_format_warning
                        }
                    ),
                    options = DeepSeekResponseFormat.entries,
                    selected = options.responseFormat,
                    enabled = enabled && !isAnthropic,
                    label = { it.displayName() },
                    onSelected = { value -> update { it.copy(responseFormat = value) } },
                )
            }

            Card(colors = CustomColors.cardColorsOnSurfaceContainer) {
                FormItem(
                    modifier = Modifier.padding(12.dp),
                    label = { Text(stringResource(R.string.assistant_deepseek_chat_parameters)) },
                    description = {
                        Text(
                            stringResource(
                                when {
                                    isAnthropic -> R.string.assistant_deepseek_anthropic_parameters_desc
                                    isResponses -> R.string.assistant_deepseek_chat_only
                                    else -> R.string.assistant_deepseek_chat_parameters_desc
                                }
                            )
                        )
                    },
                )
                HorizontalDivider()
                DeepSeekStopSequencesItem(
                    values = options.stopSequences,
                    enabled = enabled && !isResponses,
                    onValueChange = { value -> update { it.copy(stopSequences = value) } },
                )
                HorizontalDivider()
                DeepSeekSelectItem(
                    title = stringResource(R.string.assistant_deepseek_logprobs),
                    description = stringResource(R.string.assistant_deepseek_logprobs_desc),
                    warning = stringResource(
                        if (isAnthropic) R.string.assistant_deepseek_logprobs_anthropic
                        else if (isResponses) R.string.assistant_deepseek_logprobs_responses
                        else R.string.assistant_deepseek_logprobs_warning
                    ),
                    options = DeepSeekOptionalToggle.entries,
                    selected = options.logProbabilities,
                    enabled = enabled && !isResponses && !isAnthropic,
                    label = { it.displayName() },
                    onSelected = { value -> update { it.copy(logProbabilities = value) } },
                )
                HorizontalDivider()
                DeepSeekIntegerItem(
                    title = stringResource(R.string.assistant_deepseek_top_logprobs),
                    description = stringResource(R.string.assistant_deepseek_top_logprobs_desc),
                    warning = stringResource(
                        if (isAnthropic) {
                            R.string.assistant_deepseek_top_logprobs_anthropic
                        } else if (!isResponses && options.logProbabilities != DeepSeekOptionalToggle.ENABLED) {
                            R.string.assistant_deepseek_top_logprobs_requires_logprobs
                        } else {
                            R.string.assistant_deepseek_top_logprobs_warning
                        }
                    ),
                    value = options.topLogProbs,
                    enabled = enabled && !isAnthropic &&
                        (isResponses || options.logProbabilities == DeepSeekOptionalToggle.ENABLED),
                    onValueChange = { value -> update { it.copy(topLogProbs = value) } },
                )
            }

            Card(colors = CustomColors.cardColorsOnSurfaceContainer) {
                DeepSeekUserIdItem(
                    value = options.userId,
                    enabled = enabled,
                    onValueChange = { value -> update { it.copy(userId = value) } },
                )
                HorizontalDivider()
                DeepSeekSelectItem(
                    title = stringResource(R.string.assistant_deepseek_image_detail),
                    description = stringResource(R.string.assistant_deepseek_image_detail_desc),
                    warning = stringResource(
                        if (isAnthropic) R.string.assistant_deepseek_image_detail_anthropic
                        else if (supportsVision) R.string.assistant_deepseek_image_detail_warning
                        else R.string.assistant_deepseek_image_detail_unsupported
                    ),
                    options = DeepSeekImageDetail.entries,
                    selected = options.imageDetail,
                    enabled = enabled && supportsVision && !isAnthropic,
                    label = { it.displayName() },
                    onSelected = { value -> update { it.copy(imageDetail = value) } },
                )
            }
        }
    }
}

@Composable
private fun <T> DeepSeekSelectItem(
    title: String,
    description: String,
    warning: String,
    options: List<T>,
    selected: T,
    enabled: Boolean,
    label: @Composable (T) -> String,
    onSelected: (T) -> Unit,
) {
    DeepSeekTextItem(title, description, warning) {
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
private fun DeepSeekStopSequencesItem(
    values: List<String>,
    enabled: Boolean,
    onValueChange: (List<String>) -> Unit,
) {
    DeepSeekTextItem(
        title = stringResource(R.string.assistant_deepseek_stop_sequences),
        description = stringResource(R.string.assistant_deepseek_stop_sequences_desc),
        warning = stringResource(R.string.assistant_deepseek_stop_sequences_warning),
    ) {
        OutlinedTextField(
            value = values.joinToString("\n"),
            onValueChange = { raw -> onValueChange(raw.lines().take(16)) },
            enabled = enabled,
            minLines = 3,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun DeepSeekIntegerItem(
    title: String,
    description: String,
    warning: String,
    value: Int?,
    enabled: Boolean,
    onValueChange: (Int?) -> Unit,
) {
    var rawValue by remember(value) { mutableStateOf(value?.toString().orEmpty()) }
    val parsedValue = rawValue.toIntOrNull()
    val invalid = rawValue.isNotBlank() && (parsedValue == null || parsedValue !in 0..20)
    DeepSeekTextItem(title, description, warning) {
        OutlinedTextField(
            value = rawValue,
            onValueChange = { raw ->
                rawValue = raw
                if (raw.isBlank()) onValueChange(null)
                else raw.toIntOrNull()?.takeIf { it in 0..20 }?.let(onValueChange)
            },
            enabled = enabled,
            singleLine = true,
            isError = invalid,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            placeholder = { Text(stringResource(R.string.assistant_deepseek_option_auto)) },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun DeepSeekUserIdItem(
    value: String,
    enabled: Boolean,
    onValueChange: (String) -> Unit,
) {
    DeepSeekTextItem(
        title = stringResource(R.string.assistant_deepseek_user_id),
        description = stringResource(R.string.assistant_deepseek_user_id_desc),
        warning = stringResource(R.string.assistant_deepseek_user_id_warning),
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = { raw ->
                if (raw.length <= 512 && raw.all { it.isLetterOrDigit() || it == '-' || it == '_' }) {
                    onValueChange(raw)
                }
            },
            enabled = enabled,
            singleLine = true,
            placeholder = { Text(stringResource(R.string.assistant_deepseek_option_auto)) },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun DeepSeekTextItem(
    title: String,
    description: String,
    warning: String,
    content: @Composable () -> Unit,
) {
    FormItem(
        modifier = Modifier.padding(12.dp),
        label = { Text(title) },
        description = { Text(description); Text(warning) },
    ) {
        content()
    }
}

@Composable
private fun DeepSeekOptionalToggle.displayName(): String = stringResource(
    when (this) {
        DeepSeekOptionalToggle.DEFAULT -> R.string.assistant_deepseek_option_auto
        DeepSeekOptionalToggle.ENABLED -> R.string.assistant_deepseek_option_enabled
        DeepSeekOptionalToggle.DISABLED -> R.string.assistant_deepseek_option_disabled
    }
)

@Composable
private fun DeepSeekToolChoice.displayName(): String = stringResource(
    when (this) {
        DeepSeekToolChoice.DEFAULT -> R.string.assistant_deepseek_option_default
        DeepSeekToolChoice.AUTO -> R.string.assistant_deepseek_tool_auto
        DeepSeekToolChoice.NONE -> R.string.assistant_deepseek_tool_none
        DeepSeekToolChoice.REQUIRED -> R.string.assistant_deepseek_tool_required
    }
)

@Composable
private fun DeepSeekResponseFormat.displayName(): String = stringResource(
    when (this) {
        DeepSeekResponseFormat.AUTO -> R.string.assistant_deepseek_option_auto
        DeepSeekResponseFormat.TEXT -> R.string.assistant_deepseek_format_text
        DeepSeekResponseFormat.JSON_OBJECT -> R.string.assistant_deepseek_format_json_object
    }
)

@Composable
private fun DeepSeekImageDetail.displayName(): String = stringResource(
    when (this) {
        DeepSeekImageDetail.AUTO -> R.string.assistant_deepseek_option_auto
        DeepSeekImageDetail.LOW -> R.string.assistant_deepseek_image_low
        DeepSeekImageDetail.HIGH -> R.string.assistant_deepseek_image_high
        DeepSeekImageDetail.ORIGINAL -> R.string.assistant_deepseek_image_original
    }
)
