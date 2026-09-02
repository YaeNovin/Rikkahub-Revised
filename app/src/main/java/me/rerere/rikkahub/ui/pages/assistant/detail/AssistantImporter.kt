package me.rerere.rikkahub.ui.pages.assistant.detail

import android.content.Context
import android.net.Uri
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SplitButtonLayout
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.dokar.sonner.ToastType
import com.dokar.sonner.ToasterState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.InjectionPosition
import me.rerere.rikkahub.data.model.Lorebook
import me.rerere.rikkahub.data.model.PromptInjection
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.ui.components.ui.AutoAIIcon
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.utils.ImageUtils
import me.rerere.rikkahub.utils.jsonPrimitiveOrNull
import me.rerere.rikkahub.R
import me.rerere.rikkahub.service.formatUserFacingError
import org.koin.compose.koinInject

@Composable
fun AssistantImporter(
    modifier: Modifier = Modifier,
    onImport: (Assistant, List<Lorebook>) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier,
    ) {
        SillyTavernImporter(onImport = onImport)
    }
}

@Composable
private fun SillyTavernImporter(
    onImport: (Assistant, List<Lorebook>) -> Unit
) {
    val context = LocalContext.current
    val filesManager: FilesManager = koinInject()
    val scope = rememberCoroutineScope()
    val toaster = LocalToaster.current
    var isLoading by remember { mutableStateOf(false) }

    val jsonPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            isLoading = true
            scope.launch {
                try {
                    runCatching {
                        importAssistantFromUri(
                            context = context,
                            uri = uri,
                            onImport = onImport,
                            toaster = toaster,
                            filesManager = filesManager,
                        )
                    }.onFailure { exception ->
                        exception.printStackTrace()
                        toaster.show(context.formatUserFacingError(exception))
                    }
                } finally {
                    isLoading = false
                }
            }
        }
    }

    val pngPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            isLoading = true
            scope.launch {
                try {
                    runCatching {
                        importAssistantFromUri(
                            context = context,
                            uri = uri,
                            onImport = onImport,
                            toaster = toaster,
                            filesManager = filesManager,
                        )
                    }.onFailure { exception ->
                        exception.printStackTrace()
                        toaster.show(context.formatUserFacingError(exception))
                    }
                } finally {
                    isLoading = false
                }
            }
        }
    }

    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedButton(
            onClick = {
                pngPickerLauncher.launch(arrayOf("image/png"))
            },
            enabled = !isLoading
        ) {
            AutoAIIcon(name = "tavern", modifier = Modifier.padding(end = 8.dp))
            Text(text = if (isLoading) stringResource(R.string.assistant_importer_importing) else stringResource(R.string.assistant_importer_import_tavern_png))
        }

        OutlinedButton(
            onClick = {
                jsonPickerLauncher.launch(arrayOf("application/json"))
            },
            enabled = !isLoading
        ) {
            AutoAIIcon(name = "tavern", modifier = Modifier.padding(end = 8.dp))
            Text(text = if (isLoading) stringResource(R.string.assistant_importer_importing) else stringResource(R.string.assistant_importer_import_tavern_json))
        }
    }
}

// region Parsing Strategy

private interface TavernCardParser {
    val specName: String
    fun parse(context: Context, json: JsonObject, background: String?): TavernImportResult
}

internal data class TavernImportResult(
    val assistant: Assistant,
    val lorebooks: List<Lorebook> = emptyList(),
)

private class CharaCardV2Parser : TavernCardParser {
    override val specName: String = "chara_card_v2"

    override fun parse(context: Context, json: JsonObject, background: String?): TavernImportResult {
        val data = json["data"]?.jsonObject ?: error(context.getString(R.string.assistant_importer_missing_data_field))
        val name = data["name"]?.jsonPrimitiveOrNull?.contentOrNull
            ?: error(context.getString(R.string.assistant_importer_missing_name_field))
        val firstMessage = data["first_mes"]?.jsonPrimitiveOrNull?.contentOrNull
        val system = data["system_prompt"]?.jsonPrimitiveOrNull?.contentOrNull
        val description = data["description"]?.jsonPrimitiveOrNull?.contentOrNull
        val personality = data["personality"]?.jsonPrimitiveOrNull?.contentOrNull
        val scenario = data["scenario"]?.jsonPrimitiveOrNull?.contentOrNull

        val prompt = buildString {
            appendLine("You are roleplaying as $name.")
            appendLine()
            if (!system.isNullOrBlank()) {
                appendLine(system)
                appendLine()
            }
            appendLine("## Description of the character")
            appendLine(description ?: "Empty")
            appendLine()
            appendLine("## Personality of the character")
            appendLine(personality ?: "Empty")
            appendLine()
            appendLine("## Scenario")
            append(scenario ?: "Empty")
        }

        val lorebook = parseEmbeddedTavernLorebook(data, name)
        return TavernImportResult(Assistant(
            name = name,
            presetMessages = if (firstMessage != null) listOf(UIMessage.assistant(firstMessage)) else emptyList(),
            systemPrompt = prompt,
            background = background,
            lorebookIds = lorebook?.let { setOf(it.id) }.orEmpty(),
        ), lorebook?.let(::listOf).orEmpty())
    }
}

