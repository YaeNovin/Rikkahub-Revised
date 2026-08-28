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
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.ai.provider.OpenAIGenerationOptions
import me.rerere.ai.provider.OpenAIParallelToolCalls
import me.rerere.ai.provider.OpenAIReasoningContext
import me.rerere.ai.provider.OpenAIReasoningMode
import me.rerere.ai.provider.OpenAIReasoningSummary
import me.rerere.ai.provider.OpenAIServiceTier
import me.rerere.ai.provider.OpenAITextVerbosity
import me.rerere.ai.provider.OpenAIToolChoice
import me.rerere.ai.provider.ProviderRequestChannel
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.providers.openai.requestChannel
import me.rerere.ai.provider.providers.openai.resolveOpenAIModelParameterSupport
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
fun AssistantOpenAIPage(id: String) {
    val vm: AssistantDetailVM = koinViewModel(parameters = { parametersOf(id) })
    val assistant by vm.assistant.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val providers by vm.providers.collectAsStateWithLifecycle()
    val model = providers.findModelById(assistant.chatModelId ?: settings.chatModelId)
    val provider = model?.findProvider(providers) as? ProviderSetting.OpenAI
    val support = resolveOpenAIModelParameterSupport(model?.modelId.orEmpty())
    val enabled = provider != null && support.available
    val isResponses = provider?.useResponseApi == true
    val isOfficial = provider?.requestChannel() == ProviderRequestChannel.OPENAI_API
    val unavailableMessage = when {
        model == null -> stringResource(R.string.assistant_openai_unavailable_no_model)
        provider == null -> stringResource(R.string.assistant_openai_unavailable_protocol)
        support.retired -> stringResource(R.string.assistant_openai_unavailable_retired)
        !support.available -> stringResource(R.string.assistant_openai_unavailable_model)
        else -> null
    }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.assistant_page_tab_openai)) },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        AssistantOpenAIContent(
            innerPadding = innerPadding,
            assistant = assistant,
            enabled = enabled,
            isResponses = isResponses,
            isOfficial = isOfficial,
            supportsVerbosity = support.supportsVerbosity,
            supportsReasoningOptions = support.supportsReasoningOptions,
            supportsReasoningContext = support.supportsReasoningContext,
            supportsReasoningMode = support.supportsReasoningMode,
            supportsUltrafast = support.supportsUltrafast && isOfficial,
            channel = provider?.requestChannel(),
            unavailableMessage = unavailableMessage,
            onUpdate = vm::update,
        )
    }
}

