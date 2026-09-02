package me.rerere.rikkahub.ui.pages.extensions

import me.rerere.ai.core.MessageRole
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.ExtensionManagementMode
import me.rerere.rikkahub.data.files.SkillScanProblemKind
import me.rerere.rikkahub.data.files.SkillScanResult
import me.rerere.rikkahub.data.model.InjectionPosition
import me.rerere.rikkahub.data.model.PromptInjection
import me.rerere.rikkahub.data.model.evaluateKeywords

internal enum class ExtensionCategory {
    QUICK_MESSAGES,
    PROMPTS,
    SKILLS,
    WORKSPACES,
}

internal enum class ExtensionItemKind {
    QUICK_MESSAGE,
    MODE_INJECTION,
    LOREBOOK,
    SKILL,
    WORKSPACE,
}

internal enum class ExtensionIssueKind {
    EMPTY_NAME,
    EMPTY_CONTENT,
    EMPTY_LOREBOOK,
    DUPLICATE_NAME,
    INVALID_REGEX,
    INVALID_KEYWORD_EXPRESSION,
    MISSING_TRIGGER,
    INVALID_INJECTION_DEPTH,
    INVALID_SCAN_DEPTH,
    INVALID_ROLE,
    MISSING_SKILL_MANIFEST,
    UNREADABLE_SKILL_MANIFEST,
    MISSING_SKILL_NAME,
    MISSING_SKILL_DESCRIPTION,
    MISSING_REFERENCE,
    INACCESSIBLE_WORKSPACE,
    BROKEN_WORKSPACE,
    SETTING_CONFLICT,
    SETTING_OVERLAP,
    MUTUALLY_EXCLUSIVE_MODES,
}

internal data class WorkspaceAuditInput(
    val id: String,
    val name: String,
    val accessible: Boolean,
    val broken: Boolean,
)

internal data class ExtensionIssue(
    val category: ExtensionCategory,
    val itemKey: String,
    val itemTitle: String,
    val kind: ExtensionIssueKind,
)

internal data class ExtensionSearchItem(
    val category: ExtensionCategory,
    val kind: ExtensionItemKind,
    val key: String,
    val title: String,
    val description: String,
    val enabled: Boolean,
    val assistantCount: Int,
    val hasIssue: Boolean,
    private val searchText: String,
) {
    fun matches(query: String): Boolean {
        val normalized = query.trim().lowercase()
        return normalized.isEmpty() || searchText.contains(normalized)
    }
}

internal data class ExtensionCategorySummary(
    val category: ExtensionCategory,
    val totalCount: Int,
    val enabledCount: Int,
    val issueCount: Int,
    val assistantCount: Int,
)

internal data class ExtensionAudit(
    val summaries: Map<ExtensionCategory, ExtensionCategorySummary>,
    val issues: List<ExtensionIssue>,
    val searchItems: List<ExtensionSearchItem>,
)

