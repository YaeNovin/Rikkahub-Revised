package me.rerere.rikkahub.ui.components.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.SecondaryScrollableTabRow
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.ExtensionManagementMode
import me.rerere.rikkahub.data.files.SkillManager
import me.rerere.rikkahub.data.files.SkillMetadata
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.ActiveMode
import me.rerere.rikkahub.data.model.ModeActivationScope
import me.rerere.rikkahub.data.model.PromptInjection
import me.rerere.rikkahub.data.model.resolveActiveModes
import me.rerere.rikkahub.data.model.selectExclusiveMode
import me.rerere.rikkahub.data.model.withQuickMessageIds
import me.rerere.rikkahub.ui.components.ai.ExtensionEmptyState
import me.rerere.rikkahub.ui.components.ai.LorebooksContent
import me.rerere.rikkahub.ui.components.ai.ModeInjectionsContent
import me.rerere.rikkahub.ui.components.ai.QuickMessagesContent
import me.rerere.rikkahub.ui.components.ai.SkillsContent
import org.koin.compose.koinInject
import me.rerere.ai.core.MessageRole
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import me.rerere.rikkahub.ui.components.ui.AppearanceAlertDialog as AlertDialog


@Composable
fun ExtensionSelector(
    modifier: Modifier = Modifier,
    assistant: Assistant,
    settings: Settings,
    onUpdate: (Assistant) -> Unit,
    conversation: Conversation? = null,
    onUpdateConversation: ((Conversation) -> Unit)? = null,
    onNavigateToQuickMessages: () -> Unit = {},
    onNavigateToPrompts: () -> Unit = {},
    onNavigateToSkills: () -> Unit = {},
) {
    val skillManager: SkillManager = koinInject()
    var skills by remember { mutableStateOf<List<SkillMetadata>>(emptyList()) }

    LaunchedEffect(Unit) {
        // 打开扩展面板时清理运行时被删除的技能（残留的 enabledSkills 引用），
        // prune 顺带返回现存技能列表，避免重复读盘
        skills = skillManager.pruneOrphanedEnabledSkills()
    }

    val useConversationInjections =
        assistant.allowConversationPromptInjection && conversation != null && onUpdateConversation != null
    val entertainmentMode = settings.extensionManagementMode == ExtensionManagementMode.ENTERTAINMENT
    val currentUserTurn = conversation?.currentMessages?.count { it.role == MessageRole.USER } ?: 0
    val activeModes = if (entertainmentMode) {
        resolveActiveModes(
            modeInjections = settings.modeInjections,
            assistantModeIds = assistant.modeInjectionIds,
            conversationModeIds = if (useConversationInjections) {
                conversation.modeInjectionIds
            } else {
                emptySet()
            },
            temporaryModes = if (useConversationInjections) {
                conversation.temporaryModeInjections
            } else {
                emptyMap()
            },
            currentUserTurn = currentUserTurn,
        )
    } else emptyList()
    val selectedModeInjectionIds = if (entertainmentMode) {
        activeModes.mapTo(linkedSetOf()) { it.injection.id }
    } else if (useConversationInjections) {
        assistant.modeInjectionIds + conversation.modeInjectionIds
    } else {
        assistant.modeInjectionIds
    }
    val selectedLorebookIds = if (useConversationInjections) {
        assistant.lorebookIds + conversation.lorebookIds
    } else {
        assistant.lorebookIds
    }

    val pagerState = rememberPagerState { 4 }
    val scope = rememberCoroutineScope()
    var pendingMode by remember { mutableStateOf<PromptInjection.ModeInjection?>(null) }

    Column(
        modifier = modifier
    ) {
        SecondaryScrollableTabRow(
            selectedTabIndex = pagerState.currentPage,
            containerColor = Color.Transparent,
            modifier = Modifier.fillMaxWidth(),
            edgePadding = 4.dp,
        ) {
            Tab(
                selected = pagerState.currentPage == 0,
                onClick = {
                    scope.launch { pagerState.animateScrollToPage(0) }
                },
                text = { Text(stringResource(R.string.extension_selector_tab_quick_messages)) }
            )
            Tab(
                selected = pagerState.currentPage == 1,
                onClick = {
                    scope.launch { pagerState.animateScrollToPage(1) }
                },
                text = { Text(stringResource(R.string.extension_selector_tab_mode_injections)) }
            )
            Tab(
                selected = pagerState.currentPage == 2,
                onClick = {
                    scope.launch { pagerState.animateScrollToPage(2) }
                },
                text = { Text(stringResource(R.string.extension_selector_tab_lorebooks)) }
            )
            Tab(
                selected = pagerState.currentPage == 3,
                onClick = {
                    scope.launch { pagerState.animateScrollToPage(3) }
                },
                text = { Text(stringResource(R.string.extension_selector_tab_skills)) }
            )
        }

        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) { page ->
            when (page) {
                0 -> {
                    if (settings.quickMessages.isNotEmpty()) {
                        QuickMessagesContent(
                            quickMessages = settings.quickMessages,
                            selectedIds = assistant.quickMessageIds,
                            onToggle = { id, checked ->
                                val newIds = if (checked) {
                                    assistant.quickMessageIds + id
                                } else {
                                    assistant.quickMessageIds - id
                                }
                                onUpdate(assistant.withQuickMessageIds(newIds))
                            },
                            onManage = onNavigateToQuickMessages,
                        )
                    } else {
                        ExtensionEmptyState(
                            message = stringResource(R.string.extension_selector_quick_messages_empty),
                            buttonText = stringResource(R.string.extension_selector_go_to_extensions),
                            onAction = onNavigateToQuickMessages,
                        )
                    }
                }

                1 -> {
                    if (settings.modeInjections.isNotEmpty()) {
                        ModeInjectionsContent(
                            modeInjections = settings.modeInjections,
                            selectedIds = selectedModeInjectionIds,
                            onToggle = { id, checked ->
                                if (entertainmentMode && conversation != null && onUpdateConversation != null) {
                                    if (checked) {
                                        pendingMode = settings.modeInjections.firstOrNull { it.id == id }
                                    } else {
                                        val active = activeModes.firstOrNull { it.injection.id == id }
                                        when (active?.scope) {
                                            ModeActivationScope.ASSISTANT_DEFAULT ->
                                                onUpdate(assistant.copy(modeInjectionIds = assistant.modeInjectionIds - id))
                                            ModeActivationScope.CONVERSATION ->
                                                onUpdateConversation(conversation.copy(modeInjectionIds = conversation.modeInjectionIds - id))
                                            ModeActivationScope.TEMPORARY ->
                                                onUpdateConversation(
                                                    conversation.copy(
                                                        temporaryModeInjections = conversation.temporaryModeInjections - id
                                                    )
                                                )
                                            null -> Unit
                                        }
                                    }
                                    return@ModeInjectionsContent
                                }
                                if (useConversationInjections) {
                                    if (checked) {
                                        onUpdateConversation(
                                            conversation.copy(
                                                modeInjectionIds = selectExclusiveMode(
                                                    conversation.modeInjectionIds,
                                                    id,
                                                    settings.modeInjections,
                                                )
                                            )
                                        )
                                    } else if (id in conversation.modeInjectionIds) {
                                        onUpdateConversation(
                                            conversation.copy(modeInjectionIds = conversation.modeInjectionIds - id)
                                        )
                                    } else {
                                        onUpdate(assistant.copy(modeInjectionIds = assistant.modeInjectionIds - id))
                                    }
                                } else {
                                    val newIds = if (checked) {
                                        selectExclusiveMode(
                                            assistant.modeInjectionIds,
                                            id,
                                            settings.modeInjections,
                                        )
                                    } else {
                                        assistant.modeInjectionIds - id
                                    }
                                    onUpdate(assistant.copy(modeInjectionIds = newIds))
                                }
                            },
                            onManage = onNavigateToPrompts,
                            scopeLabels = activeModes.associate { active ->
                                active.injection.id to active.scopeLabel()
                            },
                        )
                    } else {
                        ExtensionEmptyState(
                            message = stringResource(R.string.extension_selector_mode_injections_empty),
                            buttonText = stringResource(R.string.extension_selector_go_to_extensions),
                            onAction = onNavigateToPrompts,
                        )
                    }
                }

                2 -> {
                    if (settings.lorebooks.isNotEmpty()) {
                        LorebooksContent(
                            lorebooks = settings.lorebooks,
                            selectedIds = selectedLorebookIds,
                            onToggle = { id, checked ->
                                if (useConversationInjections) {
                                    if (checked) {
                                        onUpdateConversation(
                                            conversation.copy(lorebookIds = conversation.lorebookIds + id)
                                        )
                                    } else if (id in conversation.lorebookIds) {
                                        onUpdateConversation(
                                            conversation.copy(lorebookIds = conversation.lorebookIds - id)
                                        )
                                    } else {
                                        onUpdate(assistant.copy(lorebookIds = assistant.lorebookIds - id))
                                    }
                                } else {
                                    val newIds = if (checked) assistant.lorebookIds + id
                                    else assistant.lorebookIds - id
                                    onUpdate(assistant.copy(lorebookIds = newIds))
                                }
                            },
                            onManage = onNavigateToPrompts,
                        )
                    } else {
                        ExtensionEmptyState(
                            message = stringResource(R.string.extension_selector_lorebooks_empty),
                            buttonText = stringResource(R.string.extension_selector_go_to_extensions),
                            onAction = onNavigateToPrompts,
                        )
                    }
                }

                3 -> {
                    if (skills.isNotEmpty()) {
                        SkillsContent(
                            skills = skills,
                            enabledSkills = assistant.enabledSkills,
                            onToggle = { name, checked ->
                                val newSkills = if (checked) {
                                    assistant.enabledSkills + name
                                } else {
                                    assistant.enabledSkills - name
                                }
                                onUpdate(assistant.copy(enabledSkills = newSkills))
                            },
                            onManage = onNavigateToSkills,
                        )
                    } else {
                        ExtensionEmptyState(
                            message = stringResource(R.string.extension_selector_skills_empty),
                            buttonText = stringResource(R.string.extension_selector_go_to_skills),
                            onAction = onNavigateToSkills,
                        )
                    }
                }
            }
        }
    }

    pendingMode?.let { mode ->
        ModeActivationScopeDialog(
            mode = mode,
            allModes = settings.modeInjections,
            assistant = assistant,
            conversation = conversation,
            currentUserTurn = currentUserTurn,
            onUpdateAssistant = onUpdate,
            onUpdateConversation = onUpdateConversation,
            onDismiss = { pendingMode = null },
        )
    }
}

