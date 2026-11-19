package me.rerere.common.android

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.net.URI

@Serializable
enum class DiagnosticSource { APPLICATION, HTTP, PROVIDER, JAVA_CRASH, PROCESS_EXIT, RENDERER, LOG_STORAGE }
@Serializable
enum class DiagnosticSeverity { INFO, WARNING, ERROR, FATAL }
@Serializable
enum class DiagnosticOutcome { IN_PROGRESS, COMPLETED, FAILED, CANCELED, INTERRUPTED, UNKNOWN }

@Serializable
data class DiagnosticClient(
    val name: String,
    val packageName: String,
    val versionName: String? = null,
    val versionCode: Long? = null,
    val buildType: String? = null,
)

@Serializable
data class DiagnosticMetadata(
    val schemaVersion: Int = 3,
    val source: DiagnosticSource = DiagnosticSource.APPLICATION,
    val severity: DiagnosticSeverity = DiagnosticSeverity.INFO,
    val outcome: DiagnosticOutcome = DiagnosticOutcome.UNKNOWN,
    val sessionId: String? = null,
    val requestId: String? = null,
    val attributes: Map<String, String> = emptyMap(),
    val truncated: Boolean = false,
    val detailAvailable: Boolean = false,
    val client: DiagnosticClient? = null,
    val errorType: String? = null,
)

/** One credential policy for capture, private storage, copying, exports and AI input.
 * This removes credentials, not personal conversation content. AI uses a separate minimal projection.
 */
object DiagnosticSanitizer {
    private const val REDACTED = "[REDACTED]"
    private val auth = Regex("(?i)(\\b(?:bearer|basic)\\s+)[a-z0-9._~+/=-]+")
    private val named = Regex("""(?i)((?:["']?)(?:[\w-]*(?:api[_-]?key|access[_-]?key|secret|password|token|signature|credential)|key|authorization|cookie|set-cookie)["']?\s*[:=]\s*["']?)[^\s"'&,;}]+""")
    private val urls = Regex("""https?://[^\s<>"']+""", RegexOption.IGNORE_CASE)
    fun sensitive(name: String): Boolean {
        val key = name.lowercase().filter(Char::isLetterOrDigit)
        if (key in setOf("maxtokens", "maxoutputtokens", "inputtokens", "outputtokens", "totaltokens", "prompttokens", "completiontokens", "cachedtokens", "thinkingbudgettokens")) return false
        return key in setOf("key", "authorization", "proxyauthorization", "cookie", "setcookie") ||
            listOf("apikey", "accesskey", "token", "secret", "password", "signature", "credential").any(key::contains)
    }
    fun url(value: String): String = runCatching {
        val uri = URI(value)
        if (uri.scheme !in setOf("http", "https") || uri.host == null) return@runCatching value
        val query = uri.rawQuery?.split('&')?.joinToString("&") { field -> field.substringBefore('=') + "=$REDACTED" }
        // Keep path and query names useful, but never user-info, fragments or opaque query values.
        "${uri.scheme}://${uri.host}" + (if (uri.port >= 0) ":${uri.port}" else "") +
            uri.rawPath.orEmpty() + (query?.let { "?$it" } ?: "")
    }.getOrDefault("[invalid URL omitted]")

    fun text(value: String, limit: Int = 32_768): String = value.take(limit).replace(urls) { url(it.value) }
        .replace(auth) { it.groupValues[1] + REDACTED }
        .replace(named) { it.groupValues[1] + REDACTED }

    fun body(value: String, secrets: List<String> = emptyList(), limit: Int = 262_144): String {
        var bounded = value.take(limit)
        secrets.filter { it.length >= 4 }.distinct().sortedByDescending(String::length).forEach { bounded = bounded.replace(it, REDACTED) }
        val parsed = runCatching { Json.parseToJsonElement(bounded) }.getOrNull()
        if (parsed != null) return element(parsed).toString()
        return bounded.lineSequence().joinToString("\n") { line ->
            if (line.startsWith("data:")) {
                val data = line.removePrefix("data:").trimStart()
                "data: " + (runCatching { element(Json.parseToJsonElement(data)).toString() }.getOrNull() ?: text(data, limit))
            } else text(line, limit)
        }
    }