@Composable
private fun AssistantOpenAIContent(
    innerPadding: PaddingValues,
    assistant: Assistant,
    enabled: Boolean,
    isResponses: Boolean,
    isOfficial: Boolean,
    supportsVerbosity: Boolean,
    supportsReasoningOptions: Boolean,
    supportsReasoningContext: Boolean,
    supportsReasoningMode: Boolean,
    supportsUltrafast: Boolean,
    channel: ProviderRequestChannel?,
    unavailableMessage: String?,
    onUpdate: (Assistant) -> Unit,
) {
    val options = assistant.openAIOptions
    val advancedEnabled = enabled && isResponses
    val reasoningEnabled = advancedEnabled && supportsReasoningOptions

    fun update(transform: (OpenAIGenerationOptions) -> OpenAIGenerationOptions) {
        onUpdate(assistant.copy(openAIOptions = transform(options)))
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
                label = { Text(stringResource(R.string.assistant_openai_experimental_title)) },
                description = {
                    Text(unavailableMessage ?: stringResource(R.string.assistant_openai_available_desc))
                    channel?.let {
                        Text(
                            stringResource(
                                R.string.assistant_openai_current_channel,
                                it.openAIDisplayName(),
                                if (isResponses) "Responses" else "Chat Completions",
                            )
                        )
                    }
                    Text(stringResource(R.string.assistant_openai_apply_and_log_desc))
                    Text(stringResource(R.string.assistant_openai_compatible_warning))
                },
            )
        }

        Column(
            modifier = Modifier.alpha(if (enabled) 1f else 0.45f),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Card(colors = CustomColors.cardColorsOnSurfaceContainer) {
                OpenAISelectItem(
                    title = stringResource(R.string.assistant_openai_verbosity),
                    description = stringResource(R.string.assistant_openai_verbosity_desc),
                    warning = stringResource(R.string.assistant_openai_verbosity_warning),
                    options = OpenAITextVerbosity.entries,
                    selected = options.verbosity,
                    enabled = enabled && supportsVerbosity,
                    label = { it.displayName() },
                    onSelected = { value -> update { it.copy(verbosity = value) } },
                )
                HorizontalDivider()
                OpenAISelectItem(
                    title = stringResource(R.string.assistant_openai_service_tier),
                    description = stringResource(R.string.assistant_openai_service_tier_desc),
                    warning = stringResource(R.string.assistant_openai_service_tier_warning),
                    options = OpenAIServiceTier.entries.filter {
                        it != OpenAIServiceTier.ULTRAFAST || supportsUltrafast
                    },
                    selected = options.serviceTier.takeIf {
                        it != OpenAIServiceTier.ULTRAFAST || supportsUltrafast
                    } ?: OpenAIServiceTier.AUTO,
                    enabled = enabled,
                    label = { it.displayName() },
                    onSelected = { value -> update { it.copy(serviceTier = value) } },
                )
            }

            Card(colors = CustomColors.cardColorsOnSurfaceContainer) {
                OpenAISelectItem(
                    title = stringResource(R.string.assistant_openai_parallel_tools),
                    description = stringResource(R.string.assistant_openai_parallel_tools_desc),
                    warning = stringResource(R.string.assistant_openai_parallel_tools_warning),
                    options = OpenAIParallelToolCalls.entries,
                    selected = options.parallelToolCalls,
                    enabled = enabled,
                    label = { it.displayName() },
                    onSelected = { value -> update { it.copy(parallelToolCalls = value) } },
                )
                HorizontalDivider()
                OpenAISelectItem(
                    title = stringResource(R.string.assistant_openai_tool_choice),
                    description = stringResource(R.string.assistant_openai_tool_choice_desc),
                    warning = stringResource(R.string.assistant_openai_tool_choice_warning),
                    options = OpenAIToolChoice.entries,
                    selected = options.toolChoice,
                    enabled = enabled,
                    label = { it.displayName() },
                    onSelected = { value -> update { it.copy(toolChoice = value) } },
                )
            }

            Card(colors = CustomColors.cardColorsOnSurfaceContainer) {
                FormItem(
                    modifier = Modifier.padding(12.dp),
                    label = { Text(stringResource(R.string.assistant_openai_responses_title)) },
                    description = {
                        Text(
                            if (isResponses) {
                                stringResource(R.string.assistant_openai_responses_desc)
                            } else {
                                stringResource(R.string.assistant_openai_responses_unavailable)
                            }
                        )
                    },
                )
                HorizontalDivider()
                OpenAISelectItem(
                    title = stringResource(R.string.assistant_openai_reasoning_summary),
                    description = stringResource(R.string.assistant_openai_reasoning_summary_desc),
                    warning = stringResource(R.string.assistant_openai_reasoning_summary_warning),
                    options = OpenAIReasoningSummary.entries,
                    selected = options.reasoningSummary,
                    enabled = reasoningEnabled,
                    label = { it.displayName() },
                    onSelected = { value -> update { it.copy(reasoningSummary = value) } },
                )
                HorizontalDivider()
                OpenAISelectItem(
                    title = stringResource(R.string.assistant_openai_reasoning_context),
                    description = stringResource(R.string.assistant_openai_reasoning_context_desc),
                    warning = stringResource(R.string.assistant_openai_reasoning_context_warning),
                    options = OpenAIReasoningContext.entries,
                    selected = options.reasoningContext,
                    enabled = reasoningEnabled && isOfficial && supportsReasoningContext,
                    label = { it.displayName() },
                    onSelected = { value -> update { it.copy(reasoningContext = value) } },
                )
                HorizontalDivider()
                OpenAISelectItem(
                    title = stringResource(R.string.assistant_openai_reasoning_mode),
                    description = stringResource(R.string.assistant_openai_reasoning_mode_desc),
                    warning = stringResource(R.string.assistant_openai_reasoning_mode_warning),
                    options = OpenAIReasoningMode.entries,
                    selected = options.reasoningMode,
                    enabled = reasoningEnabled && isOfficial && supportsReasoningMode,
                    label = { it.displayName() },
                    onSelected = { value -> update { it.copy(reasoningMode = value) } },
                )
                HorizontalDivider()
                OpenAIMaxToolCallsItem(
                    value = options.maxToolCalls,
                    enabled = advancedEnabled,
                    onValueChange = { value -> update { it.copy(maxToolCalls = value) } },
                )
            }
        }
    }
}

