package me.rerere.rikkahub.data.db.entity

import androidx.room.*

@Entity(tableName = "memory_source", primaryKeys = ["memory_uid", "conversation_id", "message_id"],
    foreignKeys = [ForeignKey(entity = MemoryEntity::class, parentColumns = ["uid"], childColumns = ["memory_uid"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("conversation_id")])
data class MemorySourceEntity(
    @ColumnInfo(name = "memory_uid") val memoryUid: String,
    @ColumnInfo(name = "conversation_id") val conversationId: String,
    @ColumnInfo(name = "message_id") val messageId: String,
)

@Entity(tableName = "memory_deletion")
data class MemoryDeletionEntity(
    @PrimaryKey val uid: String,
    @ColumnInfo(name = "scope_type") val scopeType: String,
    @ColumnInfo(name = "scope_id") val scopeId: String,
    val revision: Long,
    @ColumnInfo(name = "deleted_at") val deletedAt: Long,
)

@Entity(tableName = "memory_run", indices = [Index("conversation_id")])
data class MemoryRunEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "conversation_id") val conversationId: String?,
    @ColumnInfo(name = "model_id") val modelId: String,
    val operation: String,
    val status: String,
    @ColumnInfo(name = "input_hash") val inputHash: String,
    @ColumnInfo(name = "started_at") val startedAt: Long,
    @ColumnInfo(name = "finished_at") val finishedAt: Long? = null,
    @ColumnInfo(name = "duration_ms") val durationMs: Long? = null,
    @ColumnInfo(name = "request_id") val requestId: String? = null,
)
