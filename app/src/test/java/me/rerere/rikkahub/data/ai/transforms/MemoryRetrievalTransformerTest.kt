package me.rerere.rikkahub.data.ai.transforms

import me.rerere.rikkahub.data.model.AssistantMemory
import me.rerere.rikkahub.data.model.MemoryType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryRetrievalTransformerTest {
    private val nowMs = 2_000_000_000_000L

    @Test
    fun `recent episodic memory receives a gentle relevance boost`() {
        val recent = memory(MemoryType.EPISODIC, createdAt = nowMs - 86_400_000L)
        val old = memory(MemoryType.EPISODIC, createdAt = nowMs - 365L * 86_400_000L)

        val recentScore = applyEpisodicRecencyBoost(recent, score = 0.5f, nowMs = nowMs)
        val oldScore = applyEpisodicRecencyBoost(old, score = 0.5f, nowMs = nowMs)

        assertTrue(recentScore > oldScore)
        assertTrue(recentScore < 0.59f)
    }

    @Test
    fun `facts and non-positive matches are not boosted`() {
        val fact = memory(MemoryType.FACT, createdAt = nowMs)
        val episode = memory(MemoryType.EPISODIC, createdAt = nowMs)

        assertEquals(0.5f, applyEpisodicRecencyBoost(fact, 0.5f, nowMs), 0.0001f)
        assertEquals(0f, applyEpisodicRecencyBoost(episode, 0f, nowMs), 0.0001f)
    }

    private fun memory(type: MemoryType, createdAt: Long) = AssistantMemory(
        id = 1,
        content = "memory",
        type = type,
        createdAt = createdAt,
    )
}
