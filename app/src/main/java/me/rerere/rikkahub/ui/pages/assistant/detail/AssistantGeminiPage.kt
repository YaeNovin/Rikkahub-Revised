package me.rerere.rikkahub.ui.pages.assistant.detail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import me.rerere.rikkahub.ui.components.ui.AppearanceAlertDialog as AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.Lucide
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import me.rerere.ai.provider.GeminiGenerationOptions
import me.rerere.ai.provider.GeminiMediaResolution
import me.rerere.ai.provider.GeminiResponseMimeType
import me.rerere.ai.provider.GeminiSafetySettings
import me.rerere.ai.provider.GeminiSafetyThreshold
import me.rerere.ai.provider.ProviderRequestChannel
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.providers.google.requestChannel
import me.rerere.ai.registry.ModelRegistry
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

private enum class GeminiDialogType {
    MEDIA_RESOLUTION,
    STOP_SEQUENCES,
    RESPONSE_FORMAT,
    REPETITION_PENALTIES,
    SAFETY,
}

@Composable
fun AssistantGeminiPage(id: String) {
    val vm: AssistantDetailVM = koinViewModel(parameters = { parametersOf(id) })
    val assistant by vm.assistant.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val providers by vm.providers.collectAsStateWithLifecycle()
    val model = providers.findModelById(assistant.chatModelId ?: settings.chatModelId)
    val provider = model?.findProvider(providers)
    val isGemini3 = model != null && ModelRegistry.GEMINI_3_SERIES.match(model.modelId)
    val isGemini37Flash = model != null && ModelRegistry.GEMINI_3_7_FLASH.match(model.modelId)
    val enabled = isGemini3 && provider is ProviderSetting.Google
    val requestChannel = (provider as? ProviderSetting.Google)?.requestChannel()
    val unavailableMessage = when {
        model == null -> stringResource(R.string.assistant_gemini_unavailable_no_model)
        !isGemini3 -> stringResource(R.string.assistant_gemini_unavailable_model)
        provider !is ProviderSetting.Google -> stringResource(R.string.assistant_gemini_unavailable_protocol)
        else -> null
    }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.assistant_page_tab_gemini)) },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        AssistantGeminiContent(
            innerPadding = innerPadding,
            assistant = assistant,
            enabled = enabled,
            isGemini37Flash = isGemini37Flash,
            requestChannel = requestChannel,
            unavailableMessage = unavailableMessage,
            onUpdate = vm::update,
        )
    }
}

