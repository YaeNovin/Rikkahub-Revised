package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import me.rerere.rikkahub.ui.components.ui.AppearanceModalBottomSheet as ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import me.rerere.ai.core.ReasoningLevel
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_COMPRESS_PROMPT
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_OCR_PROMPT
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_SUGGESTION_PROMPT
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_TITLE_PROMPT
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_TRANSLATION_PROMPT
import me.rerere.rikkahub.data.ai.transformers.PromptVariableCatalog
import me.rerere.rikkahub.data.ai.transformers.PromptVariableResolutionContext
import me.rerere.rikkahub.data.ai.transformers.PromptVariableScope
import me.rerere.rikkahub.data.ai.transformers.resolvePromptVariables
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.datastore.resolveBackgroundChatModel
import me.rerere.rikkahub.ui.components.ai.ReasoningButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.PromptVariableReference
import me.rerere.rikkahub.utils.plus
import me.rerere.rikkahub.utils.applyPlaceholders

@Composable
internal fun PromptSettingsPage(settings: Settings, vm: SettingVM, contentPadding: PaddingValues) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = contentPadding + PaddingValues(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            PromptSettingItem(
                title = stringResource(R.string.setting_model_page_prompt_translation),
                variableScope = PromptVariableScope.TRANSLATION_PROMPT,
                promptValue = settings.translatePrompt,
                defaultPrompt = DEFAULT_TRANSLATION_PROMPT,
                settings = settings,
                onSavePrompt = { prompt -> vm.updateSettings { it.copy(translatePrompt = prompt) } },
                reasoningLevel = ReasoningLevel.fromBudgetTokens(settings.translateThinkingBudget),
                onUpdateReasoningLevel = { vm.updateSettings(settings.copy(translateThinkingBudget = it.budgetTokens)) },
            )
        }
        item {
            PromptSettingItem(
                title = stringResource(R.string.setting_model_page_prompt_title),
                variableScope = PromptVariableScope.TITLE_PROMPT,
                promptValue = settings.titlePrompt,
                defaultPrompt = DEFAULT_TITLE_PROMPT,
                settings = settings,
                onSavePrompt = { prompt -> vm.updateSettings { it.copy(titlePrompt = prompt) } },
            )
        }
        item {
            PromptSettingItem(
                title = stringResource(R.string.setting_model_page_prompt_suggestion),
                variableScope = PromptVariableScope.SUGGESTION_PROMPT,
                promptValue = settings.suggestionPrompt,
                defaultPrompt = DEFAULT_SUGGESTION_PROMPT,
                settings = settings,
                onSavePrompt = { prompt -> vm.updateSettings { it.copy(suggestionPrompt = prompt) } },
            )
        }
        item {
            PromptSettingItem(
                title = stringResource(R.string.setting_model_page_prompt_ocr),
                variableScope = null,
                promptValue = settings.ocrPrompt,
                defaultPrompt = DEFAULT_OCR_PROMPT,
                settings = settings,
                onSavePrompt = { prompt -> vm.updateSettings { it.copy(ocrPrompt = prompt) } },
            )
        }
        item {
            PromptSettingItem(
                title = stringResource(R.string.setting_model_page_prompt_compress),
                variableScope = PromptVariableScope.COMPRESS_PROMPT,
                promptValue = settings.compressPrompt,
                defaultPrompt = DEFAULT_COMPRESS_PROMPT,
                settings = settings,
                onSavePrompt = { prompt -> vm.updateSettings { it.copy(compressPrompt = prompt) } },
            )
        }
    }
}

