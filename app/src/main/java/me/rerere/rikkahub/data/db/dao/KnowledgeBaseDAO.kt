package me.rerere.rikkahub.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import me.rerere.rikkahub.data.db.entity.KnowledgeBaseEntity
import me.rerere.rikkahub.data.db.entity.KnowledgeChunkEntity
import me.rerere.rikkahub.data.db.entity.KnowledgeCitationEntity
import me.rerere.rikkahub.data.db.entity.KnowledgeDocumentEntity

@Dao
interface KnowledgeBaseDAO {
    @Query("SELECT * FROM knowledge_base ORDER BY updated_at DESC")
    fun observeBases(): Flow<List<KnowledgeBaseEntity>>

    @Query("SELECT * FROM knowledge_base WHERE id IN (:ids) ORDER BY updated_at DESC")
    suspend fun getBases(ids: List<String>): List<KnowledgeBaseEntity>

    @Query("SELECT id FROM knowledge_base WHERE id IN (:ids) AND enabled = 1")
    suspend fun getEnabledBaseIds(ids: List<String>): List<String>

    @Query("SELECT id FROM knowledge_base WHERE id IN (:ids) AND enabled = 1 AND rag_enabled = 1")
    suspend fun getRagEnabledBaseIds(ids: List<String>): List<String>

    @Query("SELECT * FROM knowledge_base WHERE id = :id LIMIT 1")
    suspend fun getBase(id: String): KnowledgeBaseEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBase(base: KnowledgeBaseEntity)

    @Query("DELETE FROM knowledge_base WHERE id = :id")
    suspend fun deleteBase(id: String)

    @Query("UPDATE knowledge_base SET enabled = :enabled, updated_at = :updatedAt WHERE id = :id")
    suspend fun updateBaseEnabled(id: String, enabled: Boolean, updatedAt: Long)

    @Query("UPDATE knowledge_base SET rag_enabled = :ragEnabled, updated_at = :updatedAt WHERE id = :id")
    suspend fun updateBaseRagEnabled(id: String, ragEnabled: Boolean, updatedAt: Long)

    @Query("SELECT * FROM knowledge_document WHERE knowledge_base_id = :baseId ORDER BY updated_at DESC")
    fun observeDocuments(baseId: String): Flow<List<KnowledgeDocumentEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDocument(document: KnowledgeDocumentEntity)

    @Query("UPDATE knowledge_document SET status = :status, error_message = :errorMessage, updated_at = :updatedAt WHERE id = :id")
    suspend fun updateDocumentStatus(id: String, status: String, errorMessage: String?, updatedAt: Long)

    @Query("DELETE FROM knowledge_document WHERE id = :id")
    suspend fun deleteDocument(id: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChunks(chunks: List<KnowledgeChunkEntity>)

    @Update
    suspend fun updateChunks(chunks: List<KnowledgeChunkEntity>)

    @Query("DELETE FROM knowledge_chunk WHERE document_id = :documentId")
    suspend fun deleteChunks(documentId: String)

    @Query(
        "SELECT ordinal, content, page_start, page_end, section_path, char_start, char_end " +
            "FROM knowledge_chunk WHERE document_id = :documentId ORDER BY ordinal LIMIT :limit"
    )
    suspend fun getDocumentPreviewChunks(
        documentId: String,
        limit: Int,
    ): List<KnowledgeDocumentPreviewChunk>

    @Query("SELECT COUNT(*) FROM knowledge_chunk WHERE knowledge_base_id = :baseId")
    suspend fun countChunks(baseId: String): Int

    @Query("SELECT id FROM knowledge_chunk WHERE id IN (:chunkIds)")
    suspend fun getExistingChunkIds(chunkIds: List<String>): List<String>

    @Query(
        "SELECT c.id, c.document_id, c.knowledge_base_id, c.ordinal, c.content, " +
            "c.page_start, c.page_end, c.section_path, c.char_start, c.char_end, " +
            "c.embedding, c.embedding_model_id, c.embedding_dimension, " +
            "d.title AS document_title, d.source_uri " +
            "FROM knowledge_chunk c INNER JOIN knowledge_document d ON d.id = c.document_id " +
            "WHERE c.knowledge_base_id IN (:baseIds) " +
            "AND d.status IN ('ready', 'ready_without_embedding') " +
            "AND c.embedding_model_id = :embeddingModelId " +
            "AND c.embedding IS NOT NULL " +
            "LIMIT :limit"
    )
    suspend fun getEmbeddedChunks(
        baseIds: List<String>,
        embeddingModelId: String,
        limit: Int,
    ): List<KnowledgeChunkSearchRow>

    @Query(
        "SELECT c.id, c.document_id, c.knowledge_base_id, c.ordinal, c.content, " +
            "c.page_start, c.page_end, c.section_path, c.char_start, c.char_end, " +
            "c.embedding, c.embedding_model_id, c.embedding_dimension, " +
            "d.title AS document_title, d.source_uri " +
            "FROM knowledge_chunk c INNER JOIN knowledge_document d ON d.id = c.document_id " +
            "WHERE c.knowledge_base_id IN (:baseIds) " +
            "AND d.status IN ('ready', 'ready_without_embedding') " +
            "LIMIT :limit"
    )
    suspend fun getChunks(baseIds: List<String>, limit: Int): List<KnowledgeChunkSearchRow>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCitations(citations: List<KnowledgeCitationEntity>)

    @Query("DELETE FROM knowledge_citation WHERE conversation_id = :conversationId AND message_id = :messageId")
    suspend fun deleteCitations(conversationId: String, messageId: String)
}

data class KnowledgeDocumentPreviewChunk(
    val ordinal: Int,
    val content: String,
    @androidx.room.ColumnInfo(name = "page_start")
    val pageStart: Int?,
    @androidx.room.ColumnInfo(name = "page_end")
    val pageEnd: Int?,
    @androidx.room.ColumnInfo(name = "section_path")
    val sectionPath: String,
    @androidx.room.ColumnInfo(name = "char_start")
    val charStart: Int,
    @androidx.room.ColumnInfo(name = "char_end")
    val charEnd: Int,
)

data class KnowledgeChunkSearchRow(
    val id: String,
    @androidx.room.ColumnInfo(name = "document_id")
    val documentId: String,
    @androidx.room.ColumnInfo(name = "knowledge_base_id")
    val knowledgeBaseId: String,
    val ordinal: Int,
    val content: String,
    @androidx.room.ColumnInfo(name = "page_start")
    val pageStart: Int?,
    @androidx.room.ColumnInfo(name = "page_end")
    val pageEnd: Int?,
    @androidx.room.ColumnInfo(name = "section_path")
    val sectionPath: String,
    @androidx.room.ColumnInfo(name = "char_start")
    val charStart: Int,
    @androidx.room.ColumnInfo(name = "char_end")
    val charEnd: Int,
    val embedding: ByteArray?,
    @androidx.room.ColumnInfo(name = "embedding_model_id")
    val embeddingModelId: String?,
    @androidx.room.ColumnInfo(name = "embedding_dimension")
    val embeddingDimension: Int?,
    @androidx.room.ColumnInfo(name = "document_title")
    val documentTitle: String,
    @androidx.room.ColumnInfo(name = "source_uri")
    val sourceUri: String,
)