    fun element(value: JsonElement, depth: Int = 0): JsonElement = when {
        depth > 32 -> JsonPrimitive("[nested content omitted]")
        value is JsonObject -> JsonObject(value.entries.take(256).associate { (key, entry) ->
            key to if (sensitive(key)) JsonPrimitive(REDACTED) else element(entry, depth + 1)
        })
        value is JsonArray -> JsonArray(value.take(512).map { element(it, depth + 1) })
        value is JsonPrimitive && value.isString -> {
            val content = value.content.take(262_144)
            val nested = if (content.firstOrNull() in listOf('{', '[')) runCatching { Json.parseToJsonElement(content) }.getOrNull() else null
            JsonPrimitive(if (nested != null) element(nested, depth + 1).toString() else text(content, 262_144))
        }
        else -> value
    }
}

internal fun LogEntry.summaryOnly(): LogEntry = when (this) {
    is LogEntry.RequestLog -> copy(requestBody = null, responseBody = null, requestHeaders = emptyMap(), responseHeaders = emptyMap(),
        diagnostic = diagnostic?.copy(detailAvailable = true))
    is LogEntry.ProviderRequestLog -> copy(requestBody = null, responseBody = null, requestHeaders = emptyMap(), responseHeaders = emptyMap(),
        parameters = parameters.filterKeys { !it.startsWith("body.") }, diagnostic = diagnostic?.copy(detailAvailable = true))
    is LogEntry.ErrorLog -> copy(details = "", diagnostic = diagnostic?.copy(detailAvailable = true))
    is LogEntry.TextLog -> this
}

internal fun LogEntry.sanitized(): LogEntry {
    fun s(text: String) = DiagnosticSanitizer.text(text)
    val metadata = diagnostic?.let { it.copy(
        client = it.client?.let { client -> client.copy(name = s(client.name).take(128),
            packageName = s(client.packageName).take(256), versionName = client.versionName?.let(::s)?.take(128),
            buildType = client.buildType?.let(::s)?.take(64)) },
        errorType = it.errorType?.let(::s)?.take(256),
        attributes = it.attributes.entries.take(32).associate { (key, value) ->
        key.take(80) to if (DiagnosticSanitizer.sensitive(key)) "[REDACTED]" else s(value).take(2048)
    }) }
    return when (this) {
        is LogEntry.TextLog -> copy(message = s(message), tag = s(tag).take(80), diagnostic = metadata)
        is LogEntry.ErrorLog -> copy(name = s(name).take(256), summary = s(summary).take(2048), details = s(details), reason = reason?.let(::s)?.take(2048), diagnostic = metadata?.copy(truncated = metadata.truncated || details.length > 32768))
        is LogEntry.RequestLog -> copy(url = DiagnosticSanitizer.url(url), error = error?.let(::s),
            requestHeaders = requestHeaders.mapValues { (key, value) -> if (DiagnosticSanitizer.sensitive(key)) "[REDACTED]" else s(value) },
            responseHeaders = responseHeaders.mapValues { (key, value) -> if (DiagnosticSanitizer.sensitive(key)) "[REDACTED]" else s(value) },
            requestBody = requestBody?.let { DiagnosticSanitizer.body(it) }, responseBody = responseBody?.let { DiagnosticSanitizer.body(it) }, diagnostic = metadata)
        is LogEntry.ProviderRequestLog -> copy(url = url?.let(DiagnosticSanitizer::url), error = error?.let(::s),
            requestHeaders = requestHeaders.mapValues { (key, value) -> if (DiagnosticSanitizer.sensitive(key)) "[REDACTED]" else s(value) },
            responseHeaders = responseHeaders.mapValues { (key, value) -> if (DiagnosticSanitizer.sensitive(key)) "[REDACTED]" else s(value) },
            parameters = parameters.mapValues { (key, value) -> if (DiagnosticSanitizer.sensitive(key)) "[REDACTED]" else DiagnosticSanitizer.body(value) },
            requestBody = requestBody?.let { DiagnosticSanitizer.body(it) }, responseBody = responseBody?.let { DiagnosticSanitizer.body(it) }, diagnostic = metadata)
    }
}
