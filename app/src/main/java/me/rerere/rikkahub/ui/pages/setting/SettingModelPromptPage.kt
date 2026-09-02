package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
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
import me.rerere.rikkahub.data.ai.transformers.PromptVariableScope
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.ui.components.ai.ReasoningButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.PromptVariableReference
import me.rerere.rikkahub.utils.plus

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
                onPromptChange = { vm.updateSettings(settings.copy(translatePrompt = it)) },
                onResetPrompt = { vm.updateSettings(settings.copy(translatePrompt = DEFAULT_TRANSLATION_PROMPT)) },
                reasoningLevel = ReasoningLevel.fromBudgetTokens(settings.translateThinkingBudget),
                onUpdateReasoningLevel = { vm.updateSettings(settings.copy(translateThinkingBudget = it.budgetTokens)) },
            )
        }
        item {
            PromptSettingItem(
                title = stringResource(R.string.setting_model_page_prompt_title),
                variableScope = PromptVariableScope.TITLE_PROMPT,
                promptValue = settings.titlePrompt,
                onPromptChange = { vm.updateSettings(settings.copy(titlePrompt = it)) },
                onResetPrompt = { vm.updateSettings(settings.copy(titlePrompt = DEFAULT_TITLE_PROMPT)) },
            )
        }
        item {
            PromptSettingItem(
                title = stringResource(R.string.setting_model_page_prompt_suggestion),
                variableScope = PromptVariableScope.SUGGESTION_PROMPT,
                promptValue = settings.suggestionPrompt,
                onPromptChange = { vm.updateSettings(settings.copy(suggestionPrompt = it)) },
                onResetPrompt = { vm.updateSettings(settings.copy(suggestionPrompt = DEFAULT_SUGGESTION_PROMPT)) },
            )
        }
        item {
            PromptSettingItem(
                title = stringResource(R.string.setting_model_page_prompt_ocr),
                variableScope = null,
                promptValue = settings.ocrPrompt,
                onPromptChange = { vm.updateSettings(settings.copy(ocrPrompt = it)) },
                onResetPrompt = { vm.updateSettings(settings.copy(ocrPrompt = DEFAULT_OCR_PROMPT)) },
            )
        }
        item {
            PromptSettingItem(
                title = stringResource(R.string.setting_model_page_prompt_compress),
                variableScope = PromptVariableScope.COMPRESS_PROMPT,
                promptValue = settings.compressPrompt,
                onPromptChange = { vm.updateSettings(settings.copy(compressPrompt = it)) },
                onResetPrompt = { vm.updateSettings(settings.copy(compressPrompt = DEFAULT_COMPRESS_PROMPT)) },
            )
        }
    }
}

@Composable
private fun PromptSettingItem(
    title: String,
    variableScope: PromptVariableScope?,
    promptValue: String,
    onPromptChange: (String) -> Unit,
    onResetPrompt: () -> Unit,
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
        var editorValue by remember(title) {
            mutableStateOf(
                TextFieldValue(
                    text = promptValue,
                    selection = TextRange(promptValue.length),
                )
            )
        }
        LaunchedEffect(title, promptValue) {
            if (editorValue.text != promptValue) {
                editorValue = TextFieldValue(
                    text = promptValue,
                    selection = TextRange(promptValue.length),
                )
            }
        }
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
                    onValueChange = {
                        editorValue = it
                        onPromptChange(it.text)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = 15,
                )
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
                            onPromptChange(text)
                        },
                    )
                }
                TextButton(onClick = onResetPrompt) {
                    Text(stringResource(R.string.setting_model_page_reset_to_default))
                }
            }
        }
    }
}
