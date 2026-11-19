package me.rerere.ai.provider.providers.google

import kotlinx.serialization.json.*
import me.rerere.ai.provider.GeminiSearchOptions
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.parameterModelId
import me.rerere.ai.provider.ModelParameterFamily
import me.rerere.ai.provider.BuiltInTools
import me.rerere.ai.provider.inferParameterFamily
import me.rerere.ai.ui.UIMessageAnnotation
import java.time.Instant

fun Model.isGeminiModel(): Boolean = inferParameterFamily() == ModelParameterFamily.GEMINI

fun Model.isGemini4Model(): Boolean = isGeminiModel() &&
    Regex("(?:^|/)gemini[-._ ]*4(?:[-._ ]|$)").containsMatchIn(parameterModelId().lowercase())

fun Model.supportsGoogleToolCombination(): Boolean = isGeminiModel() &&
    (Regex("(?:^|/)gemini[-._ ]*(\\d+)").find(parameterModelId().lowercase())
        ?.groupValues?.get(1)?.toIntOrNull() ?: 0) >= 3

/** Read legacy model switches only until an assistant explicitly chooses its tool state. */
fun GeminiSearchOptions.resolvedFor(model: Model): GeminiSearchOptions = copy(
    enabled = enabled ?: (BuiltInTools.Search in model.tools),
    urlContext = urlContext ?: (BuiltInTools.UrlContext in model.tools),
)

/** Image Search grounding is documented for Nano Banana 2, not a generic Google Lens API. */
fun Model.supportsGoogleImageSearch(): Boolean =
    parameterModelId().lowercase().removePrefix("models/").startsWith("gemini-3.1-flash-image")

fun geminiSearchTimeRangeError(options: GeminiSearchOptions): String? {
    val start = options.startTime.trim()
    val end = options.endTime.trim()
    if (start.isBlank() && end.isBlank()) return null
    if (start.isBlank() || end.isBlank()) return "开始和结束时间需要同时填写"
    return runCatching {
        require(Instant.parse(start) <= Instant.parse(end))
        null
    }.getOrElse { "请输入有效的 RFC 3339 时间，且开始时间不能晚于结束时间" }
}

internal fun buildGoogleSearchTool(options: GeminiSearchOptions, model: Model): JsonObject {
    val imageSearch = options.imageSearch && model.supportsGoogleImageSearch()
    // Preserve image-model preferences when switching to a text-only Gemini model.
    val webSearch = options.webSearch || !imageSearch
    geminiSearchTimeRangeError(options)?.let { error(it) }
    return buildJsonObject {
        put("googleSearch", buildJsonObject {
            // Keep the legacy empty tool unchanged for the default web-only configuration.
            if (imageSearch || !webSearch) put("searchTypes", buildJsonObject {
                if (webSearch) put("webSearch", buildJsonObject {})
                if (imageSearch) put("imageSearch", buildJsonObject {})
            })
            if (options.startTime.isNotBlank()) put("timeRangeFilter", buildJsonObject {
                put("startTime", options.startTime.trim())
                put("endTime", options.endTime.trim())
            })
        })
    }
}

/** Shared by streaming and non-streaming decoding, including metadata-only terminal events. */
internal fun parseGoogleGroundingAnnotations(candidate: JsonObject): List<UIMessageAnnotation> = buildList {
    fun JsonObject.str(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()
    val grounding = (candidate["groundingMetadata"] ?: candidate["grounding_metadata"]) as? JsonObject
    val chunks = (grounding?.get("groundingChunks") ?: grounding?.get("grounding_chunks")) as? JsonArray
    val supports = ((grounding?.get("groundingSupports") ?: grounding?.get("grounding_supports")) as? JsonArray).orEmpty()
    chunks.orEmpty().forEachIndexed { chunkIndex, element ->
        val chunk = element as? JsonObject ?: return@forEachIndexed
        val web = chunk["web"] as? JsonObject
        val image = chunk["image"] as? JsonObject
        val source = image ?: web ?: return@forEachIndexed
        val url = if (image != null) source.str("sourceUri").ifBlank { source.str("source_uri") } else source.str("uri")
        if (url.isNotBlank()) add(UIMessageAnnotation.UrlCitation(
            title = source.str("title").ifBlank { url }, url = url,
            imageUrl = image?.str("imageUri")?.ifBlank { image.str("image_uri") }?.takeIf(String::isNotBlank),
            groundingSegments = supports.mapNotNull { supportElement ->
                val support = supportElement as? JsonObject ?: return@mapNotNull null
                val indices = (support["groundingChunkIndices"] ?: support["grounding_chunk_indices"]) as? JsonArray
                if (indices.orEmpty().none { (it as? JsonPrimitive)?.intOrNull == chunkIndex }) return@mapNotNull null
                val segment = support["segment"] as? JsonObject ?: return@mapNotNull null
                fun index(key: String, alias: String) = ((segment[key] ?: segment[alias]) as? JsonPrimitive)?.intOrNull
                UIMessageAnnotation.GroundingSegment(segment.str("text"), index("partIndex", "part_index"),
                    index("startIndex", "start_index"), index("endIndex", "end_index"))
            }.distinct(),
        ))
    }
    val context = (candidate["urlContextMetadata"] ?: candidate["url_context_metadata"]) as? JsonObject
    val urls = (context?.get("urlMetadata") ?: context?.get("url_metadata")) as? JsonArray
    urls.orEmpty().forEach { element ->
        val url = element as? JsonObject ?: return@forEach
        val uri = url.str("retrievedUrl").ifBlank { url.str("retrieved_url") }
        if (uri.isNotBlank()) add(UIMessageAnnotation.UrlCitation(
            title = uri, url = uri,
            retrievalStatus = url.str("urlRetrievalStatus").ifBlank { url.str("url_retrieval_status") }.takeIf(String::isNotBlank),
        ))
    }
    val entry = (grounding?.get("searchEntryPoint") ?: grounding?.get("search_entry_point")) as? JsonObject
    val html = entry?.str("renderedContent")?.ifBlank { entry.str("rendered_content") }.orEmpty()
    fun strings(key: String, alias: String) = ((grounding?.get(key) ?: grounding?.get(alias)) as? JsonArray)
        .orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
    val webQueries = strings("webSearchQueries", "web_search_queries")
    val imageQueries = strings("imageSearchQueries", "image_search_queries")
    if (html.isNotBlank() || webQueries.isNotEmpty() || imageQueries.isNotEmpty()) {
        add(UIMessageAnnotation.GoogleSearchSuggestions(html, webQueries, imageQueries))
    }
}.distinct()