private class CharaCardV3Parser : TavernCardParser {
    override val specName: String = "chara_card_v3"

    override fun parse(context: Context, json: JsonObject, background: String?): TavernImportResult {
        val data = json["data"]?.jsonObject ?: error(context.getString(R.string.assistant_importer_missing_data_field))
        val name = data["name"]?.jsonPrimitiveOrNull?.contentOrNull ?: error(context.getString(R.string.assistant_importer_missing_name_field))
        val description = data["description"]?.jsonPrimitiveOrNull?.contentOrNull
        val firstMessage = data["first_mes"]?.jsonPrimitiveOrNull?.contentOrNull
        val system = data["system_prompt"]?.jsonPrimitiveOrNull?.contentOrNull
        val personality = data["personality"]?.jsonPrimitiveOrNull?.contentOrNull
        val scenario = data["scenario"]?.jsonPrimitiveOrNull?.contentOrNull

        val prompt = buildString {
            appendLine("You are roleplaying as $name.")
            appendLine()
            if (!system.isNullOrBlank()) {
                appendLine(system)
                appendLine()
            }
            appendLine("## Description of the character")
            appendLine(description ?: "Empty")
            appendLine()
            appendLine("## Personality of the character")
            appendLine(personality ?: "Empty")
            appendLine()
            appendLine("## Scenario")
            append(scenario ?: "Empty")
        }

        val lorebook = parseEmbeddedTavernLorebook(data, name)
        return TavernImportResult(Assistant(
            name = name,
            presetMessages = if (firstMessage != null) listOf(UIMessage.assistant(firstMessage)) else emptyList(),
            systemPrompt = prompt,
            background = background,
            lorebookIds = lorebook?.let { setOf(it.id) }.orEmpty(),
        ), lorebook?.let(::listOf).orEmpty())
    }
}

private val TAVERN_PARSERS: Map<String, TavernCardParser> = listOf(
    CharaCardV2Parser(),
    CharaCardV3Parser()
).associateBy { it.specName }

private fun parseAssistantFromJson(
    context: Context,
    json: JsonObject,
    background: String?,
): TavernImportResult {
    val spec = json["spec"]?.jsonPrimitive?.contentOrNull
        ?: error(context.getString(R.string.assistant_importer_missing_spec_field))
    val parser = TAVERN_PARSERS[spec] ?: error(context.getString(R.string.assistant_importer_unsupported_spec, spec))
    return parser.parse(context = context, json = json, background = background)
}

// endregion

private suspend fun importAssistantFromUri(
    context: Context,
    uri: Uri,
    onImport: (Assistant, List<Lorebook>) -> Unit,
    toaster: ToasterState,
    filesManager: FilesManager,
) {
    try {
        val mime = withContext(Dispatchers.IO) { filesManager.getFileMimeType(uri) }
        val (jsonString, backgroundStr) = withContext(Dispatchers.IO) {
            when (mime) {
                "image/png" -> {
                    val result = ImageUtils.getTavernCharacterMeta(context, uri)
                    result.map { base64Data ->
                        val json = String(Base64.decode(base64Data, Base64.DEFAULT))
                        val bg = filesManager.createChatFilesByContents(listOf(uri)).first().toString()
                        json to bg
                    }.getOrElse { throw it }
                }

                "application/json" -> {
                    val json = context.contentResolver.openInputStream(uri)?.bufferedReader()
                        .use { it?.readText() }
                        ?: error(context.getString(R.string.assistant_importer_read_json_failed))
                    json to null
                }

                else -> error(context.getString(R.string.assistant_importer_unsupported_file_type, mime ?: "unknown"))
            }
        }
        val json = Json.parseToJsonElement(jsonString).jsonObject
        val imported = parseAssistantFromJson(context = context, json = json, background = backgroundStr)
        onImport(imported.assistant, imported.lorebooks)
    } catch (exception: Exception) {
        exception.printStackTrace()
        toaster.show(
                    message = context.formatUserFacingError(exception),
            type = ToastType.Error
        )
    }
}

