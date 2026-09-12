package me.rerere.rikkahub.data.ai.transforms

import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.memory.ConversationMemoryRecord
import me.rerere.rikkahub.data.model.AssistantMemory
import me.rerere.rikkahub.data.model.MemoryType
import me.rerere.rikkahub.data.model.MemoryLifecycleState
import me.rerere.rikkahub.data.repository.isRetrievable
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

    @Test
    fun `conversation retrieval excludes visible and future branch turns`() {
        val visible = UIMessage.user("visible")
        val eligible = UIMessage.user("eligible old turn")
        val future = UIMessage.user("future branch turn")
        val records = listOf(visible, eligible, future).mapIndexed { index, message ->
            ConversationMemoryRecord(
                id = "record-$index",
                sourceMessageId = message.id.toString(),
                ordinal = index,
                content = message.toText(),
                embedding = null,
                embeddingModelId = null,
                embeddingDimension = null,
            )
        }

        val result = records.eligibleForRequest(
            conversationMessages = listOf(visible, eligible),
            requestMessages = listOf(visible),
        )

        assertEquals(listOf("eligible old turn"), result.map { it.content })
    }

    @Test
    fun `only active memories are eligible for normal retrieval`() {
        val memories = listOf(
            memory(MemoryType.EPISODIC, nowMs, MemoryLifecycleState.ACTIVE),
            memory(MemoryType.EPISODIC, nowMs, MemoryLifecycleState.COMPLETED),
            memory(MemoryType.EPISODIC, nowMs, MemoryLifecycleState.SUPERSEDED),
        )

        assertEquals(
            listOf(MemoryLifecycleState.ACTIVE),
            memories.filter(AssistantMemory::isRetrievable).map { it.lifecycleState },
        )
    }

    private fun memory(
        type: MemoryType,
        createdAt: Long,
        lifecycleState: MemoryLifecycleState = MemoryLifecycleState.ACTIVE,
    ) = AssistantMemory(
        id = 1,
        content = "memory",
        type = type,
        createdAt = createdAt,
        lifecycleState = lifecycleState,
    )
}
