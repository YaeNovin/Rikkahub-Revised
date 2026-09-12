package me.rerere.rikkahub.ui.pages.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyHorizontalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items as rowItems
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Edit01
import me.rerere.hugeicons.stroke.Menu03
import me.rerere.hugeicons.stroke.Search01
import me.rerere.hugeicons.stroke.Sparkles
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.ChatSuggestionDisplayMode
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.ChatSuggestionAction
import me.rerere.rikkahub.data.model.ChatSuggestionCategory
import me.rerere.rikkahub.data.model.ChatSuggestionItem
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.currentChatSuggestions
import me.rerere.rikkahub.data.model.availableSuggestionActions
import me.rerere.rikkahub.data.model.suggestionSourceMessage
import me.rerere.rikkahub.data.model.suggestionConfig
import me.rerere.rikkahub.data.model.SuggestionTrigger
import me.rerere.rikkahub.service.SuggestionGenerationState
import me.rerere.rikkahub.ui.components.ui.Tooltip

@Composable
internal fun ChatSuggestions(
    modifier: Modifier = Modifier,
    conversation: Conversation,
    settings: Settings,
    generationState: SuggestionGenerationState = SuggestionGenerationState.IDLE,
    actions: SuggestionUiActions = SuggestionUiActions(),
    onSuggestionAction: (ChatSuggestionItem) -> Unit,
    onRefreshSuggestions: () -> Unit,
    onDismissSuggestions: () -> Unit,
) {
    val displayState = if (generationState == SuggestionGenerationState.IDLE && conversation.suggestionSession.info?.error != null)
        SuggestionGenerationState.FAILED else generationState
    val config = conversation.suggestionConfig(settings)
    if (config.options.trigger == SuggestionTrigger.DISABLED || conversation.suggestionSession.paused || conversation.suggestionSession.collapsed) return
    if (conversation.suggestionSourceMessage() == null) return
    val suggestions = remember(conversation.id, conversation.messageNodes, conversation.chatSuggestionItems, conversation.chatSuggestions, conversation.suggestionSession.target, conversation.memoryMode) {
        conversation.currentChatSuggestions()
    }
    val availableActions = conversation.availableSuggestionActions(settings)
    if (suggestions.isEmpty() && displayState == SuggestionGenerationState.IDLE) return

    var showMore by remember(conversation.id) { mutableStateOf(false) }
    val resolvedMode = when (config.displayMode) {
        ChatSuggestionDisplayMode.AUTO -> if (suggestions.size == 1) {
            ChatSuggestionDisplayMode.COMPACT
        } else if (
            suggestions.any { it.description.isNotBlank() || it.action != ChatSuggestionAction.INSERT_TEXT }
        ) {
            ChatSuggestionDisplayMode.RICH
        } else {
            ChatSuggestionDisplayMode.TWO_ROW
        }

        else -> config.displayMode
    }

    val appearance = settings.advancedAppearanceSetting
    val maxHeight = appearance.chatSuggestionMaxHeight.coerceIn(72f, 220f).dp
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        ChatSuggestionSurface(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(HugeIcons.Sparkles, contentDescription = null, modifier = Modifier.size(16.dp))
                Text(stringResource(R.string.chat_page_suggestion_quick_category),
                    style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                if (conversation.suggestionSession.target != null) {
                    Text(stringResource(R.string.chat_page_suggestion_selected_message), style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (displayState == SuggestionGenerationState.GENERATING) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                } else if (displayState == SuggestionGenerationState.FAILED) {
                    SuggestionIconButton(HugeIcons.Edit01, stringResource(R.string.chat_page_suggestion_retry), onRefreshSuggestions)
                }
                if (suggestions.isEmpty() && displayState == SuggestionGenerationState.IDLE) {
                    SuggestionIconButton(HugeIcons.Menu03, stringResource(R.string.chat_page_more_suggestions), { showMore = true })
                } else if (suggestions.isNotEmpty()) {
                    SuggestionIconButton(HugeIcons.Menu03, stringResource(R.string.chat_page_more_suggestions), { showMore = true })
                }
            }
        }
        if (suggestions.isEmpty()) {
            ChatSuggestionSurface(modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(if (displayState == SuggestionGenerationState.GENERATING)
                        R.string.chat_page_suggestion_generating else R.string.chat_page_suggestion_generation_failed),
                        modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    if (displayState != SuggestionGenerationState.GENERATING) {
                        TextButton(onClick = onRefreshSuggestions) { Text(stringResource(R.string.chat_page_suggestion_retry)) }
                        TextButton(onClick = onDismissSuggestions) { Text(stringResource(android.R.string.cancel)) }
                    }
                }
            }
        } else {
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                when (resolvedMode) {
                    ChatSuggestionDisplayMode.COMPACT -> CompactSuggestions(suggestions, availableActions, onSuggestionAction, Modifier.fillMaxWidth())
                    ChatSuggestionDisplayMode.TWO_ROW -> TwoRowSuggestions(suggestions, availableActions, onSuggestionAction,
                        Modifier.fillMaxWidth().heightIn(min = 58.dp, max = maxHeight))
                    ChatSuggestionDisplayMode.RICH -> RichSuggestions(suggestions, availableActions, onSuggestionAction,
                        Modifier.fillMaxWidth().heightIn(min = 72.dp, max = maxHeight),
                        cardWidth = maxWidth.times(.78f).coerceIn(176.dp, 280.dp))
                    ChatSuggestionDisplayMode.AUTO -> Unit
                }
            }
        }
    }

    if (showMore) {
        SuggestionControlPanel(
            conversation = conversation,
            settings = settings,
            state = displayState,
            actions = actions,
            onDismiss = { showMore = false },
            onRefresh = onRefreshSuggestions,
            onClear = onDismissSuggestions,
            onAction = { suggestion ->
                showMore = false
                onSuggestionAction(suggestion)
            },
        )
    }
}

