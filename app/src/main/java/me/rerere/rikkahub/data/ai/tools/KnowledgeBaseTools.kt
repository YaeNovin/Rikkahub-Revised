package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.db.dao.KnowledgeChunkSearchRow
import me.rerere.rikkahub.data.repository.KnowledgeBaseRepository

private const val DEFAULT_RESULT_LIMIT = 4
private const val MAX_RESULT_LIMIT = 6
private const val MAX_QUERY_CHARS = 1_200
private const val MAX_EXCERPT_CHARS = 1_600

/**
 * Exposes the assistant's bound knowledge bases as read-only model tools.
 * Automatic retrieval still injects context before generation; this tool lets
 * tool-capable models request more focused excerpts when that context is not enough.
 */
fun createKnowledgeBaseTools(
    knowledgeBaseIds: Set<String>,
    repository: KnowledgeBaseRepository,
    search: (suspend (query: String, limit: Int) -> List<KnowledgeChunkSearchRow>)? = null,
): List<Tool> = listOf(
    createKnowledgeListTool(knowledgeBaseIds, repository),
    search?.let(::createKnowledgeSearchTool)
        ?: createKnowledgeSearchTool(knowledgeBaseIds, repository),
)

fun createKnowledgeSearchTool(
    knowledgeBaseIds: Set<String>,
    repository: KnowledgeBaseRepository,
): Tool = createKnowledgeSearchTool { query, limit ->
    val enabledIds = repository.getEnabledBaseIds(knowledgeBaseIds)
    repository.searchLexicalChunks(enabledIds, query, limit).map { it.first }
}

internal fun createKnowledgeSearchTool(
    search: suspend (query: String, limit: Int) -> List<KnowledgeChunkSearchRow>,
): Tool = Tool(
    name = "kb_search",
    description = """
        Search the assistant's bound knowledge bases for source excerpts. Use this when you need a precise fact,
        quote, page, or section that is not already available in the conversation context. Results are read-only
        and include document, page, section, source, and chunk identifiers for reference.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("query", buildJsonObject {
                    put("type", "string")
                    put("description", "Specific terms or question to look up in the bound knowledge bases")
                })
                put("limit", buildJsonObject {
                    put("type", "integer")
                    put("description", "Maximum excerpts to return, from 1 to $MAX_RESULT_LIMIT. Defaults to $DEFAULT_RESULT_LIMIT")
                })
            },
            required = listOf("query"),
        )
    },
    execute = { input ->
        val params = input.jsonObject
        val query = params["query"]?.jsonPrimitive?.contentOrNull?.trim()
            ?: error("query is required")
        require(query.isNotBlank()) { "query is required" }
        require(query.length <= MAX_QUERY_CHARS) { "query is too long (max $MAX_QUERY_CHARS characters)" }
        val limit = params["limit"]?.jsonPrimitive?.intOrNull
            ?.coerceIn(1, MAX_RESULT_LIMIT)
            ?: DEFAULT_RESULT_LIMIT
        val results = search(query, limit)
        listOf(
            UIMessagePart.Text(
                buildJsonObject {
                    put("query", query)
                    put("results", buildJsonArray {
                        results.forEachIndexed { index, row ->
                            add(
                                buildJsonObject {
                                    put("rank", index + 1)
                                    put("chunkId", row.id)
                                    put("knowledgeBaseId", row.knowledgeBaseId)
                                    put("documentId", row.documentId)
                                    put("title", row.documentTitle)
                                    put("sourceUri", row.sourceUri)
                                    put("excerpt", row.content.take(MAX_EXCERPT_CHARS))
                                    row.pageStart?.let { put("pageStart", it) }
                                    row.pageEnd?.let { put("pageEnd", it) }
                                    if (row.sectionPath.isNotBlank()) put("sectionPath", row.sectionPath)
                                }
                            )
                        }
                    })
                }.toString()
            )
        )
    },
)

private fun createKnowledgeListTool(
    knowledgeBaseIds: Set<String>,
    repository: KnowledgeBaseRepository,
): Tool = Tool(
    name = "kb_list",
    description = "List the knowledge bases bound to this assistant. Use this to inspect the available sources before calling kb_search. This tool is read-only.",
    parameters = { InputSchema.Obj(properties = buildJsonObject {}) },
    execute = {
        val bases = repository.getBases(knowledgeBaseIds)
        listOf(
            UIMessagePart.Text(
                buildJsonObject {
                    put("knowledgeBases", buildJsonArray {
                        bases.forEach { base ->
                            add(
                                buildJsonObject {
                                    put("id", base.id)
                                    put("name", base.name)
                                    if (base.description.isNotBlank()) put("description", base.description)
                                    put("chunkCount", repository.countChunks(base.id))
                                }
                            )
                        }
                    })
                }.toString()
            )
        )
    },
)
