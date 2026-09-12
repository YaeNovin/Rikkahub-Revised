package me.rerere.rikkahub.ui.pages.assistant.detail

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import me.rerere.rikkahub.ui.components.ui.AppearanceAlertDialog as AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.components.ui.FormItem
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.ui.theme.JetbrainsMono

private val requestJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    prettyPrint = true
}

@Composable
internal fun CustomRequestOverview(
    modelId: String?,
    route: ParameterRequestRoute?,
    issues: List<CustomRequestIssue>,
    presets: List<CustomRequestPreset>,
    existingBodyKeys: Set<String>,
    effectiveHeaders: Map<String, String>,
    effectiveBody: JsonObject,
    onImportBody: (JsonObject) -> Unit,
    onApplyPreset: (CustomRequestPreset) -> Unit,
) {
    var showImportDialog by remember { mutableStateOf(false) }

    Card(colors = CustomColors.cardColorsOnSurfaceContainer) {
        FormItem(
            modifier = Modifier.padding(12.dp),
            label = { Text(stringResource(R.string.assistant_request_overview_title)) },
            description = {
                Text(
                    stringResource(
                        R.string.assistant_request_current_model,
                        modelId ?: stringResource(R.string.assistant_request_no_model),
                    )
                )
                route?.DisplayText() ?: Text(stringResource(R.string.assistant_request_no_route))
                Text(stringResource(R.string.assistant_request_scope_desc))
                Text(stringResource(R.string.assistant_request_priority_desc))
            },
        )
    }

    if (issues.isNotEmpty()) {
        Card(colors = CustomColors.cardColorsOnSurfaceContainer) {
            FormItem(
                modifier = Modifier.padding(12.dp),
                label = { Text(stringResource(R.string.assistant_request_diagnostics_title)) },
                description = {
                    issues.forEach { issue ->
                        Text(
                            text = issue.displayText(),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                },
            )
        }
    }

    Card(colors = CustomColors.cardColorsOnSurfaceContainer) {
        FormItem(
            modifier = Modifier.padding(12.dp),
            label = { Text(stringResource(R.string.assistant_request_tools_title)) },
            description = { Text(stringResource(R.string.assistant_request_import_desc)) },
        ) {
            OutlinedButton(
                onClick = { showImportDialog = true },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.assistant_request_import_json))
            }
        }
        if (presets.isNotEmpty()) {
            HorizontalDivider()
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = stringResource(R.string.assistant_request_official_templates),
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    text = stringResource(R.string.assistant_request_template_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                presets.forEach { preset ->
                    val added = preset.bodies.all { it.key in existingBodyKeys }
                    OutlinedButton(
                        onClick = { onApplyPreset(preset) },
                        enabled = !added,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            if (added) {
                                stringResource(
                                    R.string.assistant_request_template_added,
                                    preset.displayName(),
                                )
                            } else {
                                preset.displayName()
                            }
                        )
                    }
                    Text(
                        text = preset.description(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    if (effectiveHeaders.isNotEmpty() || effectiveBody.isNotEmpty()) {
        Card(colors = CustomColors.cardColorsOnSurfaceContainer) {
            FormItem(
                modifier = Modifier.padding(12.dp),
                label = { Text(stringResource(R.string.assistant_request_preview_title)) },
                description = { Text(stringResource(R.string.assistant_request_preview_desc)) },
            ) {
                SelectionContainer {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (effectiveHeaders.isNotEmpty()) {
                            Text(
                                text = effectiveHeaders.entries.joinToString("\n") { (name, value) ->
                                    "$name: $value"
                                },
                                fontFamily = JetbrainsMono,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        if (effectiveBody.isNotEmpty()) {
                            Text(
                                text = requestJson.encodeToString(
                                    JsonObject.serializer(),
                                    effectiveBody,
                                ),
                                fontFamily = JetbrainsMono,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
        }
    }

    if (showImportDialog) {
        ImportCustomBodyDialog(
            onDismiss = { showImportDialog = false },
            onImport = {
                onImportBody(it)
                showImportDialog = false
            },
        )
    }
}

@Composable
private fun ImportCustomBodyDialog(
    onDismiss: () -> Unit,
    onImport: (JsonObject) -> Unit,
) {
    var raw by remember { mutableStateOf("{\n  \n}") }
    val parsed = remember(raw) {
        runCatching {
            requestJson.parseToJsonElement(raw) as? JsonObject
                ?: error("The root value must be a JSON object")
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.assistant_request_import_json)) },
        text = {
            OutlinedTextField(
                value = raw,
                onValueChange = { raw = it },
                minLines = 8,
                maxLines = 16,
                isError = parsed.isFailure,
                supportingText = {
                    if (parsed.isFailure) {
                        Text(stringResource(R.string.assistant_request_import_invalid))
                    }
                },
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = JetbrainsMono),
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            Button(
                enabled = parsed.isSuccess,
                onClick = { parsed.getOrNull()?.let(onImport) },
            ) {
                Text(stringResource(R.string.assistant_page_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.assistant_page_cancel))
            }
        },
    )
}

@Composable
private fun CustomRequestIssue.displayText(): String = stringResource(
    when (kind) {
        CustomRequestIssueKind.EMPTY_HEADER_NAME -> R.string.assistant_request_issue_empty_header
        CustomRequestIssueKind.INVALID_HEADER -> R.string.assistant_request_issue_invalid_header
        CustomRequestIssueKind.DUPLICATE_HEADER -> R.string.assistant_request_issue_duplicate_header
        CustomRequestIssueKind.AUTH_HEADER -> R.string.assistant_request_issue_auth_header
        CustomRequestIssueKind.EMPTY_BODY_KEY -> R.string.assistant_request_issue_empty_body
        CustomRequestIssueKind.DUPLICATE_BODY -> R.string.assistant_request_issue_duplicate_body
        CustomRequestIssueKind.MODEL_OVERRIDES_HEADER -> R.string.assistant_request_issue_model_header
        CustomRequestIssueKind.MODEL_OVERRIDES_BODY -> R.string.assistant_request_issue_model_body
        CustomRequestIssueKind.APP_MANAGED_BODY -> R.string.assistant_request_issue_managed_body
        CustomRequestIssueKind.PROTOCOL_MISMATCH -> R.string.assistant_request_issue_protocol
    },
    field.ifBlank { stringResource(R.string.assistant_request_unnamed_field) },
)

@Composable
private fun CustomRequestPreset.displayName(): String = stringResource(
    when (id) {
        CustomRequestPresetId.OPENAI_NO_STORAGE -> R.string.assistant_request_preset_openai_store
        CustomRequestPresetId.OPENAI_AUTO_TRUNCATION -> R.string.assistant_request_preset_openai_truncation
        CustomRequestPresetId.GEMINI_JSON_OUTPUT -> R.string.assistant_request_preset_gemini_json
        CustomRequestPresetId.CLAUDE_USER_METADATA -> R.string.assistant_request_preset_claude_metadata
        CustomRequestPresetId.DEEPSEEK_JSON_OUTPUT -> R.string.assistant_request_preset_deepseek_json
        CustomRequestPresetId.QWEN_WEB_SEARCH -> R.string.assistant_request_preset_qwen_search
    }
)

@Composable
private fun CustomRequestPreset.description(): String = stringResource(
    when (id) {
        CustomRequestPresetId.OPENAI_NO_STORAGE -> R.string.assistant_request_preset_openai_store_desc
        CustomRequestPresetId.OPENAI_AUTO_TRUNCATION -> R.string.assistant_request_preset_openai_truncation_desc
        CustomRequestPresetId.GEMINI_JSON_OUTPUT -> R.string.assistant_request_preset_gemini_json_desc
        CustomRequestPresetId.CLAUDE_USER_METADATA -> R.string.assistant_request_preset_claude_metadata_desc
        CustomRequestPresetId.DEEPSEEK_JSON_OUTPUT -> R.string.assistant_request_preset_deepseek_json_desc
        CustomRequestPresetId.QWEN_WEB_SEARCH -> R.string.assistant_request_preset_qwen_search_desc
    }
)
