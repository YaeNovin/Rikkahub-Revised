package me.rerere.rikkahub.data.model

import kotlin.uuid.Uuid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import me.rerere.rikkahub.utils.JsonInstant

class PromptInjectionRulesTest {
    @Test
    fun `keyword expressions support and or not parentheses and quoted phrases`() {
        val entry = PromptInjection.RegexInjection(
            keywordExpression = "(dragon OR wyrm) AND NOT \"deep sleep\"",
        )

        val matched = entry.evaluateKeywords("A dragon guards the gate")
        assertTrue(matched.matched)
        assertEquals(listOf("dragon"), matched.matchedTerms)
        assertFalse(entry.evaluateKeywords("The dragon is in deep sleep").matched)
        assertTrue(entry.evaluateKeywords("A wyrm circles overhead").matched)
    }

    @Test
    fun `invalid keyword expressions return a diagnostic error`() {
        val entry = PromptInjection.RegexInjection(keywordExpression = "dragon AND (")
        val result = entry.evaluateKeywords("dragon")
        assertFalse(result.matched)
        assertTrue(result.error?.isNotBlank() == true)
    }

    @Test
    fun `temporary and conversation modes override assistant defaults by exclusive group`() {
        val firstPerson = PromptInjection.ModeInjection(
            name = "First person",
            exclusiveGroup = "perspective",
        )
        val thirdPerson = PromptInjection.ModeInjection(
            name = "Third person",
            exclusiveGroup = "Perspective",
        )
        val relaxed = PromptInjection.ModeInjection(
            name = "Relaxed",
            exclusiveGroup = "tone",
        )

        val active = resolveActiveModes(
            modeInjections = listOf(firstPerson, thirdPerson, relaxed),
            assistantModeIds = setOf(firstPerson.id, relaxed.id),
            conversationModeIds = setOf(thirdPerson.id),
            temporaryModes = mapOf(firstPerson.id to 5),
            currentUserTurn = 3,
        )

        assertEquals(listOf("First person", "Relaxed"), active.map { it.injection.name })
        assertEquals(ModeActivationScope.TEMPORARY, active.first().scope)
        assertEquals(2, active.first().remainingTurns)
    }

    @Test
    fun `exclusive selection removes only modes from the same group`() {
        val first = PromptInjection.ModeInjection(exclusiveGroup = "perspective")
        val third = PromptInjection.ModeInjection(exclusiveGroup = "perspective")
        val combat = PromptInjection.ModeInjection(exclusiveGroup = "scene")

        assertEquals(
            setOf(third.id, combat.id),
            selectExclusiveMode(setOf(first.id, combat.id), third.id, listOf(first, third, combat)),
        )
    }

    @Test
    fun `conversation runtime fields survive serialization`() {
        val modeId = Uuid.random()
        val entryId = Uuid.random()
        val conversation = Conversation(
            assistantId = Uuid.random(),
            messageNodes = emptyList(),
            temporaryModeInjections = mapOf(modeId to 12),
            lorebookRuntimeStates = mapOf(
                entryId to LorebookEntryRuntimeState(8, 10, 13)
            ),
        )

        val decoded = JsonInstant.decodeFromString<Conversation>(
            JsonInstant.encodeToString(conversation)
        )
        assertEquals(conversation.temporaryModeInjections, decoded.temporaryModeInjections)
        assertEquals(conversation.lorebookRuntimeStates, decoded.lorebookRuntimeStates)
    }
}
