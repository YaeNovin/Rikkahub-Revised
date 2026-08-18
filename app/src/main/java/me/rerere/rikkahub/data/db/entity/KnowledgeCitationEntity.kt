package me.rerere.rikkahub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "knowledge_citation",
    foreignKeys = [
        ForeignKey(
            entity = KnowledgeChunkEntity::class,
            parentColumns = ["id"],
            childColumns = ["chunk_id"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [Index("conversation_id"), Index("message_id"), Index("chunk_id")]
)
data class KnowledgeCitationEntity(
    @PrimaryKey
    val id: String,
    @ColumnInfo(name = "conversation_id")
    val conversationId: String,
    @ColumnInfo(name = "message_id")
    val messageId: String,
    @ColumnInfo(name = "chunk_id")
    val chunkId: String,
    val rank: Int,
    val score: Float,
    val excerpt: String,
    @ColumnInfo(name = "page_start")
    val pageStart: Int? = null,
    @ColumnInfo(name = "page_end")
    val pageEnd: Int? = null,
    @ColumnInfo(name = "section_path")
    val sectionPath: String = "",
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
)
