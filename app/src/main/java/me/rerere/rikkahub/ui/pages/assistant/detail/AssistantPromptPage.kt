package me.rerere.rikkahub.ui.pages.assistant.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SecondaryScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.ArrowDown01
import me.rerere.hugeicons.stroke.ArrowUp01
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.Refresh03
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.transformers.AssistantPromptPreview
import me.rerere.rikkahub.data.ai.transformers.PromptTemplateCatalog
import me.rerere.rikkahub.data.ai.transformers.PromptTemplateDescriptor
import me.rerere.rikkahub.data.ai.transformers.PromptVariableScope
import me.rerere.rikkahub.data.ai.transformers.TemplateTransformer
import me.rerere.rikkahub.data.ai.transformers.buildAssistantPromptPreview
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AssistantAffectScope
import me.rerere.rikkahub.data.model.AssistantRegex
import me.rerere.rikkahub.data.model.normalizeAssistantRegexes
import me.rerere.rikkahub.data.model.normalizePresetMessages
import me.rerere.rikkahub.data.model.testAssistantRegex
import me.rerere.rikkahub.data.model.validateAssistantRegex
import me.rerere.rikkahub.data.model.withPresetText
import me.rerere.rikkahub.service.formatUserFacingError
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.FormItem
import me.rerere.rikkahub.ui.components.ui.Select
import me.rerere.rikkahub.ui.components.ui.TextArea
import me.rerere.rikkahub.ui.components.ui.PromptVariableReference
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.ui.theme.JetbrainsMono
import me.rerere.rikkahub.utils.UiState
import me.rerere.rikkahub.utils.insertAtCursor
import me.rerere.rikkahub.utils.onError
import me.rerere.rikkahub.utils.onLoading
import me.rerere.rikkahub.utils.onSuccess
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import org.koin.core.parameter.parametersOf
import kotlin.uuid.Uuid

private const val PROMPT_SAVE_DEBOUNCE_MS = 450L

@Composable
fun AssistantPromptPage(id: String) {
    val vm: AssistantDetailVM = koinViewModel(parameters = { parametersOf(id) })
    val assistant by vm.assistant.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val workspaces by vm.workspaces.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.assistant_page_tab_prompt)) },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        if (settings.assistants.none { it.id == assistant.id }) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
        } else {
            AssistantPromptEditor(
                innerPadding = innerPadding,
                assistant = assistant,
                settings = settings,
                workspaces = workspaces,
                onSave = vm::updatePromptSettings,
            )
        }
    }
}

@Composable
private fun AssistantPromptEditor(
    innerPadding: PaddingValues,
    assistant: Assistant,
    settings: Settings,
    workspaces: List<me.rerere.rikkahub.data.db.entity.WorkspaceEntity>,
    onSave: (Assistant) -> Unit,
) {
    val templateTransformer = koinInject<TemplateTransformer>()
    var draft by remember(assistant.id) { mutableStateOf(assistant.normalizePromptEditorIdentity()) }
    var lastPersisted by remember(assistant.id) { mutableStateOf(assistant) }
    val latestDraft by rememberUpdatedState(draft)
    val latestPersisted by rememberUpdatedState(lastPersisted)
    val latestOnSave by rememberUpdatedState(onSave)
    val workspace = remember(assistant.workspaceId, workspaces) {
        workspaces.firstOrNull { it.id == assistant.workspaceId?.toString() }
    }

    LaunchedEffect(assistant) {
        if (draft == lastPersisted) draft = assistant
        lastPersisted = assistant
    }
    LaunchedEffect(draft, lastPersisted) {
        if (draft == lastPersisted) return@LaunchedEffect
        delay(PROMPT_SAVE_DEBOUNCE_MS)
        val persistable = draft.sanitizePromptDraft(lastPersisted, templateTransformer)
        if (persistable != lastPersisted) onSave(persistable)
    }
    DisposableEffect(assistant.id) {
        val editingAssistantId = assistant.id
        onDispose {
            val pending = latestDraft
            val persisted = latestPersisted
            if (pending.id == editingAssistantId && pending != persisted) {
                val persistable = pending.sanitizePromptDraft(persisted, templateTransformer)
                if (persistable != persisted) latestOnSave(persistable)
            }
        }
    }

    val pagerState = rememberPagerState { 4 }
    val scope = rememberCoroutineScope()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(innerPadding),
    ) {
        SecondaryScrollableTabRow(
            selectedTabIndex = pagerState.currentPage,
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ) {
            PromptTab.entries.forEachIndexed { index, tab ->
                Tab(
                    selected = pagerState.currentPage == index,
                    onClick = { scope.launch { pagerState.animateScrollToPage(index) } },
                    text = { Text(stringResource(tab.labelRes)) },
                )
            }
        }
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) { page ->
            when (PromptTab.entries[page]) {
                PromptTab.SYSTEM -> SystemPromptTab(draft, settings, workspace) { draft = it }
                PromptTab.TEMPLATE -> MessageTemplateTab(draft, templateTransformer) { draft = it }
                PromptTab.PRESETS -> PresetMessagesTab(draft) { draft = it }
                PromptTab.REGEX -> RegexTab(draft) { draft = it }
            }
        }
    }
}

