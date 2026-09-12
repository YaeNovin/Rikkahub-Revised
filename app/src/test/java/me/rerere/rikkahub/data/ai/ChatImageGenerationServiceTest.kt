package me.rerere.rikkahub.data.ai

import me.rerere.rikkahub.data.model.ChatImageGenerationSettings
import org.junit.Assert.assertEquals
import org.junit.Test

class ChatImageGenerationServiceTest {
    @Test
    fun `chat image options always constrain to one output`() {
        assertEquals(5, ChatImageGenerationSettings(count = 5).normalized().count)
        assertEquals(1, ChatImageGenerationSettings(count = 0).normalized().count)
    }
}
