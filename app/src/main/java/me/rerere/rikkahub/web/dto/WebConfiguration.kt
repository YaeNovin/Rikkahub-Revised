package me.rerere.rikkahub.web.dto

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.data.ai.mcp.McpServerConfig
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.rikkahub.web.BadRequestException
import me.rerere.rikkahub.web.ConflictException
import me.rerere.rikkahub.web.NotFoundException
import me.rerere.search.SearchServiceOptions
import me.rerere.search.withDedicatedSearchModel
import java.net.URI
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

@Serializable
data class ConfigurationUpdate(val revision: String, val values: JsonObject)

private val codec = Json(JsonInstant) { ignoreUnknownKeys = false }
private val conversationFields = setOf(
    "chatModelId", "fastModelId", "titleModelId", "translateModeId", "compressModelId",
    "memoryExtractionModelId", "embeddingModelId", "suggestionModelId", "enableTitleGeneration",
    "titleMaxLength", "titlePrompt", "translatePrompt", "translateThinkingBudget", "compressPrompt",
    "enableSuggestion", "suggestionCount", "suggestionMaxLength", "suggestionStyle",
    "suggestionInsertMode", "suggestionDisplayMode", "suggestionPrompt", "suggestionOptions",
    "enableGenerationRetry", "generationRetryMaxRetries", "generationRetryInitialIntervalSeconds",
    "generationRetryMaxDurationSeconds",
)
private val assistantFields = setOf("id", "name", "enableRollingContextCompression", "rollingContextCompressionThresholdTokens", "enabledSkills")
private val securityFields = setOf("webServerPort", "webServerLocalhostOnly", "webServerJwtEnabled", "webServerAccessPassword")
// These values are write-only. JSON null means preserve; an explicit empty value clears.
// Arbitrary headers, bodies, scripts and overrides can contain credentials too.
internal val privateConfigurationFields = setOf("apiKey", "privateKey", "password", "webServerAccessPassword", "headers", "customHeaders", "customBodies", "providerOverwrite", "extraParameters", "searchScript", "scrapeScript", "inputSchema")
// Explicit response allowlist, including nested objects. New native fields are not
// automatically browser-visible; credentials and opaque request payloads stay write-only.
private val publicConfigurationKeys = conversationFields + assistantFields + securityFields + privateConfigurationFields + setOf(
    "providers", "searchServices", "searchCommonOptions", "searchServiceSelected", "mcpServers", "sendOnEnter",
    "id", "type", "enabled", "name", "models", "balanceOption", "baseUrl", "chatCompletionsPath", "useResponseApi", "includeHistoryReasoning",
    "vertexAI", "useServiceAccount", "serviceAccountEmail", "location", "projectId", "promptCaching", "promptCacheTtl",
    "apiPath", "resultPath", "modelId", "displayName", "contextWindowTokens", "inputModalities", "outputModalities", "abilities", "tools",
    "commonOptions", "enable", "description", "needsApproval", "oauth", "url", "username", "depth", "engines", "language",
    "maxTokens", "maxTokensPerPage", "searchUrl", "scrapeUrl", "summary", "model", "customUrl", "systemPrompt", "zone",
    "kind", "baseUrlEnabled", "endpoint", "apiKeyEnabled", "maxResults", "allowedXHandles", "excludedXHandles", "fromDate", "toDate",
    "timeoutSeconds", "resultsPath", "titlePath", "urlPath", "snippetPath", "value", "resultSize", "trigger", "intervalSeconds", "contextBudget",
    "includeSummary", "includeMemory", "goal", "balancedCategories", "previewBeforeInsert", "parameterForms",
)

private val revisionKey = ByteArray(32).also { SecureRandom().nextBytes(it) }
fun configurationRevision(value: JsonElement): String = Mac.getInstance("HmacSHA256").run {
    init(SecretKeySpec(revisionKey, "HmacSHA256"))
    doFinal(value.toString().toByteArray()).joinToString("") { "%02x".format(it) }
}