private enum class PromptTab(val labelRes: Int) {
    SYSTEM(R.string.assistant_prompt_tab_system),
    TEMPLATE(R.string.assistant_prompt_tab_template),
    PRESETS(R.string.assistant_prompt_tab_presets),
    REGEX(R.string.assistant_prompt_tab_regex),
}

private fun Assistant.sanitizePromptDraft(
    persisted: Assistant,
    templateTransformer: TemplateTransformer,
): Assistant {
    val persistedRegexes = persisted.regexes.associateBy { it.id }
    return copy(
        messageTemplate = if (templateTransformer.validate(messageTemplate).isValid) {
            messageTemplate
        } else {
            persisted.messageTemplate
        },
        regexes = regexes.mapNotNull { regex ->
            if (!regex.enabled || validateAssistantRegex(regex).isValid) {
                regex
            } else {
                persistedRegexes[regex.id]
            }
        },
    )
}

private fun Assistant.normalizePromptEditorIdentity(): Assistant = copy(
    presetMessages = normalizePresetMessages(presetMessages),
    regexes = normalizeAssistantRegexes(regexes),
)

@Composable
private fun SystemPromptTab(
    assistant: Assistant,
    settings: Settings,
    workspace: me.rerere.rikkahub.data.db.entity.WorkspaceEntity?,
    onUpdate: (Assistant) -> Unit,
) {
    val latestAssistant by rememberUpdatedState(assistant)
    val latestOnUpdate by rememberUpdatedState(onUpdate)
    val systemPromptState = rememberTextFieldState(assistant.systemPrompt)
    LaunchedEffect(assistant.id, assistant.systemPrompt) {
        if (systemPromptState.text.toString() != assistant.systemPrompt) {
            systemPromptState.setTextAndPlaceCursorAtEnd(assistant.systemPrompt)
        }
    }
    LaunchedEffect(assistant.id, systemPromptState) {
        snapshotFlow { systemPromptState.text.toString() }
            .distinctUntilChanged()
            .collect { text ->
                val current = latestAssistant
                if (text != current.systemPrompt) {
                    latestOnUpdate(current.copy(systemPrompt = text))
                }
            }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .imePadding(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Card(colors = CustomColors.cardColorsOnSurfaceContainer) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TextArea(
                        state = systemPromptState,
                        label = stringResource(R.string.assistant_page_system_prompt),
                        minLines = 6,
                        maxLines = 14,
                    )
                    PromptTemplatePicker(
                        onTemplateSelected = { template ->
                            systemPromptState.insertAtCursor(template.content)
                        },
                    )
                }
            }
        }
        item {
            Card(colors = CustomColors.cardColorsOnSurfaceContainer) {
                PromptVariableReference(
                    scope = PromptVariableScope.ASSISTANT_SYSTEM,
                    modifier = Modifier.padding(16.dp),
                    onInsert = { systemPromptState.insertAtCursor(it) },
                )
            }
        }
        item {
            Card(colors = CustomColors.cardColorsOnSurfaceContainer) {
                Column {
                    PromptSwitchItem(
                        label = stringResource(R.string.assistant_page_allow_conversation_system_prompt),
                        description = stringResource(R.string.assistant_page_allow_conversation_system_prompt_desc),
                        checked = assistant.allowConversationSystemPrompt,
                        onCheckedChange = {
                            onUpdate(assistant.copy(allowConversationSystemPrompt = it))
                        },
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    PromptSwitchItem(
                        label = stringResource(R.string.assistant_page_allow_conversation_prompt_injection),
                        description = stringResource(R.string.assistant_page_allow_conversation_prompt_injection_desc),
                        checked = assistant.allowConversationPromptInjection,
                        onCheckedChange = {
                            onUpdate(assistant.copy(allowConversationPromptInjection = it))
                        },
                    )
                }
            }
        }
        item {
            ActualPromptPreviewCard(
                assistant = assistant,
                settings = settings,
                workspace = workspace,
            )
        }
    }
}

