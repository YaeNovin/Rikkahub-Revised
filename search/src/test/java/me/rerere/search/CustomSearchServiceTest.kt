package me.rerere.search

import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class CustomSearchServiceTest {
    @Test fun `gateway null results are successful empty web and X searches without retries`(): Unit = runBlocking {
        val original = SearchService.httpClient
        try {
            for (kind in listOf(CustomSearchKind.WEB, CustomSearchKind.X)) {
                var requests = 0
                SearchService.httpClient = OkHttpClient.Builder().addInterceptor { chain ->
                    requests++
                    Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                        .code(200).message("OK")
                        .body("""{"provider":"grok-native","query":"RikkaHub","results":null}""".toResponseBody()).build()
                }.build()
                val result = CustomSearchService.search(buildJsonObject { put("query", "RikkaHub") },
                    SearchCommonOptions(), configured().copy(kind = kind))
                assertTrue(result.isSuccess)
                assertTrue(result.getOrThrow().items.isEmpty())
                assertEquals(1, requests)
            }
            assertThrows(IllegalStateException::class.java) { parseCustomSearchResponse("{}", configured()) }
        } finally { SearchService.httpClient = original }
    }
    private lateinit var originalClient: OkHttpClient
    @Before fun saveClient() { originalClient = SearchService.httpClient }
    @After fun restoreClient() { SearchService.httpClient = originalClient }
    private fun configured() = SearchServiceOptions.CustomOptions(baseUrl = "https://search.example")
    private fun body(options: SearchServiceOptions.CustomOptions) = buildCustomSearchBody("今日科技 \"新闻\"", options)
    private val goodResponse = """{"results":[{"url":"https://example.org/news","title":"标题","snippet":"摘要"}]}"""

    @Test fun `new provider is registered with empty base and no hidden default network destination`() {
        val options = SearchServiceOptions.CustomOptions()
        assertEquals("", options.baseUrl)
        assertFalse(options.apiKeyEnabled)
        assertFalse(options.maxResults.enabled)
        assertFalse(options.withDedicatedSearchModel().model!!.enabled)
        assertEquals("", options.withDedicatedSearchModel().model!!.value)
        assertSame(CustomSearchService, SearchService.getService(options))
        assertEquals("自定义搜索服务", SearchServiceOptions.TYPES[options::class])
        assertNotNull(customSearchConfigurationError(options))
        assertNull(CustomSearchService.scrapingParameters(options))
    }

    @Test fun `model is optional gated trimmed and available for all endpoint kinds`() {
        for (kind in CustomSearchKind.entries) {
            val options = configured().copy(kind = kind, model = OptionalSearchValue(true, "  vendor/search-v2  "))
            assertEquals(JsonPrimitive("vendor/search-v2"), body(options)["model"])
            assertFalse(body(options.copy(model = options.model!!.copy(enabled = false))).containsKey("model"))
            for (value in listOf("", "  \n\t")) {
                val blank = options.copy(model = OptionalSearchValue(true, value))
                assertFalse(body(blank).containsKey("model"))
                assertNull(customSearchConfigurationError(blank))
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            body(configured().copy(model = OptionalSearchValue(true, "x".repeat(8193))))
        }
        assertFalse(body(configured().copy(model = OptionalSearchValue(false, "x".repeat(8193)))).containsKey("model"))
    }

    @Test fun `legacy text model migrates without dropping unrelated extras and can be disabled`() {
        val language = CustomSearchParameter(enabled = true, name = "language", value = "zh")
        for (enabled in listOf(false, true)) {
            val legacy = configured().copy(extraParameters = listOf(language,
                CustomSearchParameter(enabled = enabled, name = " model ", value = "search-v1")))
            val persisted = Json.decodeFromString(SearchServiceOptions.serializer(),
                Json.encodeToString(SearchServiceOptions.serializer(), legacy)) as SearchServiceOptions.CustomOptions
            val updated = persisted.withDedicatedSearchModel()
            assertEquals(OptionalSearchValue(enabled, "search-v1"), updated.model)
            assertEquals(listOf(language), updated.extraParameters)
            assertEquals(enabled, body(persisted).containsKey("model"))
            val disabled = updated.copy(model = updated.model!!.copy(enabled = false))
            assertEquals("search-v1", disabled.model!!.value)
            assertFalse(body(disabled).containsKey("model"))
            assertEquals(updated, updated.withDedicatedSearchModel())
        }
    }

    @Test fun `explicit model cannot be overridden or reenabled by extra parameters`() {
        val extra = CustomSearchParameter(enabled = true, name = "model", value = "extra-model")
        for (enabled in listOf(false, true)) {
            assertThrows(IllegalArgumentException::class.java) {
                body(configured().copy(model = OptionalSearchValue(enabled, "chosen-model"), extraParameters = listOf(extra)))
            }
        }
        val invalidLegacy = configured().copy(extraParameters = listOf(extra, extra.copy()))
        assertEquals(invalidLegacy.extraParameters, invalidLegacy.withDedicatedSearchModel().extraParameters)
        assertNotNull(customSearchConfigurationError(invalidLegacy))
    }

    @Test fun `polymorphic persistence preserves checked states disabled values and old providers`() {
        val options = configured().copy(apiKey = "disabled-key", maxResults = OptionalSearchValue(false, "13"),
            fromDate = OptionalSearchValue(true, "2026-01-01"),
            extraParameters = listOf(CustomSearchParameter(name = "language", value = "zh", enabled = true)))
        val encoded = Json.encodeToString(SearchServiceOptions.serializer(), options)
        assertTrue(encoded.contains("custom_http"))
        assertEquals(options, Json.decodeFromString(SearchServiceOptions.serializer(), encoded))
        for (old in listOf<SearchServiceOptions>(SearchServiceOptions.BingLocalOptions(), SearchServiceOptions.CustomJsOptions())) {
            val decoded = Json.decodeFromString(SearchServiceOptions.serializer(), Json.encodeToString(SearchServiceOptions.serializer(), old))
            assertEquals(old.id, decoded.id)
            assertEquals(old::class, decoded::class)
        }
    }

    @Test fun `URL composition preserves prefix and never uses disabled base`() {
        assertEquals("https://search.example/v1/web_search", customSearchUrl(configured()).toString())
        assertEquals("https://search.example/proxy/v1/web_search", customSearchUrl(configured().copy(baseUrl = "https://search.example/proxy/")).toString())
        assertEquals("http://127.0.0.1:8080/search", customSearchUrl(configured().copy(baseUrlEnabled = false,
            baseUrl = "not-used", endpoint = "http://127.0.0.1:8080/search")).toString())
        for (invalid in listOf(
            configured().copy(baseUrl = ""), configured().copy(baseUrl = "javascript:alert(1)"),
            configured().copy(baseUrl = "https://user:password@search.example"),
            configured().copy(baseUrl = "https://search.example?secret=1"),
            configured().copy(endpoint = "//evil.example/search"), configured().copy(endpoint = "../search"),
            configured().copy(endpoint = "https://evil.example/search"), configured().copy(endpoint = "%2e%2e/search"),
            configured().copy(endpoint = "search?max_results=20"), configured().copy(endpoint = "search#fragment"),
            configured().copy(baseUrlEnabled = false),
        )) assertNotNull(customSearchConfigurationError(invalid))
    }

    @Test fun `only checked body fields are transmitted even when inactive drafts are invalid`() {
        val options = configured().copy(maxResults = OptionalSearchValue(false, "invalid"),
            kind = CustomSearchKind.WEB, allowedXHandles = OptionalSearchValue(true, "bad!"),
            fromDate = OptionalSearchValue(true, "not a date"),
            extraParameters = listOf(CustomSearchParameter(enabled = false, name = "query", type = CustomSearchParameterType.JSON, value = "{")))
        assertEquals(setOf("query"), body(options).keys)
        assertNull(customSearchConfigurationError(options))
        assertEquals(5, body(options.copy(maxResults = OptionalSearchValue(true, "5"))).getValue("max_results").jsonPrimitive.int)
        for (count in listOf("0", "21", "1.5", "", "null")) assertThrows(IllegalArgumentException::class.java) {
            body(options.copy(maxResults = OptionalSearchValue(true, count)))
        }
    }

    @Test fun `X filters strip at signs serialize arrays and validate real calendar dates`() {
        val options = configured().copy(kind = CustomSearchKind.X, endpoint = "/v1/x_search",
            allowedXHandles = OptionalSearchValue(true, "@alpha, beta\nalpha"),
            excludedXHandles = OptionalSearchValue(true, "gamma"),
            fromDate = OptionalSearchValue(true, "2024-02-29"), toDate = OptionalSearchValue(true, "2026-10-01"))
        assertEquals(Json.parseToJsonElement("""["alpha","beta"]"""), body(options)["allowed_x_handles"])
        assertEquals("2024-02-29", body(options).getValue("from_date").jsonPrimitive.content)
        for (bad in listOf("2025-02-29", "2026-13-01", "2026-1-01", "0000-01-01", "2027-01-01")) {
            assertThrows(IllegalArgumentException::class.java) { body(options.copy(fromDate = OptionalSearchValue(true, bad))) }
        }
        assertThrows(IllegalArgumentException::class.java) { body(options.copy(allowedXHandles = OptionalSearchValue(true, "bad!"))) }
        val disabled = options.copy(allowedXHandles = OptionalSearchValue(), excludedXHandles = OptionalSearchValue(),
            fromDate = OptionalSearchValue(), toDate = OptionalSearchValue())
        assertEquals(setOf("query"), body(disabled).keys)
    }

    @Test fun `extra parameters preserve JSON types including false and zero without overriding reserved fields`() {
        val extras = listOf(
            CustomSearchParameter(enabled = true, name = "language", value = "zh"),
            CustomSearchParameter(enabled = true, name = "depth", type = CustomSearchParameterType.NUMBER, value = "0"),
            CustomSearchParameter(enabled = true, name = "safe", type = CustomSearchParameterType.BOOLEAN, value = "false"),
            CustomSearchParameter(enabled = true, name = "domains", type = CustomSearchParameterType.JSON, value = """["example.org"]"""),
        )
        val result = body(configured().copy(extraParameters = extras))
        assertEquals(JsonPrimitive(false), result["safe"])
        assertEquals(JsonPrimitive(0), result["depth"])
        assertTrue(result["domains"] is JsonArray)
        for (name in listOf("query", "max_results", "allowed_x_handles", "to_date", "bad key")) {
            assertThrows(IllegalArgumentException::class.java) { body(configured().copy(extraParameters = listOf(extras[0].copy(name = name)))) }
        }
        assertThrows(IllegalArgumentException::class.java) { body(configured().copy(extraParameters = listOf(extras[0], extras[0]))) }
        for (value in listOf("NaN", "Infinity", "1e999", "true", "\"1\"")) {
            assertThrows(IllegalArgumentException::class.java) { body(configured().copy(extraParameters = listOf(extras[1].copy(value = value)))) }
        }
        assertThrows(IllegalArgumentException::class.java) { body(configured().copy(extraParameters = List(33) { extras[0] })) }
        assertThrows(IllegalArgumentException::class.java) { body(configured().copy(extraParameters = listOf(extras[3].copy(value = "[".repeat(33) + "]".repeat(33))))) }
    }

    @Test fun `API key checkbox controls header and fixed protocol cannot be replaced by extra parameters`() {
        val off = buildCustomSearchRequest("hello", configured().copy(apiKey = "not-sent\ninvalid"))
        assertNull(off.header("Authorization"))
        assertEquals("POST", off.method)
        assertEquals("application/json", off.header("Content-Type"))
        assertEquals("Bearer test-key", buildCustomSearchRequest("hello", configured().copy(apiKeyEnabled = true, apiKey = "test-key")).header("Authorization"))
        assertNotNull(customSearchConfigurationError(configured().copy(apiKeyEnabled = true)))
        assertNotNull(customSearchConfigurationError(configured().copy(apiKeyEnabled = true, apiKey = "key\r\nInjected: yes")))
    }

    @Test fun `response maps screenshot protocol and object paths into existing search results`() {
        val expected = SearchResult(items = listOf(SearchResult.SearchResultItem("标题", "https://example.org/news", "摘要")))
        assertEquals(expected, parseCustomSearchResponse(goodResponse, configured()))
        val alternate = """{"data":{"hits":[{"link":"https://example.org/news","meta":{"title":"标题","content":"摘要"}}]}}"""
        assertEquals(expected, parseCustomSearchResponse(alternate, configured().copy(resultsPath = "data.hits", urlPath = "link", titlePath = "meta.title", snippetPath = "meta.content")))
        assertEquals(expected, parseCustomSearchResponse("""[{"url":"https://example.org/news","title":"标题","snippet":"摘要"}]""", configured().copy(resultsPath = "")))
    }

    @Test fun `empty results succeed while bad shape and unsafe links fail safely`() {
        assertTrue(parseCustomSearchResponse("""{"results":[]}""", configured()).items.isEmpty())
        val sparse = parseCustomSearchResponse("""{"results":[null,{"url":"https://example.org"},{"url":"javascript:alert(1)"}]}""", configured())
        assertEquals(1, sparse.items.size)
        assertEquals("(Untitled)", sparse.items.single().title)
        assertEquals("", sparse.items.single().text)
        for (source in listOf("{}", "not-json-secret", """{"results":{}}""", """{"results":[{"url":"javascript:alert(1)"}]}""")) {
            val error = runCatching { parseCustomSearchResponse(source, configured()) }.exceptionOrNull()
            assertNotNull(error)
            assertFalse(error!!.message.orEmpty().contains("secret"))
        }
        assertNotNull(customSearchConfigurationError(configured().copy(resultsPath = "$.results[*]")))
    }

    @Test fun `complete service path ignores model overrides and does not inject global count`() = runBlocking {
        var captured: Request? = null
        SearchService.httpClient = OkHttpClient.Builder().addInterceptor { chain ->
            captured = chain.request()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK").body(goodResponse.toResponseBody()).build()
        }.build()
        val result = CustomSearchService.search(buildJsonObject {
            put("query", "真实问题"); put("max_results", 20); put("url", "https://evil.example"); put("apiKey", "evil"); put("model", "untrusted-model")
        }, SearchCommonOptions(resultSize = 19), configured())
        assertTrue(result.isSuccess)
        assertEquals("search.example", captured!!.url.host)
        assertNull(captured!!.header("Authorization"))
        val buffer = Buffer(); captured!!.body!!.writeTo(buffer)
        assertEquals(Json.parseToJsonElement("""{"query":"真实问题"}"""), Json.parseToJsonElement(buffer.readUtf8()))
    }

    @Test fun `service sends only the user configured model as a JSON string`() = runBlocking {
        var captured: Request? = null
        SearchService.httpClient = OkHttpClient.Builder().addInterceptor { chain ->
            captured = chain.request()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK").body(goodResponse.toResponseBody()).build()
        }.build()
        val options = configured().copy(model = OptionalSearchValue(true, "vendor/model-\"quoted\""))
        val result = CustomSearchService.search(buildJsonObject {
            put("query", "hello"); put("model", "ignored")
        }, SearchCommonOptions(), options)
        assertTrue(result.isSuccess)
        val buffer = Buffer(); captured!!.body!!.writeTo(buffer)
        val request = Json.parseToJsonElement(buffer.readUtf8()).jsonObject
        assertEquals(setOf("query", "model"), request.keys)
        assertEquals(options.model!!.value, request.getValue("model").jsonPrimitive.content)
        assertEquals(setOf("query"), (CustomSearchService.parameters(options) as me.rerere.ai.core.InputSchema.Obj).properties.keys)
    }

    @Test fun `HTTP failures and response size errors never echo private response contents`() = runBlocking {
        for ((code, payload) in listOf(401 to "secret-key-query", 302 to "redirect", 200 to "x".repeat(2 * 1024 * 1024 + 1))) {
            SearchService.httpClient = OkHttpClient.Builder().addInterceptor { chain ->
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("Server").body(payload.toResponseBody()).build()
            }.build()
            val result = CustomSearchService.search(buildJsonObject { put("query", "private") }, SearchCommonOptions(), configured())
            assertTrue(result.isFailure)
            assertFalse(result.exceptionOrNull()!!.message.orEmpty().contains("secret-key-query"))
        }
    }

    @Test fun `local timeout is bounded and redirects and automatic retries are disabled`() {
        val client = customSearchClient(originalClient, 90)
        assertEquals(90000, client.callTimeoutMillis)
        assertEquals(90000, client.readTimeoutMillis)
        assertFalse(client.followRedirects)
        assertFalse(client.retryOnConnectionFailure)
        assertThrows(IllegalArgumentException::class.java) { customSearchClient(originalClient, 181) }
    }

    @Test fun `cancellation cancels the underlying call and is not converted to an ordinary failure`() = runBlocking {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        var call: Call? = null
        SearchService.httpClient = OkHttpClient.Builder().eventListener(object : EventListener() {
            override fun callStart(value: Call) { call = value }
        }).addInterceptor { chain ->
            entered.countDown(); release.await(3, TimeUnit.SECONDS)
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK").body(goodResponse.toResponseBody()).build()
        }.build()
        val job = async { CustomSearchService.search(buildJsonObject { put("query", "cancel") }, SearchCommonOptions(), configured()) }
        try {
            withContext(Dispatchers.IO) { assertTrue(entered.await(3, TimeUnit.SECONDS)) }
            job.cancelAndJoin()
            assertTrue(job.isCancelled)
            assertTrue(call!!.isCanceled())
        } finally { release.countDown() }
    }
}
