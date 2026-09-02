package me.rerere.search

import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.search.SearchResult.SearchResultItem
import me.rerere.search.SearchService.Companion.httpClient
import me.rerere.search.SearchService.Companion.json
import me.rerere.search.SearchService.Companion.keyRoulette
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

private const val ANYSEARCH_BASE_URL = "https://api.anysearch.com"
private const val ANYSEARCH_CLIENT = "rikkahub/android"

object AnySearchService : SearchService<SearchServiceOptions.AnySearchOptions> {
    override val name: String = "AnySearch"

    @Composable
    override fun Description() {
        val uriHandler = LocalUriHandler.current
        TextButton(
            onClick = {
                uriHandler.openUri("https://anysearch.com/console/api-keys")
            }
        ) {
            Text(stringResource(R.string.click_to_get_api_key))
        }
    }

    override fun parameters(options: SearchServiceOptions.AnySearchOptions): InputSchema =
        InputSchema.Obj(
            properties = buildJsonObject {
                put("query", buildJsonObject {
                    put("type", "string")
                    put("description", "search keyword")
                })
            },
            required = listOf("query")
        )

    override fun scrapingParameters(options: SearchServiceOptions.AnySearchOptions): InputSchema =
        InputSchema.Obj(
            properties = buildJsonObject {
                put("url", buildJsonObject {
                    put("type", "string")
                    put("description", "url to scrape")
                })
            },
            required = listOf("url")
        )

    override suspend fun search(
        params: JsonObject,
        commonOptions: SearchCommonOptions,
        serviceOptions: SearchServiceOptions.AnySearchOptions
    ): Result<SearchResult> = withContext(Dispatchers.IO) {
        runCatching {
            val query = params["query"]?.jsonPrimitive?.content?.trim()
                ?.takeIf(String::isNotEmpty)
                ?: error("query is required")
            val body = buildAnySearchRequest(query, commonOptions.resultSize, serviceOptions)
            val request = requestBuilder("$ANYSEARCH_BASE_URL/v1/search", serviceOptions)
                .post(body.toString().toRequestBody())
                .build()

            httpClient.newCall(request).await().use { response ->
                val responseBody = response.body.string()
                if (!response.isSuccessful) {
                    throw anySearchHttpError(response.code, response.message, responseBody)
                }
                parseAnySearchSearchResponse(responseBody)
            }
        }
    }

    override suspend fun scrape(
        params: JsonObject,
        commonOptions: SearchCommonOptions,
        serviceOptions: SearchServiceOptions.AnySearchOptions
    ): Result<ScrapedResult> = withContext(Dispatchers.IO) {
        runCatching {
            val url = params["url"]?.jsonPrimitive?.content?.trim()
                ?.takeIf(String::isNotEmpty)
                ?: error("url is required")
            val body = buildJsonObject {
                put("url", url)
            }
            val request = requestBuilder("$ANYSEARCH_BASE_URL/v1/extract", serviceOptions)
                .post(body.toString().toRequestBody())
                .build()

            httpClient.newCall(request).await().use { response ->
                val responseBody = response.body.string()
                if (!response.isSuccessful) {
                    throw anySearchHttpError(response.code, response.message, responseBody)
                }
                parseAnySearchExtractResponse(responseBody, url)
            }
        }
    }

    private fun requestBuilder(
        url: String,
        options: SearchServiceOptions.AnySearchOptions
    ): Request.Builder {
        val builder = Request.Builder()
            .url(url)
            .addHeader("Content-Type", "application/json")
            .addHeader("X-Anysearch-Client", ANYSEARCH_CLIENT)

        if (options.apiKey.isNotBlank()) {
            val apiKey = keyRoulette.next(options.apiKey, options.id.toString())
            if (apiKey.isNotBlank()) {
                builder.addHeader("Authorization", "Bearer $apiKey")
            }
        }
        return builder
    }
}

internal fun buildAnySearchRequest(
    query: String,
    resultSize: Int,
    options: SearchServiceOptions.AnySearchOptions
): JsonObject = buildJsonObject {
    put("query", query)
    put("max_results", resultSize.coerceIn(1, 10))
    options.zone.trim().takeIf(String::isNotEmpty)?.let { put("zone", it) }
    options.language.trim().takeIf(String::isNotEmpty)?.let { put("language", it) }
    put("format", "json")
}

