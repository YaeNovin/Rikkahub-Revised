package me.rerere.search

import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalUriHandler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import me.rerere.ai.core.InputSchema
import me.rerere.common.http.awaitAndUse
import me.rerere.search.SearchService.Companion.httpClient
import me.rerere.search.SearchService.Companion.json
import me.rerere.search.SearchService.Companion.keyRoulette
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.time.LocalDate

/** Official Custom/Global REST APIs; uses a search API key, not an Ark model endpoint. */
object VolcengineSearchService : SearchService<SearchServiceOptions.VolcengineOptions> {
    override val name = "豆包 / 火山引擎"

    @Composable override fun Description() {
        val handler = LocalUriHandler.current
        TextButton(onClick = { handler.openUri("https://console.volcengine.com/search-infinity/api-key") }) {
            Text("获取豆包搜索 API Key")
        }
    }

    override fun parameters(options: SearchServiceOptions.VolcengineOptions): InputSchema = InputSchema.Obj(
        properties = buildJsonObject {
            put("query", buildJsonObject {
                put("type", "string")
                put("description", "Search query, 1-100 Unicode characters; optional for visual search.")
                put("maxLength", 100)
            })
            if (options.global && options.searchType == "visual") {
                put("image_url", buildJsonObject {
                    put("type", "string")
                    put("description", "Public HTTP(S) URL of the image to find visually similar images. Do not send a local file URL.")
                })
                put("region_of_interest", buildJsonObject {
                    put("type", "object")
                    put("properties", buildJsonObject {
                        listOf("XMin", "YMin", "XMax", "YMax").forEach { key ->
                            put(key, buildJsonObject { put("type", "number"); put("minimum", 0); put("maximum", 1) })
                        }
                    })
                    put("required", JsonArray(listOf("XMin", "YMin", "XMax", "YMax").map(::JsonPrimitive)))
                })
            }
        },
        required = if (options.global && options.searchType == "visual") listOf("image_url") else listOf("query"),
    )

    override fun scrapingParameters(options: SearchServiceOptions.VolcengineOptions): InputSchema? = null
    override suspend fun scrape(params: JsonObject, commonOptions: SearchCommonOptions,
        serviceOptions: SearchServiceOptions.VolcengineOptions): Result<ScrapedResult> =
        Result.failure(UnsupportedOperationException("豆包搜索：此服务配置不提供网页抓取"))

    override suspend fun search(params: JsonObject, commonOptions: SearchCommonOptions,
        serviceOptions: SearchServiceOptions.VolcengineOptions): Result<SearchResult> = withContext(Dispatchers.IO) {
        runCatching {
            val body = buildVolcengineSearchRequest(params, commonOptions.resultSize, serviceOptions)
            val key = keyRoulette.next(serviceOptions.activeApiKey(), "${serviceOptions.id}:${if (serviceOptions.global) "global" else "custom"}").trim()
            require(key.isNotBlank()) { "豆包搜索：请填写搜索服务 API Key" }
            val request = Request.Builder()
                .url(volcengineSearchEndpoint(serviceOptions))
                .header("Authorization", "Bearer $key")
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()
            httpClient.newCall(request).awaitAndUse { response ->
                val raw = response.body.string()
                if (!response.isSuccessful) error("豆包搜索：HTTP ${response.code} ${response.message}")
                parseVolcengineSearchResponse(raw, serviceOptions.global)
            }
        }.onFailure { if (it is CancellationException) throw it }
    }
}

internal fun volcengineSearchEndpoint(options: SearchServiceOptions.VolcengineOptions): String =
    "https://open.feedcoopapi.com/search_api/" + if (options.global) "global_search" else "web_search"

