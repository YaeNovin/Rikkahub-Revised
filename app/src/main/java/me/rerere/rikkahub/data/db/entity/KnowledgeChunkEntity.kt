package me.rerere.rikkahub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "knowledge_chunk",
    foreignKeys = [
        ForeignKey(
            entity = KnowledgeDocumentEntity::class,
            parentColumns = ["id"],
            childColumns = ["document_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = KnowledgeBaseEntity::class,
            parentColumns = ["id"],
            childColumns = ["knowledge_base_id"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [
        Index("document_id"),
        Index(value = ["knowledge_base_id", "ordinal"]),
        Index(value = ["embedding_model_id"]),
    ]
)
data class KnowledgeChunkEntity(
    @PrimaryKey
    val id: String,
    @ColumnInfo(name = "document_id")
    val documentId: String,
    @ColumnInfo(name = "knowledge_base_id")
    val knowledgeBaseId: String,
    val ordinal: Int,
    val content: String,
    @ColumnInfo(name = "page_start")
    val pageStart: Int? = null,
    @ColumnInfo(name = "page_end")
    val pageEnd: Int? = null,
    @ColumnInfo(name = "section_path")
    val sectionPath: String = "",
    @ColumnInfo(name = "char_start")
    val charStart: Int = 0,
    @ColumnInfo(name = "char_end")
    val charEnd: Int = 0,
    val embedding: ByteArray? = null,
    @ColumnInfo(name = "embedding_model_id")
    val embeddingModelId: String? = null,
    @ColumnInfo(name = "embedding_dimension")
    val embeddingDimension: Int? = null,
)