@Composable
internal fun AssistantGeminiContent(
    innerPadding: PaddingValues,
    assistant: Assistant,
    enabled: Boolean,
    isGemini37Flash: Boolean,
    requestChannel: ProviderRequestChannel?,
    unavailableMessage: String?,
    onUpdate: (Assistant) -> Unit,
) {
    val options = assistant.geminiOptions
    var activeDialog by remember { mutableStateOf<GeminiDialogType?>(null) }

    fun update(transform: (GeminiGenerationOptions) -> GeminiGenerationOptions) {
        onUpdate(assistant.copy(geminiOptions = transform(options)))
    }

    activeDialog?.let { type ->
        GeminiOptionsDialog(
            type = type,
            initial = options,
            onDismiss = { activeDialog = null },
            onConfirm = { value ->
                onUpdate(assistant.copy(geminiOptions = value))
                activeDialog = null
            },
        )
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
                label = { Text(stringResource(R.string.assistant_gemini_experimental_title)) },
                description = {
                    Text(unavailableMessage ?: stringResource(R.string.assistant_gemini_available_desc))
                    requestChannel?.let { channel ->
                        Text(
                            stringResource(
                                R.string.assistant_gemini_current_channel,
                                channel.displayName(),
                            )
                        )
                    }
                    Text(stringResource(R.string.assistant_gemini_apply_and_log_desc))
                    Text(stringResource(R.string.assistant_gemini_experimental_desc))
                    if (isGemini37Flash) {
                        Text(stringResource(R.string.assistant_gemini_37_thinking_desc))
                    }
                },
            )
        }

        Column(
            modifier = Modifier.alpha(if (enabled) 1f else 0.45f),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Card(colors = CustomColors.cardColorsOnSurfaceContainer) {
                GeminiSwitchItem(
                    title = stringResource(R.string.assistant_gemini_include_thoughts),
                    description = stringResource(R.string.assistant_gemini_include_thoughts_desc),
                    warning = stringResource(R.string.assistant_gemini_include_thoughts_warning),
                    checked = options.includeThoughts,
                    enabled = enabled,
                    onCheckedChange = { value -> update { it.copy(includeThoughts = value) } },
                )
                HorizontalDivider()
                OptionalIntItem(
                    title = stringResource(R.string.assistant_gemini_seed),
                    description = stringResource(R.string.assistant_gemini_seed_desc),
                    warning = stringResource(R.string.assistant_gemini_seed_warning),
                    value = options.seed,
                    enabled = enabled,
                    onValueChange = { value -> update { it.copy(seed = value) } },
                )
            }

            Card(colors = CustomColors.cardColorsOnSurfaceContainer) {
                GeminiDialogItem(
                    title = stringResource(R.string.assistant_gemini_media_resolution),
                    description = stringResource(R.string.assistant_gemini_media_resolution_desc),
                    value = options.mediaResolution.displayName(),
                    enabled = enabled,
                    onClick = { activeDialog = GeminiDialogType.MEDIA_RESOLUTION },
                )
                HorizontalDivider()
                GeminiDialogItem(
                    title = stringResource(R.string.assistant_gemini_stop_sequences),
                    description = stringResource(R.string.assistant_gemini_stop_sequences_desc),
                    value = if (options.stopSequences.isEmpty()) {
                        stringResource(R.string.assistant_gemini_option_auto)
                    } else {
                        stringResource(R.string.assistant_gemini_stop_sequences_summary, options.stopSequences.size)
                    },
                    enabled = enabled,
                    onClick = { activeDialog = GeminiDialogType.STOP_SEQUENCES },
                )
            }

            Card(colors = CustomColors.cardColorsOnSurfaceContainer) {
                GeminiDialogItem(
                    title = stringResource(R.string.assistant_gemini_response_format),
                    description = stringResource(R.string.assistant_gemini_response_format_desc),
                    value = options.responseMimeType.displayName(),
                    enabled = enabled,
                    onClick = { activeDialog = GeminiDialogType.RESPONSE_FORMAT },
                )
            }

            Card(colors = CustomColors.cardColorsOnSurfaceContainer) {
                val automatic = stringResource(R.string.assistant_gemini_option_auto)
                GeminiDialogItem(
                    title = stringResource(R.string.assistant_gemini_penalties_title),
                    description = stringResource(R.string.assistant_gemini_penalties_desc),
                    value = stringResource(
                        R.string.assistant_gemini_penalties_summary,
                        options.presencePenalty?.toString() ?: automatic,
                        options.frequencyPenalty?.toString() ?: automatic,
                    ),
                    enabled = enabled,
                    onClick = { activeDialog = GeminiDialogType.REPETITION_PENALTIES },
                )
            }

            Card(colors = CustomColors.cardColorsOnSurfaceContainer) {
                val configuredSafety = listOf(
                    options.safetySettings.harassment,
                    options.safetySettings.hateSpeech,
                    options.safetySettings.sexuallyExplicit,
                    options.safetySettings.dangerousContent,
                ).count { it != GeminiSafetyThreshold.DEFAULT }
                GeminiDialogItem(
                    title = stringResource(R.string.assistant_gemini_safety_title),
                    description = stringResource(R.string.assistant_gemini_safety_desc),
                    value = if (configuredSafety == 0) {
                        stringResource(R.string.assistant_gemini_safety_default)
                    } else {
                        stringResource(R.string.assistant_gemini_safety_summary, configuredSafety)
                    },
                    enabled = enabled,
                    onClick = { activeDialog = GeminiDialogType.SAFETY },
                )
            }
        }
    }
}

