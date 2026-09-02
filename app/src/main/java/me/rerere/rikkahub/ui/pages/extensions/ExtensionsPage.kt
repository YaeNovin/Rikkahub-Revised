package me.rerere.rikkahub.ui.pages.extensions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.AlertCircle
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.hugeicons.stroke.Book03
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.CheckmarkCircle02
import me.rerere.hugeicons.stroke.Folder01
import me.rerere.hugeicons.stroke.Puzzle
import me.rerere.hugeicons.stroke.Search01
import me.rerere.hugeicons.stroke.Zap
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.datastore.ExtensionManagementMode
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.plus
import org.koin.androidx.compose.koinViewModel

@Composable
fun ExtensionsPage() {
    val vm = koinViewModel<ExtensionsVM>()
    val state by vm.uiState.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val navController = LocalNavController.current
    var query by rememberSaveable { mutableStateOf("") }
    val visibleCategories = when (state.mode) {
        ExtensionManagementMode.NORMAL -> ExtensionCategory.entries
        ExtensionManagementMode.ENTERTAINMENT -> ExtensionCategory.entries
            .filterNot { it == ExtensionCategory.WORKSPACES }
    }
    val searchResults = state.audit.searchItems.filter {
        it.category in visibleCategories && it.matches(query)
    }
    val visibleIssues = state.audit.issues.filter { it.category in visibleCategories }

    LaunchedEffect(Unit) { vm.refresh() }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.extensions_page_title)) },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = innerPadding + PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                ExtensionModeSelector(selected = state.mode, onSelect = vm::setMode)
            }

            item {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text(stringResource(R.string.extensions_page_search)) },
                    leadingIcon = { Icon(HugeIcons.Search01, null) },
                    trailingIcon = if (query.isNotEmpty()) {
                        {
                            IconButton(onClick = { query = "" }) {
                                Icon(
                                    imageVector = HugeIcons.Cancel01,
                                    contentDescription = stringResource(R.string.extensions_page_clear_search),
                                )
                            }
                        }
                    } else null,
                )
            }

            if (query.isNotBlank()) {
                if (searchResults.isEmpty()) {
                    item {
                        Text(
                            text = stringResource(R.string.extensions_page_search_empty),
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 16.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    item {
                        CardGroup(title = { Text(stringResource(R.string.extensions_page_search_results)) }) {
                            searchResults.forEach { result ->
                                item(
                                    onClick = { navController.navigate(result.category.destination()) },
                                    overlineContent = { Text(stringResource(result.kind.labelResource())) },
                                    headlineContent = {
                                        Text(
                                            text = result.title.ifBlank {
                                                stringResource(R.string.extensions_page_unnamed_item)
                                            },
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    },
                                    supportingContent = {
                                        Column {
                                            if (result.description.isNotBlank()) {
                                                Text(
                                                    text = result.description,
                                                    maxLines = 2,
                                                    overflow = TextOverflow.Ellipsis,
                                                )
                                            }
                                            Text(
                                                stringResource(
                                                    R.string.extensions_page_search_item_status,
                                                    if (result.enabled) {
                                                        stringResource(R.string.extensions_page_status_enabled)
                                                    } else {
                                                        stringResource(R.string.extensions_page_status_not_enabled)
                                                    },
                                                    result.assistantCount,
                                                )
                                            )
                                        }
                                    },
                                    leadingContent = { Icon(result.category.icon(), null) },
                                    trailingContent = {
                                        Icon(
                                            imageVector = if (result.hasIssue) HugeIcons.AlertCircle else HugeIcons.ArrowRight01,
                                            contentDescription = null,
                                            tint = if (result.hasIssue) MaterialTheme.colorScheme.error else Color.Unspecified,
                                        )
                                    },
                                )
                            }
                        }
                    }
                }
            } else {
                item {
                    CardGroup(title = { Text(stringResource(R.string.extensions_page_section_overview)) }) {
                        visibleCategories.forEach { category ->
                            val summary = state.audit.summaries[category]
                                ?: ExtensionCategorySummary(category, 0, 0, 0, 0)
                            item(
                                onClick = { navController.navigate(category.destination()) },
                                headlineContent = { Text(stringResource(category.titleResource())) },
                                supportingContent = {
                                    Column {
                                        Text(stringResource(category.descriptionResource()))
                                        Text(
                                            text = stringResource(
                                                R.string.extensions_page_summary_metrics,
                                                summary.totalCount,
                                                summary.enabledCount,
                                                summary.issueCount,
                                                summary.assistantCount,
                                            ),
                                            color = if (summary.issueCount > 0) {
                                                MaterialTheme.colorScheme.error
                                            } else {
                                                MaterialTheme.colorScheme.onSurfaceVariant
                                            },
                                        )
                                    }
                                },
                                leadingContent = { Icon(category.icon(), null) },
                                trailingContent = { Icon(HugeIcons.ArrowRight01, null) },
                            )
                        }
                    }
                }

                item {
                    CardGroup(
                        title = {
                            Text(
                                stringResource(
                                    R.string.extensions_page_health_title,
                                    visibleIssues.distinctBy { it.itemKey }.size,
                                )
                            )
                        },
                    ) {
                        if (visibleIssues.isEmpty()) {
                            item(
                                headlineContent = { Text(stringResource(R.string.extensions_page_health_ok)) },
                                leadingContent = {
                                    Icon(
                                        HugeIcons.CheckmarkCircle02,
                                        null,
                                        tint = MaterialTheme.colorScheme.primary,
                                    )
                                },
                            )
                        } else {
                            visibleIssues.forEach { issue ->
                                item(
                                    onClick = { navController.navigate(issue.category.destination()) },
                                    headlineContent = {
                                        Text(
                                            issue.itemTitle.ifBlank {
                                                stringResource(R.string.extensions_page_unnamed_item)
                                            },
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    },
                                    supportingContent = { Text(stringResource(issue.kind.messageResource())) },
                                    leadingContent = {
                                        Icon(
                                            HugeIcons.AlertCircle,
                                            null,
                                            tint = MaterialTheme.colorScheme.error,
                                        )
                                    },
                                    trailingContent = { Icon(HugeIcons.ArrowRight01, null) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ExtensionModeSelector(
    selected: ExtensionManagementMode,
    onSelect: (ExtensionManagementMode) -> Unit,
) {
    val modes = ExtensionManagementMode.entries
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            modes.forEachIndexed { index, mode ->
                SegmentedButton(
                    selected = selected == mode,
                    onClick = { onSelect(mode) },
                    shape = SegmentedButtonDefaults.itemShape(index, modes.size),
                ) {
                    Text(
                        stringResource(
                            when (mode) {
                                ExtensionManagementMode.NORMAL -> R.string.extensions_page_mode_normal
                                ExtensionManagementMode.ENTERTAINMENT -> R.string.extensions_page_mode_entertainment
                            }
                        )
                    )
                }
            }
        }
        Text(
            text = stringResource(
                when (selected) {
                    ExtensionManagementMode.NORMAL -> R.string.extensions_page_mode_normal_desc
                    ExtensionManagementMode.ENTERTAINMENT -> R.string.extensions_page_mode_entertainment_desc
                }
            ),
            modifier = Modifier.padding(horizontal = 4.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun ExtensionCategory.destination(): Screen = when (this) {
    ExtensionCategory.QUICK_MESSAGES -> Screen.QuickMessages
    ExtensionCategory.PROMPTS -> Screen.Prompts
    ExtensionCategory.SKILLS -> Screen.Skills
    ExtensionCategory.WORKSPACES -> Screen.Workspaces
}

private fun ExtensionCategory.icon(): ImageVector = when (this) {
    ExtensionCategory.QUICK_MESSAGES -> HugeIcons.Zap
    ExtensionCategory.PROMPTS -> HugeIcons.Book03
    ExtensionCategory.SKILLS -> HugeIcons.Puzzle
    ExtensionCategory.WORKSPACES -> HugeIcons.Folder01
}

private fun ExtensionCategory.titleResource(): Int = when (this) {
    ExtensionCategory.QUICK_MESSAGES -> R.string.assistant_page_quick_messages
    ExtensionCategory.PROMPTS -> R.string.extensions_page_prompts
    ExtensionCategory.SKILLS -> R.string.extensions_page_agent_skills
    ExtensionCategory.WORKSPACES -> R.string.extensions_page_workspace
}

private fun ExtensionCategory.descriptionResource(): Int = when (this) {
    ExtensionCategory.QUICK_MESSAGES -> R.string.extensions_page_quick_messages_desc
    ExtensionCategory.PROMPTS -> R.string.extensions_page_prompts_desc
    ExtensionCategory.SKILLS -> R.string.extensions_page_agent_skills_desc
    ExtensionCategory.WORKSPACES -> R.string.extensions_page_workspace_desc
}

private fun ExtensionItemKind.labelResource(): Int = when (this) {
    ExtensionItemKind.QUICK_MESSAGE -> R.string.extensions_page_kind_quick_message
    ExtensionItemKind.MODE_INJECTION -> R.string.extensions_page_kind_mode_injection
    ExtensionItemKind.LOREBOOK -> R.string.extensions_page_kind_lorebook
    ExtensionItemKind.SKILL -> R.string.extensions_page_kind_skill
    ExtensionItemKind.WORKSPACE -> R.string.extensions_page_kind_workspace
}

private fun ExtensionIssueKind.messageResource(): Int = when (this) {
    ExtensionIssueKind.EMPTY_NAME -> R.string.extensions_page_issue_empty_name
    ExtensionIssueKind.EMPTY_CONTENT -> R.string.extensions_page_issue_empty_content
    ExtensionIssueKind.EMPTY_LOREBOOK -> R.string.extensions_page_issue_empty_lorebook
    ExtensionIssueKind.DUPLICATE_NAME -> R.string.extensions_page_issue_duplicate_name
    ExtensionIssueKind.INVALID_REGEX -> R.string.extensions_page_issue_invalid_regex
    ExtensionIssueKind.INVALID_KEYWORD_EXPRESSION -> R.string.extensions_page_issue_invalid_keyword_expression
    ExtensionIssueKind.MISSING_TRIGGER -> R.string.extensions_page_issue_missing_trigger
    ExtensionIssueKind.INVALID_INJECTION_DEPTH -> R.string.extensions_page_issue_invalid_depth
    ExtensionIssueKind.INVALID_SCAN_DEPTH -> R.string.extensions_page_issue_invalid_scan_depth
    ExtensionIssueKind.INVALID_ROLE -> R.string.extensions_page_issue_invalid_role
    ExtensionIssueKind.MISSING_SKILL_MANIFEST -> R.string.extensions_page_issue_missing_skill_manifest
    ExtensionIssueKind.UNREADABLE_SKILL_MANIFEST -> R.string.extensions_page_issue_unreadable_skill_manifest
    ExtensionIssueKind.MISSING_SKILL_NAME -> R.string.extensions_page_issue_missing_skill_name
    ExtensionIssueKind.MISSING_SKILL_DESCRIPTION -> R.string.extensions_page_issue_missing_skill_description
    ExtensionIssueKind.MISSING_REFERENCE -> R.string.extensions_page_issue_missing_reference
    ExtensionIssueKind.INACCESSIBLE_WORKSPACE -> R.string.extensions_page_issue_inaccessible_workspace
    ExtensionIssueKind.BROKEN_WORKSPACE -> R.string.extensions_page_issue_broken_workspace
    ExtensionIssueKind.SETTING_CONFLICT -> R.string.extensions_page_issue_setting_conflict
    ExtensionIssueKind.SETTING_OVERLAP -> R.string.extensions_page_issue_setting_overlap
    ExtensionIssueKind.MUTUALLY_EXCLUSIVE_MODES -> R.string.extensions_page_issue_mutually_exclusive_modes
}
