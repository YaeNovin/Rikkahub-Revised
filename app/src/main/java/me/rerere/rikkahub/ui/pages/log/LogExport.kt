package me.rerere.rikkahub.ui.pages.log

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.common.android.LogEntry
import java.io.Writer
import java.time.Instant

internal enum class LogFilter { ALL, ERRORS, REQUESTS, TEXT }
internal enum class LogSection { REQUESTS, SOFTWARE }
internal enum class LogExportMode { REDACTED, FULL }

internal fun LogEntry.section(): LogSection = when (this) {
    is LogEntry.RequestLog, is LogEntry.ProviderRequestLog -> LogSection.REQUESTS
    is LogEntry.ErrorLog, is LogEntry.TextLog -> LogSection.SOFTWARE
}

internal fun LogEntry.isFailure(): Boolean = when (this) {
    is LogEntry.ErrorLog -> true
    is LogEntry.RequestLog -> !error.isNullOrBlank() || (responseCode ?: 0) >= 400
    is LogEntry.ProviderRequestLog -> !error.isNullOrBlank() || (responseCode ?: 0) >= 400
    is LogEntry.TextLog -> false
}

internal fun filterLogs(logs: List<LogEntry>, filter: LogFilter, query: String): List<LogEntry> {
    val needle = query.trim()
    return logs.filter { log ->
        val matchesType = when (filter) {
            LogFilter.ALL -> true
            LogFilter.ERRORS -> log.isFailure()
            LogFilter.REQUESTS -> log is LogEntry.RequestLog || log is LogEntry.ProviderRequestLog
            LogFilter.TEXT -> log is LogEntry.TextLog
        }
        matchesType && (needle.isEmpty() || log.searchFields().any { it.contains(needle, ignoreCase = true) })
    }.sortedByDescending { it.timestamp }
}

private fun LogEntry.searchFields(): List<String> = listOf(tag) + when (this) {
    is LogEntry.ErrorLog -> listOfNotNull(name, summary, reason, details)
    is LogEntry.TextLog -> listOf(message)
    is LogEntry.RequestLog -> listOfNotNull(url, method, responseCode?.toString(), error, requestBody)
    is LogEntry.ProviderRequestLog -> listOfNotNull(
        provider, model, channel, operation, url, method, responseCode?.toString(), error, requestBody,
    ) + parameters.flatMap { listOf(it.key, it.value) }
}

private val exportJson = Json { prettyPrint = true; encodeDefaults = true }
private val sensitiveFields = setOf(
    "authorization", "proxyauthorization", "apikey", "xapikey", "xgoogapikey",
    "key", "token", "accesstoken", "refreshtoken", "cookie", "setcookie", "password", "secret",
)
private val querySecret = Regex("(?i)([?&](?:key|api[_-]?key|access[_-]?token|token)=)[^&\\s\"<>]+")
private val bearerSecret = Regex("(?i)(bearer\\s+)[a-z0-9._~+/=-]+")
private val namedSecret = Regex("""(?i)(["']?(?:api[_-]?key|access[_-]?token|refresh[_-]?token|password|secret)["']?\s*[:=]\s*["'])[^"'\r\n]+""")

private fun redactText(value: String): String = value
    .replace(querySecret) { "${it.groupValues[1]}[REDACTED]" }
    .replace(bearerSecret) { "${it.groupValues[1]}[REDACTED]" }
    .replace(namedSecret) { "${it.groupValues[1]}[REDACTED]" }

private fun redact(element: JsonElement): JsonElement = when (element) {
    is JsonObject -> JsonObject(element.mapValues { (key, value) ->
        if (key.lowercase().replace("-", "").replace("_", "") in sensitiveFields) {
            JsonPrimitive("[REDACTED]")
        } else redact(value)
    })
    is JsonArray -> JsonArray(element.map(::redact))
    is JsonPrimitive -> if (element.isString) {
        // Request bodies may contain nested JSON stored as a string.
        val nested = runCatching { exportJson.parseToJsonElement(element.content) }.getOrNull()
        JsonPrimitive(if (nested is JsonObject || nested is JsonArray) {
            exportJson.encodeToString(redact(nested))
        } else redactText(element.content))
    } else element
}

internal fun logAnalysisPayload(log: LogEntry): String {
    require(log.isFailure()) { "Only failed logs can be analyzed" }
    val encoded = exportJson.encodeToJsonElement(LogEntry.serializer(), log)
    val sanitized = exportJson.encodeToString(redact(encoded))
    // Bound a single diagnostic request while retaining the root-cause tail of long traces.
    return if (sanitized.length <= 24_000) sanitized else
        sanitized.take(16_000) + "\n[... truncated ...]\n" + sanitized.takeLast(8_000)
}

internal fun writeLogExport(writer: Writer, logs: List<LogEntry>, version: String, mode: LogExportMode = LogExportMode.REDACTED) {
    val report = buildJsonObject {
        put("schemaVersion", 1)
        put("appVersion", version)
        put("exportedAt", Instant.now().toString())
        put("count", logs.size)
        put("logs", JsonArray(logs.map { log ->
            val encoded = exportJson.encodeToJsonElement(LogEntry.serializer(), log)
            val fields = (redact(encoded) as JsonObject).toMutableMap()
            if (mode == LogExportMode.REDACTED) {
                (fields["parameters"] as? JsonObject)?.let { params ->
                    fields["parameters"] = JsonObject(params.filterKeys { !it.startsWith("body.") })
                }
            }
            listOf("requestBody", "responseBody").forEach { key ->
                val body = (fields[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
                if (body != null) fields[key] = JsonPrimitive(if (mode == LogExportMode.REDACTED) {
                    me.rerere.rikkahub.data.ai.sanitizeRequestBody(body)
                } else me.rerere.rikkahub.data.ai.detailedLogBody(body))
            }
            fields["time"] = JsonPrimitive(Instant.ofEpochMilli(log.timestamp).toString())
            JsonObject(fields)
        }))
    }
    writer.write(exportJson.encodeToString(report))
}
