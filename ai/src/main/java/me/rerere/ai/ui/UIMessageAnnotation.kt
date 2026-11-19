package me.rerere.ai.ui

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed class UIMessageAnnotation {
    @Serializable
    @SerialName("url_citation")
    data class UrlCitation(
        val title: String,
        val url: String,
        val imageUrl: String? = null,
        val retrievalStatus: String? = null,
        val groundingSegments: List<GroundingSegment> = emptyList(),
    ) : UIMessageAnnotation()

    @Serializable
    data class GroundingSegment(
        val text: String = "",
        val partIndex: Int? = null,
        val startIndex: Int? = null,
        val endIndex: Int? = null,
    )

    @Serializable
    @SerialName("google_search_suggestions")
    data class GoogleSearchSuggestions(
        val html: String = "",
        val webQueries: List<String> = emptyList(),
        val imageQueries: List<String> = emptyList(),
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