@Composable
private fun <T> OpenAISelectItem(
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
        description = {
            Text(description)
            Text(warning)
        },
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
private fun OpenAIMaxToolCallsItem(
    value: Int?,
    enabled: Boolean,
    onValueChange: (Int?) -> Unit,
) {
    FormItem(
        modifier = Modifier.padding(12.dp),
        label = { Text(stringResource(R.string.assistant_openai_max_tool_calls)) },
        description = {
            Text(stringResource(R.string.assistant_openai_max_tool_calls_desc))
            Text(stringResource(R.string.assistant_openai_max_tool_calls_warning))
        },
    ) {
        OutlinedTextField(
            value = value?.toString().orEmpty(),
            onValueChange = { raw ->
                when {
                    raw.isBlank() -> onValueChange(null)
                    raw.toIntOrNull()?.let { it > 0 } == true -> onValueChange(raw.toInt())
                }
            },
            enabled = enabled,
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            placeholder = { Text(stringResource(R.string.assistant_openai_option_auto)) },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun ProviderRequestChannel.openAIDisplayName(): String = stringResource(
    when (this) {
        ProviderRequestChannel.ANTHROPIC_API -> R.string.log_page_channel_anthropic_api
        ProviderRequestChannel.OPENAI_API -> R.string.log_page_channel_openai_api
        ProviderRequestChannel.XAI_API -> R.string.log_page_channel_xai_api
        ProviderRequestChannel.COMPATIBLE_ENDPOINT -> R.string.log_page_channel_compatible
        ProviderRequestChannel.GOOGLE_AI_STUDIO -> R.string.log_page_channel_google_ai_studio
        ProviderRequestChannel.VERTEX_AI -> R.string.log_page_channel_vertex_ai
    }
)

@Composable
private fun OpenAITextVerbosity.displayName(): String = stringResource(
    when (this) {
        OpenAITextVerbosity.AUTO -> R.string.assistant_openai_option_auto
        OpenAITextVerbosity.LOW -> R.string.assistant_openai_option_low
        OpenAITextVerbosity.MEDIUM -> R.string.assistant_openai_option_medium
        OpenAITextVerbosity.HIGH -> R.string.assistant_openai_option_high
    }
)

@Composable
private fun OpenAIServiceTier.displayName(): String = stringResource(
    when (this) {
        OpenAIServiceTier.AUTO -> R.string.assistant_openai_option_auto
        OpenAIServiceTier.DEFAULT -> R.string.assistant_openai_tier_default
        OpenAIServiceTier.FLEX -> R.string.assistant_openai_tier_flex
        OpenAIServiceTier.FAST -> R.string.assistant_openai_tier_fast
        OpenAIServiceTier.ULTRAFAST -> R.string.assistant_openai_tier_ultrafast
    }
)

@Composable
private fun OpenAIParallelToolCalls.displayName(): String = stringResource(
    when (this) {
        OpenAIParallelToolCalls.AUTO -> R.string.assistant_openai_option_auto
        OpenAIParallelToolCalls.ENABLED -> R.string.assistant_openai_option_enabled
        OpenAIParallelToolCalls.DISABLED -> R.string.assistant_openai_option_disabled
    }
)

@Composable
private fun OpenAIToolChoice.displayName(): String = stringResource(
    when (this) {
        OpenAIToolChoice.DEFAULT -> R.string.assistant_openai_option_default
        OpenAIToolChoice.AUTO -> R.string.assistant_openai_tool_auto
        OpenAIToolChoice.NONE -> R.string.assistant_openai_tool_none
        OpenAIToolChoice.REQUIRED -> R.string.assistant_openai_tool_required
    }
)

@Composable
private fun OpenAIReasoningSummary.displayName(): String = stringResource(
    when (this) {
        OpenAIReasoningSummary.DISABLED -> R.string.assistant_openai_option_disabled
        OpenAIReasoningSummary.AUTO -> R.string.assistant_openai_option_auto
        OpenAIReasoningSummary.CONCISE -> R.string.assistant_openai_summary_concise
        OpenAIReasoningSummary.DETAILED -> R.string.assistant_openai_summary_detailed
    }
)

@Composable
private fun OpenAIReasoningContext.displayName(): String = stringResource(
    when (this) {
        OpenAIReasoningContext.AUTO -> R.string.assistant_openai_option_auto
        OpenAIReasoningContext.CURRENT_TURN -> R.string.assistant_openai_context_current
        OpenAIReasoningContext.ALL_TURNS -> R.string.assistant_openai_context_all
    }
)

@Composable
private fun OpenAIReasoningMode.displayName(): String = stringResource(
    when (this) {
        OpenAIReasoningMode.STANDARD -> R.string.assistant_openai_mode_standard
        OpenAIReasoningMode.PRO -> R.string.assistant_openai_mode_pro
    }
)
