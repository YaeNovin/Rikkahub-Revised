package me.rerere.search

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import me.rerere.ai.core.InputSchema
import me.rerere.common.http.awaitAndUse
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.SocketTimeoutException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.TimeUnit

private const val MAX_CUSTOM_RESPONSE_BYTES = 2L * 1024 * 1024
private val customJson = Json { ignoreUnknownKeys = true }
private val reservedParameters = setOf("query", "model", "max_results", "allowed_x_handles", "excluded_x_handles", "from_date", "to_date")

/** Fixed JSON POST adapter. Only query comes from the model; configuration is user-owned. */
object CustomSearchService : SearchService<SearchServiceOptions.CustomOptions> {
    override val name = "自定义搜索服务"
    @Composable override fun Description() {
        Text("适用于 JSON POST 搜索接口；只发送已勾选的可选参数。不会执行脚本，不提供网页抓取。")
    }
    override fun parameters(options: SearchServiceOptions.CustomOptions): InputSchema = InputSchema.Obj(
        properties = buildJsonObject {
            put("query", buildJsonObject { put("type", "string"); put("description", "Search query") })
        }, required = listOf("query"),
    )
    override fun scrapingParameters(options: SearchServiceOptions.CustomOptions): InputSchema? = null

    override suspend fun search(params: JsonObject, commonOptions: SearchCommonOptions,
        serviceOptions: SearchServiceOptions.CustomOptions): Result<SearchResult> = withContext(Dispatchers.IO) {
        try {
            val query = (params["query"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?: error("query 必须是搜索文本")
            val request = buildCustomSearchRequest(query, serviceOptions)
            customSearchClient(SearchService.httpClient, serviceOptions.timeoutSeconds).newCall(request).awaitAndUse { response ->
                // Do not echo response bodies, URLs, queries or credentials in diagnostics.
                require(response.isSuccessful) { "自定义搜索失败：HTTP ${response.code}" }
                val body = response.body
                require(body.contentLength() <= MAX_CUSTOM_RESPONSE_BYTES) { "搜索响应超过 2 MiB 限制" }
                val source = body.source()
                require(!source.request(MAX_CUSTOM_RESPONSE_BYTES + 1)) { "搜索响应超过 2 MiB 限制" }
                Result.success(parseCustomSearchResponse(source.readUtf8(), serviceOptions))
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: SocketTimeoutException) {
            Result.failure(IllegalStateException("自定义搜索超时，请检查服务状态或增加请求超时"))
        } catch (_: IOException) {
            Result.failure(IllegalStateException("自定义搜索网络请求失败，请检查接口地址、网络或超时设置"))
        } catch (error: Exception) {
            Result.failure(error)
        }
    }
    override suspend fun scrape(params: JsonObject, commonOptions: SearchCommonOptions,
        serviceOptions: SearchServiceOptions.CustomOptions): Result<ScrapedResult> =
        Result.failure(IllegalStateException("自定义搜索服务未提供网页抓取接口"))
}

internal fun customSearchClient(client: OkHttpClient, timeoutSeconds: Int): OkHttpClient {
    require(timeoutSeconds in 10..180) { "请求超时需要 10–180 秒" }
    return client.newBuilder().callTimeout(timeoutSeconds.toLong(), TimeUnit.SECONDS)
        .readTimeout(timeoutSeconds.toLong(), TimeUnit.SECONDS)
        .connectTimeout(minOf(timeoutSeconds, 30).toLong(), TimeUnit.SECONDS)
        // Never forward credentials/query to a redirect target or duplicate a paid POST.
        .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build()
}

fun customSearchUrl(options: SearchServiceOptions.CustomOptions): HttpUrl {
    fun validate(raw: String, message: String): HttpUrl {
        val url = raw.trim().toHttpUrlOrNull() ?: error(message)
        require(url.username.isEmpty() && url.password.isEmpty() && url.fragment == null && url.query == null) {
            "接口地址不能包含用户名、密码、查询参数或片段；搜索参数请在下方勾选"
        }
        return url
    }
    if (!options.baseUrlEnabled) return validate(options.endpoint, "关闭 Base URL 时，请填写完整的 HTTP(S) 接口 URL")
    val base = validate(options.baseUrl, "请填写 Base URL，或关闭后使用完整接口 URL")
    val endpoint = options.endpoint.trim().trimStart('/')
    require(endpoint.isNotEmpty() && !options.endpoint.trim().startsWith("//") &&
        !endpoint.contains(":") && !endpoint.contains('\\') && !endpoint.contains('%') &&
        !endpoint.contains('?') && !endpoint.contains('#') &&
        endpoint.split('/').none { it == "." || it == ".." }) { "启用 Base URL 时，接口路径必须是普通相对路径" }
    return validate(base.toString().trimEnd('/') + "/" + endpoint, "接口路径无效")
}

fun buildCustomSearchBody(query: String, options: SearchServiceOptions.CustomOptions): JsonObject {
    require(query.isNotBlank() && query.length <= 16000) { "搜索内容不能为空且不能超过 16000 字符" }
    require(options.extraParameters.size <= 32) { "额外参数最多 32 项" }
    val normalized = options.withDedicatedSearchModel()
    return buildJsonObject {
        put("query", query)
        normalized.model?.takeIf { it.enabled }?.value?.trim()?.takeIf { it.isNotEmpty() }?.let { model ->
            require(model.length <= 8192) { "model 超过 8192 字符" }
            put("model", model)
        }
        if (options.maxResults.enabled) put("max_results", customSearchCount(options))
        if (options.kind == CustomSearchKind.X) {
            fun accounts(key: String, field: OptionalSearchValue) {
                if (!field.enabled) return
                val values = field.value.split(Regex("[,，\\s]+" )).filter(String::isNotEmpty).map { it.removePrefix("@") }.distinct()
                require(values.isNotEmpty() && values.size <= 100 && values.all { Regex("[A-Za-z0-9_]{1,15}").matches(it) }) {
                    "$key 需要 1–100 个有效 X 账号（逗号或换行分隔，无需 @）"
                }
                put(key, JsonArray(values.map(::JsonPrimitive)))
            }
            accounts("allowed_x_handles", options.allowedXHandles)
            accounts("excluded_x_handles", options.excludedXHandles)
            fun date(key: String, field: OptionalSearchValue): String? {
                if (!field.enabled) return null
                val value = field.value.trim()
                val parser = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).apply { isLenient = false }
                require(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}").matches(value) &&
                    runCatching { parser.parse(value) != null && value.substring(0, 4).toInt() > 0 }.getOrDefault(false)) {
                    "$key 需要有效的 YYYY-MM-DD 日期"
                }
                put(key, value)
                return value
            }
            val from = date("from_date", options.fromDate)
            val to = date("to_date", options.toDate)
            require(from == null || to == null || from <= to) { "from_date 不能晚于 to_date" }
        }
        val names = mutableSetOf<String>()
        normalized.extraParameters.filter { it.enabled }.forEachIndexed { index, field ->
            val key = field.name.trim()
            require(Regex("[A-Za-z_][A-Za-z0-9_-]{0,63}").matches(key)) { "额外参数 ${index + 1} 名称无效" }
            require(key !in reservedParameters && names.add(key)) { "额外参数名称重复或覆盖固定字段，请使用对应的勾选项" }
            require(field.value.length <= 8192) { "额外参数值超过 8192 字符" }
            val value = when (field.type) {
                CustomSearchParameterType.STRING -> JsonPrimitive(field.value)
                CustomSearchParameterType.BOOLEAN -> {
                    require(field.value.trim() in setOf("true", "false")) { "额外参数 ${index + 1} 需要 true 或 false" }
                    JsonPrimitive(field.value.trim() == "true")
                }
                CustomSearchParameterType.NUMBER -> {
                    val number = runCatching { customJson.parseToJsonElement(field.value) }.getOrNull() as? JsonPrimitive
                    require(number != null && !number.isString && number.doubleOrNull?.isFinite() == true) { "额外参数 ${index + 1} 需要有限 JSON 数字" }
                    number
                }
                CustomSearchParameterType.JSON -> parseBoundedCustomJson(field.value, "额外参数 ${index + 1} JSON 无效")
            }
            put(key, value)
        }
    }.also { require(it.toString().length <= 65536) { "搜索请求超过 64 KiB 字符限制" } }
}

private fun customSearchCount(options: SearchServiceOptions.CustomOptions): Int =
    requireNotNull(options.maxResults.value.trim().toIntOrNull()?.takeIf { it in 1..20 }) {
        "max_results 需要 1–20 的整数"
    }

internal fun buildCustomSearchRequest(query: String, options: SearchServiceOptions.CustomOptions): Request {
    val url = customSearchUrl(options)
    val body = buildCustomSearchBody(query, options)
    // Validate mappings before a potentially billed request.
    validateCustomSearchMapping(options)
    require(options.timeoutSeconds in 10..180) { "请求超时需要 10–180 秒" }
    return Request.Builder().url(url).post(body.toString().toRequestBody("application/json".toMediaType()))
        .header("Content-Type", "application/json").header("Accept", "application/json")
        .apply {
            if (options.apiKeyEnabled) {
                val key = options.apiKey.trim()
                require(key.isNotEmpty() && key.length <= 8192 && key.all { it.code in 33..126 }) { "已启用 API Key，请填写有效的单个密钥" }
                header("Authorization", "Bearer $key")
            }
        }.build()
}

/** Pure local validation for the editor/test action; never makes a network request. */
fun customSearchConfigurationError(options: SearchServiceOptions.CustomOptions): String? =
    runCatching { buildCustomSearchRequest("配置检查", options) }.exceptionOrNull()?.message

private fun mappingPath(path: String, rootAllowed: Boolean = false): List<String> {
    if (rootAllowed && path.isBlank()) return emptyList()
    val parts = path.trim().split('.')
    require(parts.size <= 8 && parts.all { Regex("[A-Za-z_][A-Za-z0-9_-]{0,63}").matches(it) }) {
        "结果字段路径无效，请使用 results 或 data.results 这样的点分对象路径"
    }
    return parts
}

fun validateCustomSearchMapping(options: SearchServiceOptions.CustomOptions) {
    mappingPath(options.resultsPath, true)
    listOf(options.titlePath, options.urlPath, options.snippetPath).forEach { mappingPath(it) }
}

private fun parseBoundedCustomJson(source: String, error: String): JsonElement {
    var depth = 0; var quoted = false; var escaped = false
    source.forEach { c ->
        if (quoted) { if (escaped) escaped = false else if (c == '\\') escaped = true else if (c == '"') quoted = false }
        else when (c) { '"' -> quoted = true; '{', '[' -> require(++depth <= 32) { "JSON 嵌套超过 32 层" }; '}', ']' -> depth-- }
    }
    return try { customJson.parseToJsonElement(source) } catch (_: Exception) { throw IllegalArgumentException(error) }
}

internal fun parseCustomSearchResponse(source: String, options: SearchServiceOptions.CustomOptions): SearchResult {
    require(source.length <= MAX_CUSTOM_RESPONSE_BYTES) { "搜索响应过大" }
    validateCustomSearchMapping(options)
    fun resolve(value: JsonElement, path: List<String>): JsonElement? = path.fold(value as JsonElement?) { current, key ->
        (current as? JsonObject)?.get(key)
    }
    val root = parseBoundedCustomJson(source, "搜索响应不是有效 JSON，请检查接口配置")
    // Some gateways encode a successful no-match response as results:null.
    // Distinguish that explicit value from a missing/mistyped mapping, and never retry
    // a successful paid POST merely because the provider found no results.
    val resultValue = resolve(root, mappingPath(options.resultsPath, true))
    val rows = when (resultValue) {
        JsonNull -> JsonArray(emptyList())
        is JsonArray -> resultValue
        else -> error("搜索响应中未找到结果数组，请检查结果列表字段映射")
    }
    val title = mappingPath(options.titlePath); val url = mappingPath(options.urlPath); val snippet = mappingPath(options.snippetPath)
    fun text(item: JsonElement, path: List<String>): String = (resolve(item, path) as? JsonPrimitive)
        ?.takeIf { it.isString }?.content.orEmpty()
    val items = rows.asSequence().filterIsInstance<JsonObject>().mapNotNull { item ->
        val link = text(item, url).trim()
        val parsed = link.toHttpUrlOrNull() ?: return@mapNotNull null
        if (parsed.username.isNotEmpty() || parsed.password.isNotEmpty()) return@mapNotNull null
        SearchResult.SearchResultItem(text(item, title).take(2000).ifBlank { "(Untitled)" }, link, text(item, snippet).take(16000))
    }.take(if (options.maxResults.enabled) customSearchCount(options) else 100).toList()
    require(rows.isEmpty() || items.isNotEmpty()) { "搜索结果缺少有效 HTTP(S) 链接，请检查 URL 字段映射" }
    return SearchResult(items = items)
}
