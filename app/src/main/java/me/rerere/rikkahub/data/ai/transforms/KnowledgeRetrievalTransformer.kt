package me.rerere.rikkahub.data.ai.transforms

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import me.rerere.ai.provider.EmbeddingGenerationParams
import me.rerere.ai.provider.EmbeddingTaskType
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessageAnnotation
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.transformers.InputMessageTransformer
import me.rerere.rikkahub.data.ai.transformers.TransformerContext
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.db.dao.KnowledgeChunkSearchRow
import me.rerere.rikkahub.data.db.entity.KnowledgeBaseEntity
import me.rerere.rikkahub.data.repository.KnowledgeBaseRepository
import me.rerere.rikkahub.data.knowledge.rankKnowledgePages
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt
import kotlin.uuid.Uuid

private const val TAG = "KnowledgeRetrieval"
private const val RESULT_LIMIT = 6
private const val MAX_CHUNK_CHARS_IN_PROMPT = 2400
private const val MIN_SEMANTIC_MATCH_SCORE = 0.15f
internal const val KNOWLEDGE_CONTEXT_START_TAG = "<knowledge_context>"

class KnowledgeRetrievalTransformer(
    private val repository: KnowledgeBaseRepository,
    private val providerManager: ProviderManager,
) : InputMessageTransformer {
    override suspend fun transform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> = withContext(Dispatchers.IO) {
        if (ctx.assistant.knowledgeBaseIds.isEmpty()) return@withContext messages
        val query = messages.asReversed()
            .firstOrNull { it.role.name == "USER" }
            ?.toText()
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: return@withContext messages
        val boundBaseIds = ctx.assistant.knowledgeBaseIds.map(Uuid::toString).toSet()
        val ragBaseIds = repository.getRagEnabledBaseIds(boundBaseIds)
        if (ragBaseIds.isEmpty()) return@withContext messages
        val bases = repository.getBases(ragBaseIds)

        // A knowledge base may be indexed with a model different from the global
        // default. Search every distinct configured model and merge with lexical
        // matches so exact CJK terms are not hidden by weak third-party vectors.
        val semanticMatches = semanticSearch(ctx.settings, bases, query, RESULT_LIMIT)
            .filter { (_, score) -> score >= MIN_SEMANTIC_MATCH_SCORE }
        val candidates = mergeMatches(
            semanticMatches = semanticMatches,
            lexicalMatches = repository.searchLexicalChunks(ragBaseIds, query, RESULT_LIMIT),
        )

        if (candidates.isEmpty()) return@withContext messages
        val scored = candidates.take(RESULT_LIMIT)
        val annotations = scored.map { (row, score) ->
            UIMessageAnnotation.KnowledgeCitation(
                chunkId = row.id,
                knowledgeBaseId = row.knowledgeBaseId,
                documentId = row.documentId,
                title = row.documentTitle,
                sourceUri = row.sourceUri,
                excerpt = row.content.take(MAX_CHUNK_CHARS_IN_PROMPT),
                score = score,
                pageStart = row.pageStart,
                pageEnd = row.pageEnd,
                sectionPath = row.sectionPath,
            )
        }
        val contextPrompt = buildContextPrompt(annotations)
        val systemIndex = messages.indexOfFirst { it.role.name == "SYSTEM" }
        if (systemIndex >= 0) {
            val system = messages[systemIndex]
            val parts = system.parts.toMutableList()
            val textIndex = parts.indexOfFirst { it is UIMessagePart.Text }
            if (textIndex >= 0) {
                val originalText = (parts[textIndex] as UIMessagePart.Text).text
                parts[textIndex] = UIMessagePart.Text("$originalText\n\n$contextPrompt")
            } else {
                parts += UIMessagePart.Text(contextPrompt)
            }
            messages.toMutableList().apply {
                this[systemIndex] = system.copy(
                    parts = parts,
                    annotations = system.annotations + annotations,
                )
            }
        } else {
            listOf(UIMessage.system(contextPrompt).copy(annotations = annotations)) + messages
        }
    }

    /** Search entry point used by kb_search, including semantic ranking and fallback. */
    suspend fun searchForTool(
        settings: Settings,
        baseIds: Set<String>,
        query: String,
        limit: Int,
    ): List<KnowledgeChunkSearchRow> = withContext(Dispatchers.IO) {
        val enabledBaseIds = repository.getEnabledBaseIds(baseIds)
        if (enabledBaseIds.isEmpty()) return@withContext emptyList()
        val bases = repository.getBases(enabledBaseIds)
        val resultLimit = limit.coerceIn(1, 100)
        val semanticMatches = semanticSearch(settings, bases, query, resultLimit)
            .filter { (_, score) -> score >= MIN_SEMANTIC_MATCH_SCORE }
        mergeMatches(
            semanticMatches = semanticMatches,
            lexicalMatches = repository.searchLexicalChunks(enabledBaseIds, query, resultLimit),
        ).take(resultLimit).map { it.first }
    }

    private suspend fun semanticSearch(
        settings: Settings,
        bases: List<KnowledgeBaseEntity>,
        query: String,
        limit: Int,
    ): List<Pair<KnowledgeChunkSearchRow, Float>> {
        // Chunks retain the model that produced their vector. Query those models
        // directly so changing the global default does not make existing vectors
        // unreachable. A base/default model is only used when no indexed model is
        // available yet (for example, while a document is being indexed).
        val rowsByBase = repository.getEmbeddingModels(bases.map { it.id }.toSet())
            .groupBy { it.knowledgeBaseId }
        val modelIdsByBase = linkedMapOf<String, LinkedHashSet<Uuid>>()
        bases.forEach { base ->
            val indexedIds = rowsByBase[base.id].orEmpty()
                .mapNotNull { row -> parseUuidOrNull(row.embeddingModelId) }
                .toCollection(linkedSetOf())
            val fallbackIds = if (indexedIds.isEmpty()) {
                listOfNotNull(
                    base.embeddingModelId?.let(::parseUuidOrNull),
                    settings.embeddingModelId,
                )
            } else {
                emptyList()
            }
            modelIdsByBase[base.id] = (indexedIds + fallbackIds).toCollection(linkedSetOf())
        }

        val groups = linkedMapOf<Uuid, LinkedHashSet<String>>()
        modelIdsByBase.forEach { (baseId, modelIds) ->
            modelIds.forEach { modelId ->
                val model = settings.findModelById(modelId)
                if (model?.type == me.rerere.ai.provider.ModelType.EMBEDDING) {
                    groups.getOrPut(modelId) { linkedSetOf() }.add(baseId)
                }
            }
        }

        return groups.mapNotNull { (modelId, baseIds) ->
            val model = settings.findModelById(modelId) ?: return@mapNotNull null
            runCatching {
                val providerSetting = model.findProvider(settings.providers)
                    ?.takeIf { it.enabled }
                    ?: error("Embedding provider not found for ${model.modelId}")
                val queryVector = providerManager.getProviderByType(providerSetting)
                    .generateEmbedding(
                        providerSetting = providerSetting,
                        params = EmbeddingGenerationParams(
                            model = model,
                            input = listOf(query),
                            taskType = EmbeddingTaskType.RETRIEVAL_QUERY,
                            customHeaders = model.customHeaders,
                            customBody = model.customBodies,
                        )
                    ).embeddings.firstOrNull()
                    ?.takeIf { it.isNotEmpty() && it.all(Float::isFinite) }
                    ?: error("Empty query embedding")

                rankKnowledgePages(
                    limit = limit,
                    loadPage = { afterId, pageSize ->
                        repository.searchEmbeddedChunks(baseIds, modelId.toString(), pageSize, afterId)
                    },
                ) { row ->
                    val vector = row.embedding?.toFloatArray() ?: return@rankKnowledgePages 0f
                    if (row.embeddingDimension != null && row.embeddingDimension != vector.size) {
                        return@rankKnowledgePages 0f
                    }
                    val score = cosineSimilarity(queryVector, vector)
                    if (score.isFinite() && score >= MIN_SEMANTIC_MATCH_SCORE) score else 0f
                }
            }.onFailure { error ->
                if (error is CancellationException) throw error
                Log.w(TAG, "Embedding retrieval failed for ${model.modelId}; using lexical fallback", error)
            }.getOrDefault(emptyList())
        }.flatten().sortedByDescending { it.second }
    }

    private fun parseUuidOrNull(value: String): Uuid? =
        runCatching { Uuid.parse(value) }.getOrNull()

    private fun mergeMatches(
        semanticMatches: List<Pair<KnowledgeChunkSearchRow, Float>>,
        lexicalMatches: List<Pair<KnowledgeChunkSearchRow, Float>>,
    ): List<Pair<KnowledgeChunkSearchRow, Float>> {
        val merged = LinkedHashMap<String, Pair<KnowledgeChunkSearchRow, Float>>()
        semanticMatches.forEach { (row, score) -> merged[row.id] = row to score }
        lexicalMatches.forEach { (row, score) ->
            val current = merged[row.id]
            if (current == null || score > current.second) merged[row.id] = row to score
        }
        return merged.values.sortedByDescending { it.second }
    }

    private fun buildContextPrompt(citations: List<UIMessageAnnotation.KnowledgeCitation>): String = buildString {
        appendLine(KNOWLEDGE_CONTEXT_START_TAG)
        appendLine("The following excerpts were retrieved from the assistant's bound knowledge bases.")
        appendLine("Use them as evidence when relevant. Do not invent details that are not present in the excerpts.")
        citations.forEachIndexed { index, citation ->
            appendLine()
            appendLine("[${index + 1}] ${citation.title}")
            citation.pageStart?.let { start ->
                append("Page $start")
                citation.pageEnd?.takeIf { it != start }?.let { append("-$it") }
                appendLine()
            }
            if (citation.sectionPath.isNotBlank()) appendLine("Section: ${citation.sectionPath}")
            appendLine(citation.excerpt)
        }
        appendLine("</knowledge_context>")
    }
}