@Composable
private fun CompactSuggestions(
    suggestions: List<ChatSuggestionItem>,
    availableActions: Set<ChatSuggestionAction>,
    onSuggestionAction: (ChatSuggestionItem) -> Unit,
    modifier: Modifier,
) {
    LazyRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        rowItems(suggestions, key = ChatSuggestionItem::id) { suggestion ->
            SuggestionChip(
                suggestion = suggestion,
                enabled = suggestion.action in availableActions,
                onClick = { onSuggestionAction(suggestion) },
            )
        }
    }
}

@Composable
private fun TwoRowSuggestions(
    suggestions: List<ChatSuggestionItem>,
    availableActions: Set<ChatSuggestionAction>,
    onSuggestionAction: (ChatSuggestionItem) -> Unit,
    modifier: Modifier,
) {
    LazyHorizontalGrid(
        rows = GridCells.Fixed(2),
        modifier = modifier.height(102.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        gridItems(suggestions, key = ChatSuggestionItem::id) { suggestion ->
            SuggestionChip(
                suggestion = suggestion,
                enabled = suggestion.action in availableActions,
                onClick = { onSuggestionAction(suggestion) },
            )
        }
    }
}

@Composable
private fun SuggestionChip(
    suggestion: ChatSuggestionItem,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    ChatSuggestionSurface(
        onClick = onClick,
        enabled = enabled,
    ) {
        Text(
            text = suggestion.text,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .widthIn(max = 260.dp)
                .padding(horizontal = 10.dp, vertical = 6.dp),
        )
    }
}

@Composable
private fun RichSuggestions(
    suggestions: List<ChatSuggestionItem>,
    availableActions: Set<ChatSuggestionAction>,
    onSuggestionAction: (ChatSuggestionItem) -> Unit,
    modifier: Modifier,
    cardWidth: androidx.compose.ui.unit.Dp = 248.dp,
) {
    LazyRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        rowItems(suggestions, key = ChatSuggestionItem::id) { suggestion ->
            val enabled = suggestion.action in availableActions
            ChatSuggestionSurface(
                onClick = { onSuggestionAction(suggestion) },
                enabled = enabled,
                modifier = Modifier
                    .widthIn(min = 176.dp, max = cardWidth)
                    .heightIn(min = 72.dp),
            ) {
                Row(
                    modifier = Modifier.padding(10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Icon(
                        imageVector = suggestion.category.icon(),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp),
                    )
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = suggestion.text,
                            style = MaterialTheme.typography.labelLarge,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        val supporting = if (enabled) {
                            suggestion.description.ifBlank { suggestion.action.label() }
                        } else {
                            stringResource(R.string.chat_page_suggestion_capability_unavailable)
                        }
                        Text(
                            text = supporting,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (enabled) {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            } else {
                                MaterialTheme.colorScheme.tertiary
                            },
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}


@Composable
private fun SuggestionIconButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    Tooltip(tooltip = { Text(label) }) {
        ChatSuggestionSurface(
            onClick = onClick,
            modifier = Modifier.size(28.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(icon, label, Modifier.size(15.dp))
            }
        }
    }
}

private fun ChatSuggestionCategory.icon(): ImageVector = when (this) {
    ChatSuggestionCategory.FOLLOW_UP -> HugeIcons.Search01
    ChatSuggestionCategory.ACTION -> HugeIcons.Edit01
    ChatSuggestionCategory.DIRECTION -> HugeIcons.Sparkles
    ChatSuggestionCategory.DIALOGUE, ChatSuggestionCategory.ROLE_ACTION,
    ChatSuggestionCategory.INNER_THOUGHT, ChatSuggestionCategory.PLOT -> HugeIcons.Sparkles
}


@Composable
private fun ChatSuggestionAction.label(): String = when (this) {
    ChatSuggestionAction.INSERT_TEXT -> stringResource(R.string.chat_page_suggestion_insert)
    ChatSuggestionAction.COPY_TEXT -> stringResource(R.string.chat_page_suggestion_copy)
    ChatSuggestionAction.SAVE_QUICK_MESSAGE -> stringResource(R.string.chat_page_suggestion_save)
    ChatSuggestionAction.CREATE_BRANCH -> stringResource(R.string.chat_page_suggestion_branch)
    ChatSuggestionAction.SEARCH_WEB -> stringResource(R.string.chat_page_suggestion_action_web)
    ChatSuggestionAction.SEARCH_KNOWLEDGE -> stringResource(R.string.chat_page_suggestion_action_knowledge)
    ChatSuggestionAction.SEARCH_MEMORY -> stringResource(R.string.chat_page_suggestion_action_memory)
    ChatSuggestionAction.SEARCH_CONVERSATIONS -> stringResource(R.string.chat_page_suggestion_action_conversations)
    ChatSuggestionAction.ASK_USER -> stringResource(R.string.chat_page_suggestion_action_ask)
    ChatSuggestionAction.WORKSPACE -> stringResource(R.string.chat_page_suggestion_action_workspace)
    ChatSuggestionAction.USE_SKILL -> stringResource(R.string.chat_page_suggestion_action_skill)
    ChatSuggestionAction.MCP -> stringResource(R.string.chat_page_suggestion_action_mcp)
    ChatSuggestionAction.IMAGE_DRAFT -> "准备图片提示词"
}
