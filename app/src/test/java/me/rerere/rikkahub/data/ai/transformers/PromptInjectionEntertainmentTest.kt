package me.rerere.rikkahub.data.ai.transformers

import kotlin.uuid.Uuid
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Lorebook
import me.rerere.rikkahub.data.model.LorebookEntryStatus
import me.rerere.rikkahub.data.model.LorebookOverflowStrategy
import me.rerere.rikkahub.data.model.PromptInjection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptInjectionEntertainmentTest {
    @Test
    fun `conversation and temporary modes are ignored when assistant disallows overrides`() {
        val assistantMode = PromptInjection.ModeInjection(content = "assistant")
        val conversationMode = PromptInjection.ModeInjection(content = "conversation")
        val temporaryMode = PromptInjection.ModeInjection(content = "temporary")

        val evaluation = evaluateInjections(
            messages = listOf(UIMessage.user("hello")),
            assistant = Assistant(
                modeInjectionIds = setOf(assistantMode.id),
                allowConversationPromptInjection = false,
            ),
            modeInjections = listOf(assistantMode, conversationMode, temporaryMode),
            lorebooks = emptyList(),
            conversationModeInjectionIds = setOf(conversationMode.id),
            temporaryModeInjections = mapOf(temporaryMode.id to 10),
            currentUserTurn = 1,
            entertainmentMode = true,
        )

        assertEquals(listOf(assistantMode.id), evaluation.injections.map { it.id })
    }

    @Test
    fun `budget exclusion does not activate sticky or cooldown state`() {
        val lorebookId = Uuid.random()
        val entry = PromptInjection.RegexInjection(
            name = "Large event",
            content = "x".repeat(200),
            keywordExpression = "event",
            stickyTurns = 3,
            cooldownTurns = 2,
        )
        val evaluation = evaluateInjections(
            messages = listOf(UIMessage.user("event")),
            assistant = Assistant(lorebookIds = setOf(lorebookId)),
            modeInjections = emptyList(),
            lorebooks = listOf(
                Lorebook(
                    id = lorebookId,
                    entries = listOf(entry),
                    tokenBudget = 1,
                    overflowStrategy = LorebookOverflowStrategy.DROP_LOW_PRIORITY,
                )
            ),
            entertainmentMode = true,
        )

        assertTrue(evaluation.injections.isEmpty())
        assertFalse(entry.id in evaluation.runtimeStates)
        assertEquals(LorebookEntryStatus.BUDGET_EXCEEDED, evaluation.diagnostics.entries.single().status)
    }

    @Test
    fun `triggered entry stays active then observes cooldown on a later match`() {
        val lorebookId = Uuid.random()
        val entry = PromptInjection.RegexInjection(
            name = "Random event",
            content = "Event content",
            keywordExpression = "event",
            scanDepth = 1,
            stickyTurns = 2,
            cooldownTurns = 2,
        )
        val lorebook = Lorebook(id = lorebookId, entries = listOf(entry))
        val assistant = Assistant(lorebookIds = setOf(lorebookId))

        val first = evaluateInjections(
            messages = listOf(UIMessage.user("event")),
            assistant = assistant,
            modeInjections = emptyList(),
            lorebooks = listOf(lorebook),
            entertainmentMode = true,
        )
        assertEquals(LorebookEntryStatus.USED, first.diagnostics.entries.single().status)

        val sticky = evaluateInjections(
            messages = listOf(UIMessage.user("event"), UIMessage.user("nothing")),
            assistant = assistant,
            modeInjections = emptyList(),
            lorebooks = listOf(lorebook),
            runtimeStates = first.runtimeStates,
            entertainmentMode = true,
        )
        assertEquals(LorebookEntryStatus.ACTIVE_FROM_PREVIOUS_TURN, sticky.diagnostics.entries.single().status)

        val cooldown = evaluateInjections(
            messages = listOf(
                UIMessage.user("event"),
                UIMessage.user("nothing"),
                UIMessage.user("event"),
            ),
            assistant = assistant,
            modeInjections = emptyList(),
            lorebooks = listOf(lorebook),
            runtimeStates = first.runtimeStates,
            entertainmentMode = true,
        )
        assertTrue(cooldown.injections.isEmpty())
        assertEquals(LorebookEntryStatus.COOLDOWN, cooldown.diagnostics.entries.single().status)
    }
}
