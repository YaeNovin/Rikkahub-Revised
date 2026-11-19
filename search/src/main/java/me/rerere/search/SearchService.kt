package me.rerere.search

import android.content.Context
import androidx.compose.runtime.Composable
import me.rerere.common.http.awaitAndUse
import okhttp3.ResponseBody.Companion.toResponseBody
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import me.rerere.ai.core.InputSchema
import me.rerere.ai.util.KeyRoulette
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Response
import java.util.concurrent.TimeUnit
import kotlin.uuid.Uuid

interface SearchService<T : SearchServiceOptions> {
    val name: String

    fun parameters(options: T): InputSchema?

    fun scrapingParameters(options: T): InputSchema?

    @Composable
    fun Description()

    suspend fun search(
        params: JsonObject,
        commonOptions: SearchCommonOptions,
        serviceOptions: T
    ): Result<SearchResult>

    suspend fun scrape(
        params: JsonObject,
        commonOptions: SearchCommonOptions,
        serviceOptions: T
    ): Result<ScrapedResult>

    companion object {
        @Suppress("UNCHECKED_CAST")
        fun <T : SearchServiceOptions> getService(options: T): SearchService<T> {
            return when (options) {
                is SearchServiceOptions.TavilyOptions -> TavilySearchService
                is SearchServiceOptions.ExaOptions -> ExaSearchService
                is SearchServiceOptions.ZhipuOptions -> ZhipuSearchService
                is SearchServiceOptions.BingLocalOptions -> BingSearchService
                is SearchServiceOptions.SearXNGOptions -> SearXNGService
                is SearchServiceOptions.LinkUpOptions -> LinkUpService
                is SearchServiceOptions.BraveOptions -> BraveSearchService
                is SearchServiceOptions.MetasoOptions -> MetasoSearchService
                is SearchServiceOptions.OllamaOptions -> OllamaSearchService
                is SearchServiceOptions.PerplexityOptions -> PerplexitySearchService
                is SearchServiceOptions.FirecrawlOptions -> FirecrawlSearchService
                is SearchServiceOptions.JinaOptions -> JinaSearchService
                is SearchServiceOptions.BochaOptions -> BochaSearchService
                is SearchServiceOptions.RikkaHubOptions -> RikkaHubSearchService
                is SearchServiceOptions.GrokOptions -> GrokSearchService
                is SearchServiceOptions.TinyfishOptions -> TinyfishSearchService
                is SearchServiceOptions.SerperOptions -> SerperSearchService
                is SearchServiceOptions.AnySearchOptions -> AnySearchService
                is SearchServiceOptions.DeepSeekOptions -> DeepSeekSearchService
                is SearchServiceOptions.VolcengineOptions -> VolcengineSearchService
                is SearchServiceOptions.CustomJsOptions -> CustomJsSearchService
                is SearchServiceOptions.CustomOptions -> CustomSearchService
            } as SearchService<T>
        }

        @Volatile
        internal var httpClient: OkHttpClient = OkHttpClient.Builder()
            .retryOnConnectionFailure(true)
            .followRedirects(true)
            .followSslRedirects(true)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(60, TimeUnit.SECONDS)
            .build()

        @Volatile
        internal var keyRoulette: KeyRoulette = KeyRoulette.default()

        fun init(client: OkHttpClient, context: Context? = null) {
            // Search tools must not inherit the AI streaming client's 10-minute read timeout.
            httpClient = client.newBuilder()
                .readTimeout(45, TimeUnit.SECONDS)
                .callTimeout(60, TimeUnit.SECONDS)
                .build()
            keyRoulette = if (context != null) KeyRoulette.lru(context) else KeyRoulette.default()
        }

        internal val json by lazy {
            Json {
                ignoreUnknownKeys = true
                explicitNulls = false
            }
        }
    }
}

@Serializable
data class SearchCommonOptions(
    val resultSize: Int = 10
)

@Serializable
data class SearchResult(
    val answer: String? = null,
    val items: List<SearchResultItem>,
    val images: List<String> = emptyList(),
) {
    @Serializable
    data class SearchResultItem(
        val title: String,
        val url: String,
        val text: String,
    )
}

@Serializable
data class ScrapedResult(
    val urls: List<ScrapedResultUrl>,
)

@Serializable
data class ScrapedResultUrl(
    val url: String,
    val content: String,
    val metadata: ScrapedResultMetadata? = null,
)

@Serializable
data class ScrapedResultMetadata(
    val title: String? = null,
    val description: String? = null,
    val language: String? = null,
)

@Serializable
sealed class SearchServiceOptions {
    abstract val id: Uuid

    open val displayName: String
        get() = TYPES[this::class] ?: "Unknown"

