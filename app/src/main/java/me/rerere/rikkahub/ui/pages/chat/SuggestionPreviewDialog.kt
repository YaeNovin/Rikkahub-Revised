package me.rerere.rikkahub.ui.pages.chat

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.*
import me.rerere.ai.ui.AskUserProtocol
import me.rerere.ai.ui.AskUserNumbers
import me.rerere.rikkahub.data.model.*
import me.rerere.rikkahub.ui.components.message.AskUserOptions
import me.rerere.rikkahub.ui.components.ui.AppearanceAlertDialog
import java.util.Calendar
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SuggestionPreviewDialog(item: ChatSuggestionItem, initialText: String,
    onInsert: (String, SuggestionInsertLocation) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val request = remember(item) { item.parameterForm?.let { AskUserProtocol.parseRequest(it).getOrNull() } }
    val answers = remember(item.id) { mutableStateMapOf<String, JsonElement>().also { map ->
        request?.questions?.forEach { question ->
            question.defaultValue?.let { map[question.id] = JsonPrimitive(it) }
            if (question.defaultValues.isNotEmpty()) map[question.id] = JsonArray(question.defaultValues.map(::JsonPrimitive))
        }
    } }
    var preview by remember(item.id) { mutableStateOf(initialText) }
    var filled by remember(item.id) { mutableStateOf(request == null) }
    var validationError by remember { mutableStateOf<String?>(null) }
    AppearanceAlertDialog(onDismissRequest = onDismiss, title = { Text(if (item.action == ChatSuggestionAction.IMAGE_DRAFT) "图片提示词草稿" else "建议预览") }, text = {
        Column(Modifier.fillMaxWidth().heightIn(max = 500.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!filled && request != null) {
                val visibleAnswers = AskUserProtocol.visibleAnswers(request, answers)
                val visibleIds = request.questions.filter { AskUserProtocol.isQuestionVisible(it, visibleAnswers) }.map { it.id }.toSet()
                LaunchedEffect(visibleIds) { answers.keys.toList().filterNot(visibleIds::contains).forEach(answers::remove) }
                request.questions.filter { AskUserProtocol.isQuestionVisible(it, visibleAnswers) }.forEach { question -> key(question.id) {
                    Text(question.question, style = MaterialTheme.typography.titleSmall)
                    if (question.description.isNotBlank()) Text(question.description, style = MaterialTheme.typography.bodySmall)
                    when (question.selectionType) {
                        AskUserProtocol.SelectionType.SINGLE, AskUserProtocol.SelectionType.MULTI -> {
                            val selected = when (val value = answers[question.id]) {
                                is JsonArray -> value.map { it.jsonPrimitive.content }.toSet()
                                is JsonPrimitive -> setOf(value.content)
                                else -> emptySet()
                            }
                            AskUserOptions(question, selected) { value ->
                                answers[question.id] = if (question.selectionType == AskUserProtocol.SelectionType.MULTI) {
                                    JsonArray((if (value in selected) selected - value else selected + value).map(::JsonPrimitive))
                                } else JsonPrimitive(value)
                            }
                        }
                        AskUserProtocol.SelectionType.DATE, AskUserProtocol.SelectionType.TIME -> TextButton(onClick = {
                            val now = Calendar.getInstance()
                            if (question.selectionType == AskUserProtocol.SelectionType.DATE) DatePickerDialog(context, { _, y, m, d ->
                                answers[question.id] = JsonPrimitive(String.format(Locale.US, "%04d-%02d-%02d", y, m + 1, d))
                            }, now.get(Calendar.YEAR), now.get(Calendar.MONTH), now.get(Calendar.DAY_OF_MONTH)).show()
                            else TimePickerDialog(context, { _, h, m -> answers[question.id] = JsonPrimitive(String.format(Locale.US, "%02d:%02d", h, m)) },
                                now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE), true).show()
                        }) { Text((answers[question.id] as? JsonPrimitive)?.content ?: "点击选择") }
                        AskUserProtocol.SelectionType.SLIDER, AskUserProtocol.SelectionType.RATING -> {
                            val min = question.minValue!!; val max = question.maxValue!!; val step = question.step!!
                            val answered = (answers[question.id] as? JsonPrimitive)?.content?.toDoubleOrNull()
                            val value = answered ?: min
                            if (question.selectionType == AskUserProtocol.SelectionType.RATING) FlowRow {
                                repeat(((max - min) / step).toInt() + 1) { index ->
                                    val score = min + index * step
                                    TextButton(onClick = { answers[question.id] = JsonPrimitive(AskUserNumbers.format(score)) }) { Text(if (answered != null && score <= value) "★" else "☆") }
                                }
                            } else Slider(((value - min) / (max - min)).toFloat().coerceIn(0f, 1f),
                                { answers[question.id] = JsonPrimitive(AskUserNumbers.snapFraction(min, max, step, it)) })
                            OutlinedTextField((answers[question.id] as? JsonPrimitive)?.content.orEmpty(), { answers[question.id] = JsonPrimitive(it) }, label = { Text(question.unit.ifBlank { "精确数值" }) })
                            Text("范围 ${AskUserNumbers.format(min)}～${AskUserNumbers.format(max)}，步长 ${AskUserNumbers.format(step)}", style = MaterialTheme.typography.bodySmall)
                        }
                        else -> OutlinedTextField((answers[question.id] as? JsonPrimitive)?.content.orEmpty(),
                            { answers[question.id] = JsonPrimitive(it.take(question.maxLength ?: 16384)) }, placeholder = { Text(question.placeholder) }, modifier = Modifier.fillMaxWidth())
                    }
                } }
                validationError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                TextButton(onClick = {
                    val result = AskUserProtocol.validateAnswer(request, buildJsonObject { put("answers", JsonObject(answers.toMap())) }.toString())
                    result.onSuccess { encoded ->
                        val values = Json.parseToJsonElement(encoded).jsonObject["answers"]!!.jsonObject.mapValues { (_, value) ->
                            if (value is JsonArray) value.joinToString("、") { it.jsonPrimitive.content } else value.jsonPrimitive.content
                        }
                        preview = QuickMessage(title = item.text, content = initialText).render(request.questions.associate { it.id to "" } + values)
                        filled = true; validationError = null
                    }.onFailure { validationError = "请检查必填项、范围和选项：${it.message}" }
                }) { Text("填写完成，预览草稿") }
            } else {
                OutlinedTextField(preview, { preview = it }, modifier = Modifier.fillMaxWidth(), minLines = 4, label = { Text("实际插入内容") })
                if (request != null) TextButton(onClick = { filled = false }) { Text("重新填写参数") }
                Text("插入后可继续编辑，不会自动发送。", style = MaterialTheme.typography.bodySmall)
            }
        }
    }, confirmButton = {
        FlowRow {
            SuggestionInsertLocation.entries.forEach { location -> TextButton(onClick = { onInsert(preview, location); onDismiss() }, enabled = filled && preview.isNotBlank()) {
                Text(when(location) { SuggestionInsertLocation.APPEND -> "追加"; SuggestionInsertLocation.REPLACE -> "替换"; SuggestionInsertLocation.CURSOR -> "光标处" })
            } }
        }
    }, dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}
