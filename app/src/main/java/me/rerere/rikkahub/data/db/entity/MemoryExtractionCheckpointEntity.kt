package me.rerere.rikkahub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "memory_extraction_checkpoint",
    foreignKeys = [
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversation_id"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [Index("conversation_id")],
)
data class MemoryExtractionCheckpointEntity(
    @PrimaryKey
    @ColumnInfo(name = "conversation_id")
    val conversationId: String,
    @ColumnInfo(name = "assistant_id")
    val assistantId: String,
    @ColumnInfo(name = "last_user_message_id", defaultValue = "")
    val lastUserMessageId: String = "",
    @ColumnInfo(name = "last_user_content_hash", defaultValue = "")
    val lastUserContentHash: String = "",
    @ColumnInfo(name = "last_attempt_at", defaultValue = "0")
    val lastAttemptAt: Long = 0L,
    @ColumnInfo(name = "last_completed_at", defaultValue = "0")
    val lastCompletedAt: Long = 0L,
)
