package me.rerere.rikkahub.ui.pages.chat

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.mapSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.model.*
import me.rerere.rikkahub.ui.components.ui.AppearanceAlertDialog
import me.rerere.rikkahub.utils.JsonInstant

internal val InspirationCardStateSaver = Saver<InspirationCard?, String>(
    save = { card -> card?.let { JsonInstant.encodeToString(InspirationCard.serializer(), it) } ?: "" },
    restore = { encoded -> if (encoded.isBlank()) null else runCatching { JsonInstant.decodeFromString<InspirationCard>(encoded) }.getOrNull() },
)

private val InspirationAnswersSaver = mapSaver<Map<String, String>>(save = { it },
    restore = { values -> values.mapValues { it.value as String } })

@Composable
internal fun InspirationDraftDialog(
    card: InspirationCard,
    automatic: Map<String, String>,
    readDraft: () -> String,
    onInsert: (String, InspirationInsert, String) -> Boolean,
    onDismiss: () -> Unit,
) {
    val template = remember(card) { card.template() }
    val names = remember(template, automatic) { template.placeholderNames().filterNot(automatic::containsKey) }
    var values by rememberSaveable(card.id, stateSaver = InspirationAnswersSaver) { mutableStateOf<Map<String, String>>(emptyMap()) }
    var reviewedDraft by remember(card.id) { mutableStateOf(readDraft()) }
    var conflict by remember { mutableStateOf(false) }
    val ready = names.size <= 12 && names.all { !values[it].isNullOrBlank() }
    val rendered = remember(template, values, automatic) { template.render(automatic + values) }
    val currentDraft = readDraft()
    fun insert(mode: InspirationInsert) {
        if (onInsert(rendered, mode, reviewedDraft)) onDismiss()
        else { reviewedDraft = readDraft(); conflict = true }
    }
    AppearanceAlertDialog(onDismissRequest = onDismiss, title = { Text(card.title) }, text = {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.inspiration_draft_hint), style = MaterialTheme.typography.bodySmall)
            if (names.size > 12) Text(stringResource(R.string.inspiration_variable_limit), color = MaterialTheme.colorScheme.error)
            else names.forEach { name ->
                OutlinedTextField(value = values[name].orEmpty(), onValueChange = { values = values + (name to it.take(2000)) },
                    label = { Text(name) }, modifier = Modifier.fillMaxWidth(), maxLines = 4)
            }
            Text(stringResource(R.string.inspiration_preview), style = MaterialTheme.typography.labelLarge)
            Text(rendered, style = MaterialTheme.typography.bodyMedium)
            if (currentDraft.isNotBlank()) {
                Text(stringResource(R.string.inspiration_existing_draft), style = MaterialTheme.typography.labelLarge)
                Text(currentDraft.take(500), maxLines = 4, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            }
            if (conflict) Text(stringResource(R.string.inspiration_draft_changed), color = MaterialTheme.colorScheme.error)
        }
    }, confirmButton = {
        if (currentDraft.isBlank()) TextButton(onClick = { insert(InspirationInsert.AUTO) }, enabled = ready) {
            Text(stringResource(R.string.inspiration_insert))
        } else {
            TextButton(onClick = { insert(InspirationInsert.APPEND) }, enabled = ready) { Text(stringResource(R.string.inspiration_append)) }
            TextButton(onClick = { insert(InspirationInsert.REPLACE) }, enabled = ready) { Text(stringResource(R.string.inspiration_replace)) }
        }
    }, dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } })
}
