package me.rerere.search

import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.search.SearchService.Companion.httpClient
import me.rerere.search.SearchService.Companion.json
import me.rerere.search.SearchService.Companion.keyRoulette
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** https://api-docs.deepseek.com/guides/search/ */
object DeepSeekSearchService : SearchService<SearchServiceOptions.DeepSeekOptions> {
    override val name: String = "DeepSeek"

    @Composable
    override fun Description() {
        val uriHandler = LocalUriHandler.current
        TextButton(onClick = { uriHandler.openUri("https://platform.deepseek.com/api_keys") }) {
            Text(stringResource(R.string.click_to_get_api_key))
        }
    }

    override fun parameters(options: SearchServiceOptions.DeepSeekOptions): InputSchema =
        InputSchema.Obj(
            properties = buildJsonObject {
                put("query", buildJsonObject {
                    put("type", "string")
                    put("description", "Search query (1-128 Unicode characters). Supports site:, lang:, after:, before: and exact phrases.")
                    put("minLength", 1)
                    put("maxLength", 128)
                })
            },
            required = listOf("query")
        )

    override fun scrapingParameters(options: SearchServiceOptions.DeepSeekOptions): InputSchema =
        InputSchema.Obj(
            properties = buildJsonObject {
                put("url", buildJsonObject {
                    put("type", "string")
                    put("description", "HTTP or HTTPS page URL to fetch as Markdown")
                    put("maxLength", 8192)
                })
            },
            required = listOf("url")
        )

    override suspend fun search(
        params: JsonObject,
        commonOptions: SearchCommonOptions,
        serviceOptions: SearchServiceOptions.DeepSeekOptions
    ): Result<SearchResult> = withContext(Dispatchers.IO) {
        runCatching {
            val query = params["query"]?.jsonPrimitive?.contentOrNull.orEmpty().trim()
            require(query.isNotBlank() && query.codePointCount(0, query.length) in 1..128) {
                "DeepSeek: query must contain 1 to 128 Unicode characters"
            }
            val body = buildJsonObject {
                put("query", query)
                put("count", commonOptions.resultSize.coerceIn(1, 50))
                put("need_content", serviceOptions.needContent)
            }
            val response = json.decodeFromString<DeepSeekSearchResponse>(
                request("search", body, serviceOptions)
            )
            SearchResult(items = response.results.mapNotNull { item ->
                val url = item.url.trim().takeIf(String::isNotEmpty) ?: return@mapNotNull null
                SearchResult.SearchResultItem(
                    title = item.title.ifBlank { "(Untitled)" },
                    url = url,
                    text = item.content?.takeIf(String::isNotBlank)
                        ?: item.summary?.takeIf(String::isNotBlank)
                        ?: item.snippet.orEmpty(),
                )
            })
        }.onFailure { if (it is CancellationException) throw it }
    }

    override suspend fun scrape(
        params: JsonObject,
        commonOptions: SearchCommonOptions,
        serviceOptions: SearchServiceOptions.DeepSeekOptions
    ): Result<ScrapedResult> = withContext(Dispatchers.IO) {
        runCatching {
            val url = params["url"]?.jsonPrimitive?.contentOrNull.orEmpty().trim()
            val parsed = url.toHttpUrlOrNull()
            require(url.length in 1..8192 && parsed != null &&
                parsed.username.isEmpty() && parsed.password.isEmpty()) {
                "DeepSeek: url must be an HTTP or HTTPS URL without credentials (up to 8192 characters)"
            }
            val response = json.decodeFromString<DeepSeekFetchResponse>(
                request("fetch", buildJsonObject { put("url", url) }, serviceOptions)
            )
            require(response.content.isNotBlank()) { "DeepSeek: no page content returned" }
            ScrapedResult(urls = listOf(ScrapedResultUrl(
                url = url,
                content = response.content,
                metadata = response.title?.takeIf(String::isNotBlank)?.let {
                    ScrapedResultMetadata(title = it)
                },
            )))
        }.onFailure { if (it is CancellationException) throw it }
    }

    private suspend fun request(
        tool: String,
        body: JsonObject,
        options: SearchServiceOptions.DeepSeekOptions
    ): String {
        val apiKey = keyRoulette.next(options.apiKey, options.id.toString()).trim()
        require(apiKey.isNotBlank()) { "DeepSeek: API key is required" }
        val request = Request.Builder()
            .url("https://api.deepseek.com/v1/tools/$tool")
            .header("Authorization", "Bearer $apiKey")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        return httpClient.newCall(request).await().use { response ->
            val responseBody = response.body.string()
            if (!response.isSuccessful) {
                val detail = runCatching {
                    json.decodeFromString<DeepSeekErrorResponse>(responseBody).error.message
                }.getOrNull()?.takeIf(String::isNotBlank) ?: response.message
                error("DeepSeek: HTTP ${response.code}: $detail")
            }
            responseBody
        }
    }
}

@Serializable
private data class DeepSeekSearchResponse(val results: List<DeepSeekSearchItem>)

@Serializable
private data class DeepSeekSearchItem(
    val title: String = "",
    val url: String,
    val snippet: String? = null,
    val summary: String? = null,
    val content: String? = null,
)

@Serializable
private data class DeepSeekFetchResponse(val title: String? = null, val content: String)

@Serializable
private data class DeepSeekErrorResponse(val error: DeepSeekError)

@Serializable
private data class DeepSeekError(val message: String)
