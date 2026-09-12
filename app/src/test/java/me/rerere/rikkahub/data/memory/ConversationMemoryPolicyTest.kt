package me.rerere.rikkahub.data.memory

import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.resolveMemoryExtractionModel
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.ConversationMemoryMode
import me.rerere.rikkahub.data.model.memoryAssistant
import me.rerere.rikkahub.data.model.usesVectorMemory
import me.rerere.rikkahub.data.model.memoryCapabilityMode
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

class ConversationMemoryPolicyTest {
    private val assistant = Assistant(enableMemory = true, enableMemoryRag = true)
    private val conversation = Conversation.ofId(Uuid.random(), assistant.id)

    @Test fun `extraction only overrides assistant rag but preserves shared memory and episodic choices`() {
        val source = assistant.copy(enableMemory = false, enableMemoryRag = true, useGlobalMemory = true, enableEpisodicMemory = true)
        val effective = conversation.copy(memoryMode = ConversationMemoryMode.EXTRACTION_ONLY).memoryAssistant(source, Settings())
        assertTrue(effective.enableMemory)
        assertFalse(effective.enableMemoryRag)
        assertTrue(effective.useGlobalMemory)
        assertTrue(effective.enableEpisodicMemory)
        assertFalse(source.enableMemory)
        assertTrue(source.enableMemoryRag)
        assertEquals("basic_prompt", effective.memoryCapabilityMode())
    }

    @Test fun `changing extraction model to embedding never silently reactivates rag in extraction only mode`() {
        val model = Model(modelId = "vector", type = ModelType.EMBEDDING)
        val settings = Settings(memoryExtractionModelId = model.id, providers = listOf(ProviderSetting.OpenAI(models = listOf(model))))
        val effective = conversation.copy(memoryMode = ConversationMemoryMode.EXTRACTION_ONLY).memoryAssistant(assistant, settings)
        assertFalse(effective.enableMemoryRag)
        assertNull(settings.resolveMemoryExtractionModel())
    }

    @Test fun `every memory mode survives persistence and branch copying`() {
        ConversationMemoryMode.entries.forEach { mode ->
            val source = conversation.copy(memoryMode = mode)
            val encoded = me.rerere.rikkahub.utils.JsonInstant.encodeToString(Conversation.serializer(), source)
            val restored = me.rerere.rikkahub.utils.JsonInstant.decodeFromString(Conversation.serializer(), encoded)
            assertEquals(mode, restored.memoryMode)
            assertEquals(mode, ConversationMemoryMode.valueOf(mode.name))
            val branch = restored.copy(id = Uuid.random(), sourceConversationId = source.id)
            assertEquals(source.memoryAssistant(assistant, Settings()), branch.memoryAssistant(assistant, Settings()))
        }
    }

    @Test fun `rag only is described as active by capability diagnostics`() {
        val effective = conversation.copy(memoryMode = ConversationMemoryMode.RAG_ONLY).memoryAssistant(assistant, Settings())
        assertEquals("rag_background", effective.memoryCapabilityMode())
        assertEquals("disabled", conversation.memoryAssistant(assistant, Settings()).memoryCapabilityMode())
    }

    @Test
    fun `new conversations are isolated even with assistant memory enabled`() {
        val effective = conversation.memoryAssistant(assistant, Settings())
        assertFalse(effective.enableMemory)
        assertFalse(effective.enableMemoryRag)
        assertTrue(assistant.enableMemory)
    }

    @Test
    fun `RAG can operate without shared memory or a chat extractor`() {
        val effective = conversation.copy(memoryMode = ConversationMemoryMode.RAG_ONLY)
            .memoryAssistant(assistant.copy(enableMemory = false, enableMemoryRag = false), Settings())
        assertFalse(effective.enableMemory)
        assertTrue(effective.enableMemoryRag)
    }

    @Test
    fun `embedding selection switches enabled memory to vector only`() {
        val model = Model(modelId = "embedding", type = ModelType.EMBEDDING)
        val settings = Settings(
            memoryExtractionModelId = model.id,
            providers = listOf(ProviderSetting.OpenAI(models = listOf(model))),
        )
        val effective = conversation.copy(memoryMode = ConversationMemoryMode.ENABLED)
            .memoryAssistant(assistant, settings)
        assertTrue(settings.usesVectorMemory())
        assertFalse(settings.copy(memoryExtractionModelId = null).usesVectorMemory())
        assertFalse(settings.copy(providers = emptyList()).usesVectorMemory())
        assertFalse(settings.copy(providers = listOf(
            ProviderSetting.OpenAI(models = listOf(model.copy(type = ModelType.CHAT)))
        )).usesVectorMemory())
        assertFalse(effective.enableMemory)
        assertTrue(effective.enableMemoryRag)
        assertFalse(conversation.memoryAssistant(assistant, settings).enableMemoryRag)
        assertNull(settings.resolveMemoryExtractionModel())
    }

    @Test
    fun `legacy inherited mode follows assistant settings`() {
        val inherited = conversation.copy(memoryMode = ConversationMemoryMode.INHERIT)
        assertEquals(assistant, inherited.memoryAssistant(assistant, Settings()))
        val disabled = assistant.copy(enableMemory = false, enableMemoryRag = false)
        assertEquals(disabled, inherited.memoryAssistant(disabled, Settings()))
    }

    @Test
    fun `conversation memory mode survives serialization`() {
        val source = conversation.copy(memoryMode = ConversationMemoryMode.RAG_ONLY)
        val encoded = me.rerere.rikkahub.utils.JsonInstant.encodeToString(Conversation.serializer(), source)
        val restored = me.rerere.rikkahub.utils.JsonInstant.decodeFromString(Conversation.serializer(), encoded)
        assertEquals(source.memoryMode, restored.memoryMode)
    }

    @Test
    fun `image only user message starts a distinct indexed turn`() {
        val image = UIMessage(
            role = MessageRole.USER,
            parts = listOf(UIMessagePart.Image("data:image/png;base64,private-image")),
        )
        val messages = listOf(
            UIMessage.user("earlier question"), UIMessage.assistant("earlier answer"),
            image, UIMessage.assistant("image description"),
        )
        val drafts = buildConversationMemoryDrafts("conversation", "assistant", messages)
        assertEquals(2, drafts.size)
        assertEquals(image.id.toString(), drafts.last().sourceMessageId)
        assertTrue(drafts.last().content.contains("image description"))
        assertFalse(drafts.last().content.contains("private-image"))
        assertNotNull(buildMemoryExtractionTranscript(messages))
    }
}