@Composable
private fun PromptSwitchItem(
    label: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    FormItem(
        modifier = Modifier.padding(8.dp),
        label = { Text(label) },
        description = { Text(description) },
        tail = {
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
            )
        },
    )
}

@Composable
private fun PromptTemplatePicker(
    onTemplateSelected: (PromptTemplateDescriptor) -> Unit,
) {
    Select(
        options = PromptTemplateCatalog.all,
        selectedOption = PromptTemplateCatalog.all.first(),
        onOptionSelected = { selected ->
            if (selected.id != PromptTemplateDescriptor.Id.NONE) {
                onTemplateSelected(selected)
            }
        },
        optionToString = { template -> stringResource(template.labelRes) },
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun ActualPromptPreviewCard(
    assistant: Assistant,
    settings: Settings,
    workspace: me.rerere.rikkahub.data.db.entity.WorkspaceEntity?,
) {
    val context = LocalContext.current
    val transformer = koinInject<TemplateTransformer>()
    val defaultInput = stringResource(R.string.assistant_prompt_preview_default_input)
    var sampleInput by remember(assistant.id, defaultInput) { mutableStateOf(defaultInput) }
    val model = settings.findModelById(assistant.chatModelId ?: settings.chatModelId)
    val preview by produceState<UiState<AssistantPromptPreview>>(
        initialValue = UiState.Loading,
        assistant,
        settings,
        model,
        sampleInput,
        workspace,
    ) {
        delay(200)
        value = if (model == null) {
            UiState.Error(IllegalStateException(context.getString(R.string.assistant_prompt_preview_no_model)))
        } else {
            runCatching {
                buildAssistantPromptPreview(
                    context = context,
                    model = model,
                    assistant = assistant,
                    settings = settings,
                    sampleUserInput = sampleInput,
                    templateTransformer = transformer,
                    workspace = workspace,
                )
            }.fold(
                onSuccess = { UiState.Success(it) },
                onFailure = { UiState.Error(it) },
            )
        }
    }

    Card(colors = CustomColors.cardColorsOnSurfaceContainer) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = stringResource(R.string.assistant_prompt_preview_title),
                style = MaterialTheme.typography.titleMedium,
            )
            OutlinedTextField(
                value = sampleInput,
                onValueChange = { sampleInput = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.assistant_prompt_preview_input)) },
                minLines = 2,
                maxLines = 5,
            )
            model?.let {
                Text(
                    text = stringResource(R.string.assistant_prompt_preview_model, it.displayName),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            preview.onLoading {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
            }.onError { error ->
                Text(
                    text = context.formatUserFacingError(error),
                    color = MaterialTheme.colorScheme.error,
                )
            }.onSuccess { result ->
                Text(
                    text = stringResource(
                        R.string.assistant_prompt_preview_estimated_tokens,
                        result.estimatedTokens,
                    ),
                    style = MaterialTheme.typography.labelMedium,
                )
                result.messages.forEach { message ->
                    PreviewMessage(message)
                }
            }
            Text(
                text = stringResource(R.string.assistant_prompt_preview_runtime_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun PreviewMessage(message: UIMessage) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.small,
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = messageRoleLabel(message.role),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            SelectionContainer {
                Text(
                    text = message.toText(),
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = JetbrainsMono,
                        lineHeight = 17.sp,
                    ),
                )
            }
        }
    }
}