@Composable
private fun PromptSettingItem(
    title: String,
    variableScope: PromptVariableScope?,
    promptValue: String,
    defaultPrompt: String,
    settings: Settings,
    onSavePrompt: (String) -> Unit,
    reasoningLevel: ReasoningLevel? = null,
    onUpdateReasoningLevel: ((ReasoningLevel) -> Unit)? = null,
) {
    var showEditor by remember { mutableStateOf(false) }

    CardGroup(title = { Text(title) }) {
        item(
            onClick = { showEditor = true },
            headlineContent = { Text(stringResource(R.string.setting_model_page_prompt)) },
            supportingContent = {
                Text(
                    if (variableScope == null) {
                        stringResource(R.string.setting_model_page_ocr_prompt_vars)
                    } else {
                        stringResource(
                            R.string.prompt_variable_card_summary,
                            PromptVariableCatalog.primaryForScope(variableScope).size,
                        )
                    }
                )
            },
            trailingContent = {
                Icon(
                    HugeIcons.ArrowRight01,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
            },
        )
        if (reasoningLevel != null && onUpdateReasoningLevel != null) {
            item(
                headlineContent = { Text(stringResource(R.string.assistant_page_thinking_budget)) },
                trailingContent = {
                    ReasoningButton(
                        reasoningLevel = reasoningLevel,
                        onUpdateReasoningLevel = onUpdateReasoningLevel,
                    )
                },
            )
        }
    }

    if (showEditor) {
        var editorValue by remember(title, showEditor) {
            mutableStateOf(
                TextFieldValue(
                    text = promptValue,
                    selection = TextRange(promptValue.length),
                )
            )
        }
        val requiredVariable = variableScope.requiredContentVariable()
        val missingRequiredVariable = requiredVariable != null &&
            !editorValue.text.containsPromptVariable(requiredVariable)
        val preview = expandedPromptPreview(
            prompt = editorValue.text,
            scope = variableScope,
            settings = settings,
        )
        ModalBottomSheet(
            onDismissRequest = { showEditor = false },
        ) {
            Column(
                modifier = Modifier
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 16.dp)
                    .imePadding(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                )
                if (variableScope == null) {
                    Text(
                        text = stringResource(R.string.setting_model_page_ocr_prompt_no_variables),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                OutlinedTextField(
                    value = editorValue,
                    onValueChange = { editorValue = it },
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = 15,
                    isError = editorValue.text.isBlank(),
                    supportingText = if (editorValue.text.isBlank()) {
                        { Text(stringResource(R.string.setting_model_page_prompt_empty_error)) }
                    } else null,
                )
                if (missingRequiredVariable) {
                    Text(
                        text = stringResource(
                            R.string.setting_model_page_prompt_required_variable,
                            "{$requiredVariable}",
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
                variableScope?.let { scope ->
                    PromptVariableReference(
                        scope = scope,
                        onInsert = { token ->
                            val selection = editorValue.selection
                            val start = selection.min.coerceIn(0, editorValue.text.length)
                            val end = selection.max.coerceIn(start, editorValue.text.length)
                            val text = editorValue.text.replaceRange(start, end, token)
                            val cursor = start + token.length
                            editorValue = TextFieldValue(text, TextRange(cursor))
                        },
                    )
                }
                Text(
                    text = stringResource(R.string.setting_model_page_prompt_preview),
                    style = MaterialTheme.typography.labelLarge,
                )
                Text(
                    text = preview,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 8,
                )
                TextButton(
                    onClick = {
                        editorValue = TextFieldValue(
                            text = defaultPrompt,
                            selection = TextRange(defaultPrompt.length),
                        )
                    }
                ) {
                    Text(stringResource(R.string.setting_model_page_reset_to_default))
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = { showEditor = false }) {
                        Text(stringResource(R.string.setting_model_page_prompt_cancel))
                    }
                    TextButton(
                        onClick = {
                            onSavePrompt(editorValue.text)
                            showEditor = false
                        },
                        enabled = editorValue.text.isNotBlank(),
                    ) {
                        Text(stringResource(R.string.setting_model_page_prompt_save))
                    }
                }
            }
        }
    }
}

private fun PromptVariableScope?.requiredContentVariable(): String? = when (this) {
    PromptVariableScope.TITLE_PROMPT,
    PromptVariableScope.SUGGESTION_PROMPT,
    PromptVariableScope.COMPRESS_PROMPT -> "content"
    PromptVariableScope.TRANSLATION_PROMPT -> "source_text"
    else -> null
}

private fun String.containsPromptVariable(key: String): Boolean =
    Regex("\\{\\{?\\s*${Regex.escape(key)}\\s*}}?", RegexOption.IGNORE_CASE).containsMatchIn(this)

@Composable
private fun expandedPromptPreview(
    prompt: String,
    scope: PromptVariableScope?,
    settings: Settings,
): String {
    val context = LocalContext.current
    val model = when (scope) {
        PromptVariableScope.TITLE_PROMPT -> settings.resolveBackgroundChatModel(settings.titleModelId)
        PromptVariableScope.SUGGESTION_PROMPT -> settings.resolveBackgroundChatModel(settings.suggestionModelId)
        else -> settings.resolveBackgroundChatModel(settings.fastModelId)
    }
    val resolved = PromptVariableResolutionContext(
        settings = settings,
        model = model,
        assistant = settings.getCurrentAssistant(),
        context = context,
    ).resolvePromptVariables().toMutableMap().apply {
        put("content", stringResource(R.string.setting_model_page_prompt_preview_sample))
        put("source_text", stringResource(R.string.setting_model_page_prompt_preview_sample))
        put("target_lang", java.util.Locale.getDefault().displayName)
        put("target_tokens", "1024")
        put("additional_context", "")
        put("max_title_length", settings.titleMaxLength.toString())
        put("suggestion_count", settings.suggestionCount.toString())
        put("suggestion_max_length", settings.suggestionMaxLength.toString())
        put("suggestion_style", settings.suggestionStyle.name.lowercase())
    }
    return prompt.applyPlaceholders(*resolved.toList().toTypedArray())
}
