package me.rerere.rikkahub.data.memory

import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.db.entity.ConversationMemoryExclusionEntity
import me.rerere.rikkahub.data.model.*
import org.junit.Assert.*
import org.junit.Test

class MemoryScopeTest {
    @Test fun `facts remain shared but events require the current owning conversation`() {
        val event = AssistantMemory(1, "Event", MemoryType.EPISODIC, sourceConversationId = "a")
        assertTrue(event.isVisibleInConversation("a"))
        assertFalse(event.isVisibleInConversation("b"))
        assertFalse(event.copy(sourceConversationId = null).isVisibleInConversation("a"))
        assertTrue(event.copy(type = MemoryType.FACT).isVisibleInConversation("b"))
    }

    @Test fun `deletion suppresses only that conversation chunk version including fallback drafts`() {
        val messages = listOf(UIMessage.user("Remember this"), UIMessage.assistant("OK"))
        val draft = buildConversationMemoryDrafts("a", "assistant", messages).single()
        val exclusion = ConversationMemoryExclusionEntity("a", draft.id, draft.contentHash)
        assertTrue(filterExcludedConversationChunks(listOf(draft), listOf(exclusion)).isEmpty())
        val edited = draft.copy(content = "changed", contentHash = memoryContentHash("changed"))
        assertEquals(listOf(edited), filterExcludedConversationChunks(listOf(edited), listOf(exclusion)))
        val branch = buildConversationMemoryDrafts("branch", "assistant", messages).single()
        assertEquals(listOf(branch), filterExcludedConversationChunks(listOf(branch), listOf(exclusion)))
        assertTrue(mergeConversationMemoryEntities(listOf(draft), emptyList()).isEmpty())
    }

    @Test fun `content hash identifies content version rather than a globally unique memory`() {
        assertEquals(memoryContentHash("same"), memoryContentHash("same"))
        assertNotEquals(memoryContentHash("same"), memoryContentHash("changed"))
        assertEquals(64, memoryContentHash("中文").length)
    }
}
