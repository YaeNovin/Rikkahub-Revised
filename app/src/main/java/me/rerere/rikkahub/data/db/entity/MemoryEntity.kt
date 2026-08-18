package me.rerere.rikkahub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(indices = [Index(value = ["assistant_id"])])
data class MemoryEntity(
    @PrimaryKey(true)
    val id: Int = 0,
    @ColumnInfo("assistant_id")
    val assistantId: String,
    @ColumnInfo("content")
    val content: String = "",
    @ColumnInfo(name = "memory_type", defaultValue = "fact")
    val memoryType: String = "fact",
    @ColumnInfo(name = "created_at", defaultValue = "0")
    val createdAt: Long = 0L,
    @ColumnInfo(name = "source_conversation_id")
    val sourceConversationId: String? = null,
    @ColumnInfo(name = "embedding")
    val embedding: ByteArray? = null,
    @ColumnInfo(name = "embedding_model_id")
    val embeddingModelId: String? = null,
    @ColumnInfo(name = "embedding_dimension")
    val embeddingDimension: Int? = null,
)