private fun JsonObject.only(keys: Set<String>) = JsonObject(filterKeys { it in keys })

fun configurationRaw(settings: Settings, group: String, assistantId: String? = null): JsonObject {
    val all by lazy { codec.encodeToJsonElement(settings).jsonObject }
    return when (group) {
        "preferences" -> buildJsonObject { put("sendOnEnter", settings.displaySetting.sendOnEnter) }
        "conversations" -> JsonObject(all.only(conversationFields) + ("suggestionOptions" to JsonObject(all.getValue("suggestionOptions").jsonObject.filterKeys { it != "imageSuggestions" })))
        "security" -> all.only(securityFields)
        "providers" -> buildJsonObject { put("providers", codec.encodeToJsonElement(settings.providers)) }
        "search" -> JsonObject(all.only(setOf("searchCommonOptions", "searchServiceSelected")) + ("searchServices" to codec.encodeToJsonElement(settings.searchServices.map { if (it is SearchServiceOptions.CustomOptions) it.withDedicatedSearchModel() else it })))
        "mcp" -> buildJsonObject { put("mcpServers", codec.encodeToJsonElement(settings.mcpServers)) }
        "assistant" -> codec.encodeToJsonElement(settings.assistants.find { it.id.toString() == assistantId }
            ?: throw NotFoundException("助手不存在")).jsonObject.only(assistantFields)
        else -> throw NotFoundException("未知配置板块")
    }
}

fun configurationPublic(value: JsonElement): JsonElement = when (value) {
    is JsonObject -> JsonObject(value.filterKeys { it in publicConfigurationKeys }.mapValues { (key, item) ->
        when {
            key in privateConfigurationFields || key == "oauth" -> JsonNull
            key == "models" && item is JsonArray -> JsonArray(item.filter { it.jsonObject["type"]?.jsonPrimitive?.content !in setOf("VIDEO") }.map(::configurationPublic))
            else -> configurationPublic(item)
        }
    })
    is JsonArray -> JsonArray(value.map(::configurationPublic))
    else -> value
}

fun configurationDocument(settings: Settings, group: String, assistantId: String? = null) = buildJsonObject {
    val raw = configurationRaw(settings, group, assistantId)
    put("revision", configurationRevision(raw))
    put("values", configurationPublic(raw))
}

/** Restore write-only fields by identity, never by array position. */
private fun mergeConfiguration(old: JsonElement?, next: JsonElement, key: String = ""): JsonElement {
    if (key == "providerOverwrite" && next is JsonObject && next["__clear"] == JsonPrimitive(true) && next.size == 1) return JsonNull
    if (key == "oauth") {
        if (next != JsonNull) throw BadRequestException("OAuth 授权请从手机发起")
        return old ?: JsonNull
    }
    if (key in privateConfigurationFields && next == JsonNull) return old ?: when (key) {
        "headers", "customHeaders", "customBodies", "extraParameters" -> JsonArray(emptyList())
        "providerOverwrite", "inputSchema" -> JsonNull
        else -> JsonPrimitive("")
    }
    if (next is JsonObject) {
        val base = old as? JsonObject ?: JsonObject(emptyMap())
        return JsonObject(base + next.mapValues { (k, v) -> mergeConfiguration(base[k], v, k) })
    }
    if (next is JsonArray) {
        val previous = old as? JsonArray ?: JsonArray(emptyList())
        val result = next.map { item ->
            val identityKey = (item as? JsonObject)?.let { obj -> listOf("id", "name", "type").firstOrNull { it in obj } }
            val identity = identityKey?.let { item.jsonObject[it] }
            val prior = identity?.let { id -> previous.find { (it as? JsonObject)?.get(identityKey) == id } }
            mergeConfiguration(prior, item)
        }
        return JsonArray(if (key == "models") result + previous.filter { it.jsonObject["type"]?.jsonPrimitive?.content in setOf("VIDEO") } else result)
    }
    return next
}

