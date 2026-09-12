package me.rerere.rikkahub.ui.pages.assistant.detail

import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.Delete01
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.ai.provider.CustomBody
import me.rerere.ai.provider.CustomHeader
import me.rerere.ai.util.hasValidHttpSyntax
import me.rerere.highlight.LocalCodeHighlighter
import me.rerere.rikkahub.R
import me.rerere.rikkahub.service.formatUserFacingError
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.richtext.HighlightCodeVisualTransformation
import me.rerere.rikkahub.ui.theme.JetbrainsMono
import me.rerere.rikkahub.ui.theme.LocalDarkMode

private val jsonLenient = Json {
    ignoreUnknownKeys = true
    isLenient = true
    prettyPrint = true
}

@Composable
fun CustomHeaders(headers: List<CustomHeader>, onUpdate: (List<CustomHeader>) -> Unit) {
    Column(
        modifier = Modifier.padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(stringResource(R.string.assistant_page_custom_headers))
        Spacer(Modifier.height(8.dp))

        headers.forEachIndexed { index, header ->
            var headerName by remember(header.name) { mutableStateOf(header.name) }
            var headerValue by remember(header.value) { mutableStateOf(header.value) }
            val normalizedName = header.name.trim()
            val duplicate = header.enabled && normalizedName.isNotBlank() &&
                headers.count { it.enabled && it.name.trim().equals(normalizedName, ignoreCase = true) } > 1
            val invalid = header.enabled && normalizedName.isNotBlank() && !header.hasValidHttpSyntax()

            CardGroup {
                item(
                    supportingContent = {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(stringResource(R.string.assistant_request_entry_enabled))
                                Switch(
                                    checked = header.enabled,
                                    onCheckedChange = { enabled ->
                                        val updatedHeaders = headers.toMutableList()
                                        updatedHeaders[index] = updatedHeaders[index].copy(enabled = enabled)
                                        onUpdate(updatedHeaders)
                                    },
                                )
                            }
                            OutlinedTextField(
                                value = headerName,
                                onValueChange = {
                                    headerName = it
                                    val updatedHeaders = headers.toMutableList()
                                    updatedHeaders[index] = updatedHeaders[index].copy(name = it.trim())
                                    onUpdate(updatedHeaders)
                                },
                                label = { Text(stringResource(R.string.assistant_page_header_name)) },
                                modifier = Modifier.fillMaxWidth(),
                                enabled = header.enabled,
                                isError = invalid || duplicate || normalizedName.isBlank(),
                                supportingText = {
                                    when {
                                        normalizedName.isBlank() -> Text(stringResource(R.string.assistant_request_issue_empty_header_short))
                                        invalid -> Text(stringResource(R.string.assistant_request_issue_invalid_header_short))
                                        duplicate -> Text(stringResource(R.string.assistant_request_issue_duplicate_header_short))
                                    }
                                },
                            )
                            OutlinedTextField(
                                value = headerValue,
                                onValueChange = {
                                    headerValue = it
                                    val updatedHeaders = headers.toMutableList()
                                    updatedHeaders[index] =
                                        updatedHeaders[index].copy(value = it.trim())
                                    onUpdate(updatedHeaders)
                                },
                                label = { Text(stringResource(R.string.assistant_page_header_value)) },
                                modifier = Modifier.fillMaxWidth(),
                                enabled = header.enabled,
                            )
                        }
                    },
                    trailingContent = {
                        IconButton(onClick = {
                            val updatedHeaders = headers.toMutableList()
                            updatedHeaders.removeAt(index)
                            onUpdate(updatedHeaders)
                        }) {
                            Icon(
                                HugeIcons.Delete01,
                                contentDescription = stringResource(R.string.assistant_page_delete_header)
                            )
                        }
                    },
                    headlineContent = {},
                )
            }
        }

        Button(
            onClick = {
                val updatedHeaders = headers.toMutableList()
                updatedHeaders.add(CustomHeader("", ""))
                onUpdate(updatedHeaders)
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(HugeIcons.Add01, contentDescription = stringResource(R.string.assistant_page_add_header))
            Spacer(Modifier.width(4.dp))
            Text(stringResource(R.string.assistant_page_add_header))
        }
    }
}