    companion object {
        val DEFAULT = BingLocalOptions()

        val TYPES = mapOf(
            BingLocalOptions::class to "Bing",
            RikkaHubOptions::class to "RikkaHub",
            ZhipuOptions::class to "智谱",
            TavilyOptions::class to "Tavily",
            ExaOptions::class to "Exa",
            SearXNGOptions::class to "SearXNG",
            LinkUpOptions::class to "LinkUp",
            BraveOptions::class to "Brave",
            MetasoOptions::class to "秘塔",
            OllamaOptions::class to "Ollama",
            PerplexityOptions::class to "Perplexity",
            FirecrawlOptions::class to "Firecrawl",
            JinaOptions::class to "Jina",
            BochaOptions::class to "博查",
            GrokOptions::class to "Grok",
            TinyfishOptions::class to "Tinyfish",
            SerperOptions::class to "Serper",
            AnySearchOptions::class to "AnySearch",
            DeepSeekOptions::class to "DeepSeek",
            VolcengineOptions::class to "豆包 / 火山引擎",
            CustomOptions::class to "自定义搜索服务",
            CustomJsOptions::class to "Custom JS",
        )
    }

    @Serializable
    @SerialName("bing_local")
    class BingLocalOptions(
        override val id: Uuid = Uuid.random()
    ) : SearchServiceOptions()

    @Serializable
    @SerialName("zhipu")
    data class ZhipuOptions(
        override val id: Uuid = Uuid.random(),
        val apiKey: String = "",
    ) : SearchServiceOptions()

    @Serializable
    @SerialName("tavily")
    data class TavilyOptions(
        override val id: Uuid = Uuid.random(),
        val apiKey: String = "",
        val depth: String = "advanced",
    ) : SearchServiceOptions()

    @Serializable
    @SerialName("exa")
    data class ExaOptions(
        override val id: Uuid = Uuid.random(),
        val apiKey: String = "",
    ) : SearchServiceOptions()

    @Serializable
    @SerialName("searxng")
    data class SearXNGOptions(
        override val id: Uuid = Uuid.random(),
        val url: String = "",
        val engines: String = "",
        val language: String = "",
        val username: String = "",
        val password: String = "",
    ) : SearchServiceOptions()

    @Serializable
    @SerialName("linkup")
    data class LinkUpOptions(
        override val id: Uuid = Uuid.random(),
        val apiKey: String = "",
        val depth: String = "standard",
    ) : SearchServiceOptions()

    @Serializable
    @SerialName("brave")
    data class BraveOptions(
        override val id: Uuid = Uuid.random(),
        val apiKey: String = "",
    ) : SearchServiceOptions()

    @Serializable
    @SerialName("metaso")
    data class MetasoOptions(
        override val id: Uuid = Uuid.random(),
        val apiKey: String = "",
    ) : SearchServiceOptions()

    @Serializable
    @SerialName("ollama")
    data class OllamaOptions(
        override val id: Uuid = Uuid.random(),
        val apiKey: String = "",
    ) : SearchServiceOptions()

    @Serializable
    @SerialName("perplexity")
    data class PerplexityOptions(
        override val id: Uuid = Uuid.random(),
        val apiKey: String = "",
        val maxTokens: Int? = null,
        val maxTokensPerPage: Int? = null,
    ) : SearchServiceOptions()

    @Serializable
    @SerialName("firecrawl")
    data class FirecrawlOptions(
        override val id: Uuid = Uuid.random(),
        val apiKey: String = "",
    ) : SearchServiceOptions()

    @Serializable
    @SerialName("jina")
    data class JinaOptions(
        override val id: Uuid = Uuid.random(),
        val apiKey: String = "",
        val searchUrl: String = "https://s.jina.ai/",
        val scrapeUrl: String = "https://r.jina.ai/",
    ) : SearchServiceOptions()

    @Serializable
    @SerialName("bocha")
    data class BochaOptions(
        override val id: Uuid = Uuid.random(),
        val apiKey: String = "",
        val summary: Boolean = true,
    ) : SearchServiceOptions()

    @Serializable
    @SerialName("rikkahub")
    data class RikkaHubOptions(
        override val id: Uuid = Uuid.random(),
        val apiKey: String = "",
        val depth: String = "standard",
    ) : SearchServiceOptions()

    @Serializable
    @SerialName("grok")
    data class GrokOptions(
        override val id: Uuid = Uuid.random(),
        val apiKey: String = "",
        val model: String = "grok-4-1-fast-non-reasoning",
        val customUrl: String = "https://api.x.ai/v1/responses",
        val systemPrompt: String = "You are a helpful search assistant. Search the web to find accurate and up-to-date information for the user's query. Provide a comprehensive answer with citations.",
    ) : SearchServiceOptions()

    @Serializable
    @SerialName("tinyfish")
    data class TinyfishOptions(
        override val id: Uuid = Uuid.random(),
        val apiKey: String = "",
    ) : SearchServiceOptions()

    @Serializable
    @SerialName("serper")
    data class SerperOptions(
        override val id: Uuid = Uuid.random(),
        val apiKey: String = "",
    ) : SearchServiceOptions()

    @Serializable
    @SerialName("anysearch")
    data class AnySearchOptions(
        override val id: Uuid = Uuid.random(),
        val apiKey: String = "",
        val zone: String = "",
        val language: String = "",
    ) : SearchServiceOptions()

