package me.rerere.rikkahub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "knowledge_document",
    foreignKeys = [
        ForeignKey(
            entity = KnowledgeBaseEntity::class,
            parentColumns = ["id"],
            childColumns = ["knowledge_base_id"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [Index("knowledge_base_id"), Index("content_hash")]
)
data class KnowledgeDocumentEntity(
    @PrimaryKey
    val id: String,
    @ColumnInfo(name = "knowledge_base_id")
    val knowledgeBaseId: String,
    val title: String,
    @ColumnInfo(name = "source_uri")
    val sourceUri: String,
    @ColumnInfo(name = "mime_type")
    val mimeType: String,
    @ColumnInfo(name = "content_hash")
    val contentHash: String,
    @ColumnInfo(name = "page_count")
    val pageCount: Int? = null,
    val status: String = STATUS_INDEXING,
    @ColumnInfo(name = "error_message")
    val errorMessage: String? = null,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
) {
    companion object {
        const val STATUS_INDEXING = "indexing"
        const val STATUS_READY = "ready"
        const val STATUS_READY_WITHOUT_EMBEDDING = "ready_without_embedding"
        const val STATUS_FAILED = "failed"
    }
}
