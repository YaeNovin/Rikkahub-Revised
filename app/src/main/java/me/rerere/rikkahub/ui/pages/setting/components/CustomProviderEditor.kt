package me.rerere.rikkahub.ui.pages.setting.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import me.rerere.rikkahub.ui.components.ui.AppearanceModalBottomSheet as ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberBottomSheetState
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
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.R
import me.rerere.rikkahub.service.formatUserFacingError
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

@Composable
fun CustomProviderEditor(
    provider: ProviderSetting,
    onApply: (ProviderSetting) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (provider.builtIn) return
    val context = LocalContext.current

    var showEditor by remember { mutableStateOf(false) }
    OutlinedButton(
        onClick = { showEditor = true },
        modifier = modifier,
    ) {
        Text(stringResource(R.string.setting_provider_page_advanced_editor))
    }

    if (showEditor) {
        var draft by remember(provider) {
            mutableStateOf(CustomProviderConfigCodec.export(provider))
        }
        var error by remember { mutableStateOf<String?>(null) }
        val sheetState = rememberBottomSheetState(
            initialValue = SheetValue.Hidden,
            enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
        )

        ModalBottomSheet(
            onDismissRequest = { showEditor = false },
            sheetState = sheetState,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.9f)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = stringResource(R.string.setting_provider_page_advanced_editor),
                    style = androidx.compose.material3.MaterialTheme.typography.titleLarge,
                )
                Text(
                    text = stringResource(R.string.setting_provider_page_advanced_editor_desc),
                    style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                    color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = draft,
                    onValueChange = {
                        draft = it
                        error = null
                    },
                    label = { Text(stringResource(R.string.setting_provider_page_configuration_json)) },
                    isError = error != null,
                    supportingText = error?.let { { Text(it) } },
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                ) {
                    TextButton(
                        onClick = { draft = CustomProviderConfigCodec.export(provider) },
                    ) {
                        Text(stringResource(R.string.setting_provider_page_load_current_configuration))
                    }
                    TextButton(onClick = { showEditor = false }) {
                        Text(stringResource(R.string.cancel))
                    }
                    TextButton(
                        onClick = {
                            runCatching {
                                CustomProviderConfigCodec.import(
                                    raw = draft,
                                    existing = provider,
                                )
                            }.onSuccess { parsed ->
                                onApply(parsed)
                                showEditor = false
                            }.onFailure { throwable ->
                                error = context.formatUserFacingError(throwable)
                            }
                        },
                    ) {
                        Text(stringResource(R.string.setting_provider_page_apply_configuration))
                    }
                }
            }
        }
    }
}

