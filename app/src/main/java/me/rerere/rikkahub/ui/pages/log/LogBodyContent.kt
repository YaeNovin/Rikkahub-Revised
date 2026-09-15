package me.rerere.rikkahub.ui.pages.log

import android.content.ClipData
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import me.rerere.rikkahub.ui.components.richtext.richContentColors
import me.rerere.rikkahub.ui.components.ui.JsonTree
import me.rerere.rikkahub.ui.components.ui.LongTextContent
import me.rerere.rikkahub.ui.components.ui.isLongDisplayText
import me.rerere.rikkahub.ui.theme.JetbrainsMono
import me.rerere.rikkahub.utils.JsonInstantPretty

internal data class LogBodyPresentation(val format: String, val text: String, val json: JsonElement? = null)

internal fun presentLogBody(body: String, systemInstruction: Boolean = false): LogBodyPresentation {
    val trimmed = body.trim()
    // The application's lenient parser can accept an HTML token as a JSON primitive.
    // Only attempt JSON detection for a JSON-shaped prefix, using strict parsing.
    val jsonPrefix = trimmed.firstOrNull()?.let { it in "{[\"-0123456789" } == true || trimmed in setOf("true", "false", "null")
    val parsed = if (jsonPrefix) runCatching { Json.parseToJsonElement(body) }.getOrNull() else null
    if (systemInstruction && parsed is JsonObject) {
        val parts = (parsed["parts"] as? JsonArray).orEmpty()
        // Only project standard text-only instruction parts. Unknown/custom fields stay visible.
        if (parsed.keys.all { it in setOf("parts", "role") } && parts.isNotEmpty() && parts.all {
                it is JsonObject && it.keys == setOf("text") && (it["text"] as? JsonPrimitive)?.isString == true
            }) {
            val role = (parsed["role"] as? JsonPrimitive)?.contentOrNull?.let { "role: $it\n\n" }.orEmpty()
            return LogBodyPresentation("TEXT · systemInstruction", role + parts.joinToString("\n\n") { it.jsonObject.getValue("text").jsonPrimitive.content })
        }
    }
    return when {
        parsed != null -> LogBodyPresentation("JSON", JsonInstantPretty.encodeToString(JsonElement.serializer(), parsed), parsed)
        body.trimStart().startsWith('<') && '>' in body -> LogBodyPresentation("HTML/XML", body.replace(Regex(">\\s*<"), ">\n<"))
        body.lineSequence().any { it.startsWith("data:") || it.startsWith("event:") } -> LogBodyPresentation("SSE", body)
        else -> LogBodyPresentation("TEXT", body)
    }
}

@Composable
internal fun LogBodyContent(label: String, body: String, systemInstruction: Boolean = false) {
    val long = remember(body) { isLongDisplayText(body) }
    var expanded by rememberSaveable { mutableStateOf(false) }
    val presentation by produceState<LogBodyPresentation?>(null, body, systemInstruction) {
        value = withContext(Dispatchers.Default) { presentLogBody(body, systemInstruction) }
    }
    val rich = richContentColors()
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Text("${presentation?.format ?: "…"} · ${body.length} 字符", style = MaterialTheme.typography.labelSmall)
            }
            TextButton(onClick = { scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(label, body))) } }) { Text("复制") }
            if (long) TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "收起" else "展开") }
        }
        Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium,
            color = rich.container, contentColor = MaterialTheme.colorScheme.onSurface,
            border = BorderStroke(1.dp, rich.border), tonalElevation = 0.dp) {
            when {
                long && !expanded -> Text(
                    (presentation?.text ?: body).take(400), Modifier.padding(12.dp),
                    maxLines = 4, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall, fontFamily = JetbrainsMono,
                )
                presentation?.json != null && !long -> JsonTree(presentation!!.json!!, Modifier.padding(8.dp), initialExpandLevel = 2)
                long -> LongTextContent(presentation?.text ?: body, Modifier.fillMaxWidth().height(360.dp))
                else -> Text(presentation?.text ?: body, Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodySmall, fontFamily = JetbrainsMono)
            }
        }
    }
}
