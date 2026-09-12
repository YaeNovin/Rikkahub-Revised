package me.rerere.rikkahub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey

/** No deleted text or vector is retained; the hash prevents background recreation of this version. */
@Entity(tableName = "conversation_memory_exclusion", primaryKeys = ["conversation_id", "chunk_id", "content_hash"],
    foreignKeys = [ForeignKey(entity = ConversationEntity::class, parentColumns = ["id"], childColumns = ["conversation_id"], onDelete = ForeignKey.CASCADE)])
data class ConversationMemoryExclusionEntity(
    @ColumnInfo(name = "conversation_id") val conversationId: String,
    @ColumnInfo(name = "chunk_id") val chunkId: String,
    @ColumnInfo(name = "content_hash") val contentHash: String,
)
