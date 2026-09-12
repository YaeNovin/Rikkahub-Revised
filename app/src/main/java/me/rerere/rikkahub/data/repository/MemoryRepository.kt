package me.rerere.rikkahub.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import me.rerere.rikkahub.data.db.dao.MemoryDAO
import me.rerere.rikkahub.data.db.entity.MemoryEntity
import me.rerere.rikkahub.data.model.AssistantMemory
import me.rerere.rikkahub.data.model.MemoryLifecycleState
import me.rerere.rikkahub.data.model.MemoryType
import me.rerere.rikkahub.data.model.isVisibleInConversation
import me.rerere.rikkahub.data.model.*
import me.rerere.rikkahub.data.db.entity.MemorySourceEntity
import me.rerere.rikkahub.data.db.entity.MemoryRunEntity
import kotlin.time.Clock
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicLong

class MemoryRepository(private val memoryDAO: MemoryDAO) {
    private val mutationMutex = Mutex()
    private val writeEpoch = AtomicLong()
    fun captureWriteEpoch(): Long = writeEpoch.get()
    fun isWriteEpochCurrent(epoch: Long): Boolean = writeEpoch.get() == epoch
    suspend fun <T> withCurrentWriteEpoch(epoch: Long, action: suspend () -> T): T = mutationMutex.withLock {
        if (!isWriteEpochCurrent(epoch)) throw kotlinx.coroutines.CancellationException("Memory was cleared")
        action()
    }
    suspend fun withBulkDeletion(action: suspend () -> Unit) = mutationMutex.withLock {
        writeEpoch.incrementAndGet()
        action()
    }
    companion object {
        const val GLOBAL_MEMORY_ID = "__global__"
    }

    fun getMemoriesOfAssistantFlow(assistantId: String): Flow<List<AssistantMemory>> =
        memoryDAO.getMemoriesOfAssistantFlow(assistantId)
            .map { entities -> entities.map(MemoryEntity::toAssistantMemory) }

    fun getActiveMemoriesOfAssistantFlow(assistantId: String): Flow<List<AssistantMemory>> =
        getMemoriesOfAssistantFlow(assistantId).map { memories ->
            memories.filter(AssistantMemory::isRetrievable)
        }

    suspend fun getMemoriesOfAssistant(assistantId: String): List<AssistantMemory> {
        return memoryDAO.getMemoriesOfAssistant(assistantId)
            .map(MemoryEntity::toAssistantMemory)
    }

    suspend fun getMemoryRecordsOfAssistant(assistantId: String): List<MemorySearchRecord> =
        memoryDAO.getMemoriesOfAssistant(assistantId).map { entity ->
            MemorySearchRecord(
                memory = entity.toAssistantMemory(),
                embedding = entity.embedding,
                embeddingModelId = entity.embeddingModelId,
                embeddingDimension = entity.embeddingDimension,
                embeddingKey = entity.embeddingKey,
            )
        }

    suspend fun getMemoriesForConversation(assistantId: String, conversationId: String?): List<AssistantMemory> =
        getMemoriesOfAssistant(assistantId).filter { it.isVisibleInConversation(conversationId) }

    suspend fun getRecordsForConversation(assistantId: String, conversationId: String?): List<MemorySearchRecord> =
        getMemoryRecordsOfAssistant(assistantId).filter { it.memory.isVisibleInConversation(conversationId) }

    suspend fun getSources(uid: String) = memoryDAO.getSources(uid)
    suspend fun getRun(id: String) = memoryDAO.getRun(id)
    suspend fun saveRun(run: MemoryRunEntity) = memoryDAO.upsertRun(run)