@Composable
private fun GeminiSwitchItem(
    title: String,
    description: String,
    warning: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    FormItem(
        modifier = Modifier.padding(12.dp),
        label = { Text(title) },
        description = {
            Text(description)
            WarningText(warning)
        },
        tail = {
            Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
        },
    )
}

@Composable
private fun GeminiDialogItem(
    title: String,
    description: String,
    value: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick),
    ) {
        FormItem(
            modifier = Modifier.padding(12.dp),
            label = { Text(title) },
            description = { Text(description) },
            tail = {
                Icon(imageVector = Lucide.ChevronRight, contentDescription = null)
            },
        ) {
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun GeminiOptionsDialog(
    type: GeminiDialogType,
    initial: GeminiGenerationOptions,
    onDismiss: () -> Unit,
    onConfirm: (GeminiGenerationOptions) -> Unit,
) {
    var draft by remember(type, initial) { mutableStateOf(initial) }
    var presencePenaltyValid by remember(type, initial) {
        mutableStateOf(initial.presencePenalty.isValidPenalty())
    }
    var frequencyPenaltyValid by remember(type, initial) {
        mutableStateOf(initial.frequencyPenalty.isValidPenalty())
    }
    val schemaRequired = draft.responseMimeType == GeminiResponseMimeType.ENUM
    val schemaValid = draft.responseJsonSchema.isBlank() || parseJsonObject(draft.responseJsonSchema) != null
    val canConfirm = draft.stopSequences.size <= 5 &&
        (!schemaRequired || draft.responseJsonSchema.isNotBlank()) &&
        schemaValid &&
        presencePenaltyValid && frequencyPenaltyValid
    val title = when (type) {
        GeminiDialogType.MEDIA_RESOLUTION -> R.string.assistant_gemini_media_resolution
        GeminiDialogType.STOP_SEQUENCES -> R.string.assistant_gemini_stop_sequences
        GeminiDialogType.RESPONSE_FORMAT -> R.string.assistant_gemini_response_format
        GeminiDialogType.REPETITION_PENALTIES -> R.string.assistant_gemini_penalties_title
        GeminiDialogType.SAFETY -> R.string.assistant_gemini_safety_title
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(title)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 560.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                when (type) {
                    GeminiDialogType.MEDIA_RESOLUTION -> {
                        Text(stringResource(R.string.assistant_gemini_media_resolution_detail))
                        WarningText(stringResource(R.string.assistant_gemini_media_resolution_warning))
                        GeminiSelectItem(
                            title = stringResource(R.string.assistant_gemini_media_resolution),
                            description = stringResource(R.string.assistant_gemini_media_resolution_choices),
                            options = GeminiMediaResolution.entries,
                            selected = draft.mediaResolution,
                            enabled = true,
                            label = { it.displayName() },
                            onSelected = { draft = draft.copy(mediaResolution = it) },
                        )
                    }

                    GeminiDialogType.STOP_SEQUENCES -> {
                        Text(stringResource(R.string.assistant_gemini_stop_sequences_detail))
                        WarningText(stringResource(R.string.assistant_gemini_stop_sequences_warning))
                        StopSequencesItem(
                            value = draft.stopSequences,
                            enabled = true,
                            onValueChange = { draft = draft.copy(stopSequences = it) },
                        )
                    }

                    GeminiDialogType.RESPONSE_FORMAT -> {
                        Text(stringResource(R.string.assistant_gemini_response_format_detail))
                        WarningText(stringResource(R.string.assistant_gemini_response_format_warning))
                        GeminiSelectItem(
                            title = stringResource(R.string.assistant_gemini_response_format),
                            description = stringResource(R.string.assistant_gemini_response_format_choices),
                            options = GeminiResponseMimeType.entries,
                            selected = draft.responseMimeType,
                            enabled = true,
                            label = { it.displayName() },
                            onSelected = { value ->
                                draft = draft.copy(
                                    responseMimeType = value,
                                    responseJsonSchema = if (
                                        value == GeminiResponseMimeType.JSON ||
                                        value == GeminiResponseMimeType.ENUM
                                    ) draft.responseJsonSchema else "",
                                )
                            },
                        )
                        if (
                            draft.responseMimeType == GeminiResponseMimeType.JSON ||
                            draft.responseMimeType == GeminiResponseMimeType.ENUM
                        ) {
                            HorizontalDivider()
                            JsonSchemaItem(
                                value = draft.responseJsonSchema,
                                enabled = true,
                                required = schemaRequired,
                                onValueChange = { draft = draft.copy(responseJsonSchema = it) },
                            )
                        }
                    }

                    GeminiDialogType.REPETITION_PENALTIES -> {
                        Text(stringResource(R.string.assistant_gemini_penalties_detail))
                        WarningText(stringResource(R.string.assistant_gemini_penalties_warning))
                        OptionalFloatItem(
                            title = stringResource(R.string.assistant_gemini_presence_penalty),
                            description = stringResource(R.string.assistant_gemini_presence_penalty_desc),
                            value = draft.presencePenalty,
                            enabled = true,
                            onValueChange = { draft = draft.copy(presencePenalty = it) },
                            onValidityChange = { presencePenaltyValid = it },
                        )
                        HorizontalDivider()
                        OptionalFloatItem(
                            title = stringResource(R.string.assistant_gemini_frequency_penalty),
                            description = stringResource(R.string.assistant_gemini_frequency_penalty_desc),
                            value = draft.frequencyPenalty,
                            enabled = true,
                            onValueChange = { draft = draft.copy(frequencyPenalty = it) },
                            onValidityChange = { frequencyPenaltyValid = it },
                        )
                    }

                    GeminiDialogType.SAFETY -> {
                        Text(stringResource(R.string.assistant_gemini_safety_detail))
                        WarningText(stringResource(R.string.assistant_gemini_safety_warning))
                        SafetySettingItems(
                            settings = draft.safetySettings,
                            enabled = true,
                            onUpdate = { draft = draft.copy(safetySettings = it) },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(draft) }, enabled = canConfirm) {
                Text(stringResource(R.string.confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

@Composable
private fun <T> GeminiSelectItem(
    title: String,
    description: String,
    options: List<T>,
    selected: T,
    enabled: Boolean,
    label: @Composable (T) -> String,
    onSelected: (T) -> Unit,
) {
    FormItem(
        modifier = Modifier.padding(vertical = 4.dp),
        label = { Text(title) },
        description = { Text(description) },
    ) {
        Select(
            options = options,
            selectedOption = selected,
            onOptionSelected = onSelected,
            modifier = Modifier.fillMaxWidth(),
            enabled = enabled,
            optionToString = label,
        )
    }
}

@Composable
private fun OptionalIntItem(
    title: String,
    description: String,
    warning: String,
    value: Int?,
    enabled: Boolean,
    onValueChange: (Int?) -> Unit,
) {
    var input by remember(value) { mutableStateOf(value?.toString().orEmpty()) }
    FormItem(
        modifier = Modifier.padding(12.dp),
        label = { Text(title) },
        description = {
            Text(description)
            WarningText(warning)
        },
        tail = {
            Switch(
                checked = value != null,
                enabled = enabled,
                onCheckedChange = { checked -> onValueChange(if (checked) 0 else null) },
            )
        },
    ) {
        if (value != null) {
            OutlinedTextField(
                value = input,
                onValueChange = { text ->
                    input = text
                    text.toIntOrNull()?.let(onValueChange)
                },
                enabled = enabled,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                isError = input.toIntOrNull() == null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
        }
    }
}

@Composable
private fun OptionalFloatItem(
    title: String,
    description: String,
    value: Float?,
    enabled: Boolean,
    onValueChange: (Float?) -> Unit,
    onValidityChange: (Boolean) -> Unit,
) {
    var input by remember(value) { mutableStateOf(value?.toString().orEmpty()) }
    val parsed = input.toFloatOrNull()
    FormItem(
        modifier = Modifier.padding(vertical = 4.dp),
        label = { Text(title) },
        description = { Text(description) },
        tail = {
            Switch(
                checked = value != null,
                enabled = enabled,
                onCheckedChange = { checked ->
                    onValidityChange(true)
                    onValueChange(if (checked) 0f else null)
                },
            )
        },
    ) {
        if (value != null) {
            OutlinedTextField(
                value = input,
                onValueChange = { text ->
                    input = text
                    val next = text.toFloatOrNull()
                    onValidityChange(next.isValidPenalty() && next != null)
                    next?.takeIf { it.isValidPenalty() }?.let(onValueChange)
                },
                enabled = enabled,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                isError = !parsed.isValidPenalty(),
                supportingText = { Text("-2.0 - <2.0") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            )
        }
    }
}

@Composable
private fun StopSequencesItem(
    value: List<String>,
    enabled: Boolean,
    onValueChange: (List<String>) -> Unit,
) {
    var input by remember(value) { mutableStateOf(value.joinToString("\n")) }
    val sequences = input.lines().map(String::trim).filter(String::isNotEmpty)
    OutlinedTextField(
        value = input,
        onValueChange = { text ->
            input = text
            onValueChange(text.lines().map(String::trim).filter(String::isNotEmpty))
        },
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
        minLines = 3,
        maxLines = 7,
        isError = sequences.size > 5,
        supportingText = {
            Text(stringResource(R.string.assistant_gemini_stop_sequences_count, sequences.size))
        },
    )
}

@Composable
private fun JsonSchemaItem(
    value: String,
    enabled: Boolean,
    required: Boolean,
    onValueChange: (String) -> Unit,
) {
    var input by remember(value) { mutableStateOf(value) }
    val parsed = input.takeIf(String::isNotBlank)?.let(::parseJsonObject)
    val isError = (required && input.isBlank()) || (input.isNotBlank() && parsed == null)
    FormItem(
        modifier = Modifier.padding(vertical = 4.dp),
        label = { Text(stringResource(R.string.assistant_gemini_json_schema)) },
        description = { Text(stringResource(R.string.assistant_gemini_json_schema_desc)) },
    ) {
        OutlinedTextField(
            value = input,
            onValueChange = { text ->
                input = text
                onValueChange(text)
            },
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
            minLines = 5,
            isError = isError,
            supportingText = {
                if (isError) {
                    Text(
                        stringResource(
                            if (required && input.isBlank()) {
                                R.string.assistant_gemini_json_schema_required
                            } else {
                                R.string.assistant_gemini_json_schema_invalid
                            }
                        )
                    )
                }
            },
        )
    }
}

@Composable
private fun SafetySettingItems(
    settings: GeminiSafetySettings,
    enabled: Boolean,
    onUpdate: (GeminiSafetySettings) -> Unit,
) {
    val items = listOf(
        SafetyItem(
            title = R.string.assistant_gemini_safety_harassment,
            description = R.string.assistant_gemini_safety_harassment_desc,
            selected = settings.harassment,
            update = { onUpdate(settings.copy(harassment = it)) },
        ),
        SafetyItem(
            title = R.string.assistant_gemini_safety_hate,
            description = R.string.assistant_gemini_safety_hate_desc,
            selected = settings.hateSpeech,
            update = { onUpdate(settings.copy(hateSpeech = it)) },
        ),
        SafetyItem(
            title = R.string.assistant_gemini_safety_sexual,
            description = R.string.assistant_gemini_safety_sexual_desc,
            selected = settings.sexuallyExplicit,
            update = { onUpdate(settings.copy(sexuallyExplicit = it)) },
        ),
        SafetyItem(
            title = R.string.assistant_gemini_safety_dangerous,
            description = R.string.assistant_gemini_safety_dangerous_desc,
            selected = settings.dangerousContent,
            update = { onUpdate(settings.copy(dangerousContent = it)) },
        ),
    )
    items.forEachIndexed { index, item ->
        if (index > 0) HorizontalDivider()
        GeminiSelectItem(
            title = stringResource(item.title),
            description = stringResource(item.description),
            options = GeminiSafetyThreshold.entries,
            selected = item.selected,
            enabled = enabled,
            label = { it.displayName() },
            onSelected = item.update,
        )
    }
}

private data class SafetyItem(
    val title: Int,
    val description: Int,
    val selected: GeminiSafetyThreshold,
    val update: (GeminiSafetyThreshold) -> Unit,
)

@Composable
private fun WarningText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
    )
}

@Composable
private fun GeminiMediaResolution.displayName(): String = stringResource(
    when (this) {
        GeminiMediaResolution.AUTO -> R.string.assistant_gemini_option_auto
        GeminiMediaResolution.LOW -> R.string.assistant_gemini_option_low
        GeminiMediaResolution.MEDIUM -> R.string.assistant_gemini_option_medium
        GeminiMediaResolution.HIGH -> R.string.assistant_gemini_option_high
        GeminiMediaResolution.ULTRA_HIGH -> R.string.assistant_gemini_option_ultra_high
    }
)

@Composable
private fun GeminiResponseMimeType.displayName(): String = stringResource(
    when (this) {
        GeminiResponseMimeType.AUTO -> R.string.assistant_gemini_format_auto
        GeminiResponseMimeType.TEXT -> R.string.assistant_gemini_format_text
        GeminiResponseMimeType.JSON -> R.string.assistant_gemini_format_json
        GeminiResponseMimeType.ENUM -> R.string.assistant_gemini_format_enum
    }
)

@Composable
private fun GeminiSafetyThreshold.displayName(): String = stringResource(
    when (this) {
        GeminiSafetyThreshold.DEFAULT -> R.string.assistant_gemini_safety_default
        GeminiSafetyThreshold.OFF -> R.string.assistant_gemini_safety_off
        GeminiSafetyThreshold.BLOCK_NONE -> R.string.assistant_gemini_safety_block_none
        GeminiSafetyThreshold.BLOCK_ONLY_HIGH -> R.string.assistant_gemini_safety_block_high
        GeminiSafetyThreshold.BLOCK_MEDIUM_AND_ABOVE -> R.string.assistant_gemini_safety_block_medium
        GeminiSafetyThreshold.BLOCK_LOW_AND_ABOVE -> R.string.assistant_gemini_safety_block_low
    }
)

@Composable
private fun ProviderRequestChannel.displayName(): String = stringResource(
    when (this) {
        ProviderRequestChannel.ANTHROPIC_API -> R.string.log_page_channel_anthropic_api
        ProviderRequestChannel.OPENAI_API -> R.string.log_page_channel_openai_api
        ProviderRequestChannel.XAI_API -> R.string.log_page_channel_xai_api
        ProviderRequestChannel.GOOGLE_AI_STUDIO -> R.string.log_page_channel_google_ai_studio
        ProviderRequestChannel.VERTEX_AI -> R.string.log_page_channel_vertex_ai
        ProviderRequestChannel.COMPATIBLE_ENDPOINT -> R.string.log_page_channel_compatible
    }
)

private fun Float?.isValidPenalty(): Boolean = this == null || (this >= -2f && this < 2f)

private fun parseJsonObject(value: String): JsonObject? = runCatching {
    Json.parseToJsonElement(value).jsonObject
}.getOrNull()
