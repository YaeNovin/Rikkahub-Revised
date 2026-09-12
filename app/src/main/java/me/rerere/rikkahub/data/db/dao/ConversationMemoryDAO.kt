package me.rerere.rikkahub.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import me.rerere.rikkahub.data.db.entity.ConversationMemoryExclusionEntity
import me.rerere.rikkahub.data.db.entity.ConversationMemoryEntity
import me.rerere.rikkahub.data.db.entity.MemoryExtractionCheckpointEntity

@Dao
interface ConversationMemoryDAO {
    @Query("SELECT * FROM conversation_memory_chunk WHERE conversation_id = :conversationId ORDER BY ordinal")
    suspend fun getChunks(conversationId: String): List<ConversationMemoryEntity>

    @Query("SELECT * FROM conversation_memory_chunk WHERE conversation_id = :conversationId ORDER BY ordinal DESC LIMIT 12")
    fun observeRecentChunks(conversationId: String): kotlinx.coroutines.flow.Flow<List<ConversationMemoryEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertChunks(chunks: List<ConversationMemoryEntity>)

    @Query("SELECT * FROM conversation_memory_exclusion WHERE conversation_id = :conversationId")
    suspend fun getExclusions(conversationId: String): List<ConversationMemoryExclusionEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertExclusion(exclusion: ConversationMemoryExclusionEntity)

    @Transaction
    suspend fun upsertVisibleChunks(chunks: List<ConversationMemoryEntity>) {
        chunks.groupBy { it.conversationId }.forEach { (conversationId, values) ->
            val excluded = getExclusions(conversationId).mapTo(hashSetOf()) { it.chunkId to it.contentHash }
            upsertChunks(values.filterNot { (it.id to it.contentHash) in excluded })
        }
    }

    @Query("SELECT * FROM conversation_memory_chunk WHERE conversation_id = :conversationId AND id = :id")
    suspend fun getChunk(conversationId: String, id: String): ConversationMemoryEntity?

    @Transaction
    suspend fun deleteChunkForUser(conversationId: String, id: String): Boolean {
        val chunk = getChunk(conversationId, id) ?: return false
        insertExclusion(ConversationMemoryExclusionEntity(conversationId, id, chunk.contentHash))
        deleteChunks(conversationId, listOf(id))
        return true
    }

    @Query("UPDATE conversation_memory_chunk SET embedding = :embedding, embedding_model_id = :modelId, embedding_dimension = :dimension " +
        "WHERE conversation_id = :conversationId AND id = :id AND content_hash = :contentHash AND (:expectedKey IS NULL OR embedding_key = :expectedKey)")
    suspend fun updateVectorIfCurrent(conversationId: String, id: String, contentHash: String, embedding: ByteArray, modelId: String, dimension: Int, expectedKey: String? = null): Int

    @Query("DELETE FROM conversation_memory_chunk WHERE conversation_id = :conversationId AND id IN (:ids)")
    suspend fun deleteChunks(conversationId: String, ids: List<String>)

    @Query("DELETE FROM conversation_memory_chunk WHERE conversation_id = :conversationId")
    suspend fun deleteConversationChunks(conversationId: String)

    @Query("SELECT COUNT(*) FROM conversation_memory_chunk")
    suspend fun countAllChunks(): Int

    @Query("INSERT OR IGNORE INTO conversation_memory_exclusion(conversation_id, chunk_id, content_hash) SELECT conversation_id, id, content_hash FROM conversation_memory_chunk")
    suspend fun excludeAllCurrentChunks()

    @Query("DELETE FROM conversation_memory_chunk")
    suspend fun deleteAllChunks()

    @Transaction
    suspend fun clearAllForUser() {
        excludeAllCurrentChunks()
        deleteAllChunks()
    }

    @Query("SELECT * FROM memory_extraction_checkpoint WHERE conversation_id = :conversationId")
    suspend fun getExtractionCheckpoint(conversationId: String): MemoryExtractionCheckpointEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertExtractionCheckpoint(checkpoint: MemoryExtractionCheckpointEntity)
}