internal fun parseAnySearchSearchResponse(responseBody: String): SearchResult {
    val response = json.decodeFromString<AnySearchSearchResponse>(responseBody)
    response.requireSuccess()
    val data = response.data ?: throw anySearchApiError(
        message = "Response did not contain search data",
        requestId = response.requestId
    )
    return SearchResult(
        items = data.results.mapNotNull { result ->
            val url = result.url.orEmpty().trim()
            if (url.isEmpty()) return@mapNotNull null
            SearchResultItem(
                title = result.title.orEmpty().ifBlank { "(Untitled)" },
                url = url,
                text = result.content?.takeIf(String::isNotBlank) ?: result.snippet.orEmpty()
            )
        }
    )
}

internal fun parseAnySearchExtractResponse(
    responseBody: String,
    requestedUrl: String
): ScrapedResult {
    val response = json.decodeFromString<AnySearchExtractResponse>(responseBody)
    response.requireSuccess()
    val data = response.data ?: throw anySearchApiError(
        message = "Response did not contain extracted content",
        requestId = response.requestId
    )
    return ScrapedResult(
        urls = listOf(
            ScrapedResultUrl(
                url = data.url.orEmpty().ifBlank { requestedUrl },
                content = data.content.orEmpty(),
                metadata = data.title?.takeIf(String::isNotBlank)?.let {
                    ScrapedResultMetadata(title = it)
                }
            )
        )
    )
}

private fun anySearchHttpError(code: Int, fallbackMessage: String, responseBody: String): Exception {
    val response = runCatching {
        json.decodeFromString<AnySearchErrorResponse>(responseBody)
    }.getOrNull()
    val apiMessage = response?.message?.takeIf(String::isNotBlank) ?: fallbackMessage
    val message = response?.errorCode?.takeIf(String::isNotBlank)?.let {
        "$apiMessage [$it]"
    } ?: apiMessage
    return anySearchApiError("HTTP $code: $message", response?.requestId)
}

private fun anySearchApiError(message: String, requestId: String?): IllegalStateException {
    val requestSuffix = requestId?.takeIf(String::isNotBlank)?.let { " (request_id: $it)" }.orEmpty()
    return IllegalStateException("AnySearch: $message$requestSuffix")
}

private fun AnySearchSearchResponse.requireSuccess() {
    if (code != 0) {
        throw anySearchApiError(anySearchErrorDetail(message, errorCode), requestId)
    }
}

private fun AnySearchExtractResponse.requireSuccess() {
    if (code != 0) {
        throw anySearchApiError(anySearchErrorDetail(message, errorCode), requestId)
    }
}

private fun anySearchErrorDetail(message: String, errorCode: String?): String {
    val base = message.ifBlank { "Request failed" }
    return errorCode?.takeIf(String::isNotBlank)?.let { "$base [$it]" } ?: base
}

@Serializable
internal data class AnySearchSearchResponse(
    val code: Int,
    val message: String = "",
    @SerialName("request_id") val requestId: String? = null,
    @SerialName("error_code") val errorCode: String? = null,
    val data: AnySearchSearchData? = null,
)

@Serializable
internal data class AnySearchSearchData(
    val results: List<AnySearchResultItem> = emptyList(),
)

@Serializable
internal data class AnySearchResultItem(
    val title: String? = null,
    val url: String? = null,
    val content: String? = null,
    val snippet: String? = null,
)

@Serializable
internal data class AnySearchExtractResponse(
    val code: Int,
    val message: String = "",
    @SerialName("request_id") val requestId: String? = null,
    @SerialName("error_code") val errorCode: String? = null,
    val data: AnySearchExtractData? = null,
)

@Serializable
internal data class AnySearchExtractData(
    val url: String? = null,
    val title: String? = null,
    val content: String? = null,
)

@Serializable
private data class AnySearchErrorResponse(
    val message: String = "",
    @SerialName("request_id") val requestId: String? = null,
    @SerialName("error_code") val errorCode: String? = null,
)
