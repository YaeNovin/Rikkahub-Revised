package me.rerere.rikkahub.ui.pages.extensions

import java.io.File
import kotlin.uuid.Uuid
import me.rerere.ai.core.MessageRole
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.ExtensionManagementMode
import me.rerere.rikkahub.data.files.SkillMetadata
import me.rerere.rikkahub.data.files.SkillScanProblem
import me.rerere.rikkahub.data.files.SkillScanProblemKind
import me.rerere.rikkahub.data.files.SkillScanResult
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Lorebook
import me.rerere.rikkahub.data.model.PromptInjection
import me.rerere.rikkahub.data.model.QuickMessage
import me.rerere.rikkahub.data.model.QuickMessageGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExtensionAuditTest {
    @Test
    fun `summaries count enabled items and distinct assistant usage`() {
        val quickMessage = QuickMessage(title = "Start scene", content = "Begin at the station")
        val emptyQuickMessage = QuickMessage()
        val mode = PromptInjection.ModeInjection(name = "Narration", content = "Use third person")
        val lorebook = Lorebook(
            name = "City",
            enabled = false,
            entries = listOf(
                PromptInjection.RegexInjection(
                    name = "Station",
                    content = "The station closes at midnight",
                    keywords = listOf("station"),
                )
            ),
        )
        val workspaceId = Uuid.random()
        val skill = SkillMetadata(
            name = "dice",
            description = "Roll dice",
            skillDir = File("dice"),
        )
        val assistants = listOf(
            Assistant(
                name = "One",
                quickMessageIds = setOf(quickMessage.id),
                modeInjectionIds = setOf(mode.id),
                lorebookIds = setOf(lorebook.id),
                enabledSkills = setOf(skill.name),
                workspaceId = workspaceId,
            ),
            Assistant(name = "Two", quickMessageIds = setOf(quickMessage.id)),
        )

        val audit = buildExtensionAudit(
            settings = Settings(
                assistants = assistants,
                quickMessages = listOf(quickMessage, emptyQuickMessage),
                modeInjections = listOf(mode),
                lorebooks = listOf(lorebook),
            ),
            skillScan = SkillScanResult(skills = listOf(skill)),
            workspaces = listOf(
                WorkspaceAuditInput(
                    id = workspaceId.toString(),
                    name = "Project",
                    accessible = true,
                    broken = false,
                )
            ),
        )

        assertEquals(
            ExtensionCategorySummary(ExtensionCategory.QUICK_MESSAGES, 2, 1, 1, 2),
            audit.summaries[ExtensionCategory.QUICK_MESSAGES],
        )
        assertEquals(
            ExtensionCategorySummary(ExtensionCategory.PROMPTS, 2, 1, 0, 1),
            audit.summaries[ExtensionCategory.PROMPTS],
        )
        assertEquals(
            ExtensionCategorySummary(ExtensionCategory.SKILLS, 1, 1, 0, 1),
            audit.summaries[ExtensionCategory.SKILLS],
        )
        assertEquals(
            ExtensionCategorySummary(ExtensionCategory.WORKSPACES, 1, 1, 0, 1),
            audit.summaries[ExtensionCategory.WORKSPACES],
        )
        assertTrue(audit.searchItems.single { it.key == quickMessage.id.toString() }.matches("STATION"))
    }

    @Test
    fun `audit reports conflicts invalid rules broken skills and stale references`() {
        val first = QuickMessage(title = "Same", content = "A")
        val second = QuickMessage(title = " same ", content = "B")
        val lorebook = Lorebook(
            name = "Broken lore",
            entries = listOf(
                PromptInjection.RegexInjection(
                    name = "Bad regex",
                    content = "Content",
                    keywords = listOf("("),
                    useRegex = true,
                    scanDepth = 0,
                    role = MessageRole.SYSTEM,
                )
            ),
        )
        val missingQuickMessageId = Uuid.random()
        val staleGroupMessageId = Uuid.random()
        val missingWorkspaceId = Uuid.random()
        val audit = buildExtensionAudit(
            settings = Settings(
                assistants = listOf(
                    Assistant(
                        name = "Roleplay assistant",
                        quickMessageIds = setOf(missingQuickMessageId),
                        quickMessageGroups = listOf(
                            QuickMessageGroup(
                                name = "Actions",
                                quickMessageIds = setOf(staleGroupMessageId),
                            ),
                            QuickMessageGroup(name = " actions "),
                        ),
                        enabledSkills = setOf("missing-skill"),
                        workspaceId = missingWorkspaceId,
                    )
                ),
                quickMessages = listOf(first, second),
                modeInjections = emptyList(),
                lorebooks = listOf(lorebook),
            ),
            skillScan = SkillScanResult(
                problems = listOf(
                    SkillScanProblem("bad-skill", SkillScanProblemKind.MISSING_MANIFEST)
                )
            ),
            workspaces = listOf(
                WorkspaceAuditInput(
                    id = Uuid.random().toString(),
                    name = "Unavailable",
                    accessible = false,
                    broken = true,
                )
            ),
        )

        val kinds = audit.issues.mapTo(hashSetOf()) { it.kind }
        assertTrue(ExtensionIssueKind.DUPLICATE_NAME in kinds)
        assertTrue(ExtensionIssueKind.INVALID_REGEX in kinds)
        assertTrue(ExtensionIssueKind.INVALID_SCAN_DEPTH in kinds)
        assertTrue(ExtensionIssueKind.INVALID_ROLE in kinds)
        assertTrue(ExtensionIssueKind.MISSING_SKILL_MANIFEST in kinds)
        assertTrue(ExtensionIssueKind.MISSING_REFERENCE in kinds)
        assertTrue(ExtensionIssueKind.INACCESSIBLE_WORKSPACE in kinds)
        assertTrue(ExtensionIssueKind.BROKEN_WORKSPACE in kinds)
        assertEquals(0, audit.summaries.getValue(ExtensionCategory.QUICK_MESSAGES).assistantCount)
        assertEquals(0, audit.summaries.getValue(ExtensionCategory.SKILLS).assistantCount)
        assertEquals(0, audit.summaries.getValue(ExtensionCategory.WORKSPACES).assistantCount)
    }

    @Test
    fun `entertainment audit reports invalid expressions and same priority setting conflicts`() {
        val firstPerson = PromptInjection.ModeInjection(
            name = "First person",
            exclusiveGroup = "perspective",
        )
        val thirdPerson = PromptInjection.ModeInjection(
            name = "Third person",
            exclusiveGroup = "Perspective",
        )
        val lorebook = Lorebook(
            name = "Conflicting lore",
            entries = listOf(
                PromptInjection.RegexInjection(
                    name = "Day",
                    content = "It is daytime",
                    keywordExpression = "day AND (",
                    settingKeys = listOf("time"),
                    priority = 10,
                ),
                PromptInjection.RegexInjection(
                    name = "Night",
                    content = "It is nighttime",
                    keywords = listOf("night"),
                    settingKeys = listOf("TIME"),
                    priority = 10,
                ),
            ),
        )

        val audit = buildExtensionAudit(
            settings = Settings(
                extensionManagementMode = ExtensionManagementMode.ENTERTAINMENT,
                assistants = listOf(
                    Assistant(modeInjectionIds = setOf(firstPerson.id, thirdPerson.id))
                ),
                modeInjections = listOf(firstPerson, thirdPerson),
                lorebooks = listOf(lorebook),
            ),
            skillScan = SkillScanResult(),
            workspaces = emptyList(),
        )

        val kinds = audit.issues.map { it.kind }.toSet()
        assertTrue(ExtensionIssueKind.INVALID_KEYWORD_EXPRESSION in kinds)
        assertTrue(ExtensionIssueKind.SETTING_CONFLICT in kinds)
        assertTrue(ExtensionIssueKind.SETTING_OVERLAP in kinds)
        assertTrue(ExtensionIssueKind.MUTUALLY_EXCLUSIVE_MODES in kinds)
        assertTrue(audit.searchItems.single { it.kind == ExtensionItemKind.LOREBOOK }.hasIssue)
    }
}
