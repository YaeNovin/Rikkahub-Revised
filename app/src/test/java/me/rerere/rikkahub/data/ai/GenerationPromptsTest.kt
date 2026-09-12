package me.rerere.rikkahub.data.ai

import me.rerere.rikkahub.data.model.AssistantMemory
import me.rerere.rikkahub.data.model.MemoryLifecycleState
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

    @Test
    fun `memory prompt excludes completed and superseded memories`() {
        val prompt = buildMemoryPrompt(
            memories = listOf(
                memory(1, "Active event", MemoryType.EPISODIC),
                memory(2, "Completed event", MemoryType.EPISODIC, lifecycleState = MemoryLifecycleState.COMPLETED),
                memory(3, "Old decision", MemoryType.EPISODIC, lifecycleState = MemoryLifecycleState.SUPERSEDED),
            ),
            includeEpisodic = true,
        )

        assertTrue(prompt.contains("Active event"))
        assertFalse(prompt.contains("Completed event"))
        assertFalse(prompt.contains("Old decision"))
    }

    private fun memory(
        id: Int,
        content: String,
        type: MemoryType,
        createdAt: Long = 1L,
        sourceConversationId: String? = null,
        lifecycleState: MemoryLifecycleState = MemoryLifecycleState.ACTIVE,
    ) = AssistantMemory(
        id = id,
        content = content,
        type = type,
        createdAt = createdAt,
        sourceConversationId = sourceConversationId,
        lifecycleState = lifecycleState,
    )
}
