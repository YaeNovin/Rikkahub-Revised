package me.rerere.rikkahub.data.model

import org.junit.Assert.*
import org.junit.Test

class AssistantEditsTest {
    @Test fun `suggestion configuration update preserves concurrent name and feedback changes`() {
        val before = Assistant(name = "old")
        val feedback = listOf(SuggestionFeedback("topic", SuggestionFeedbackReason.REPETITIVE, ChatSuggestionCategory.FOLLOW_UP))
        val latest = before.copy(name = "current", suggestionFeedback = feedback)
        val config = ChatSuggestionConfig(count = 3)
        val actual = mergeAssistantEdits(before, before.copy(suggestionSettings = config), latest)
        assertEquals("current", actual.name)
        assertEquals(feedback, actual.suggestionFeedback)
        assertEquals(config, actual.suggestionSettings)
    }
    @Test fun `changing name from stale UI does not reset newly saved background`() {
        val before = Assistant(name = "old")
        val latest = before.copy(background = "file:///new.jpg", useGradientBackground = true,
            gradientBackgroundCustomColors = GradientBackgroundCustomColors(baseColors = listOf(0xFF123456)))
        val actual = mergeAssistantEdits(before, before.copy(name = "new"), latest)
        assertEquals(latest.copy(name = "new"), actual)
    }
    @Test fun `background update retains concurrent model settings and explicit clear works`() {
        val before = Assistant(background = "file:///old.jpg")
        val latest = before.copy(name = "latest", gradientBackgroundSpeed = 1.5f)
        val actual = mergeAssistantEdits(before, before.copy(background = null), latest)
        assertEquals(latest.copy(background = null), actual)
    }
    @Test fun `independent gradient edits combine instead of reverting previous slider`() {
        val before = Assistant(useGradientBackground = true)
        val latest = before.copy(gradientBackgroundSpeed = 2f)
        val actual = mergeAssistantEdits(before, before.copy(gradientBackgroundAngle = 90f), latest)
        assertEquals(2f, actual.gradientBackgroundSpeed)
        assertEquals(90f, actual.gradientBackgroundAngle)
    }
}