internal fun buildExtensionAudit(
    settings: Settings,
    skillScan: SkillScanResult,
    workspaces: List<WorkspaceAuditInput>,
): ExtensionAudit {
    val issues = mutableListOf<ExtensionIssue>()
    val items = mutableListOf<ExtensionSearchItem>()
    val assistants = settings.assistants

    fun addIssue(
        category: ExtensionCategory,
        itemKey: String,
        itemTitle: String,
        kind: ExtensionIssueKind,
    ) {
        issues += ExtensionIssue(category, itemKey, itemTitle.ifBlank { itemKey }, kind)
    }

    fun duplicateKeys(entries: List<Pair<String, String>>): Set<String> = entries
        .filter { (_, name) -> name.isNotBlank() }
        .groupBy { (_, name) -> name.trim().lowercase() }
        .values
        .filter { it.size > 1 }
        .flatten()
        .mapTo(linkedSetOf()) { (key, _) -> key }

    val quickDuplicates = duplicateKeys(settings.quickMessages.map { it.id.toString() to it.title })
    val quickMessageGroupNames = assistants.flatMap { assistant ->
        assistant.quickMessageGroups.flatMap { group ->
            group.quickMessageIds.map { quickMessageId -> quickMessageId to group.name }
        }
    }.groupBy({ it.first }, { it.second })
    settings.quickMessages.forEach { quickMessage ->
        val key = quickMessage.id.toString()
        val users = assistants.count { quickMessage.id in it.quickMessageIds }
        if (quickMessage.title.isBlank()) addIssue(ExtensionCategory.QUICK_MESSAGES, key, key, ExtensionIssueKind.EMPTY_NAME)
        if (quickMessage.content.isBlank()) addIssue(ExtensionCategory.QUICK_MESSAGES, key, quickMessage.title, ExtensionIssueKind.EMPTY_CONTENT)
        if (key in quickDuplicates) addIssue(ExtensionCategory.QUICK_MESSAGES, key, quickMessage.title, ExtensionIssueKind.DUPLICATE_NAME)
        items += ExtensionSearchItem(
            category = ExtensionCategory.QUICK_MESSAGES,
            kind = ExtensionItemKind.QUICK_MESSAGE,
            key = key,
            title = quickMessage.title,
            description = quickMessage.content,
            enabled = users > 0,
            assistantCount = users,
            hasIssue = issues.any { it.category == ExtensionCategory.QUICK_MESSAGES && it.itemKey == key },
            searchText = buildString {
                appendLine(quickMessage.title)
                append(quickMessage.content)
                if (settings.extensionManagementMode == ExtensionManagementMode.ENTERTAINMENT) {
                    appendLine()
                    appendLine(quickMessage.category)
                    appendLine(quickMessage.tags.joinToString())
                    append(quickMessageGroupNames[quickMessage.id].orEmpty().joinToString())
                }
            }.lowercase(),
        )
    }
    assistants.flatMap { it.quickMessageIds }.distinct()
        .filter { id -> settings.quickMessages.none { it.id == id } }
        .forEach { id ->
            addIssue(ExtensionCategory.QUICK_MESSAGES, "missing:$id", id.toString(), ExtensionIssueKind.MISSING_REFERENCE)
        }
    assistants.forEach { assistant ->
        val duplicateGroupIds = duplicateKeys(
            assistant.quickMessageGroups.map { it.id.toString() to it.name }
        )
        assistant.quickMessageGroups.forEach { group ->
            val key = "quick-message-group:${assistant.id}:${group.id}"
            val title = listOf(assistant.name, group.name)
                .filter { it.isNotBlank() }
                .joinToString(" · ")
            if (group.name.isBlank()) {
                addIssue(ExtensionCategory.QUICK_MESSAGES, key, title, ExtensionIssueKind.EMPTY_NAME)
            }
            if (group.id.toString() in duplicateGroupIds) {
                addIssue(ExtensionCategory.QUICK_MESSAGES, key, title, ExtensionIssueKind.DUPLICATE_NAME)
            }
            if (group.quickMessageIds.any { it !in assistant.quickMessageIds }) {
                addIssue(ExtensionCategory.QUICK_MESSAGES, key, title, ExtensionIssueKind.MISSING_REFERENCE)
            }
        }
    }

    val conflictingModeIds = if (settings.extensionManagementMode == ExtensionManagementMode.ENTERTAINMENT) {
        assistants.flatMap { assistant ->
            settings.modeInjections
                .filter { it.id in assistant.modeInjectionIds && it.exclusiveGroup.isNotBlank() }
                .groupBy { it.exclusiveGroup.trim().lowercase() }
                .values
                .filter { it.size > 1 }
                .flatten()
                .map { it.id }
        }.toSet()
    } else emptySet()

    if (settings.extensionManagementMode == ExtensionManagementMode.ENTERTAINMENT) {
        settings.lorebooks.forEach { lorebook ->
            lorebook.entries.filter { it.keywordExpression.isNotBlank() }.forEach { entry ->
                if (entry.evaluateKeywords("").error != null) {
                    addIssue(
                        ExtensionCategory.PROMPTS,
                        "lorebook:${lorebook.id}",
                        lorebook.name,
                        ExtensionIssueKind.INVALID_KEYWORD_EXPRESSION,
                    )
                }
            }
        }
        data class SettingDeclaration(
            val lorebook: me.rerere.rikkahub.data.model.Lorebook,
            val entry: PromptInjection.RegexInjection,
            val key: String,
        )
        val settingDeclarations = settings.lorebooks.flatMap { lorebook ->
            lorebook.entries.flatMap { entry ->
                entry.settingKeys.mapNotNull { key ->
                    key.trim().lowercase().takeIf(String::isNotEmpty)?.let {
                        SettingDeclaration(lorebook, entry, it)
                    }
                }
            }
        }
        settingDeclarations.groupBy { it.key }
            .values
            .filter { declarations -> declarations.map { it.entry.id }.distinct().size > 1 }
            .flatten()
            .forEach { declaration ->
                addIssue(
                    ExtensionCategory.PROMPTS,
                    "lorebook:${declaration.lorebook.id}",
                    declaration.lorebook.name,
                    ExtensionIssueKind.SETTING_OVERLAP,
                )
            }
        settingDeclarations.groupBy { it.key to it.entry.priority }
            .values
            .filter { declarations ->
                declarations.map { it.entry.content.trim() }.distinct().size > 1
            }
            .flatten()
            .forEach { declaration ->
                addIssue(
                    ExtensionCategory.PROMPTS,
                    "lorebook:${declaration.lorebook.id}",
                    declaration.lorebook.name,
                    ExtensionIssueKind.SETTING_CONFLICT,
                )
            }
    }

    val modeDuplicates = duplicateKeys(settings.modeInjections.map { it.id.toString() to it.name })
    settings.modeInjections.forEach { injection ->
        val key = "mode:${injection.id}"
        val users = assistants.count { injection.id in it.modeInjectionIds }
        validateInjection(injection, key, injection.name, ExtensionCategory.PROMPTS, issues)
        if (injection.id in conflictingModeIds) {
            addIssue(
                ExtensionCategory.PROMPTS,
                key,
                injection.name,
                ExtensionIssueKind.MUTUALLY_EXCLUSIVE_MODES,
            )
        }
        if (injection.id.toString() in modeDuplicates) addIssue(ExtensionCategory.PROMPTS, key, injection.name, ExtensionIssueKind.DUPLICATE_NAME)
        items += ExtensionSearchItem(
            category = ExtensionCategory.PROMPTS,
            kind = ExtensionItemKind.MODE_INJECTION,
            key = key,
            title = injection.name,
            description = injection.content,
            enabled = injection.enabled && users > 0,
            assistantCount = users,
            hasIssue = issues.any { it.category == ExtensionCategory.PROMPTS && it.itemKey == key },
            searchText = "${injection.name}\n${injection.content}".lowercase(),
        )
    }

    val lorebookDuplicates = duplicateKeys(settings.lorebooks.map { it.id.toString() to it.name })
    settings.lorebooks.forEach { lorebook ->
        val key = "lorebook:${lorebook.id}"
        val users = assistants.count { lorebook.id in it.lorebookIds }
        if (lorebook.name.isBlank()) addIssue(ExtensionCategory.PROMPTS, key, key, ExtensionIssueKind.EMPTY_NAME)
        if (lorebook.entries.isEmpty()) addIssue(ExtensionCategory.PROMPTS, key, lorebook.name, ExtensionIssueKind.EMPTY_LOREBOOK)
        if (lorebook.id.toString() in lorebookDuplicates) addIssue(ExtensionCategory.PROMPTS, key, lorebook.name, ExtensionIssueKind.DUPLICATE_NAME)
        val duplicateEntryIds = duplicateKeys(
            lorebook.entries.map { it.id.toString() to it.name }
        )
        lorebook.entries.forEach { entry ->
            validateInjection(entry, key, lorebook.name, ExtensionCategory.PROMPTS, issues)
            if (entry.id.toString() in duplicateEntryIds) {
                addIssue(ExtensionCategory.PROMPTS, key, lorebook.name, ExtensionIssueKind.DUPLICATE_NAME)
            }
            if (!entry.constantActive && entry.keywords.none { it.isNotBlank() }) {
                addIssue(ExtensionCategory.PROMPTS, key, lorebook.name, ExtensionIssueKind.MISSING_TRIGGER)
            }
            if (entry.scanDepth < 1) {
                addIssue(ExtensionCategory.PROMPTS, key, lorebook.name, ExtensionIssueKind.INVALID_SCAN_DEPTH)
            }
            if (entry.useRegex && entry.keywords.any { keyword ->
                    keyword.isNotBlank() && runCatching { Regex(keyword) }.isFailure
                }
            ) {
                addIssue(ExtensionCategory.PROMPTS, key, lorebook.name, ExtensionIssueKind.INVALID_REGEX)
            }
        }
        val entryText = lorebook.entries.joinToString("\n") {
            "${it.name}\n${it.keywords.joinToString()}\n${it.content}"
        }
        items += ExtensionSearchItem(
            category = ExtensionCategory.PROMPTS,
            kind = ExtensionItemKind.LOREBOOK,
            key = key,
            title = lorebook.name,
            description = lorebook.description,
            enabled = lorebook.enabled && users > 0,
            assistantCount = users,
            hasIssue = issues.any { it.category == ExtensionCategory.PROMPTS && it.itemKey == key },
            searchText = "${lorebook.name}\n${lorebook.description}\n$entryText".lowercase(),
        )
    }
    assistants.flatMap { it.modeInjectionIds }.distinct()
        .filter { id -> settings.modeInjections.none { it.id == id } }
        .forEach { id -> addIssue(ExtensionCategory.PROMPTS, "missing-mode:$id", id.toString(), ExtensionIssueKind.MISSING_REFERENCE) }
    assistants.flatMap { it.lorebookIds }.distinct()
        .filter { id -> settings.lorebooks.none { it.id == id } }
        .forEach { id -> addIssue(ExtensionCategory.PROMPTS, "missing-lorebook:$id", id.toString(), ExtensionIssueKind.MISSING_REFERENCE) }

    val duplicateSkillNames = skillScan.skills
        .groupBy { it.name.trim().lowercase() }
        .values
        .filter { it.size > 1 }
        .flatten()
        .mapTo(linkedSetOf()) { it.skillDir.absolutePath }
    skillScan.skills.forEach { skill ->
        val key = "skill:${skill.skillDir.absolutePath}"
        val users = assistants.count { skill.name in it.enabledSkills }
        if (skill.skillDir.absolutePath in duplicateSkillNames) {
            addIssue(ExtensionCategory.SKILLS, key, skill.name, ExtensionIssueKind.DUPLICATE_NAME)
        }
        items += ExtensionSearchItem(
            category = ExtensionCategory.SKILLS,
            kind = ExtensionItemKind.SKILL,
            key = key,
            title = skill.name,
            description = skill.description,
            enabled = users > 0,
            assistantCount = users,
            hasIssue = issues.any { it.category == ExtensionCategory.SKILLS && it.itemKey == key },
            searchText = "${skill.name}\n${skill.description}\n${skill.compatibility.orEmpty()}\n${skill.allowedTools.joinToString()}".lowercase(),
        )
    }
    skillScan.problems.forEach { problem ->
        val key = "broken-skill:${problem.directoryName}"
        val kind = when (problem.kind) {
            SkillScanProblemKind.MISSING_MANIFEST -> ExtensionIssueKind.MISSING_SKILL_MANIFEST
            SkillScanProblemKind.UNREADABLE_MANIFEST -> ExtensionIssueKind.UNREADABLE_SKILL_MANIFEST
            SkillScanProblemKind.MISSING_NAME -> ExtensionIssueKind.MISSING_SKILL_NAME
            SkillScanProblemKind.MISSING_DESCRIPTION -> ExtensionIssueKind.MISSING_SKILL_DESCRIPTION
        }
        addIssue(ExtensionCategory.SKILLS, key, problem.directoryName, kind)
        items += ExtensionSearchItem(
            category = ExtensionCategory.SKILLS,
            kind = ExtensionItemKind.SKILL,
            key = key,
            title = problem.directoryName,
            description = "",
            enabled = false,
            assistantCount = 0,
            hasIssue = true,
            searchText = problem.directoryName.lowercase(),
        )
    }
    val validSkillNames = skillScan.skills.mapTo(hashSetOf()) { it.name }
    assistants.flatMap { it.enabledSkills }.distinct()
        .filter { it !in validSkillNames }
        .forEach { name -> addIssue(ExtensionCategory.SKILLS, "missing-skill:$name", name, ExtensionIssueKind.MISSING_REFERENCE) }

    val workspaceDuplicates = duplicateKeys(workspaces.map { it.id to it.name })
    workspaces.forEach { workspace ->
        val key = "workspace:${workspace.id}"
        val users = assistants.count { it.workspaceId?.toString() == workspace.id }
        if (workspace.name.isBlank()) addIssue(ExtensionCategory.WORKSPACES, key, workspace.id, ExtensionIssueKind.EMPTY_NAME)
        if (workspace.id in workspaceDuplicates) addIssue(ExtensionCategory.WORKSPACES, key, workspace.name, ExtensionIssueKind.DUPLICATE_NAME)
        if (!workspace.accessible) addIssue(ExtensionCategory.WORKSPACES, key, workspace.name, ExtensionIssueKind.INACCESSIBLE_WORKSPACE)
        if (workspace.broken) addIssue(ExtensionCategory.WORKSPACES, key, workspace.name, ExtensionIssueKind.BROKEN_WORKSPACE)
        items += ExtensionSearchItem(
            category = ExtensionCategory.WORKSPACES,
            kind = ExtensionItemKind.WORKSPACE,
            key = key,
            title = workspace.name,
            description = "",
            enabled = users > 0 && workspace.accessible && !workspace.broken,
            assistantCount = users,
            hasIssue = issues.any { it.category == ExtensionCategory.WORKSPACES && it.itemKey == key },
            searchText = workspace.name.lowercase(),
        )
    }
    val workspaceIds = workspaces.mapTo(hashSetOf()) { it.id }
    assistants.mapNotNull { it.workspaceId?.toString() }.distinct()
        .filter { it !in workspaceIds }
        .forEach { id -> addIssue(ExtensionCategory.WORKSPACES, "missing-workspace:$id", id, ExtensionIssueKind.MISSING_REFERENCE) }

    val quickMessageIds = settings.quickMessages.mapTo(hashSetOf()) { it.id }
    val modeInjectionIds = settings.modeInjections.mapTo(hashSetOf()) { it.id }
    val lorebookIds = settings.lorebooks.mapTo(hashSetOf()) { it.id }
    val summaries = ExtensionCategory.entries.associateWith { category ->
        val categoryItems = items.filter { it.category == category }
        ExtensionCategorySummary(
            category = category,
            totalCount = categoryItems.size,
            enabledCount = categoryItems.count { it.enabled },
            issueCount = issues.filter { it.category == category }.distinctBy { it.itemKey }.size,
            assistantCount = when (category) {
                ExtensionCategory.QUICK_MESSAGES -> assistants.count {
                    it.quickMessageIds.any { id -> id in quickMessageIds }
                }
                ExtensionCategory.PROMPTS -> assistants.count {
                    it.modeInjectionIds.any { id -> id in modeInjectionIds } ||
                        it.lorebookIds.any { id -> id in lorebookIds }
                }
                ExtensionCategory.SKILLS -> assistants.count {
                    it.enabledSkills.any { name -> name in validSkillNames }
                }
                ExtensionCategory.WORKSPACES -> assistants.count {
                    it.workspaceId?.toString() in workspaceIds
                }
            },
        )
    }

    return ExtensionAudit(
        summaries = summaries,
        issues = issues.distinct(),
        searchItems = items,
    )
}

private fun validateInjection(
    injection: PromptInjection,
    itemKey: String,
    itemTitle: String,
    category: ExtensionCategory,
    issues: MutableList<ExtensionIssue>,
) {
    fun add(kind: ExtensionIssueKind) {
        issues += ExtensionIssue(category, itemKey, itemTitle.ifBlank { itemKey }, kind)
    }
    if (injection.name.isBlank()) add(ExtensionIssueKind.EMPTY_NAME)
    if (injection.content.isBlank()) add(ExtensionIssueKind.EMPTY_CONTENT)
    if (injection.position == InjectionPosition.AT_DEPTH && injection.injectDepth < 1) {
        add(ExtensionIssueKind.INVALID_INJECTION_DEPTH)
    }
    if (injection.role !in setOf(MessageRole.USER, MessageRole.ASSISTANT)) {
        add(ExtensionIssueKind.INVALID_ROLE)
    }
}
