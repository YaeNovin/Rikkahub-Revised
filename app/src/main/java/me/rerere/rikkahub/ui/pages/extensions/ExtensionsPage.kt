package me.rerere.rikkahub.ui.pages.extensions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import me.rerere.rikkahub.ui.components.ui.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
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
    val navController = LocalNavController.current
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    var query by rememberSaveable { mutableStateOf("") }
    var selectedCategory by rememberSaveable { mutableStateOf<ExtensionCategory?>(null) }
    var issuesOnly by rememberSaveable { mutableStateOf(false) }
    val categories = ExtensionCategory.entries.filter {
        state.mode == ExtensionManagementMode.NORMAL || it != ExtensionCategory.WORKSPACES
    }
    val results = androidx.compose.runtime.remember(state.audit, query, selectedCategory, issuesOnly, state.mode) {
        state.audit.searchItems.filter {
            it.category in categories && (selectedCategory == null || it.category == selectedCategory) &&
                it.matches(query) && (!issuesOnly || it.hasIssue)
        }
    }
    val issues = state.audit.issues.filter { it.category in categories }
    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_RESUME) { vm.refresh() }

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
        containerColor = CustomColors.scaffoldContainerColor,
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = padding + PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                ExtensionModeSelector(state.mode) { selectedCategory = null; vm.setMode(it) }
            }
            item {
                Text(
                    "普通模式适合工作与日常；娱乐模式启用互斥模式、临时轮次及世界书概率和预算规则。切换会影响注入方式，已有配置会保留。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item {
                androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    androidx.compose.material3.FilterChip(selectedCategory == null, { selectedCategory = null }, { Text("全部") })
                    categories.forEach { category ->
                        androidx.compose.material3.FilterChip(
                            selected = selectedCategory == category,
                            onClick = { selectedCategory = category },
                            label = { Text(stringResource(category.titleResource())) },
                        )
                    }
                }
            }
            if (query.isBlank() && !issuesOnly) {
                items(categories.filter { selectedCategory == null || selectedCategory == it }, key = { "category:$it" }) { category ->
                    val summary = state.audit.summaries[category]
                    CardGroup {
                        item(
                            onClick = { navController.navigate(category.destination()) },
                            headlineContent = { Text(stringResource(category.titleResource())) },
                            supportingContent = {
                                Column {
                                    Text(stringResource(category.descriptionResource()))
                                    Text("共 ${summary?.totalCount ?: 0} 项 · 助手默认使用 ${summary?.enabledCount ?: 0} 项 · 待检查 ${summary?.issueCount ?: 0} 项")
                                    Text("${summary?.assistantCount ?: 0} 个助手引用，不含对话临时选择", style = MaterialTheme.typography.bodySmall)
                                }
                            },
                            leadingContent = { Icon(category.icon(), null) },
                            trailingContent = { Icon(HugeIcons.ArrowRight01, null) },
                        )
                    }
                }
            }
            item {
                OutlinedTextField(
                    value = query, onValueChange = { query = it }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.extensions_page_search)) },
                    leadingIcon = { Icon(HugeIcons.Search01, null) },
                    trailingIcon = {
                        if (query.isNotEmpty()) IconButton(onClick = { query = "" }) {
                            Icon(HugeIcons.Cancel01, stringResource(R.string.extensions_page_clear_search))
                        }
                    },
                )
            }
            item {
                androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    androidx.compose.material3.FilterChip(issuesOnly, { issuesOnly = !issuesOnly }, { Text("仅待检查") })
                    androidx.compose.material3.TextButton(onClick = vm::refresh, enabled = !state.checking) {
                        Text(if (state.checking) "正在检查…" else "重新检查")
                    }
                }
                if (state.checking) androidx.compose.material3.LinearProgressIndicator(Modifier.fillMaxWidth())
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (!state.checking && state.error == null) {
                    Text("当前分类 ${results.size} 项 · 全部可见分类 ${issues.size} 条检查提示", style = MaterialTheme.typography.bodySmall)
                }
            }
            items(results, key = { "${it.category}:${it.key}" }) { result ->
                val itemIssues = issues.filter { it.category == result.category && it.itemKey == result.key }
                CardGroup {
                    item(
                        onClick = { navController.navigate(result.destination()) },
                        overlineContent = { Text(stringResource(result.kind.labelResource())) },
                        headlineContent = { Text(result.title.ifBlank { "未命名" }) },
                        supportingContent = {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(result.description, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text("助手默认使用：${if (result.enabled) "是" else "否"} · ${result.assistantCount} 个助手引用")
                                itemIssues.forEach { issue ->
                                    Text((if (issue.kind.isAdvisory()) "潜在重叠，需结合使用场景：" else "") + issue.itemTitle + " · " + stringResource(issue.kind.messageResource()), color = if (issue.kind.isAdvisory()) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error)
                                }
                            }
                        },
                        leadingContent = { Icon(result.category.icon(), null) },
                        trailingContent = { Icon(HugeIcons.ArrowRight01, null) },
                    )
                }
            }
            if (results.isEmpty() && !state.checking) item {
                Text(stringResource(R.string.extensions_page_search_empty))
            }
            val orphanIssues = issues.filter { issue ->
                state.audit.searchItems.none { it.category == issue.category && it.key == issue.itemKey } &&
                    (selectedCategory == null || selectedCategory == issue.category) &&
                    (query.isBlank() || issue.itemTitle.contains(query.trim(), ignoreCase = true))
            }
            items(orphanIssues, key = { "issue:${it.category}:${it.itemKey}:${it.kind}" }) { issue ->
                CardGroup {
                    item(
                        onClick = { navController.navigate(issue.category.destination()) },
                        headlineContent = { Text(issue.itemTitle) },
                        supportingContent = { Text(stringResource(issue.kind.messageResource()), color = MaterialTheme.colorScheme.error) },
                    )
                }
            }
        }
    }
}

private fun ExtensionSearchItem.destination(): Screen = when (kind) {
    ExtensionItemKind.SKILL -> if (key.startsWith("skill:")) Screen.SkillDetail(title) else Screen.Skills
    ExtensionItemKind.WORKSPACE -> Screen.WorkspaceDetail(key.removePrefix("workspace:"))
    else -> Screen.ExtensionItem(kind.name, key)
}

private fun ExtensionIssueKind.isAdvisory() = this == ExtensionIssueKind.SETTING_CONFLICT ||
    this == ExtensionIssueKind.SETTING_OVERLAP || this == ExtensionIssueKind.MUTUALLY_EXCLUSIVE_MODES
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