@Composable
private fun MessageTemplateTab(
    assistant: Assistant,
    transformer: TemplateTransformer,
    onUpdate: (Assistant) -> Unit,
) {
    var editorValue by remember(assistant.id) {
        mutableStateOf(
            TextFieldValue(
                text = assistant.messageTemplate,
                selection = TextRange(assistant.messageTemplate.length),
            )
        )
    }
    LaunchedEffect(assistant.id, assistant.messageTemplate) {
        if (editorValue.text != assistant.messageTemplate) {
            editorValue = TextFieldValue(
                text = assistant.messageTemplate,
                selection = TextRange(assistant.messageTemplate.length),
            )
        }
    }
    val validation = remember(assistant.messageTemplate) {
        transformer.validate(assistant.messageTemplate)
    }
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .imePadding(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Card(colors = CustomColors.cardColorsOnSurfaceContainer) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.assistant_page_message_template),
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(
                                text = stringResource(R.string.assistant_page_message_template_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(
                            onClick = {
                                val reset = "{{ message }}"
                                editorValue = TextFieldValue(reset, TextRange(reset.length))
                                onUpdate(assistant.copy(messageTemplate = reset))
                            },
                            enabled = assistant.messageTemplate != "{{ message }}",
                        ) {
                            Icon(HugeIcons.Refresh03, stringResource(R.string.assistant_prompt_template_reset))
                        }
                    }
                    OutlinedTextField(
                        value = editorValue,
                        onValueChange = {
                            editorValue = it
                            onUpdate(assistant.copy(messageTemplate = it.text))
                        },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 8,
                        maxLines = 18,
                        isError = !validation.isValid,
                        supportingText = if (validation.errorMessage != null) {
                            {
                                Text(
                                    stringResource(
                                        R.string.assistant_prompt_template_invalid,
                                        validation.errorMessage,
                                    )
                                )
                            }
                        } else if (!validation.preservesMessage) {
                            { Text(stringResource(R.string.assistant_page_message_template_missing_message)) }
                        } else {
                            { Text(stringResource(R.string.assistant_prompt_template_valid)) }
                        },
                        textStyle = LocalTextStyle.current.copy(
                            fontSize = 12.sp,
                            fontFamily = JetbrainsMono,
                            lineHeight = 17.sp,
                        ),
                    )
                }
            }
        }
        item {
            Card(colors = CustomColors.cardColorsOnSurfaceContainer) {
                PromptVariableReference(
                    scope = PromptVariableScope.MESSAGE_TEMPLATE,
                    modifier = Modifier.padding(16.dp),
                    onInsert = { token ->
                        val selection = editorValue.selection
                        val start = selection.min.coerceIn(0, editorValue.text.length)
                        val end = selection.max.coerceIn(start, editorValue.text.length)
                        val text = editorValue.text.replaceRange(start, end, token)
                        val cursor = start + token.length
                        editorValue = TextFieldValue(text, TextRange(cursor))
                        onUpdate(assistant.copy(messageTemplate = text))
                    },
                )
            }
        }
    }
}

