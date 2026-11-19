package me.rerere.rikkahub.utils

import me.rerere.ai.provider.Model
import org.junit.Assert.*
import org.junit.Test

class ModelIconMatchingTest {
    @Test fun `icons use recognized model names and keep ID fallback for nicknames`() {
        assertEquals("gemini-color.svg", computeAIIcon(Model(modelId = "deployment-42", displayName = "Google Gemini 4 Argon")))
        assertEquals("openai.svg", computeAIIcon(Model(modelId = "deployment", displayName = "GPT 6.1 Sol")))
        assertEquals("deepseek-color.svg", computeAIIcon(Model(modelId = "gpt-4o", displayName = "DeepSeek V4 Pro")))
        assertEquals("openai.svg", computeAIIcon(Model(modelId = "gpt-4o", displayName = "我的助手")))
        assertNull(computeAIIcon(Model(modelId = "opaque", displayName = "未命名助手")))
    }
}