internal fun parseEmbeddedTavernLorebook(data: JsonObject, characterName: String): Lorebook? {
    val book = data["character_book"] as? JsonObject ?: return null
    val rawEntries = when (val entries = book["entries"]) {
        is JsonArray -> entries.toList()
        is JsonObject -> entries.values.toList()
        else -> emptyList()
    }
    val defaultScanDepth = book.int("scan_depth") ?: 4
    val entries = rawEntries.mapNotNull { element ->
        val entry = element as? JsonObject ?: return@mapNotNull null
        val primary = entry.stringList("keys").ifEmpty { entry.stringList("key") }
        val secondary = entry.stringList("secondary_keys").ifEmpty { entry.stringList("keysecondary") }
        val constant = entry.bool("constant") ?: false
        val content = entry.string("content").orEmpty()
        if (content.isBlank() || (!constant && primary.isEmpty())) return@mapNotNull null
        val selective = entry.bool("selective") == true && secondary.isNotEmpty()
        val expression = if (selective) {
            val primaryExpression = primary.toKeywordExpression("OR")
            val secondaryLogic = entry.int("selective_logic") ?: entry.int("selectiveLogic") ?: 0
            val secondaryExpression = when (secondaryLogic) {
                1 -> "NOT (${secondary.toKeywordExpression("AND")})"
                2 -> "NOT (${secondary.toKeywordExpression("OR")})"
                3 -> secondary.toKeywordExpression("AND")
                else -> secondary.toKeywordExpression("OR")
            }
            "($primaryExpression) AND ($secondaryExpression)"
        } else {
            primary.toKeywordExpression("OR")
        }
        val extensions = entry["extensions"] as? JsonObject
        val probabilityEnabled = extensions?.bool("useProbability")
            ?: extensions?.bool("use_probability")
            ?: entry.bool("use_probability")
            ?: false
        val probability = extensions?.int("probability") ?: entry.int("probability") ?: 100
        PromptInjection.RegexInjection(
            name = entry.string("name")
                ?: entry.string("comment")
                ?: primary.firstOrNull().orEmpty(),
            enabled = entry.bool("enabled") ?: !(entry.bool("disable") ?: false),
            priority = entry.int("priority") ?: entry.int("insertion_order") ?: entry.int("order") ?: 100,
            position = mapTavernPosition(entry["position"]),
            injectDepth = entry.int("depth") ?: 4,
            content = content,
            keywords = primary,
            keywordExpression = expression,
            caseSensitive = entry.bool("case_sensitive") ?: entry.bool("caseSensitive") ?: false,
            scanDepth = entry.int("scan_depth") ?: entry.int("scanDepth") ?: defaultScanDepth,
            constantActive = constant,
            triggerProbability = if (probabilityEnabled) probability.coerceIn(0, 100) else 100,
        )
    }
    if (entries.isEmpty()) return null
    return Lorebook(
        name = book.string("name")?.takeIf(String::isNotBlank) ?: "$characterName World Book",
        description = book.string("description").orEmpty(),
        entries = entries,
        tokenBudget = (book.int("token_budget") ?: 0).coerceAtLeast(0),
    )
}

private fun mapTavernPosition(value: JsonElement?): InjectionPosition {
    val primitive = value as? JsonPrimitive
    primitive?.intOrNull?.let { position ->
        return when (position) {
            0 -> InjectionPosition.BEFORE_SYSTEM_PROMPT
            1 -> InjectionPosition.AFTER_SYSTEM_PROMPT
            2, 3 -> InjectionPosition.TOP_OF_CHAT
            4 -> InjectionPosition.AT_DEPTH
            else -> InjectionPosition.AFTER_SYSTEM_PROMPT
        }
    }
    return when (primitive?.contentOrNull?.lowercase()) {
        "before_char", "before_system", "before_system_prompt" -> InjectionPosition.BEFORE_SYSTEM_PROMPT
        "after_char", "after_system", "after_system_prompt" -> InjectionPosition.AFTER_SYSTEM_PROMPT
        "at_depth", "depth" -> InjectionPosition.AT_DEPTH
        "before_example", "after_example", "top_of_chat" -> InjectionPosition.TOP_OF_CHAT
        else -> InjectionPosition.AFTER_SYSTEM_PROMPT
    }
}

private fun JsonObject.string(name: String): String? =
    (this[name] as? JsonPrimitive)?.contentOrNull

private fun JsonObject.int(name: String): Int? =
    (this[name] as? JsonPrimitive)?.intOrNull

private fun JsonObject.bool(name: String): Boolean? =
    (this[name] as? JsonPrimitive)?.booleanOrNull

private fun JsonObject.stringList(name: String): List<String> =
    (this[name] as? JsonArray).orEmpty()
        .mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim() }
        .filter(String::isNotEmpty)

private fun List<String>.toKeywordExpression(operator: String): String = joinToString(" $operator ") { keyword ->
    "\"${keyword.replace("\"", "").trim()}\""
}
