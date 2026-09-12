package me.rerere.rikkahub

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.ai.buildMemoryPrompt
import me.rerere.rikkahub.data.ai.transformers.TransformerContext
import me.rerere.rikkahub.data.ai.transforms.MemoryRetrievalTransformer
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.memory.ConversationMemoryIndexService
import me.rerere.rikkahub.data.memory.MemoryEmbeddingService
import me.rerere.rikkahub.data.memory.MemoryExtractionOutcome
import me.rerere.rikkahub.data.memory.MemoryExtractionService
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.ConversationMemoryMode
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.data.model.memoryAssistant
import me.rerere.rikkahub.data.repository.MemoryRepository
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.uuid.Uuid

/** In-memory Room + intercepted HTTP only; never contacts a provider or opens user databases. */
class MemoryExtractionOnlyTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun extractionPersistsAndFeedsBasicMemoryWithoutEmbeddingOrRag() = runBlocking {
        Harness().use { h ->
            val effective = h.conversation.memoryAssistant(h.assistant, h.settings)
            assertFalse(effective.enableMemoryRag)
            val outcome = h.extractor.extractAndPersist(h.settings, effective, h.conversation.id.toString(), h.messages)
            assertTrue(outcome is MemoryExtractionOutcome.Saved)
            assertEquals(1, h.chatCalls.get())
            assertEquals(0, h.vectorCalls.get())
            val records = h.repository.getMemoryRecordsOfAssistant(h.assistant.id.toString())
            assertEquals(1, records.size)
            assertNull(records.single().embedding)
            assertTrue(buildMemoryPrompt(records.map { it.memory }, false).contains("Prefers short answers"))
            // Even existing vector records cannot cause a query in extraction-only mode.
            h.repository.updateEmbedding(records.single().memory.id, ByteArray(8), h.embedding.id.toString(), 2, records.single().memory.content)
            val retrieval = MemoryRetrievalTransformer(h.repository, h.providers, h.indexer)
            val transformed = retrieval.transform(TransformerContext(context, h.chat, effective, h.settings,
                conversationId = h.conversation.id, conversationMessages = h.messages), h.messages)
            assertEquals(h.messages, transformed)
            assertEquals(0, h.vectorCalls.get())
            h.indexer.synchronize(h.settings, h.conversation, isAllowed = { effective.enableMemoryRag })
            assertTrue(h.database.conversationMemoryDao().getChunks(h.conversation.id.toString()).isEmpty())
        }
    }

    @Test fun regularRagMemoryStillIndexesExtractedRecords() = runBlocking {
        Harness().use { h ->
            val effective = h.conversation.copy(memoryMode = ConversationMemoryMode.ENABLED).memoryAssistant(h.assistant, h.settings)
            h.extractor.extractAndPersist(h.settings, effective, h.conversation.id.toString(), h.messages)
            assertEquals(1, h.chatCalls.get())
            assertEquals(1, h.vectorCalls.get())
            assertNotNull(h.repository.getMemoryRecordsOfAssistant(h.assistant.id.toString()).single().embedding)
        }
    }

    @Test fun changedModeDiscardsLateExtractionResponse() = runBlocking {
        Harness(cancelDuringRequest = true).use { h ->
            val result = runCatching { h.extractor.extractAndPersist(h.settings,
                h.conversation.memoryAssistant(h.assistant, h.settings), h.conversation.id.toString(), h.messages,
                isAllowed = { h.allowed.get() }) }
            assertTrue(result.exceptionOrNull() is CancellationException)
            assertTrue(h.repository.getMemoriesOfAssistant(h.assistant.id.toString()).isEmpty())
            assertEquals(0, h.vectorCalls.get())
        }
    }

    private inner class Harness(cancelDuringRequest: Boolean = false) : AutoCloseable {
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val chat = Model(modelId = "test-extractor", type = ModelType.CHAT)
        val embedding = Model(modelId = "test-embedding", type = ModelType.EMBEDDING)
        val assistant = Assistant(enableMemory = true, enableMemoryRag = true)
        val messages = listOf(UIMessage.user("I prefer short answers."), UIMessage.assistant("Understood."))
        val conversation = Conversation.ofId(Uuid.random(), assistant.id).copy(
            memoryMode = ConversationMemoryMode.EXTRACTION_ONLY, messageNodes = messages.map(MessageNode::of))
        val settings = Settings(memoryExtractionModelId = chat.id, embeddingModelId = embedding.id,
            providers = listOf(ProviderSetting.OpenAI(baseUrl = "https://memory-test.invalid/v1", apiKey = "test-only", models = listOf(chat, embedding))))
        val allowed = AtomicBoolean(true)
        val chatCalls = AtomicInteger()
        val vectorCalls = AtomicInteger()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val body = if (chain.request().url.encodedPath.endsWith("/embeddings")) {
                vectorCalls.incrementAndGet()
                """{"data":[{"object":"embedding","index":0,"embedding":[0.1,0.2]}],"model":"test-embedding","usage":{"prompt_tokens":5,"total_tokens":5}}"""
            } else {
                chatCalls.incrementAndGet()
                if (cancelDuringRequest) allowed.set(false)
                buildJsonObject {
                    put("id", "test-completion")
                    put("model", "test-extractor")
                    put("choices", buildJsonArray { add(buildJsonObject {
                        put("index", 0)
                        put("finish_reason", "stop")
                        put("message", buildJsonObject {
                            put("role", "assistant")
                            put("content", """{"memories":[{"type":"fact","content":"Prefers short answers","confidence":0.99}]}""")
                        })
                    }) })
                }.toString()
            }
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }.build()
        val providers = ProviderManager(client, context)
        val repository = MemoryRepository(database.memoryDao())
        val indexer = ConversationMemoryIndexService(database.conversationMemoryDao(), providers, repository)
        val extractor = MemoryExtractionService(providers, MemoryEmbeddingService(repository, providers), repository,
            database.conversationMemoryDao(), Json { ignoreUnknownKeys = true })
        override fun close() { database.close(); client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll() }
    }
}
