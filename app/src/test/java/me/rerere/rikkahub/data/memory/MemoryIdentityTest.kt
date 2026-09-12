package me.rerere.rikkahub.data.memory

import kotlinx.serialization.json.JsonPrimitive
import me.rerere.ai.provider.*
import me.rerere.rikkahub.data.model.*
import org.junit.Assert.*
import org.junit.Test

class MemoryIdentityTest {
    @Test fun `ownership is explicit and is independent of provenance`() {
        assertEquals(MemoryScopeType.CONVERSATION to "chat", memoryScope(MemoryType.EPISODIC, "assistant", "chat"))
        assertEquals(MemoryScopeType.GLOBAL to "__global__", memoryScope(MemoryType.FACT, "__global__", "chat"))
        val moved = AssistantMemory(1, type = MemoryType.EPISODIC, sourceConversationId = "old", scopeType = MemoryScopeType.CONVERSATION, scopeId = "new")
        assertTrue(moved.isVisibleInConversation("new")); assertFalse(moved.isVisibleInConversation("old"))
        assertFalse(moved.copy(scopeType = MemoryScopeType.UNASSIGNED).isVisibleInConversation("new"))
    }

    @Test fun `embedding fingerprint changes for model endpoint dimensions and chunking but not rotating keys`() {
        val model = Model(modelId = "embedding", type = ModelType.EMBEDDING)
        val provider = ProviderSetting.OpenAI(baseUrl = "https://one.example/v1", apiKey = "first")
        val key = memoryEmbeddingKey(model, provider)
        assertEquals(key, memoryEmbeddingKey(model, provider.copy(apiKey = "rotated")))
        assertNotEquals(key, memoryEmbeddingKey(model.copy(modelId = "other"), provider))
        assertNotEquals(key, memoryEmbeddingKey(model, provider.copy(baseUrl = "https://two.example/v1")))
        assertNotEquals(key, memoryEmbeddingKey(model.copy(customBodies = listOf(CustomBody("dimensions", JsonPrimitive(512)))), provider))
        assertNotEquals(key, memoryEmbeddingKey(model, provider, "conversation-v1"))
        assertEquals(64, key.length)
    }
}
