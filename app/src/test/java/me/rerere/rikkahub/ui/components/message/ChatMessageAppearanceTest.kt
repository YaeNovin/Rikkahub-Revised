package me.rerere.rikkahub.ui.components.message

import me.rerere.rikkahub.data.datastore.DisplaySetting
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatMessageAppearanceTest {
    @Test
    fun `live blur is limited to compact user messages`() {
        assertTrue(canUseLiveChatBubbleBlur(isUser = true, text = "Short message"))
        assertFalse(canUseLiveChatBubbleBlur(isUser = false, text = "Short message"))
        assertFalse(canUseLiveChatBubbleBlur(isUser = true, text = "x".repeat(801)))
        assertFalse(canUseLiveChatBubbleBlur(isUser = true, text = (1..11).joinToString("\n")))
    }

    @Test
    fun `live blur rejects renderer-heavy content`() {
        assertFalse(canUseLiveChatBubbleBlur(isUser = true, text = "```kotlin\nval x = 1\n```"))
        assertFalse(canUseLiveChatBubbleBlur(isUser = true, text = "<svg viewBox=\"0 0 10 10\"></svg>"))
        assertFalse(canUseLiveChatBubbleBlur(isUser = true, text = "<table><tr></tr></table>"))
    }

    @Test
    fun `assistant bubble switch remains authoritative with a chat background`() {
        val assistant = Assistant(background = "file:///backgrounds/dark.jpg")
        val settings = Settings(
            assistantId = assistant.id,
            assistants = listOf(assistant),
            displaySetting = DisplaySetting(showAssistantBubble = false),
        )

        assertFalse(shouldRenderAssistantMessageBubble(settings))
        assertTrue(
            shouldRenderAssistantMessageBubble(
                settings.copy(
                    displaySetting = settings.displaySetting.copy(showAssistantBubble = true),
                )
            )
        )
    }
}
