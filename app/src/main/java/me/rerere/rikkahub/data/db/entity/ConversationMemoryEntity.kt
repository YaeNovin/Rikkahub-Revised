package me.rerere.rikkahub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "conversation_memory_chunk",
    foreignKeys = [
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversation_id"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [
        Index("conversation_id"),
        Index(value = ["conversation_id", "embedding_model_id"]),
        Index("source_message_id"),
    ],
)
data class ConversationMemoryEntity(
    @PrimaryKey
    val id: String,
    @ColumnInfo(name = "conversation_id")
    val conversationId: String,
    @ColumnInfo(name = "assistant_id")
    val assistantId: String,
    @ColumnInfo(name = "source_message_id")
    val sourceMessageId: String,
    val ordinal: Int,
    val content: String,
    @ColumnInfo(name = "content_hash")
    val contentHash: String,
    val embedding: ByteArray? = null,
    @ColumnInfo(name = "embedding_model_id")
    val embeddingModelId: String? = null,
    @ColumnInfo(name = "embedding_dimension")
    val embeddingDimension: Int? = null,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
    @ColumnInfo(name = "embedding_key") val embeddingKey: String? = null,
)
