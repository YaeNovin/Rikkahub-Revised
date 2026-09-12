package me.rerere.rikkahub.ui.components.message

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import me.rerere.ai.ui.AskUserProtocol
import me.rerere.rikkahub.ui.components.richtext.richContentColors

@Composable
internal fun AskUserOptions(question: AskUserProtocol.Question, selected: Set<String>, onSelect: (String) -> Unit) {
    if (question.options.isEmpty()) return
    val layout = me.rerere.ai.ui.AskUserContract.presentationFor(question)
    var search by remember(question.id, question.options) { mutableStateOf("") }
    val options = question.options.filter { value ->
        search.isBlank() || value.contains(search, true) || question.optionDetails.firstOrNull { it.value == value }?.label?.contains(search, true) == true
    }
    fun enabled(value: String) = question.selectionType != AskUserProtocol.SelectionType.MULTI || value in selected || selected.size < (question.maxItems ?: Int.MAX_VALUE)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (question.options.size > 8) OutlinedTextField(search, { search = it }, modifier = Modifier.fillMaxWidth(),
            singleLine = true, placeholder = { Text("搜索选项") })
        Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (layout == "chips") {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                options.forEach { value -> FilterChip(selected = value in selected, enabled = enabled(value),
                    onClick = { onSelect(value) }, label = { AskUserOptionLabel(question, value) }) }
            }
        } else {
            val colors = richContentColors()
            options.forEach { value ->
                Surface(onClick = { onSelect(value) }, enabled = enabled(value), modifier = Modifier.fillMaxWidth(),
                    shape = if (layout == "cards") MaterialTheme.shapes.medium else MaterialTheme.shapes.small,
                    color = if (value in selected) MaterialTheme.colorScheme.secondaryContainer else colors.container,
                    border = BorderStroke(1.dp, if (value in selected) MaterialTheme.colorScheme.primary else colors.border)) {
                    Row(Modifier.padding(if (layout == "cards") 12.dp else 6.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (question.selectionType == AskUserProtocol.SelectionType.MULTI) Checkbox(value in selected, null, enabled = enabled(value))
                        else RadioButton(value in selected, null, enabled = enabled(value))
                        Box(Modifier.weight(1f)) { AskUserOptionLabel(question, value) }
                    }
                }
            }
        }
        }
        if (options.isEmpty()) Text("没有匹配的选项", style = MaterialTheme.typography.bodySmall)
    }
}
