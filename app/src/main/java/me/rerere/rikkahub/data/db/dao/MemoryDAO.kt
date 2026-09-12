package me.rerere.rikkahub.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import androidx.room.Transaction
import androidx.room.OnConflictStrategy
import kotlinx.coroutines.flow.Flow
import me.rerere.rikkahub.data.db.entity.MemoryEntity
import me.rerere.rikkahub.data.db.entity.MemorySourceEntity
import me.rerere.rikkahub.data.db.entity.MemoryDeletionEntity
import me.rerere.rikkahub.data.db.entity.MemoryRunEntity

@Dao
interface MemoryDAO {
    @Query("SELECT * FROM memoryentity WHERE assistant_id = :assistantId")
    fun getMemoriesOfAssistantFlow(assistantId: String): Flow<List<MemoryEntity>>

    @Query("SELECT * FROM memoryentity WHERE assistant_id = :assistantId")
    suspend fun getMemoriesOfAssistant(assistantId: String): List<MemoryEntity>

    @Query("SELECT * FROM memoryentity")
    fun getAllMemoriesFlow(): Flow<List<MemoryEntity>>

    @Query("SELECT * FROM memoryentity")
    suspend fun getAllMemories(): List<MemoryEntity>

    @Query("SELECT * FROM memoryentity WHERE id = :id")
    suspend fun getMemoryById(id: Int): MemoryEntity?

    @Query("SELECT * FROM memoryentity WHERE id = :id AND assistant_id = :assistantId")
    suspend fun getMemoryByIdOfAssistant(id: Int, assistantId: String): MemoryEntity?

    @Query(
        "SELECT * FROM memoryentity " +
            "WHERE assistant_id = :assistantId AND memory_type = :memoryType " +
            "AND (:memoryType = 'fact' OR (scope_type = 'conversation' AND scope_id = :conversationId) OR (scope_type = 'unassigned' AND :conversationId IS NULL)) " +
            "AND content = :content COLLATE NOCASE LIMIT 1"
    )
    suspend fun findMemoryByContent(
        assistantId: String,
        memoryType: String,
        content: String,
        conversationId: String? = null,
    ): MemoryEntity?

    @Insert
    suspend fun insertMemory(memory: MemoryEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSources(sources: List<MemorySourceEntity>)

    @Query("SELECT * FROM memory_source WHERE memory_uid = :uid")
    suspend fun getSources(uid: String): List<MemorySourceEntity>

    @Query("SELECT * FROM memory_deletion WHERE uid = :uid")
    suspend fun getDeletion(uid: String): MemoryDeletionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDeletion(deletion: MemoryDeletionEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRun(run: MemoryRunEntity)

    @Query("SELECT * FROM memory_run WHERE id = :id")
    suspend fun getRun(id: String): MemoryRunEntity?

    @Transaction
    suspend fun insertMemoryWithSources(memory: MemoryEntity, sources: List<MemorySourceEntity>): Long {
        require(memory.uid.isNotBlank() && getDeletion(memory.uid) == null) { "Memory identity was deleted or is invalid" }
        val id = insertMemory(memory)
        insertSources(sources)
        return id
    }

    @Transaction
    suspend fun updateMemoryIfCurrent(memory: MemoryEntity, expectedRevision: Long): Boolean {
        val current = getMemoryById(memory.id) ?: return false
        if (current.revision != expectedRevision || current.uid != memory.uid) return false
        require(memory.revision == expectedRevision + 1)
        updateMemory(memory)
        return true
    }

    @Update
    suspend fun updateMemory(memory: MemoryEntity)

    @Query(
        """
        UPDATE memoryentity
        SET embedding = :embedding,
            embedding_model_id = :embeddingModelId,
            embedding_dimension = :embeddingDimension
        WHERE id = :id AND content = :expectedContent AND (:expectedKey IS NULL OR embedding_key = :expectedKey)
        """
    )
    suspend fun updateEmbedding(
        id: Int,
        embedding: ByteArray,
        embeddingModelId: String,
        embeddingDimension: Int,
        expectedContent: String,
        expectedKey: String? = null,
    )

    @Query("UPDATE memoryentity SET embedding_key = :key, embedding = NULL, embedding_model_id = NULL, embedding_dimension = NULL WHERE id = :id AND content_hash = :hash AND (embedding_key IS NULL OR embedding_key != :key)")
    suspend fun prepareEmbedding(id: Int, hash: String, key: String): Int

    @Query(
        """
        UPDATE memoryentity
        SET lifecycle_state = :lifecycleState,
            lifecycle_updated_at = :updatedAt,
            superseded_by_memory_id = :supersededByMemoryId,
            superseded_by_uid = (SELECT uid FROM memoryentity WHERE id = :supersededByMemoryId),
            revision = revision + 1
        WHERE id = :id AND assistant_id = :assistantId AND memory_type = 'episodic' AND revision = :expectedRevision
        """
    )
    suspend fun updateEpisodicLifecycle(
        assistantId: String,
        id: Int,
        lifecycleState: String,
        updatedAt: Long,
        supersededByMemoryId: Int?,
        expectedRevision: Long,
    ): Int

    @Query("DELETE FROM memoryentity WHERE id = :id")
    suspend fun deleteMemoryRow(id: Int): Int

    @Query("DELETE FROM memoryentity WHERE id = :id AND assistant_id = :assistantId")
    suspend fun deleteMemoryOfAssistantRow(id: Int, assistantId: String): Int

    @Transaction
    suspend fun deleteMemory(id: Int): Int {
        val record = getMemoryById(id) ?: return 0
        insertDeletion(MemoryDeletionEntity(record.uid, record.scopeType, record.scopeId, record.revision + 1, System.currentTimeMillis()))
        return deleteMemoryRow(id)
    }

    @Transaction
    suspend fun deleteMemoryOfAssistant(id: Int, assistantId: String): Int {
        if (getMemoryByIdOfAssistant(id, assistantId) == null) return 0
        return deleteMemory(id)
    }

    @Query("DELETE FROM memoryentity WHERE assistant_id = :assistantId")
    suspend fun deleteMemoriesOfAssistantRows(assistantId: String)

    @Query("INSERT OR REPLACE INTO memory_deletion(uid, scope_type, scope_id, revision, deleted_at) SELECT uid, scope_type, scope_id, revision + 1, :timestamp FROM memoryentity WHERE assistant_id = :assistantId")
    suspend fun markAssistantMemoriesDeleted(assistantId: String, timestamp: Long)

    @Transaction
    suspend fun deleteMemoriesOfAssistant(assistantId: String) {
        markAssistantMemoriesDeleted(assistantId, System.currentTimeMillis())
        deleteMemoriesOfAssistantRows(assistantId)
    }

    @Query("SELECT COUNT(*) FROM memoryentity")
    suspend fun countAllMemories(): Int

    @Query("DELETE FROM memoryentity")
    suspend fun deleteAllMemoryRows()

    @Query("INSERT OR REPLACE INTO memory_deletion(uid, scope_type, scope_id, revision, deleted_at) SELECT uid, scope_type, scope_id, revision + 1, :timestamp FROM memoryentity")
    suspend fun markAllDeleted(timestamp: Long)

    @Transaction
    suspend fun deleteAllMemories() {
        markAllDeleted(System.currentTimeMillis())
        deleteAllMemoryRows()
    }
}