internal fun configurationProviderDraft(settings: Settings, draft: JsonObject): ProviderSetting {
    if (draft.toString().length > 256000) throw BadRequestException("供应商配置过大")
    val old = settings.providers.find { it.id.toString() == draft["id"]?.jsonPrimitive?.content }
    return try {
        codec.decodeFromJsonElement<ProviderSetting>(mergeConfiguration(old?.let { codec.encodeToJsonElement(it) }, draft)).also {
            validateUrl(when (it) { is ProviderSetting.OpenAI -> it.baseUrl; is ProviderSetting.Google -> it.baseUrl; is ProviderSetting.Claude -> it.baseUrl })
        }
    } catch (e: Exception) { throw BadRequestException("供应商地址或配置无效") }
}

private fun validateUrl(value: String) {
    val uri = runCatching { URI(value) }.getOrNull()
    if (uri == null || uri.scheme !in setOf("http", "https") || uri.host.isNullOrBlank() || uri.userInfo != null || uri.fragment != null) {
        throw BadRequestException("地址必须是有效的 HTTP/HTTPS 地址，不能含用户名、密码或片段")
    }
}

private fun validateUnique(ids: List<String>) {
    if (ids.size != ids.toSet().size) throw BadRequestException("标识符重复")
}

fun applyConfiguration(current: Settings, group: String, request: ConfigurationUpdate, assistantId: String? = null): Settings {
    val raw = configurationRaw(current, group, assistantId)
    if (configurationRevision(raw) != request.revision) throw ConflictException("配置已在手机或其他浏览器修改，请重新加载后重试；未保存内容已保留")
    if (request.values.keys.any { it !in raw.keys }) throw BadRequestException("包含不支持的设置字段")
    val merged = mergeConfiguration(raw, request.values).jsonObject
    try {
        return when (group) {
            "preferences" -> current.copy(displaySetting = current.displaySetting.copy(sendOnEnter = merged.getValue("sendOnEnter").jsonPrimitive.boolean))
            "assistant" -> {
                if (merged["id"] != raw["id"] || merged["name"] != raw["name"]) throw BadRequestException("不能修改助手标识或名称")
                val old = current.assistants.first { it.id.toString() == assistantId }
                val assistant = codec.decodeFromJsonElement<Assistant>(JsonObject(codec.encodeToJsonElement(old).jsonObject + merged))
                require(assistant.rollingContextCompressionThresholdTokens >= me.rerere.rikkahub.data.ai.context.MIN_ROLLING_CONTEXT_THRESHOLD_TOKENS)
                current.copy(assistants = current.assistants.map { if (it.id == old.id) assistant else it })
            }
            "providers" -> {
                // Reject excluded models in input, including references to their existing IDs.
                val excluded = current.providers.flatMap { it.models }.filter { it.type in setOf(ModelType.VIDEO) }.map { it.id.toString() }.toSet()
                request.values["providers"]?.jsonArray?.forEach { p -> p.jsonObject["models"]?.jsonArray?.forEach { m ->
                    if (m.jsonObject["type"]?.jsonPrimitive?.content in setOf("VIDEO") || m.jsonObject["id"]?.jsonPrimitive?.content in excluded) throw BadRequestException("此页面不管理视频模型")
                } }
                val providers = codec.decodeFromJsonElement<List<ProviderSetting>>(merged.getValue("providers")).map { provider ->
                    val original = current.providers.find { it.id == provider.id }
                    if (original == null) provider else provider.copyProvider(builtIn = original.builtIn, description = original.description, shortDescription = original.shortDescription)
                }
                validateUnique(providers.map { it.id.toString() })
                validateUnique(providers.flatMap { it.models }.map { it.id.toString() })
                current.providers.filter { old -> providers.none { it.id == old.id } }.forEach { old ->
                    if (old.models.any { it.type in setOf(ModelType.VIDEO) }) throw BadRequestException("该供应商含视频模型，请从手机管理删除")
                }
                providers.forEach { provider ->
                    require(provider.name.isNotBlank())
                    validateUrl(when (provider) { is ProviderSetting.OpenAI -> provider.baseUrl; is ProviderSetting.Google -> provider.baseUrl; is ProviderSetting.Claude -> provider.baseUrl })
                    provider.models.forEach { require(it.modelId.isNotBlank()); require(it.contextWindowTokens == null || it.contextWindowTokens!! > 0) }
                }
                val removed = current.providers.flatMap { it.models }.map { it.id }.toSet() - providers.flatMap { it.models }.map { it.id }.toSet()
                val referenced = listOfNotNull(current.chatModelId, current.fastModelId, current.titleModelId, current.translateModeId, current.compressModelId, current.memoryExtractionModelId, current.embeddingModelId, current.suggestionModelId) + current.assistants.mapNotNull { it.chatModelId }
                if (removed.any { it in referenced }) throw BadRequestException("删除的模型仍被默认模型或助手使用，请先修改引用")
                current.copy(providers = providers, favoriteModels = current.favoriteModels.filterNot { it in removed })
            }
            "mcp" -> {
                val servers = codec.decodeFromJsonElement<List<McpServerConfig>>(merged.getValue("mcpServers"))
                validateUnique(servers.map { it.id.toString() })
                servers.forEach { require(it.commonOptions.name.isNotBlank()); validateUrl(when (it) { is McpServerConfig.SseTransportServer -> it.url; is McpServerConfig.StreamableHTTPServer -> it.url }) }
                current.copy(mcpServers = servers, assistants = current.assistants.map { it.copy(mcpServers = it.mcpServers.intersect(servers.map { s -> s.id }.toSet())) })
            }
            else -> {
                val next = codec.decodeFromJsonElement<Settings>(JsonObject(codec.encodeToJsonElement(current).jsonObject + merged)).copy(webServerJwtSecret = current.webServerJwtSecret)
                when (group) {
                    "security" -> { require(next.webServerPort in 1024..65535); require(!next.webServerJwtEnabled || next.webServerAccessPassword.isNotBlank()); require(next.webServerAccessPassword.length <= 1024) }
                    "search" -> {
                        require(next.searchServices.isNotEmpty()); require(next.searchServiceSelected in next.searchServices.indices); require(next.searchCommonOptions.resultSize in 1..100)
                        validateUnique(next.searchServices.map { it.id.toString() })
                        next.searchServices.forEach { option ->
                            when (option) {
                                is SearchServiceOptions.SearXNGOptions -> validateUrl(option.url)
                                is SearchServiceOptions.JinaOptions -> { validateUrl(option.searchUrl); validateUrl(option.scrapeUrl) }
                                is SearchServiceOptions.GrokOptions -> validateUrl(option.customUrl)
                                is SearchServiceOptions.CustomOptions -> { if (option.baseUrlEnabled) validateUrl(option.baseUrl); require(option.timeoutSeconds in 1..300) }
                                else -> Unit
                            }
                        }
                    }
                    "conversations" -> {
                        if (request.values["suggestionOptions"]?.jsonObject?.containsKey("imageSuggestions") == true) throw BadRequestException("此页面不管理图片建议")
                        require(next.titleMaxLength in 8..80); require(next.suggestionCount in 1..10); require(next.suggestionMaxLength in 10..200)
                        require(next.generationRetryMaxRetries in 1..me.rerere.ai.provider.MAX_PROVIDER_RETRIES); require(next.generationRetryInitialIntervalSeconds in 1..30); require(next.generationRetryMaxDurationSeconds in 10..300)
                        require(next.suggestionOptions.intervalSeconds in 0..3600); require(next.suggestionOptions.contextBudget in 500..12000); require(next.suggestionOptions.goal.length <= 2000)
                        request.values.filterKeys { it.endsWith("ModelId") || it == "translateModeId" }.forEach { (key, value) ->
                            if (value != raw[key] && value != JsonNull) {
                                val model = current.providers.flatMap { it.models }.find { it.id.toString() == value.jsonPrimitive.content } ?: throw BadRequestException("模型不存在")
                                require(if (key == "embeddingModelId") model.type == ModelType.EMBEDDING else if (key == "memoryExtractionModelId") model.type in setOf(ModelType.CHAT, ModelType.EMBEDDING) else model.type == ModelType.CHAT)
                            }
                        }
                    }
                }
                when (group) {
                    "security" -> current.copy(webServerPort = next.webServerPort, webServerLocalhostOnly = next.webServerLocalhostOnly, webServerJwtEnabled = next.webServerJwtEnabled, webServerAccessPassword = next.webServerAccessPassword)
                    "search" -> current.copy(searchServices = next.searchServices, searchCommonOptions = next.searchCommonOptions, searchServiceSelected = next.searchServiceSelected)
                    "conversations" -> current.copy(
                        chatModelId = next.chatModelId, fastModelId = next.fastModelId, titleModelId = next.titleModelId,
                        translateModeId = next.translateModeId, compressModelId = next.compressModelId,
                        memoryExtractionModelId = next.memoryExtractionModelId, embeddingModelId = next.embeddingModelId,
                        suggestionModelId = next.suggestionModelId, enableTitleGeneration = next.enableTitleGeneration,
                        titleMaxLength = next.titleMaxLength, titlePrompt = next.titlePrompt, translatePrompt = next.translatePrompt,
                        translateThinkingBudget = next.translateThinkingBudget, compressPrompt = next.compressPrompt,
                        enableSuggestion = next.enableSuggestion, suggestionCount = next.suggestionCount, suggestionMaxLength = next.suggestionMaxLength,
                        suggestionStyle = next.suggestionStyle, suggestionInsertMode = next.suggestionInsertMode,
                        suggestionDisplayMode = next.suggestionDisplayMode, suggestionPrompt = next.suggestionPrompt,
                        suggestionOptions = next.suggestionOptions.copy(imageSuggestions = current.suggestionOptions.imageSuggestions), enableGenerationRetry = next.enableGenerationRetry,
                        generationRetryMaxRetries = next.generationRetryMaxRetries, generationRetryInitialIntervalSeconds = next.generationRetryInitialIntervalSeconds,
                        generationRetryMaxDurationSeconds = next.generationRetryMaxDurationSeconds,
                    )
                    else -> throw NotFoundException("未知配置板块")
                }
            }
        }
    } catch (e: me.rerere.rikkahub.web.ApiException) { throw e }
    catch (_: Exception) { throw BadRequestException("设置值无效，请检查类型、范围和必填项") }
}

fun configurationTemplates(): JsonObject = buildJsonObject {
    fun template(type: String) = buildJsonObject { put("type", type) }
    put("providers", JsonArray(listOf("openai", "google", "claude").map { configurationPublic(codec.encodeToJsonElement(codec.decodeFromJsonElement<ProviderSetting>(template(it)))) }))
    put("searchServices", JsonArray(listOf("bing_local", "rikkahub", "zhipu", "tavily", "exa", "searxng", "linkup", "brave", "metaso", "ollama", "perplexity", "firecrawl", "jina", "bocha", "grok", "tinyfish", "serper", "anysearch", "custom_http", "custom_js").map { configurationPublic(codec.encodeToJsonElement(codec.decodeFromJsonElement<SearchServiceOptions>(template(it)))) }))
    put("mcpServers", JsonArray(listOf("sse", "streamable_http").map { configurationPublic(codec.encodeToJsonElement(codec.decodeFromJsonElement<McpServerConfig>(template(it)))) }))
    put("models", JsonArray(listOf(Model(type = ModelType.CHAT), Model(type = ModelType.EMBEDDING), Model(type = ModelType.IMAGE)).map { configurationPublic(codec.encodeToJsonElement(it)) }))
}
