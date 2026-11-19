package me.rerere.rikkahub.data.knowledge

import me.rerere.rikkahub.data.db.entity.KnowledgeChunkEntity
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal fun canReuseKnowledgeChunks(previous: List<KnowledgeChunkEntity>, current: List<KnowledgeChunkEntity>): Boolean =
    previous.size == current.size && previous.zip(current).all { (old, new) ->
        old.ordinal == new.ordinal && old.content == new.content &&
            old.pageStart == new.pageStart && old.pageEnd == new.pageEnd &&
            old.sectionPath == new.sectionPath && old.charStart == new.charStart && old.charEnd == new.charEnd
    }

internal fun KnowledgeChunkEntity.hasUsableEmbedding(modelId: String?): Boolean {
    val bytes = embedding ?: return false
    val dimension = embeddingDimension ?: return false
    if (modelId == null || embeddingModelId != modelId || dimension <= 0 || dimension.toLong() * 4 != bytes.size.toLong()) return false
    val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    return (0 until dimension).all { buffer.float.isFinite() }
}
