package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.rikkahub.utils.JsonInstantPretty
import me.rerere.rikkahub.utils.toLocalString
import me.rerere.search.SearchService
import me.rerere.search.SearchServiceOptions
import java.time.LocalDate
import kotlin.uuid.Uuid

internal const val SEARCH_WEB_TOOL_NAME = "search_web"
private const val SEARCH_WEB_CITATIONS = "rikkahub_search_web_citations"

internal data class SearchWebCitation(val id: String, val url: String, val title: String)

private fun searchWebCitationItems(output: List<UIMessagePart>): JsonArray? {
    val text = output.filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text }
    val result = runCatching { JsonInstant.parseToJsonElement(text) as? JsonObject }.getOrNull()
    return result?.get("items") as? JsonArray
}

private fun JsonArray.searchWebCitations(): List<SearchWebCitation> = mapNotNull { entry ->
    val item = entry as? JsonObject ?: return@mapNotNull null
    val id = (item["id"] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank) ?: return@mapNotNull null
    val url = (item["url"] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank) ?: return@mapNotNull null
    val title = (item["title"] as? JsonPrimitive)?.contentOrNull.orEmpty()
    SearchWebCitation(id, url, title)
}

internal fun UIMessagePart.Tool.withSearchWebCitationMetadata(fullOutput: List<UIMessagePart>): UIMessagePart.Tool {
    val citations = searchWebCitationItems(fullOutput)?.searchWebCitations().orEmpty()
    if (citations.isEmpty()) return this
    val items = JsonArray(citations.map {
        JsonObject(mapOf("id" to JsonPrimitive(it.id), "url" to JsonPrimitive(it.url), "title" to JsonPrimitive(it.title)))
    })
    return copy(metadata = JsonObject(metadata.orEmpty() + (SEARCH_WEB_CITATIONS to items)))
}

internal fun UIMessagePart.Tool.searchWebCitations(): List<SearchWebCitation> {
    if (toolName != SEARCH_WEB_TOOL_NAME || !isExecuted) return emptyList()
    val items = metadata?.get(SEARCH_WEB_CITATIONS) as? JsonArray ?: searchWebCitationItems(output)
    return items?.searchWebCitations().orEmpty()
}

fun createSearchTools(settings: Settings): Set<Tool> {
    return buildSet {
        add(
            Tool(
                name = SEARCH_WEB_TOOL_NAME,
                description = """
                    Search the web for up-to-date or specific information.
                    Use this when the user asks for the latest news, current facts, or needs verification.
                    Generate focused keywords and run multiple searches if needed.
                    Today is ${LocalDate.now().toLocalString(true)}.

                    Response format:
                    - items[].id (short id), title, url, text
                    - images[]: image urls related to the query (may be empty)

                    Citations:
                    - After using results, add `[citation,domain](id)` after the sentence.
                    - Multiple citations are allowed.
                    - For several sources, emit separate citation links with their exact result ids; never wrap citation markers in code.
                    - If no results are cited, omit citations.

                    Images:
                    - When images help the user understand the answer, embed relevant ones using Markdown: `![](url)`.
                    - Embed 2 to 4 images, and only use urls from `images[]` (never fabricate or alter urls).
                    - Usually place the images at the very beginning of your reply; skip them entirely if none are relevant.

                    Example:
                    The capital of France is Paris. [citation,example.com](abc123)
                    The population is about 2.1 million. [citation,example.com](abc123) [citation,example2.com](def456)
                    """.trimIndent(),
                parameters = {
                    val options = settings.searchServices.getOrElse(
                        index = settings.searchServiceSelected,
                        defaultValue = { SearchServiceOptions.DEFAULT })
                    val service = SearchService.getService(options)
                    service.parameters(options)
                },
                execute = {
                    val options = settings.searchServices.getOrElse(
                        index = settings.searchServiceSelected,
                        defaultValue = { SearchServiceOptions.DEFAULT })
                    val service = SearchService.getService(options)
                    val result = service.search(
                        params = it.jsonObject,
                        commonOptions = settings.searchCommonOptions,
                        serviceOptions = options,
                    )
                    val results =
                        JsonInstantPretty.encodeToJsonElement(result.getOrThrow()).jsonObject.let { json ->
                            val map = json.toMutableMap()
                            map["items"] =
                                JsonArray(map["items"]!!.jsonArray.mapIndexed { index, item ->
                                    JsonObject(item.jsonObject.toMutableMap().apply {
                                        put("id", JsonPrimitive(Uuid.random().toString().take(6)))
                                        put("index", JsonPrimitive(index + 1))
                                    })
                                })
                            JsonObject(map)
                        }
                    listOf(UIMessagePart.Text(results.toString()))
                }
            )
        )

        val options = settings.searchServices.getOrElse(
            index = settings.searchServiceSelected,
            defaultValue = { SearchServiceOptions.DEFAULT })
        val service = SearchService.getService(options)
        if (service.scrapingParameters(options) != null) {
            add(
                Tool(
                    name = "scrape_web",
                    description = """
                        Scrape a URL for detailed page content.
                        Use this when the user requests content from a specific page or when search snippets are insufficient.
                        Avoid using it for common questions unless the user asks.
                        """.trimIndent(),
                    parameters = {
                        val options = settings.searchServices.getOrElse(
                            index = settings.searchServiceSelected,
                            defaultValue = { SearchServiceOptions.DEFAULT })
                        val service = SearchService.getService(options)
                        service.scrapingParameters(options)
                    },
                    execute = {
                        val options = settings.searchServices.getOrElse(
                            index = settings.searchServiceSelected,
                            defaultValue = { SearchServiceOptions.DEFAULT })
                        val service = SearchService.getService(options)
                        val result = service.scrape(
                            params = it.jsonObject,
                            commonOptions = settings.searchCommonOptions,
                            serviceOptions = options,
                        )
                        val payload = JsonInstantPretty.encodeToJsonElement(result.getOrThrow()).jsonObject
                        listOf(UIMessagePart.Text(payload.toString()))
                    }
                ))
        }
    }
}
