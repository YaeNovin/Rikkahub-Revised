package me.rerere.ai.ui

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed class UIMessageAnnotation {
    @Serializable
    @SerialName("url_citation")
    data class UrlCitation(
        val title: String,
        val url: String
    ) : UIMessageAnnotation()

    @Serializable
    @SerialName("knowledge_citation")
    data class KnowledgeCitation(
        val chunkId: String,
        val knowledgeBaseId: String,
        val documentId: String,
        val title: String,
        val sourceUri: String,
        val excerpt: String,
        val score: Float,
        val pageStart: Int? = null,
        val pageEnd: Int? = null,
        val sectionPath: String = "",
    ) : UIMessageAnnotation()
}