    @Serializable
    @SerialName("deepseek")
    data class DeepSeekOptions(
        override val id: Uuid = Uuid.random(),
        val apiKey: String = "",
        val needContent: Boolean = false,
    ) : SearchServiceOptions()

    @Serializable
    @SerialName("volcengine")
    data class VolcengineOptions(
        override val id: Uuid = Uuid.random(),
        val apiKey: String = "",
        // Null preserves keys saved before the editions had independent configuration.
        val customApiKey: String? = null,
        val globalApiKey: String? = null,
        val global: Boolean = false,
        val searchType: String = "web",
        val enableWaiting: Boolean = false,
        val maxWaitTime: Int = 5000,
        val icpHostOnly: Boolean = false,
        val needContent: Boolean = false,
        val needUrl: Boolean = false,
        val sites: String = "",
        val blockHosts: String = "",
        val authInfoLevel: Int = 0,
        val timeRange: String = "",
        val queryRewrite: Boolean = false,
        val contentFormats: String = "text",
        val industry: String = "",
        val maxSnippetLength: Int = 500,
        val maxImageCountPerDoc: Int = 3,
        val imageWidthMin: Int? = null,
        val imageHeightMin: Int? = null,
        val imageWidthMax: Int? = null,
        val imageHeightMax: Int? = null,
        val imageShapes: List<String> = emptyList(),
        val shortEdgePixelMin: Int? = null,
        val shortEdgePixelMax: Int? = null,
        val aspectRatioMin: Double? = null,
        val aspectRatioMax: Double? = null,
    ) : SearchServiceOptions() {
        fun activeApiKey(): String = (if (global) globalApiKey else customApiKey) ?: apiKey

        fun withActiveApiKey(value: String): VolcengineOptions =
            if (global) copy(globalApiKey = value) else copy(customApiKey = value)
    }

    @Serializable
    @SerialName("custom_http")
    data class CustomOptions(
        override val id: Uuid = Uuid.random(),
        val name: String = "",
        val kind: CustomSearchKind = CustomSearchKind.WEB,
        val baseUrlEnabled: Boolean = true,
        val baseUrl: String = "",
        val endpoint: String = "/v1/web_search",
        val apiKeyEnabled: Boolean = false,
        val apiKey: String = "",
        // Null identifies older configurations whose model may be an extra JSON parameter.
        val model: OptionalSearchValue? = null,
        val maxResults: OptionalSearchValue = OptionalSearchValue(value = "5"),
        val allowedXHandles: OptionalSearchValue = OptionalSearchValue(),
        val excludedXHandles: OptionalSearchValue = OptionalSearchValue(),
        val fromDate: OptionalSearchValue = OptionalSearchValue(),
        val toDate: OptionalSearchValue = OptionalSearchValue(),
        val extraParameters: List<CustomSearchParameter> = emptyList(),
        val timeoutSeconds: Int = 60,
        val resultsPath: String = "results",
        val titlePath: String = "title",
        val urlPath: String = "url",
        val snippetPath: String = "snippet",
    ) : SearchServiceOptions() {
        override val displayName: String get() = name.ifBlank { "自定义搜索服务" }
    }

    @Serializable
    @SerialName("custom_js")
    data class CustomJsOptions(
        override val id: Uuid = Uuid.random(),
        val name: String = "",
        val searchScript: String = DEFAULT_SEARCH_SCRIPT,
        val scrapeScript: String = "",
    ) : SearchServiceOptions() {
        override val displayName: String
            get() = name.ifBlank { "Custom JS" }
        companion object {
            const val DEFAULT_SCRAPE_SCRIPT = """// Implement scrape(urls) function
// urls is an array of URL strings
// Use fetch(url, options?) for HTTP requests
// fetch() returns { status, ok, text(), json() }
// Return { urls: [{ url, content, metadata?: { title?, description?, language? } }] }

function scrape(urls) {
  return {
    urls: urls.map(function(url) {
      const res = fetch(url);
      const body = res.text();
      return { url: url, content: body };
    })
  };
}"""

            const val DEFAULT_SEARCH_SCRIPT = """// Implement search(query, resultSize) function
// Use fetch(url, options?) for HTTP requests
// fetch() returns { status, ok, text(), json() }
// Return { items: [{ title, url, text }], answer?: string }

function search(query, resultSize) {
  const encoded = encodeURIComponent(query);
  const res = fetch("https://example.com/search?q=" + encoded + "&limit=" + resultSize);
  const data = res.json();
  return {
    items: data.results.map(function(r) {
      return { title: r.title, url: r.url, text: r.snippet };
    })
  };
}"""
        }
    }
}

/** Search APIs return complete JSON bodies. Keep cancellation attached until those bytes arrive. */
internal suspend fun Call.await(): Response = awaitAndUse { response ->
    val contentType = response.body.contentType()
    val bytes = response.body.bytes()
    response.newBuilder().body(bytes.toResponseBody(contentType)).build()
}
