package me.rerere.rikkahub.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import me.rerere.rikkahub.data.db.entity.MemoryEntity

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

    @Insert
    suspend fun insertMemory(memory: MemoryEntity): Long

    @Update
    suspend fun updateMemory(memory: MemoryEntity)

    @Query(
        """
        UPDATE memoryentity
        SET embedding = :embedding,
            embedding_model_id = :embeddingModelId,
            embedding_dimension = :embeddingDimension
        WHERE id = :id
        """
    )
    suspend fun updateEmbedding(
        id: Int,
        embedding: ByteArray,
        embeddingModelId: String,
        embeddingDimension: Int,
    )

    @Query("DELETE FROM memoryentity WHERE id = :id")
    suspend fun deleteMemory(id: Int): Int

    @Query("DELETE FROM memoryentity WHERE id = :id AND assistant_id = :assistantId")
    suspend fun deleteMemoryOfAssistant(id: Int, assistantId: String): Int

    @Query("DELETE FROM memoryentity WHERE assistant_id = :assistantId")
    suspend fun deleteMemoriesOfAssistant(assistantId: String)
}
