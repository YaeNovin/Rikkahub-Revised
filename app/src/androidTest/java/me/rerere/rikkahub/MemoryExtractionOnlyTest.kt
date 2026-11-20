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
import me.rerere.rikkahub.data.model.memoryEmbeddingKey
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

    @Test fun assistantClearDiscardsLateExtractionButAllowsAFreshRequest() = runBlocking {
        Harness(clearDuringRequest = true).use { h ->
            val effective = h.conversation.memoryAssistant(h.assistant, h.settings)
            assertTrue(runCatching { h.extractor.extractAndPersist(h.settings, effective, h.conversation.id.toString(), h.messages) }.exceptionOrNull() is CancellationException)
            assertTrue(h.repository.getMemoriesOfAssistant(h.assistant.id.toString()).isEmpty())
            val next = h.extractor.extractAndPersist(h.settings, effective, h.conversation.id.toString(), h.messages)
            assertTrue(next is MemoryExtractionOutcome.Saved)
            assertEquals(1, h.repository.getMemoriesOfAssistant(h.assistant.id.toString()).size)
        }
    }

    @Test fun slowEmbeddingQueryUsesLexicalMemoryWithinTheSharedBudget() = runBlocking {
        Harness(slowQuery = true).use { h ->
            val memory = h.repository.addMemory(h.assistant.id.toString(), "Prefers short answers")
            val provider = h.settings.providers.single()
            val key = memoryEmbeddingKey(h.embedding, provider)
            h.repository.prepareEmbedding(memory, key)
            h.repository.updateEmbedding(memory.id, ByteArray(8), h.embedding.id.toString(), 2, memory.content, key)
            val query = listOf(UIMessage.user("short answers"))
            val start = System.nanoTime()
            val transformed = MemoryRetrievalTransformer(h.repository, h.providers, h.indexer).transform(
                TransformerContext(context, h.chat, h.assistant, h.settings, conversationId = h.conversation.id, conversationMessages = query), query)
            assertTrue("Slow vector request must not consume the provider's full timeout", (System.nanoTime() - start) / 1_000_000 < 3_000)
            assertEquals(1, h.vectorCalls.get())
            assertTrue(transformed.any { it.toText().contains("Prefers short answers") })
        }
    }

    private inner class Harness(cancelDuringRequest: Boolean = false, clearDuringRequest: Boolean = false, slowQuery: Boolean = false) : AutoCloseable {
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
                if (slowQuery) {
                    val deadline = System.nanoTime() + 4_000_000_000L
                    while (!chain.call().isCanceled() && System.nanoTime() < deadline) Thread.sleep(10)
                    if (chain.call().isCanceled()) throw java.io.IOException("Cancelled fixture query")
                }
                """{"data":[{"object":"embedding","index":0,"embedding":[0.1,0.2]}],"model":"test-embedding","usage":{"prompt_tokens":5,"total_tokens":5}}"""
            } else {
                val call = chatCalls.incrementAndGet()
                if (clearDuringRequest && call == 1) runBlocking { repository.deleteMemoriesOfAssistant(assistant.id.toString()) }
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
