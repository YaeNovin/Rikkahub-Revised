package me.rerere.rikkahub.ui.pages.chat

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.getAssistantById
import me.rerere.rikkahub.data.model.*
import me.rerere.rikkahub.ui.components.ui.AppearanceAlertDialog
import me.rerere.rikkahub.ui.components.ui.AppearanceModalBottomSheet
import org.koin.compose.koinInject
import kotlin.math.roundToInt
import kotlin.uuid.Uuid

@Composable
fun InspirationSettingsEntry(assistantId: Uuid? = null) {
    var open by rememberSaveable(assistantId?.toString()) { mutableStateOf(false) }
    TextButton(onClick = { open = true }) { Text(stringResource(R.string.inspiration_manage)) }
    if (open) InspirationSettingsSheet(assistantId, { open = false })
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun InspirationSettingsSheet(assistantId: Uuid?, onDismiss: () -> Unit) {
    val store = koinInject<SettingsStore>()
    val settings by store.settingsFlow.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val sessionKey = rememberSaveable { Uuid.random().toString() }
    val editor = androidx.lifecycle.viewmodel.compose.viewModel<InspirationEditorState>(key = "inspiration-editor:${assistantId ?: "global"}") {
        InspirationEditorState()
    }
    remember(sessionKey) {
        editor.begin(sessionKey, settings.inspirationSettings(assistantId),
            assistantId != null && settings.getAssistantById(assistantId)?.inspirationSettings == null)
    }
    var saving by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val catalog = inspirationCatalog()
    val displayed = (if (editor.draft.includeBuiltIns) catalog else emptyList()) + editor.draft.customCards
    val maxHeight = (LocalConfiguration.current.screenHeightDp * .88f).dp
    AppearanceModalBottomSheet(onDismissRequest = { if (!saving) onDismiss() }) {
        Column(Modifier.fillMaxWidth().heightIn(max = maxHeight).imePadding().padding(horizontal = 16.dp)) {
            Text(stringResource(R.string.inspiration_manage), style = MaterialTheme.typography.titleLarge)
            Text(if (assistantId == null) stringResource(R.string.inspiration_global_scope)
                else stringResource(R.string.inspiration_assistant_scope, settings.getAssistantById(assistantId)?.name.orEmpty()),
                style = MaterialTheme.typography.bodySmall)
            LazyColumn(Modifier.weight(1f, fill = false).fillMaxWidth(), contentPadding = PaddingValues(vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (assistantId != null) item {
                    InspirationSwitch(stringResource(R.string.inspiration_inherit), editor.inherited, !saving) { value ->
                        editor.inherited = value
                        if (value) editor.draft = settings.inspirationSettings(null)
                    }
                }
                item {
                    Text(stringResource(R.string.inspiration_count, editor.draft.cardCount))
                    Slider(value = editor.draft.cardCount.toFloat(), onValueChange = { editor.draft = editor.draft.copy(cardCount = it.roundToInt()) },
                        valueRange = 1f..8f, steps = 6, enabled = !editor.inherited && !saving)
                    InspirationSwitch(stringResource(R.string.inspiration_builtins), editor.draft.includeBuiltIns, !editor.inherited && !saving) {
                        editor.draft = editor.draft.copy(includeBuiltIns = it)
                    }
                    Text(stringResource(R.string.inspiration_mode_hint), style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { editor.editing = InspirationCard() }, enabled = !editor.inherited && !saving && editor.draft.customCards.size < 40) {
                        Text(stringResource(R.string.inspiration_add))
                    }
                }
                items(displayed, key = { it.id }) { card ->
                    InspirationSurface(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(card.title, style = MaterialTheme.typography.titleSmall)
                            Text(inspirationAudienceLabel(card.audience), style = MaterialTheme.typography.labelSmall)
                            Text(card.prompt, maxLines = 3, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodySmall)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                TextButton(onClick = { editor.draft = editor.draft.togglePin(card.id) }, enabled = !editor.inherited && !saving) {
                                    Text(stringResource(if (card.id in editor.draft.pinnedIds) R.string.inspiration_unpin else R.string.inspiration_pin))
                                }
                                TextButton(onClick = { editor.editing = if (card.id.startsWith("custom:")) card else card.copy(id = "custom:${Uuid.random()}") },
                                    enabled = !editor.inherited && !saving && (card.id.startsWith("custom:") || editor.draft.customCards.size < 40)) {
                                    Text(stringResource(if (card.id.startsWith("custom:")) R.string.inspiration_edit else R.string.inspiration_customize))
                                }
                                if (card.id.startsWith("custom:")) TextButton(onClick = { editor.deleting = card }, enabled = !editor.inherited && !saving) {
                                    Text(stringResource(R.string.inspiration_delete))
                                }
                            }
                        }
                    }
                }
                if (displayed.isEmpty()) item { Text(stringResource(R.string.inspiration_empty)) }
            }
            if (failed) Text(stringResource(R.string.inspiration_save_failed), color = MaterialTheme.colorScheme.error)
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss, enabled = !saving) { Text(stringResource(R.string.cancel)) }
                TextButton(onClick = {
                    scope.launch {
                        saving = true; failed = false
                        try {
                            store.update { current ->
                                if (assistantId != null && current.getAssistantById(assistantId) == null) error("Assistant removed")
                                if (editor.inherited && assistantId != null) current.copy(assistants = current.assistants.map {
                                    if (it.id == assistantId) it.copy(inspirationSettings = null) else it
                                }) else current.withInspirationSettings(assistantId) { editor.draft }
                            }
                            onDismiss()
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { failed = true }
                        finally { saving = false }
                    }
                }, enabled = !saving && !settings.init) { Text(stringResource(R.string.confirm)) }
            }
        }
    }
    editor.editing?.let { original ->
        InspirationCardEditor(original, onDismiss = { editor.editing = null }, onSave = { card ->
            editor.draft = editor.draft.copy(customCards = if (editor.draft.customCards.any { it.id == card.id })
                editor.draft.customCards.map { if (it.id == card.id) card else it } else editor.draft.customCards + card).normalized()
            editor.editing = null
        })
    }
    editor.deleting?.let { card -> AppearanceAlertDialog(onDismissRequest = { editor.deleting = null },
        title = { Text(stringResource(R.string.inspiration_delete)) }, text = { Text(card.title) },
        confirmButton = { TextButton(onClick = { editor.draft = editor.draft.removeCard(card.id); editor.deleting = null }) { Text(stringResource(R.string.confirm)) } },
        dismissButton = { TextButton(onClick = { editor.deleting = null }) { Text(stringResource(R.string.cancel)) } }) }
}