internal fun buildVolcengineSearchRequest(
    params: JsonObject, count: Int, options: SearchServiceOptions.VolcengineOptions,
): JsonObject = buildJsonObject {
    val type = options.searchType
    require(type in if (options.global) setOf("web", "image", "visual") else setOf("web", "image")) {
        "豆包搜索：当前版本不支持该搜索类型"
    }
    val query = (params["query"] as? JsonPrimitive)?.contentOrNull.orEmpty().trim()
    require(query.codePointCount(0, query.length) <= 100 && (query.isNotBlank() || type == "visual")) {
        "豆包搜索：搜索词需为 1–100 个字符"
    }
    put("Query", query)
    put("SearchType", type)
    val maxCount = if (options.global) 20 else if (type == "image") 5 else 50
    put(if (options.global) "DocCount" else "Count", count.coerceIn(1, maxCount))
    if (type != "image" || options.global) {
        put("EnableWaiting", options.enableWaiting)
        if (options.enableWaiting) put("MaxWaitTime", options.maxWaitTime.coerceIn(1, 10000))
    }
    if (options.global) {
        put("MaxSnippetLength", options.maxSnippetLength.coerceIn(1, 3000))
        if (type == "web") {
            put("MaxImageCountPerDoc", options.maxImageCountPerDoc.coerceIn(1, 10))
            put("Filter", buildJsonObject { put("IcpHostOnly", options.icpHostOnly) })
        }
        if (type == "image") {
            put("ImageFilter", buildJsonObject {
                positiveRange("ShortEdgePixel", options.shortEdgePixelMin, options.shortEdgePixelMax)
                positiveRange("AspectRatio", options.aspectRatioMin, options.aspectRatioMax)
            })
        }
        if (type == "visual") {
            val url = (params["image_url"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
            val base64 = (params["image_base64"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
            require(url.isNotEmpty() xor base64.isNotEmpty()) { "豆包搜索：图搜图需要且仅需要一张图片 URL 或 Base64" }
            if (url.isNotEmpty()) {
                val parsed = url.toHttpUrlOrNull()
                require(parsed != null && parsed.username.isEmpty() && parsed.password.isEmpty()) { "豆包搜索：图片需要有效的 HTTP(S) URL" }
            }
            require(!base64.startsWith("data:")) { "豆包搜索：Base64 不应包含 Data URL 前缀" }
            put("ImageQuery", buildJsonObject {
                if (url.isNotEmpty()) put("Url", url) else put("ImageBase64", base64)
                (params["region_of_interest"] as? JsonObject)?.let { roi ->
                    val values = listOf("XMin", "YMin", "XMax", "YMax").map { (roi[it] as? JsonPrimitive)?.doubleOrNull }
                    require(values.all { it != null && it.isFinite() && it in 0.0..1.0 } &&
                        values[0]!! < values[2]!! && values[1]!! < values[3]!!) { "豆包搜索：无效的图片检索区域" }
                    put("RegionOfInterest", roi)
                }
            })
        }
    } else {
        put("QueryControl", buildJsonObject { put("QueryRewrite", options.queryRewrite) })
        put("Filter", buildJsonObject {
            if (type == "web") {
                put("NeedContent", options.needContent)
                put("NeedUrl", options.needUrl)
                put("IcpHostOnly", options.icpHostOnly)
                put("AuthInfoLevel", options.authInfoLevel.coerceIn(0, 1))
                listOf("Sites" to (options.sites to 20), "BlockHosts" to (options.blockHosts to 5)).forEach { (key, pair) ->
                    val hosts = pair.first.split('|').map(String::trim).filter(String::isNotBlank)
                    require(hosts.size <= pair.second) { "豆包搜索：$key 最多 ${pair.second} 个域名" }
                    if (hosts.isNotEmpty()) put(key, hosts.joinToString("|"))
                }
            } else {
                positiveRange("ImageWidth", options.imageWidthMin, options.imageWidthMax)
                positiveRange("ImageHeight", options.imageHeightMin, options.imageHeightMax)
                require(options.imageShapes.all { it in setOf("横长方形", "竖长方形", "方形") }) { "豆包搜索：无效的图片形状" }
                if (options.imageShapes.isNotEmpty()) put("ImageShapes", JsonArray(options.imageShapes.map(::JsonPrimitive)))
            }
        })
        if (type == "web") {
            val range = options.timeRange.trim()
            if (range.isNotEmpty()) {
                require(range in setOf("OneDay", "OneWeek", "OneMonth", "OneYear") || runCatching {
                    val dates = range.split("..")
                    dates.size == 2 && LocalDate.parse(dates[0]) <= LocalDate.parse(dates[1])
                }.getOrDefault(false)) { "豆包搜索：无效的发文时间范围" }
                put("TimeRange", range)
            }
            require(options.contentFormats in setOf("text", "markdown")) { "豆包搜索：正文格式需为 text 或 markdown" }
            put("ContentFormats", options.contentFormats)
            if (options.industry.isNotBlank()) {
                require(options.industry in setOf("finance", "game", "health", "gov")) { "豆包搜索：无效的行业" }
                put("Industry", options.industry)
            }
        }
    }
}

private fun JsonObjectBuilder.positiveRange(key: String, min: Number?, max: Number?) {
    require(listOfNotNull(min, max).all { it.toDouble().isFinite() && it.toDouble() > 0 } &&
        (min == null || max == null || min.toDouble() <= max.toDouble())) { "豆包搜索：$key 范围无效" }
    min?.let { put("${key}Min", it) }
    max?.let { put("${key}Max", it) }
}

internal fun parseVolcengineSearchResponse(raw: String, global: Boolean): SearchResult {
    val root = json.parseToJsonElement(raw).jsonObject
    val metadata = root["ResponseMetadata"] as? JsonObject
    val requestId = (metadata?.get("RequestId") as? JsonPrimitive)?.contentOrNull.orEmpty()
    (metadata?.get("Error") as? JsonObject)?.let {
        val code = (it["Code"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        val hint = if (code == "10409") "；请使用当前版本对应的 Key，Global 不支持订阅套餐 Key" else ""
        error("豆包搜索：$code ${(it["Message"] as? JsonPrimitive)?.contentOrNull.orEmpty()}$hint (RequestId=$requestId)")
    }
    val result = root["Result"] as? JsonObject ?: error("豆包搜索：缺少 Result (RequestId=$requestId)")
    val errorCode = (result["ErrorCode"] as? JsonPrimitive)?.intOrNull ?: 0
    require(errorCode == 0) { "豆包搜索：$errorCode ${result["ErrorMsg"]} (RequestId=$requestId)" }
    fun JsonObject.str(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()
    fun JsonObject.objects(key: String) = (this[key] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
    val images = linkedSetOf<String>()
    fun addImage(url: String) { if (url.toHttpUrlOrNull() != null) images.add(url) }
    val items = if (global) result.objects("Documents").mapNotNull { doc ->
        val snippets = doc.objects("Snippet")
        snippets.forEach { ((it["Image"] as? JsonObject)?.str("ImageUrl"))?.let(::addImage) }
        val url = doc.str("Url").takeIf(String::isNotBlank) ?: return@mapNotNull null
        SearchResult.SearchResultItem(doc.str("Title").ifBlank { url }, url,
            snippets.map { it.str("Text").ifBlank { (it["Image"] as? JsonObject)?.str("Alt").orEmpty() } }
                .filter(String::isNotBlank).joinToString("\n\n"))
    } else buildList {
        result.objects("WebResults").forEach { doc ->
            doc.objects("InlineImages").forEach { addImage(it.str("ImageUrl")) }
            val text = doc.str("Content").ifBlank { doc.str("Summary").ifBlank { doc.str("Snippet") } }
            val url = doc.str("Url")
            // Ruyi cards can legitimately omit Url; keep their useful text instead of discarding it.
            add(SearchResult.SearchResultItem(doc.str("Title").ifBlank { doc.str("SiteName") }, url, text))
        }
        result.objects("ImageResults").forEach { doc ->
            val image = doc["Image"] as? JsonObject
            image?.str("Url")?.let(::addImage)
            val url = doc.str("Url").ifBlank { image?.str("Url").orEmpty() }
            if (url.isNotBlank()) add(SearchResult.SearchResultItem(doc.str("Title").ifBlank { url }, url,
                ((image?.get("Features") as? JsonObject)?.str("Description")).orEmpty()))
        }
    }
    return SearchResult(items = items, images = images.toList())
}
