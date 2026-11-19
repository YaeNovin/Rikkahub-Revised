package me.rerere.rikkahub.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "chat_image_tool_task",
    foreignKeys = [ForeignKey(entity = ConversationEntity::class, parentColumns = ["id"],
        childColumns = ["conversationId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("conversationId"), Index(value = ["conversationId", "sourceMessageId"], unique = true)],
)
data class ChatImageToolTaskEntity(
    @PrimaryKey val id: String,
    val conversationId: String,
    val messageId: String,
    val sourceMessageId: String,
    val toolCallId: String,
    val inputHash: String,
    val originalPrompt: String,
    val proposedPrompt: String,
    val requestJson: String,
    val status: String = "pending",
    val imagesJson: String = "[]",
    val error: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
)