@Composable
fun CustomBodies(customBodies: List<CustomBody>, onUpdate: (List<CustomBody>) -> Unit) {
    val context = LocalContext.current
    Column(
        modifier = Modifier.padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(stringResource(R.string.assistant_page_custom_bodies))
        Spacer(Modifier.height(8.dp))

        customBodies.forEachIndexed { index, body ->
            var bodyKey by remember(body.key) { mutableStateOf(body.key) }
            var bodyValueString by remember(body.value) {
                mutableStateOf(jsonLenient.encodeToString(JsonElement.serializer(), body.value))
            }
            var jsonParseError by remember { mutableStateOf<String?>(null) }
            val normalizedKey = body.key.trim()
            val duplicate = body.enabled && normalizedKey.isNotBlank() &&
                customBodies.count { it.enabled && it.key.trim() == normalizedKey } > 1

            CardGroup {
                item(
                    supportingContent = {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(stringResource(R.string.assistant_request_entry_enabled))
                                Switch(
                                    checked = body.enabled,
                                    onCheckedChange = { enabled ->
                                        val updatedBodies = customBodies.toMutableList()
                                        updatedBodies[index] = updatedBodies[index].copy(enabled = enabled)
                                        onUpdate(updatedBodies)
                                    },
                                )
                            }
                            OutlinedTextField(
                                value = bodyKey,
                                onValueChange = {
                                    bodyKey = it
                                    val updatedBodies = customBodies.toMutableList()
                                    updatedBodies[index] = updatedBodies[index].copy(key = it.trim())
                                    onUpdate(updatedBodies)
                                },
                                label = { Text(stringResource(R.string.assistant_page_body_key)) },
                                modifier = Modifier.fillMaxWidth(),
                                enabled = body.enabled,
                                isError = normalizedKey.isBlank() || duplicate,
                                supportingText = {
                                    when {
                                        normalizedKey.isBlank() -> Text(stringResource(R.string.assistant_request_issue_empty_body_short))
                                        duplicate -> Text(stringResource(R.string.assistant_request_issue_duplicate_body_short))
                                    }
                                },
                            )
                            OutlinedTextField(
                                value = bodyValueString,
                                onValueChange = { newString ->
                                    bodyValueString = newString
                                    try {
                                        val newJsonValue = jsonLenient.parseToJsonElement(newString)
                                        val updatedBodies = customBodies.toMutableList()
                                        updatedBodies[index] =
                                            updatedBodies[index].copy(value = newJsonValue)
                                        onUpdate(updatedBodies)
                                        jsonParseError = null
                                    } catch (e: Exception) {
                                        jsonParseError = context.formatUserFacingError(e)
                                    }
                                },
                                label = { Text(stringResource(R.string.assistant_page_body_value)) },
                                modifier = Modifier.fillMaxWidth(),
                                enabled = body.enabled,
                                isError = jsonParseError != null,
                                supportingText = {
                                    if (jsonParseError != null) {
                                        Text(jsonParseError!!)
                                    }
                                },
                                minLines = 3,
                                maxLines = 5,
                                visualTransformation = HighlightCodeVisualTransformation(
                                    language = "json",
                                    highlighter = LocalCodeHighlighter.current,
                                    darkMode = LocalDarkMode.current
                                ),
                                textStyle = LocalTextStyle.current.merge(fontFamily = JetbrainsMono),
                            )
                        }
                    },
                    trailingContent = {
                        IconButton(onClick = {
                            val updatedBodies = customBodies.toMutableList()
                            updatedBodies.removeAt(index)
                            onUpdate(updatedBodies)
                        }) {
                            Icon(
                                HugeIcons.Delete01,
                                contentDescription = stringResource(R.string.assistant_page_delete_body)
                            )
                        }
                    },
                    headlineContent = {},
                )
            }
        }

        Button(
            onClick = {
                val updatedBodies = customBodies.toMutableList()
                updatedBodies.add(CustomBody("", JsonPrimitive("")))
                onUpdate(updatedBodies)
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(HugeIcons.Add01, contentDescription = stringResource(R.string.assistant_page_add_body))
            Spacer(Modifier.width(4.dp))
            Text(stringResource(R.string.assistant_page_add_body))
        }
    }
}
