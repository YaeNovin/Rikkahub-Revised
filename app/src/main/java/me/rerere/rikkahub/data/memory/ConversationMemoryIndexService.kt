package me.rerere.rikkahub.data.memory

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.map
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.EmbeddingGenerationParams
import me.rerere.ai.provider.EmbeddingTaskType
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.usesGoogleMultimodalEmbeddingApi
import me.rerere.ai.provider.usesVolcengineMultimodalEmbeddingApi
import me.rerere.ai.provider.usesVolcengineTextEmbeddingApi
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.datastore.resolveEmbeddingModel
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.ai.provider.ModelType
import me.rerere.rikkahub.data.db.dao.ConversationMemoryDAO
import me.rerere.rikkahub.data.db.entity.ConversationMemoryEntity
import me.rerere.rikkahub.data.db.entity.ConversationMemoryExclusionEntity
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.memoryEmbeddingKey
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

private const val TAG = "ConversationMemory"
private const val CHUNK_CHARS = 2_800
private const val CHUNK_OVERLAP_CHARS = 240
private const val DEFAULT_BATCH_SIZE = 24
private const val ARK_TEXT_BATCH_SIZE = 4

data class ConversationMemoryRecord(
    val id: String,
    val sourceMessageId: String,
    val ordinal: Int,
    val content: String,
    val embedding: ByteArray?,
    val embeddingModelId: String?,
    val embeddingDimension: Int?,
    val contentHash: String = me.rerere.rikkahub.data.model.memoryContentHash(content),
    val embeddingKey: String? = null,
)

class ConversationMemoryIndexService(
    private val dao: ConversationMemoryDAO,
    private val providerManager: ProviderManager,
    private val memoryRepository: me.rerere.rikkahub.data.repository.MemoryRepository,
) {
    fun observeRecentRecords(conversationId: String) = dao.observeRecentChunks(conversationId)
        .map { chunks -> chunks.map(ConversationMemoryEntity::toRecord) }

    suspend fun deleteRecord(conversationId: String, id: String): Boolean = withContext(Dispatchers.IO) {
        dao.deleteChunkForUser(conversationId, id)
    }

    suspend fun synchronize(
        settings: Settings,
        conversation: Conversation,
        isAllowed: () -> Boolean = { true },
    ) = withContext(Dispatchers.IO) {
        val writeEpoch = memoryRepository.captureWriteEpoch()
        if (!isAllowed()) return@withContext
        val drafts = filterExcludedConversationChunks(buildConversationMemoryDrafts(
            conversationId = conversation.id.toString(),
            assistantId = conversation.assistantId.toString(),
            messages = conversation.currentMessages,
        ), dao.getExclusions(conversation.id.toString()))
        val existing = dao.getChunks(conversation.id.toString()).associateBy { it.id }
        if (!isAllowed()) return@withContext
        val validIds = drafts.mapTo(hashSetOf()) { it.id }
        val staleIds = existing.keys.filterNot(validIds::contains)
        if (staleIds.isNotEmpty()) {
            memoryRepository.withCurrentWriteEpoch(writeEpoch) { dao.deleteChunks(conversation.id.toString(), staleIds) }
        }
        if (drafts.isEmpty()) return@withContext

        val changedDrafts = drafts.filter { draft ->
            existing[draft.id]?.contentHash != draft.contentHash
        }
        if (!isAllowed()) return@withContext
        if (changedDrafts.isNotEmpty()) {
            // Text remains searchable even while no embedding model is configured or its
            // provider is temporarily unavailable. Changed text never retains an old vector.
            memoryRepository.withCurrentWriteEpoch(writeEpoch) { dao.upsertVisibleChunks(changedDrafts) }
        }

        val model = settings.memoryExtractionModelId?.let(settings::findModelById)
            ?.takeIf { it.type == ModelType.EMBEDDING }
            ?: settings.resolveEmbeddingModel() ?: return@withContext
        val providerSetting = model.findProvider(settings.providers)
            ?.takeIf { it.enabled }
            ?: return@withContext

        val modelId = model.id.toString()
        val embeddingKey = memoryEmbeddingKey(model, providerSetting, "conversation-v1")
        val pending = drafts.mapNotNull { draft ->
            val old = existing[draft.id]
            if (old?.contentHash == draft.contentHash &&
                old.embeddingModelId == modelId &&
                old.embedding != null &&
                old.embeddingDimension != null
                && old.embeddingKey == embeddingKey
            ) {
                null
            } else {
                draft.copy(embeddingKey = embeddingKey)
            }
        }
        if (pending.isEmpty()) return@withContext

        // Also persist unchanged text whose previous vector belongs to another model. This clears
        // the stale vector before network work, so a failed re-index falls back to lexical search.
        if (!isAllowed()) return@withContext
        memoryRepository.withCurrentWriteEpoch(writeEpoch) { dao.upsertVisibleChunks(pending) }
        val batchSize = when {
            model.usesVolcengineMultimodalEmbeddingApi() || model.usesGoogleMultimodalEmbeddingApi() -> 1
            model.usesVolcengineTextEmbeddingApi() || providerSetting.isArkEndpoint() -> ARK_TEXT_BATCH_SIZE
            else -> DEFAULT_BATCH_SIZE
        }
        val provider = providerManager.getProviderByType(providerSetting)
        pending.chunked(batchSize).forEach { batch ->
            if (!isAllowed()) return@withContext
            runCatching {
                memoryRepository.trackRun(conversation.id.toString(), modelId, "conversation_embedding",
                    me.rerere.rikkahub.data.model.memoryContentHash(batch.joinToString("\n") { it.contentHash })) { runId ->
                val result = provider.generateEmbedding(
                    providerSetting = providerSetting,
                    params = EmbeddingGenerationParams(
                        model = model,
                        input = batch.map { it.content },
                        taskType = EmbeddingTaskType.RETRIEVAL_DOCUMENT,
                        customHeaders = model.customHeaders,
                        customBody = model.customBodies,
                        requestId = runId,
                    ),
                )
                require(result.embeddings.size == batch.size) {
                    "Embedding provider returned ${result.embeddings.size} vectors for ${batch.size} conversation chunks"
                }
                if (!isAllowed() || !memoryRepository.isWriteEpochCurrent(writeEpoch)) throw CancellationException("Memory settings changed or memory was cleared")
                batch.zip(result.embeddings).forEach { (draft, vector) ->
                    require(vector.isNotEmpty() && vector.all(Float::isFinite)) {
                        "Embedding provider returned an empty or invalid conversation vector"
                    }
                    dao.updateVectorIfCurrent(
                        conversationId = draft.conversationId, id = draft.id, contentHash = draft.contentHash,
                        embedding = vector.toByteArray(),
                        modelId = modelId,
                        dimension = vector.size,
                        expectedKey = embeddingKey,
                    )
                }
                }
            }.onFailure { error ->
                if (error is CancellationException) throw error
                Log.w(TAG, "Unable to index ${batch.size} conversation chunks", error)
                me.rerere.common.android.Logging.logSoftwareError(TAG, "ConversationIndex", error)
            }
        }
    }

    suspend fun getRecords(
        conversationId: String,
        fallbackMessages: List<UIMessage>,
        assistantId: String,
    ): List<ConversationMemoryRecord> = withContext(Dispatchers.IO) {
        val stored = dao.getChunks(conversationId)
        val drafts = buildConversationMemoryDrafts(conversationId, assistantId, fallbackMessages)
        filterExcludedConversationChunks(mergeConversationMemoryEntities(stored, drafts), dao.getExclusions(conversationId))
            .map(ConversationMemoryEntity::toRecord)
    }
}