@Composable
internal fun InspirationSwitch(label: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, Modifier.weight(1f))
        Switch(checked, onChange, enabled = enabled)
    }
}

@Composable
internal fun inspirationAudienceLabel(audience: InspirationAudience) = stringResource(when (audience) {
    InspirationAudience.ALL -> R.string.inspiration_audience_all
    InspirationAudience.NORMAL -> R.string.inspiration_audience_normal
    InspirationAudience.ENTERTAINMENT -> R.string.inspiration_audience_entertainment
})

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun InspirationCardEditor(original: InspirationCard, onDismiss: () -> Unit, onSave: (InspirationCard) -> Unit) {
    var title by rememberSaveable(original.id) { mutableStateOf(original.title) }
    var prompt by rememberSaveable(original.id) { mutableStateOf(original.prompt) }
    var audience by rememberSaveable(original.id) { mutableStateOf(original.audience) }
    val variables = remember(prompt) { QuickMessage(content = prompt).placeholderNames() }
    AppearanceAlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.inspiration_edit)) }, text = {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(title, { title = it.take(80) }, label = { Text(stringResource(R.string.inspiration_card_title)) }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(prompt, { prompt = it.take(8000) }, label = { Text(stringResource(R.string.inspiration_card_prompt)) },
                modifier = Modifier.fillMaxWidth(), minLines = 3, maxLines = 8)
            Text(stringResource(R.string.inspiration_variables_hint), style = MaterialTheme.typography.bodySmall)
            if (variables.size > 12) Text(stringResource(R.string.inspiration_variable_limit), color = MaterialTheme.colorScheme.error)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                InspirationAudience.entries.forEach { option ->
                    FilterChip(selected = audience == option, onClick = { audience = option }, label = { Text(inspirationAudienceLabel(option)) })
                }
            }
        }
    }, confirmButton = { TextButton(onClick = { onSave(original.copy(title = title.trim(), prompt = prompt, audience = audience)) },
        enabled = title.isNotBlank() && prompt.isNotBlank() && variables.size <= 12) { Text(stringResource(R.string.confirm)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } })
}