    suspend fun <T> trackRun(conversationId: String?, modelId: String, operation: String, inputHash: String, block: suspend (String) -> T): T {
        val id = kotlin.uuid.Uuid.random().toString()
        val start = System.nanoTime()
        val run = MemoryRunEntity(id, conversationId, modelId, operation, "running", inputHash, System.currentTimeMillis(), requestId = id)
        saveRun(run)
        var status = "completed"
        try { return block(id) }
        catch (cancelled: kotlinx.coroutines.CancellationException) { status = "cancelled"; throw cancelled }
        catch (error: Exception) { status = "failed"; throw error }
        finally {
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                saveRun(run.copy(status = status, finishedAt = System.currentTimeMillis(), durationMs = (System.nanoTime() - start) / 1_000_000))
                me.rerere.common.android.Logging.recordEvent("MEMORY_RUN", "run=$id operation=$operation status=$status model=$modelId")
            }
        }
    }

    fun getGlobalMemoriesFlow(): Flow<List<AssistantMemory>> =
        memoryDAO.getMemoriesOfAssistantFlow(GLOBAL_MEMORY_ID)
            .map { entities -> entities.map(MemoryEntity::toAssistantMemory) }

    fun getActiveGlobalMemoriesFlow(): Flow<List<AssistantMemory>> =
        getGlobalMemoriesFlow().map { memories ->
            memories.filter(AssistantMemory::isRetrievable)
        }

    suspend fun getGlobalMemories(): List<AssistantMemory> {
        return memoryDAO.getMemoriesOfAssistant(GLOBAL_MEMORY_ID)
            .map(MemoryEntity::toAssistantMemory)
    }

    suspend fun deleteMemoriesOfAssistant(assistantId: String) {
        memoryDAO.deleteMemoriesOfAssistant(assistantId)
    }

    suspend fun updateContent(id: Int, content: String): AssistantMemory {
        return updateMemory(id = id, content = content)
    }

    suspend fun updateMemory(
        id: Int,
        content: String,
        type: MemoryType? = null,
        lifecycleState: MemoryLifecycleState? = null,
    ): AssistantMemory {
        val old = memoryDAO.getMemoryById(id) ?: error("Memory record #$id not found")
        return updateMemory(
            old = old,
            content = content,
            type = type,
            lifecycleState = lifecycleState,
        )
    }

    suspend fun updateContent(
        assistantId: String,
        id: Int,
        content: String,
    ): AssistantMemory? {
        return updateMemory(assistantId = assistantId, id = id, content = content)
    }

    suspend fun updateMemory(
        assistantId: String,
        id: Int,
        content: String,
        type: MemoryType? = null,
        lifecycleState: MemoryLifecycleState? = null,
        expectedRevision: Long? = null,
    ): AssistantMemory? {
        val old = memoryDAO.getMemoryByIdOfAssistant(id, assistantId) ?: return null
        if (expectedRevision != null && old.revision != expectedRevision) throw MemoryRevisionConflict()
        return updateMemory(
            old = old,
            content = content,
            type = type,
            lifecycleState = lifecycleState,
        )
    }

    private suspend fun updateMemory(
        old: MemoryEntity,
        content: String,
        type: MemoryType?,
        lifecycleState: MemoryLifecycleState? = null,
    ): AssistantMemory {
        val normalizedContent = content.trim().also {
            require(it.isNotEmpty()) { "Memory content must not be blank" }
        }
        val effectiveType = type ?: MemoryType.fromWireName(old.memoryType)
        val oldLifecycle = MemoryLifecycleState.fromWireName(old.lifecycleState)
        val effectiveLifecycle = if (effectiveType == MemoryType.EPISODIC) {
            lifecycleState ?: oldLifecycle
        } else {
            MemoryLifecycleState.ACTIVE
        }
        val lifecycleChanged = effectiveLifecycle != oldLifecycle
        val scope = if (effectiveType == MemoryType.fromWireName(old.memoryType))
            runCatching { MemoryScopeType.valueOf(old.scopeType.uppercase()) }.getOrDefault(MemoryScopeType.UNASSIGNED) to old.scopeId
            else memoryScope(effectiveType, old.assistantId, old.sourceConversationId)
        val newMemory = old.copy(
            content = normalizedContent,
            memoryType = effectiveType.name.lowercase(),
            lifecycleState = effectiveLifecycle.name.lowercase(),
            lifecycleUpdatedAt = if (lifecycleChanged) {
                Clock.System.now().toEpochMilliseconds()
            } else {
                old.lifecycleUpdatedAt
            },
            supersededByMemoryId = old.supersededByMemoryId
                .takeIf { effectiveLifecycle == MemoryLifecycleState.SUPERSEDED },
            embedding = null,
            embeddingModelId = null,
            embeddingDimension = null,
            uid = old.uid,
            revision = old.revision + 1,
            contentHash = memoryContentHash(normalizedContent),
            scopeType = scope.first.name.lowercase(),
            scopeId = scope.second,
            embeddingKey = null,
            supersededByUid = old.supersededByUid.takeIf { effectiveLifecycle == MemoryLifecycleState.SUPERSEDED },
        )
        if (!memoryDAO.updateMemoryIfCurrent(newMemory, old.revision)) throw MemoryRevisionConflict()
        return newMemory.toAssistantMemory()
    }

    suspend fun addMemory(
        assistantId: String,
        content: String,
        type: MemoryType = MemoryType.FACT,
        sourceConversationId: String? = null,
        lifecycleState: MemoryLifecycleState = MemoryLifecycleState.ACTIVE,
        sourceMessageIds: List<String> = emptyList(),
        runId: String? = null,
    ): AssistantMemory {
        val normalizedContent = content.trim().also {
            require(it.isNotEmpty()) { "Memory content must not be blank" }
        }
        val createdAt = Clock.System.now().toEpochMilliseconds()
        val effectiveLifecycle = lifecycleState.takeIf { type == MemoryType.EPISODIC }
            ?: MemoryLifecycleState.ACTIVE
        val memory = AssistantMemory(
            id = 0,
            content = normalizedContent,
            type = type,
            createdAt = createdAt,
            sourceConversationId = sourceConversationId,
            lifecycleState = effectiveLifecycle,
            lifecycleUpdatedAt = createdAt,
        )
        val scope = memoryScope(type, assistantId, sourceConversationId)
        val entity = MemoryEntity(
                    assistantId = assistantId,
                    content = memory.content,
                    memoryType = type.name.lowercase(),
                    createdAt = createdAt,
                    sourceConversationId = sourceConversationId,
                    lifecycleState = effectiveLifecycle.name.lowercase(),
                    lifecycleUpdatedAt = createdAt,
                    scopeType = scope.first.name.lowercase(), scopeId = scope.second,
                    createdByRunId = runId,
                )
        val sources = sourceConversationId?.let { conversation ->
            sourceMessageIds.distinct().ifEmpty { listOf("") }.map { MemorySourceEntity(entity.uid, conversation, it) }
        }.orEmpty()
        val id = memoryDAO.insertMemoryWithSources(entity, sources).toInt()
        return entity.copy(id = id).toAssistantMemory()
    }

    /**
     * Adds a memory only when the normalized content does not already exist for this
     * assistant and type. Background extraction can run more than once for a turn
     * (retries, regeneration, or process restarts), so duplicate rows are undesirable.
     */
    suspend fun addMemoryIfAbsent(
        assistantId: String,
        content: String,
        type: MemoryType = MemoryType.FACT,
        sourceConversationId: String? = null,
        lifecycleState: MemoryLifecycleState = MemoryLifecycleState.ACTIVE,
        expectedWriteEpoch: Long? = null,
        sourceMessageIds: List<String> = emptyList(),
        runId: String? = null,
    ): AssistantMemory? = mutationMutex.withLock {
        if (expectedWriteEpoch != null && !isWriteEpochCurrent(expectedWriteEpoch)) throw kotlinx.coroutines.CancellationException("Memory was cleared")
        val normalizedContent = content.trim().also {
            require(it.isNotEmpty()) { "Memory content must not be blank" }
        }
        val existing = memoryDAO.findMemoryByContent(
            assistantId = assistantId,
            memoryType = type.name.lowercase(),
            content = normalizedContent,
            conversationId = sourceConversationId,
        )
        if (existing != null) {
            sourceConversationId?.let { conversation ->
                memoryDAO.insertSources(sourceMessageIds.distinct().ifEmpty { listOf("") }.map { MemorySourceEntity(existing.uid, conversation, it) })
            }
            return@withLock null
        }
        addMemory(
            assistantId = assistantId,
            content = normalizedContent,
            type = type,
            sourceConversationId = sourceConversationId,
            lifecycleState = lifecycleState,
            sourceMessageIds = sourceMessageIds,
            runId = runId,
        )
    }

    suspend fun updateEpisodicLifecycle(
        assistantId: String,
        id: Int,
        state: MemoryLifecycleState,
        supersededByMemoryId: Int? = null,
        conversationId: String? = null,
        expectedRevision: Long? = null,
    ): AssistantMemory? {
        val memory = memoryDAO.getMemoryByIdOfAssistant(id, assistantId)?.toAssistantMemory() ?: return null
        if (expectedRevision != null && memory.revision != expectedRevision) return null
        if (conversationId != null) {
            if (!memory.isVisibleInConversation(conversationId)) return null
        }
        if (supersededByMemoryId != null) {
            val replacement = memoryDAO.getMemoryByIdOfAssistant(supersededByMemoryId, assistantId)?.toAssistantMemory() ?: return null
            if (replacement.scopeType != memory.scopeType || replacement.scopeId != memory.scopeId || replacement.id == memory.id) return null
        }
        val updated = memoryDAO.updateEpisodicLifecycle(
            assistantId = assistantId,
            id = id,
            lifecycleState = state.name.lowercase(),
            updatedAt = Clock.System.now().toEpochMilliseconds(),
            supersededByMemoryId = supersededByMemoryId
                .takeIf { state == MemoryLifecycleState.SUPERSEDED },
            expectedRevision = memory.revision,
        )
        if (updated == 0) return null
        return memoryDAO.getMemoryByIdOfAssistant(id, assistantId)?.toAssistantMemory()
    }

    suspend fun deleteMemory(id: Int): Boolean = memoryDAO.deleteMemory(id) > 0

    suspend fun deleteMemory(assistantId: String, id: Int): Boolean =
        memoryDAO.deleteMemoryOfAssistant(id, assistantId) > 0

    suspend fun updateEmbedding(
        id: Int,
        embedding: ByteArray,
        modelId: String,
        dimension: Int,
        expectedContent: String,
        expectedKey: String? = null,
    ) {
        memoryDAO.updateEmbedding(
            id = id,
            embedding = embedding,
            embeddingModelId = modelId,
            embeddingDimension = dimension,
            expectedContent = expectedContent,
            expectedKey = expectedKey,
        )
    }

    suspend fun prepareEmbedding(memory: AssistantMemory, key: String) =
        memoryDAO.prepareEmbedding(memory.id, memory.contentHash.ifBlank { memoryContentHash(memory.content) }, key)

    suspend fun hasCurrentVector(memory: AssistantMemory, key: String): Boolean = memoryDAO.getMemoryById(memory.id)?.let {
        it.uid == memory.uid && it.contentHash == memory.contentHash && it.embeddingKey == key && it.embedding != null
    } == true
}

data class MemorySearchRecord(
    val memory: AssistantMemory,
    val embedding: ByteArray?,
    val embeddingModelId: String?,
    val embeddingDimension: Int?,
    val embeddingKey: String? = null,
    val sources: List<MemorySourceEntity> = emptyList(),
    val createdByRun: MemoryRunEntity? = null,
)

private fun MemoryEntity.toAssistantMemory() = AssistantMemory(
    id = id,
    content = content,
    type = MemoryType.fromWireName(memoryType),
    createdAt = createdAt,
    sourceConversationId = sourceConversationId,
    lifecycleState = MemoryLifecycleState.fromWireName(lifecycleState),
    lifecycleUpdatedAt = lifecycleUpdatedAt,
    supersededByMemoryId = supersededByMemoryId,
    uid = uid, revision = revision, contentHash = contentHash,
    scopeType = runCatching { MemoryScopeType.valueOf(scopeType.uppercase()) }.getOrDefault(MemoryScopeType.UNASSIGNED),
    scopeId = scopeId, supersededByUid = supersededByUid, createdByRunId = createdByRunId,
)

fun AssistantMemory.isRetrievable(): Boolean = lifecycleState == MemoryLifecycleState.ACTIVE