@Composable
private fun PresetMessagesTab(
    assistant: Assistant,
    onUpdate: (Assistant) -> Unit,
) {
    val emptyCount = remember(assistant.presetMessages) {
        assistant.presetMessages.count { !it.isValidToUpload() }
    }
    val consecutiveRoleCount = remember(assistant.presetMessages) {
        assistant.presetMessages.zipWithNext().count { (previous, current) ->
            previous.role == current.role
        }
    }
    val presetKeys = remember(assistant.presetMessages) {
        duplicateSafeKeys(assistant.presetMessages) { it.id }
    }
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .imePadding(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(
                text = stringResource(R.string.assistant_page_preset_messages_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (emptyCount > 0) {
            item {
                Text(
                    text = stringResource(R.string.assistant_prompt_preset_empty_warning, emptyCount),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
        }
        if (consecutiveRoleCount > 0) {
            item {
                Text(
                    text = stringResource(R.string.assistant_prompt_preset_role_warning),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
        }
        itemsIndexed(
            items = assistant.presetMessages,
            key = { index, _ -> presetKeys[index] },
        ) { index, message ->
            PresetMessageCard(
                message = message,
                canMoveUp = index > 0,
                canMoveDown = index < assistant.presetMessages.lastIndex,
                onMoveUp = {
                    onUpdate(assistant.copy(presetMessages = assistant.presetMessages.move(index, index - 1)))
                },
                onMoveDown = {
                    onUpdate(assistant.copy(presetMessages = assistant.presetMessages.move(index, index + 1)))
                },
                onRoleChange = { role ->
                    onUpdate(
                        assistant.copy(
                            presetMessages = assistant.presetMessages.replaceAt(index, message.copy(role = role))
                        )
                    )
                },
                onTextChange = { text ->
                    onUpdate(
                        assistant.copy(
                            presetMessages = assistant.presetMessages.replaceAt(
                                index,
                                message.withPresetText(text),
                            )
                        )
                    )
                },
                onDelete = {
                    onUpdate(
                        assistant.copy(
                            presetMessages = assistant.presetMessages.filterIndexed { i, _ -> i != index }
                        )
                    )
                },
            )
        }
        item {
            Button(
                onClick = {
                    val nextRole = if (assistant.presetMessages.lastOrNull()?.role == MessageRole.USER) {
                        MessageRole.ASSISTANT
                    } else {
                        MessageRole.USER
                    }
                    onUpdate(
                        assistant.copy(
                            presetMessages = assistant.presetMessages + UIMessage(
                                role = nextRole,
                                parts = listOf(UIMessagePart.Text("")),
                            )
                        )
                    )
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(HugeIcons.Add01, null)
                Text(stringResource(R.string.assistant_prompt_add_preset))
            }
        }
    }
}

@Composable
private fun PresetMessageCard(
    message: UIMessage,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onRoleChange: (MessageRole) -> Unit,
    onTextChange: (String) -> Unit,
    onDelete: () -> Unit,
) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Select(
                    options = listOf(MessageRole.USER, MessageRole.ASSISTANT),
                    selectedOption = message.role,
                    onOptionSelected = onRoleChange,
                    optionToString = { messageRoleLabel(it) },
                    modifier = Modifier.width(150.dp),
                )
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onMoveUp, enabled = canMoveUp) {
                    Icon(HugeIcons.ArrowUp01, stringResource(R.string.assistant_prompt_move_up))
                }
                IconButton(onClick = onMoveDown, enabled = canMoveDown) {
                    Icon(HugeIcons.ArrowDown01, stringResource(R.string.assistant_prompt_move_down))
                }
                IconButton(onClick = onDelete) {
                    Icon(HugeIcons.Cancel01, stringResource(R.string.delete))
                }
            }
            OutlinedTextField(
                value = message.toText(),
                onValueChange = onTextChange,
                modifier = Modifier.fillMaxWidth(),
                minLines = 3,
                maxLines = 8,
            )
        }
    }
}

@Composable
private fun RegexTab(
    assistant: Assistant,
    onUpdate: (Assistant) -> Unit,
) {
    val duplicateNames = remember(assistant.regexes) {
        assistant.regexes
            .map { it.name.trim() }
            .filter { it.isNotEmpty() }
            .groupingBy { it }
            .eachCount()
            .filterValues { it > 1 }
            .keys
    }
    val regexKeys = remember(assistant.regexes) {
        duplicateSafeKeys(assistant.regexes) { it.id }
    }
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .imePadding(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(
                text = stringResource(R.string.assistant_page_regex_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        itemsIndexed(
            items = assistant.regexes,
            key = { index, _ -> regexKeys[index] },
        ) { index, regex ->
            AssistantRegexCard(
                regex = regex,
                duplicateName = regex.name.trim() in duplicateNames,
                canMoveUp = index > 0,
                canMoveDown = index < assistant.regexes.lastIndex,
                onUpdate = { updated ->
                    onUpdate(assistant.copy(regexes = assistant.regexes.replaceAt(index, updated)))
                },
                onMoveUp = {
                    onUpdate(assistant.copy(regexes = assistant.regexes.move(index, index - 1)))
                },
                onMoveDown = {
                    onUpdate(assistant.copy(regexes = assistant.regexes.move(index, index + 1)))
                },
                onDelete = {
                    onUpdate(assistant.copy(regexes = assistant.regexes.filterIndexed { i, _ -> i != index }))
                },
            )
        }
        item {
            Button(
                onClick = {
                    onUpdate(
                        assistant.copy(
                            regexes = assistant.regexes + AssistantRegex(
                                id = Uuid.random(),
                                enabled = false,
                                affectingScope = setOf(AssistantAffectScope.ASSISTANT),
                            )
                        )
                    )
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(HugeIcons.Add01, null)
                Text(stringResource(R.string.assistant_prompt_add_regex))
            }
        }
    }
}

@Composable
private fun AssistantRegexCard(
    regex: AssistantRegex,
    duplicateName: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onUpdate: (AssistantRegex) -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onDelete: () -> Unit,
) {
    var expanded by remember(regex.id) { mutableStateOf(false) }
    var testInput by remember(regex.id) { mutableStateOf("") }
    val validation = remember(regex.findRegex, regex.replaceString) {
        validateAssistantRegex(regex)
    }
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = regex.name.ifBlank { stringResource(R.string.assistant_prompt_regex_unnamed) },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f)
                        .widthIn(max = 220.dp),
                )
                Switch(
                    checked = regex.enabled,
                    onCheckedChange = { onUpdate(regex.copy(enabled = it)) },
                )
                IconButton(onClick = { expanded = !expanded }) {
                    Icon(
                        if (expanded) HugeIcons.ArrowUp01 else HugeIcons.ArrowDown01,
                        stringResource(R.string.assistant_prompt_regex_expand),
                    )
                }
            }
            if (!validation.isValid) {
                Text(
                    text = stringResource(
                        R.string.assistant_prompt_regex_invalid,
                        validation.errorMessage.orEmpty(),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            } else if (regex.affectingScope.isEmpty()) {
                Text(
                    text = stringResource(R.string.assistant_prompt_regex_no_scope),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
            if (duplicateName) {
                Text(
                    text = stringResource(R.string.assistant_prompt_regex_duplicate_name),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
            if (expanded) {
                OutlinedTextField(
                    value = regex.name,
                    onValueChange = { onUpdate(regex.copy(name = it)) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.assistant_page_regex_name)) },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = regex.findRegex,
                    onValueChange = { onUpdate(regex.copy(findRegex = it)) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.assistant_page_regex_find_regex)) },
                    placeholder = { Text("e.g., \\b\\w+@\\w+\\.\\w+\\b") },
                    isError = !validation.isValid,
                    textStyle = LocalTextStyle.current.copy(fontFamily = JetbrainsMono),
                )
                OutlinedTextField(
                    value = regex.replaceString,
                    onValueChange = { onUpdate(regex.copy(replaceString = it)) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.assistant_page_regex_replace_string)) },
                    placeholder = { Text("e.g., [EMAIL]") },
                    isError = !validation.isValid,
                    textStyle = LocalTextStyle.current.copy(fontFamily = JetbrainsMono),
                )
                Text(
                    text = stringResource(R.string.assistant_page_regex_affecting_scopes),
                    style = MaterialTheme.typography.labelMedium,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    AssistantAffectScope.entries.forEach { scope ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = scope in regex.affectingScope,
                                onCheckedChange = { checked ->
                                    onUpdate(
                                        regex.copy(
                                            affectingScope = if (checked) {
                                                regex.affectingScope + scope
                                            } else {
                                                regex.affectingScope - scope
                                            }
                                        )
                                    )
                                },
                            )
                            Text(affectScopeLabel(scope), style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = regex.visualOnly,
                        onCheckedChange = { onUpdate(regex.copy(visualOnly = it)) },
                    )
                    Text(
                        text = stringResource(R.string.assistant_page_regex_visual_only),
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
                OutlinedTextField(
                    value = testInput,
                    onValueChange = { testInput = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.assistant_prompt_regex_test_input)) },
                    minLines = 2,
                    maxLines = 5,
                )
                if (validation.isValid) {
                    val result = remember(regex.findRegex, regex.replaceString, testInput) {
                        testAssistantRegex(regex, testInput)
                    }
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(MaterialTheme.shapes.small)
                            .background(MaterialTheme.colorScheme.surface)
                            .padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.assistant_prompt_regex_test_result),
                            style = MaterialTheme.typography.labelMedium,
                        )
                        Text(
                            text = if (result.matchCount == 0) {
                                stringResource(R.string.assistant_prompt_regex_test_no_match)
                            } else {
                                stringResource(
                                    R.string.assistant_prompt_regex_test_match_count,
                                    result.matchCount,
                                )
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        SelectionContainer {
                            Text(result.output, fontFamily = JetbrainsMono)
                        }
                    }
                }
                Row(modifier = Modifier.fillMaxWidth()) {
                    IconButton(onClick = onMoveUp, enabled = canMoveUp) {
                        Icon(HugeIcons.ArrowUp01, stringResource(R.string.assistant_prompt_move_up))
                    }
                    IconButton(onClick = onMoveDown, enabled = canMoveDown) {
                        Icon(HugeIcons.ArrowDown01, stringResource(R.string.assistant_prompt_move_down))
                    }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onDelete) {
                        Icon(HugeIcons.Delete01, null)
                        Text(stringResource(R.string.delete))
                    }
                }
            }
        }
    }
}