@Composable
private fun ActiveMode.scopeLabel(): String = when (scope) {
    ModeActivationScope.ASSISTANT_DEFAULT -> stringResource(R.string.mode_scope_assistant_default)
    ModeActivationScope.CONVERSATION -> stringResource(R.string.mode_scope_conversation)
    ModeActivationScope.TEMPORARY -> stringResource(
        R.string.mode_scope_temporary_remaining,
        remainingTurns ?: 0,
    )
}

@Composable
private fun ModeActivationScopeDialog(
    mode: PromptInjection.ModeInjection,
    allModes: List<PromptInjection.ModeInjection>,
    assistant: Assistant,
    conversation: Conversation?,
    currentUserTurn: Int,
    onUpdateAssistant: (Assistant) -> Unit,
    onUpdateConversation: ((Conversation) -> Unit)?,
    onDismiss: () -> Unit,
) {
    var selectedScope by remember(mode.id) { mutableStateOf(ModeActivationScope.CONVERSATION) }
    var temporaryTurns by remember(mode.id) { mutableStateOf("3") }
    val scopes = if (conversation != null && onUpdateConversation != null) {
        ModeActivationScope.entries
    } else {
        listOf(ModeActivationScope.ASSISTANT_DEFAULT)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.mode_scope_title, mode.name)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                scopes.forEach { scope ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = selectedScope == scope,
                                onClick = { selectedScope = scope },
                            )
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = selectedScope == scope,
                            onClick = { selectedScope = scope },
                        )
                        Text(
                            text = when (scope) {
                                ModeActivationScope.ASSISTANT_DEFAULT ->
                                    stringResource(R.string.mode_scope_assistant_default)
                                ModeActivationScope.CONVERSATION ->
                                    stringResource(R.string.mode_scope_conversation)
                                ModeActivationScope.TEMPORARY ->
                                    stringResource(R.string.mode_scope_temporary)
                            },
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                }
                if (selectedScope == ModeActivationScope.TEMPORARY) {
                    OutlinedTextField(
                        value = temporaryTurns,
                        onValueChange = { temporaryTurns = it.filter(Char::isDigit).take(3) },
                        label = { Text(stringResource(R.string.mode_scope_temporary_turns)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = selectedScope != ModeActivationScope.TEMPORARY ||
                    (temporaryTurns.toIntOrNull() ?: 0) > 0,
                onClick = {
                    val peerIds = allModes.filter {
                        mode.exclusiveGroup.isNotBlank() &&
                            it.exclusiveGroup.trim().equals(mode.exclusiveGroup.trim(), true)
                    }.mapTo(hashSetOf()) { it.id }.apply { add(mode.id) }
                    when (selectedScope) {
                        ModeActivationScope.ASSISTANT_DEFAULT -> {
                            onUpdateAssistant(
                                assistant.copy(
                                    modeInjectionIds = selectExclusiveMode(
                                        assistant.modeInjectionIds,
                                        mode.id,
                                        allModes,
                                    )
                                )
                            )
                            conversation?.let { current ->
                                onUpdateConversation?.invoke(
                                    current.copy(
                                        modeInjectionIds = current.modeInjectionIds - peerIds,
                                        temporaryModeInjections = current.temporaryModeInjections - peerIds,
                                    )
                                )
                            }
                        }
                        ModeActivationScope.CONVERSATION -> conversation?.let { current ->
                            onUpdateConversation?.invoke(
                                current.copy(
                                    modeInjectionIds = selectExclusiveMode(
                                        current.modeInjectionIds,
                                        mode.id,
                                        allModes,
                                    ),
                                    temporaryModeInjections = current.temporaryModeInjections - peerIds,
                                )
                            )
                        }
                        ModeActivationScope.TEMPORARY -> conversation?.let { current ->
                            val expiresAt = currentUserTurn + (temporaryTurns.toIntOrNull() ?: 1)
                            onUpdateConversation?.invoke(
                                current.copy(
                                    temporaryModeInjections =
                                        (current.temporaryModeInjections - peerIds) + (mode.id to expiresAt)
                                )
                            )
                        }
                    }
                    onDismiss()
                },
            ) { Text(stringResource(R.string.confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}
