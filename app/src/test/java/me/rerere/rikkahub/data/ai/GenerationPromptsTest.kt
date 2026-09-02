package me.rerere.rikkahub.data.ai

import me.rerere.rikkahub.data.model.AssistantMemory
import me.rerere.rikkahub.data.model.MemoryType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GenerationPromptsTest {
    @Test
    fun `basic prompt excludes episodic memory when disabled`() {
        val prompt = buildMemoryPrompt(
            memories = listOf(
                memory(id = 1, content = "Prefers concise replies", type = MemoryType.FACT),
                memory(id = 2, content = "Visited Hangzhou yesterday", type = MemoryType.EPISODIC),
            ),
            includeEpisodic = false,
        )

        assertTrue(prompt.contains("Prefers concise replies"))
        assertFalse(prompt.contains("Visited Hangzhou yesterday"))
        assertTrue(prompt.contains("\"type\": \"fact\""))
    }

    @Test
    fun `memory prompt preserves metadata and stays within total budget`() {
        val memories = (1..10).map { id ->
            memory(
                id = id,
                content = "memory-$id " + "x".repeat(2_000),
                type = if (id % 2 == 0) MemoryType.EPISODIC else MemoryType.FACT,
                createdAt = 123_456L + id,
                sourceConversationId = "conversation-$id",
            )
        }

        val prompt = buildMemoryPrompt(
            memories = memories,
            includeEpisodic = true,
            maxChars = 800,
        )

        assertTrue(prompt.length <= 800)
        assertTrue(prompt.endsWith("</memory_context>\n"))
        assertTrue(prompt.contains("created_at_ms"))
        assertTrue(prompt.contains("source_conversation_id"))
    }

    private fun memory(
        id: Int,
        content: String,
        type: MemoryType,
        createdAt: Long = 1L,
        sourceConversationId: String? = null,
    ) = AssistantMemory(
        id = id,
        content = content,
        type = type,
        createdAt = createdAt,
        sourceConversationId = sourceConversationId,
    )
}