/** Only annotations injected for this request may be copied onto its response. */
internal fun List<UIMessage>.currentRequestKnowledgeCitations(): List<UIMessageAnnotation.KnowledgeCitation> =
    asSequence()
        .filter { message ->
            message.role.name == "SYSTEM" && message.toText().contains(KNOWLEDGE_CONTEXT_START_TAG)
        }
        .flatMap { it.annotations.asSequence() }
        .filterIsInstance<UIMessageAnnotation.KnowledgeCitation>()
        .distinctBy(UIMessageAnnotation.KnowledgeCitation::chunkId)
        .toList()

private fun ByteArray.toFloatArray(): FloatArray {
    if (size % 4 != 0) return FloatArray(0)
    val buffer = ByteBuffer.wrap(this).order(ByteOrder.LITTLE_ENDIAN)
    return FloatArray(size / 4) { buffer.float }
}

private fun cosineSimilarity(left: List<Float>, right: FloatArray): Float {
    if (left.size != right.size || left.isEmpty()) return 0f
    var dot = 0.0
    var leftNorm = 0.0
    var rightNorm = 0.0
    left.indices.forEach { index ->
        val l = left[index].toDouble()
        val r = right[index].toDouble()
        dot += l * r
        leftNorm += l * l
        rightNorm += r * r
    }
    val denominator = sqrt(leftNorm * rightNorm)
    return if (denominator == 0.0) 0f else (dot / denominator).toFloat()
}
