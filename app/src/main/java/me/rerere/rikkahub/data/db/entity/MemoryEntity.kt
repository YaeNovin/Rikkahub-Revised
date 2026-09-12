package me.rerere.rikkahub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(indices = [Index(value = ["assistant_id"]), Index(value = ["uid"], unique = true), Index(value = ["scope_type", "scope_id"])])
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
    @ColumnInfo(name = "lifecycle_state", defaultValue = "active")
    val lifecycleState: String = "active",
    @ColumnInfo(name = "lifecycle_updated_at", defaultValue = "0")
    val lifecycleUpdatedAt: Long = 0L,
    @ColumnInfo(name = "superseded_by_memory_id")
    val supersededByMemoryId: Int? = null,
    @ColumnInfo(name = "embedding")
    val embedding: ByteArray? = null,
    @ColumnInfo(name = "embedding_model_id")
    val embeddingModelId: String? = null,
    @ColumnInfo(name = "embedding_dimension")
    val embeddingDimension: Int? = null,
    @ColumnInfo(defaultValue = "''") val uid: String = kotlin.uuid.Uuid.random().toString(),
    @ColumnInfo(defaultValue = "1") val revision: Long = 1,
    @ColumnInfo(name = "content_hash", defaultValue = "''") val contentHash: String = me.rerere.rikkahub.data.model.memoryContentHash(content),
    @ColumnInfo(name = "scope_type", defaultValue = "'assistant'") val scopeType: String = "assistant",
    @ColumnInfo(name = "scope_id", defaultValue = "''") val scopeId: String = assistantId,
    @ColumnInfo(name = "superseded_by_uid") val supersededByUid: String? = null,
    @ColumnInfo(name = "embedding_key") val embeddingKey: String? = null,
    @ColumnInfo(name = "created_by_run_id") val createdByRunId: String? = null,
)