internal fun filterExcludedConversationChunks(chunks: List<ConversationMemoryEntity>, exclusions: List<ConversationMemoryExclusionEntity>): List<ConversationMemoryEntity> {
    val excluded = exclusions.mapTo(hashSetOf()) { Triple(it.conversationId, it.chunkId, it.contentHash) }
    return chunks.filterNot { Triple(it.conversationId, it.id, it.contentHash) in excluded }
}

internal fun mergeConversationMemoryEntities(
    stored: List<ConversationMemoryEntity>,
    drafts: List<ConversationMemoryEntity>,
): List<ConversationMemoryEntity> {
    if (drafts.isEmpty()) return emptyList()
    val storedById = stored.associateBy { it.id }
    return drafts.map { draft ->
        storedById[draft.id]
            ?.takeIf { it.contentHash == draft.contentHash }
            ?: draft
    }
}

internal fun buildConversationMemoryDrafts(
    conversationId: String,
    assistantId: String,
    messages: List<UIMessage>,
): List<ConversationMemoryEntity> {
    data class Turn(val sourceMessageId: String, val ordinal: Int, val content: String)

    val turns = mutableListOf<Turn>()
    var sourceMessageId: String? = null
    var turnOrdinal = 0
    var text = StringBuilder()
    fun flush() {
        val source = sourceMessageId ?: return
        val content = text.toString().trim()
        if (content.isNotBlank()) turns += Turn(source, turnOrdinal++, content)
        sourceMessageId = null
        text = StringBuilder()
    }
    messages.forEach { message ->
        if (message.role == MessageRole.USER) flush()
        val messageText = message.memoryText().takeIf { it.isNotBlank() } ?: return@forEach
        when (message.role) {
            MessageRole.USER -> {
                sourceMessageId = message.id.toString()
                text.append("User:\n").append(messageText)
            }
            MessageRole.ASSISTANT -> if (sourceMessageId != null) {
                text.append("\n\nAssistant:\n").append(messageText)
            }
            else -> Unit
        }
    }
    flush()

    val now = System.currentTimeMillis()
    return turns.flatMap { turn ->
        splitWithOverlap(turn.content).mapIndexed { part, content ->
            val id = "$conversationId:${turn.sourceMessageId}:$part"
            ConversationMemoryEntity(
                id = id,
                conversationId = conversationId,
                assistantId = assistantId,
                sourceMessageId = turn.sourceMessageId,
                ordinal = turn.ordinal * 1_000 + part,
                content = content,
                contentHash = sha256(content),
                updatedAt = now,
            )
        }
    }
}

private fun splitWithOverlap(value: String): List<String> = buildList {
    var start = 0
    while (start < value.length) {
        val end = (start + CHUNK_CHARS).coerceAtMost(value.length)
        add(value.substring(start, end))
        if (end == value.length) break
        start = (end - CHUNK_OVERLAP_CHARS).coerceAtLeast(start + 1)
    }
}

private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray())
    .joinToString("") { "%02x".format(it) }

private fun List<Float>.toByteArray(): ByteArray {
    val buffer = ByteBuffer.allocate(size * Float.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
    forEach(buffer::putFloat)
    return buffer.array()
}

private fun ConversationMemoryEntity.toRecord() = ConversationMemoryRecord(
    id = id,
    sourceMessageId = sourceMessageId,
    ordinal = ordinal,
    content = content,
    embedding = embedding,
    embeddingModelId = embeddingModelId,
    embeddingDimension = embeddingDimension,
    contentHash = contentHash,
    embeddingKey = embeddingKey,
)

private fun ProviderSetting.isArkEndpoint(): Boolean =
    this is ProviderSetting.OpenAI && baseUrl.contains(".volces.com", ignoreCase = true)
