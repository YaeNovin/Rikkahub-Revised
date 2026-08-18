package me.rerere.rikkahub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "knowledge_base",
    indices = [Index(value = ["name"])]
)
data class KnowledgeBaseEntity(
    @PrimaryKey
    val id: String,
    val name: String,
    val description: String = "",
    @ColumnInfo(name = "embedding_model_id")
    val embeddingModelId: String? = null,
    @ColumnInfo(defaultValue = "1")
    val enabled: Boolean = true,
    @ColumnInfo(name = "rag_enabled", defaultValue = "1")
    val ragEnabled: Boolean = true,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
)