@Composable
private fun messageRoleLabel(role: MessageRole): String = when (role) {
    MessageRole.SYSTEM -> stringResource(R.string.assistant_prompt_role_system)
    MessageRole.USER -> stringResource(R.string.assistant_prompt_role_user)
    MessageRole.ASSISTANT -> stringResource(R.string.assistant_prompt_role_assistant)
    else -> role.name
}

@Composable
private fun affectScopeLabel(scope: AssistantAffectScope): String = when (scope) {
    AssistantAffectScope.USER -> stringResource(R.string.assistant_prompt_role_user)
    AssistantAffectScope.ASSISTANT -> stringResource(R.string.assistant_prompt_role_assistant)
}

private fun <T> List<T>.replaceAt(index: Int, value: T): List<T> =
    mapIndexed { currentIndex, current -> if (currentIndex == index) value else current }

private fun <T> List<T>.move(from: Int, to: Int): List<T> {
    if (from !in indices || to !in indices || from == to) return this
    return toMutableList().apply { add(to, removeAt(from)) }
}

private fun <T> duplicateSafeKeys(items: List<T>, identity: (T) -> Any): List<String> {
    val occurrences = HashMap<Any, Int>()
    return items.map { item ->
        val base = identity(item).toString()
        val occurrence = occurrences[identity(item)] ?: 0
        occurrences[identity(item)] = occurrence + 1
        if (occurrence == 0) base else "$base#$occurrence"
    }
}