internal object CustomProviderConfigCodec {
    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
        explicitNulls = false
    }

    fun export(provider: ProviderSetting): String = json.encodeToString(provider.withoutSecrets())

    fun import(raw: String, existing: ProviderSetting): ProviderSetting {
        val parsed = json.decodeFromString<ProviderSetting>(raw)
        parsed.requireValidConfiguration()
        return parsed
            .copyProvider(
                id = existing.id,
                builtIn = false,
                description = existing.description,
                shortDescription = existing.shortDescription,
            )
            .restoreMissingSecrets(existing)
    }

    private fun ProviderSetting.withoutSecrets(): ProviderSetting = when (this) {
        is ProviderSetting.OpenAI -> copy(
            apiKey = "",
            baseUrl = baseUrl.redactedUrl(),
            models = models.map { it.redactedModel() },
        )
        is ProviderSetting.Google -> copy(
            apiKey = "",
            privateKey = "",
            baseUrl = baseUrl.redactedUrl(),
            models = models.map { it.redactedModel() },
        )
        is ProviderSetting.Claude -> copy(
            apiKey = "",
            baseUrl = baseUrl.redactedUrl(),
            models = models.map { it.redactedModel() },
        )
    }

    private fun ProviderSetting.restoreMissingSecrets(existing: ProviderSetting): ProviderSetting {
        val existingApiKey = existing.apiKeyOrEmpty()
        return when (this) {
            is ProviderSetting.OpenAI -> copy(
                apiKey = apiKey.ifBlank { existingApiKey },
                baseUrl = baseUrl.restoreSensitiveQueryParameters(existing.baseUrlOrEmpty()),
                models = restoreModels(existing),
            )
            is ProviderSetting.Google -> copy(
                apiKey = apiKey.ifBlank { existingApiKey },
                baseUrl = baseUrl.restoreSensitiveQueryParameters(existing.baseUrlOrEmpty()),
                privateKey = privateKey.ifBlank {
                    (existing as? ProviderSetting.Google)?.privateKey.orEmpty()
                },
                models = restoreModels(existing),
            )
            is ProviderSetting.Claude -> copy(
                apiKey = apiKey.ifBlank { existingApiKey },
                baseUrl = baseUrl.restoreSensitiveQueryParameters(existing.baseUrlOrEmpty()),
                models = restoreModels(existing),
            )
        }
    }

    private fun ProviderSetting.restoreModels(existing: ProviderSetting): List<Model> {
        val existingModels = existing.models.associateBy { it.id }
        return models.map { model -> model.restoreSensitiveFields(existingModels[model.id]) }
    }

    private fun Model.redactedModel(): Model = copy(
        providerOverwrite = null,
        customHeaders = customHeaders.map { header ->
            if (header.name.isSensitiveField()) header.copy(value = "") else header
        },
        customBodies = customBodies.map { body ->
            if (body.key.isSensitiveField()) body.copy(value = JsonPrimitive("")) else body
        },
    )

    private fun Model.restoreSensitiveFields(existing: Model?): Model {
        val oldHeaders = existing?.customHeaders.orEmpty().associateBy { it.name.lowercase() }
        val oldBodies = existing?.customBodies.orEmpty().associateBy { it.key.lowercase() }
        return copy(
            providerOverwrite = providerOverwrite ?: existing?.providerOverwrite,
            customHeaders = customHeaders.map { header ->
                if (header.value.isBlank() && header.name.isSensitiveField()) {
                    header.copy(value = oldHeaders[header.name.lowercase()]?.value.orEmpty())
                } else {
                    header
                }
            },
            customBodies = customBodies.map { body ->
                val primitive = body.value as? JsonPrimitive
                if (body.key.isSensitiveField() && primitive?.isString == true && primitive.content.isBlank()) {
                    body.copy(value = oldBodies[body.key.lowercase()]?.value ?: body.value)
                } else {
                    body
                }
            },
        )
    }

    private fun String.isSensitiveField(): Boolean {
        val normalized = lowercase()
        return normalized.contains("authorization") ||
            normalized.contains("api-key") ||
            normalized.contains("api_key") ||
            normalized.contains("token") ||
            normalized.contains("secret") ||
            normalized.contains("password") ||
            normalized == "key"
    }

    private fun ProviderSetting.apiKeyOrEmpty(): String = when (this) {
        is ProviderSetting.OpenAI -> apiKey
        is ProviderSetting.Google -> apiKey
        is ProviderSetting.Claude -> apiKey
    }

    private fun ProviderSetting.baseUrlOrEmpty(): String = when (this) {
        is ProviderSetting.OpenAI -> baseUrl
        is ProviderSetting.Google -> baseUrl
        is ProviderSetting.Claude -> baseUrl
    }

    private fun String.redactedUrl(): String {
        val url = toHttpUrlOrNull() ?: return this
        return url.newBuilder().apply {
            url.queryParameterNames
                .filter { it.isSensitiveField() }
                .forEach(::removeAllQueryParameters)
        }.build().toString()
    }

    private fun String.restoreSensitiveQueryParameters(existing: String): String {
        val targetUrl = toHttpUrlOrNull() ?: return this
        val existingUrl = existing.toHttpUrlOrNull() ?: return this
        return targetUrl.newBuilder().apply {
            existingUrl.queryParameterNames
                .filter { it.isSensitiveField() }
                .filter { targetUrl.queryParameterValues(it).isEmpty() }
                .forEach { name ->
                    existingUrl.queryParameterValues(name).forEach { value ->
                        addQueryParameter(name, value)
                    }
                }
        }.build().toString()
    }

    private fun ProviderSetting.requireValidConfiguration() {
        require(name.isNotBlank()) { "Provider name cannot be empty" }
        val baseUrl = when (this) {
            is ProviderSetting.OpenAI -> baseUrl
            is ProviderSetting.Google -> baseUrl
            is ProviderSetting.Claude -> baseUrl
        }
        val parsedUrl = baseUrl.toHttpUrlOrNull()
        require(parsedUrl != null && parsedUrl.scheme in setOf("http", "https")) {
            "Base URL must be a valid HTTP(S) URL"
        }
    }
}
