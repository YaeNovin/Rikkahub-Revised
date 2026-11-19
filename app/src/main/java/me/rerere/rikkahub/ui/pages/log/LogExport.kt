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
internal enum class DiagnosticExportKind { CSV, DETAIL }

internal fun LogEntry.section(): LogSection = when (this) {
    is LogEntry.RequestLog, is LogEntry.ProviderRequestLog -> LogSection.REQUESTS
    is LogEntry.ErrorLog, is LogEntry.TextLog -> LogSection.SOFTWARE
}

internal fun LogEntry.isFailure(): Boolean {
    val meta = diagnostic
    if (meta?.outcome == me.rerere.common.android.DiagnosticOutcome.CANCELED) return false
    if (meta?.outcome == me.rerere.common.android.DiagnosticOutcome.FAILED ||
        meta?.severity in setOf(me.rerere.common.android.DiagnosticSeverity.ERROR, me.rerere.common.android.DiagnosticSeverity.FATAL)) return true
    if (meta != null && this is LogEntry.ErrorLog) return false
    return when (this) {
    is LogEntry.ErrorLog -> true
    is LogEntry.RequestLog -> !error.isNullOrBlank() || (responseCode ?: 0) >= 400
    is LogEntry.ProviderRequestLog -> !error.isNullOrBlank() || (responseCode ?: 0) >= 400
    is LogEntry.TextLog -> Regex("(?m)^(?:[\\w.]+(?:Exception|Error):|Caused by:)").containsMatchIn(message)
    }
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
private fun redactText(value: String): String = me.rerere.common.android.DiagnosticSanitizer.text(value, 262144)
private fun redact(element: JsonElement): JsonElement = me.rerere.common.android.DiagnosticSanitizer.element(element)

internal fun logAnalysisPayload(log: LogEntry): String {
    require(log.isFailure()) { "Only failed logs can be analyzed" }
    val encoded = exportJson.encodeToJsonElement(LogEntry.serializer(), log) as JsonObject
    val allowed = setOf("id", "timestamp", "preciseTimestamp", "tag", "provider", "model", "channel", "operation",
        "responseCode", "durationMs", "durationPhase", "firstTokenMs", "name", "summary", "reason", "error", "diagnostic")
    val fields = encoded.filterKeys { it in allowed }.toMutableMap()
    val raw = when(log) { is LogEntry.ErrorLog -> log.details; is LogEntry.TextLog -> log.message; else -> "" }
    fields["details"] = JsonPrimitive(if(raw.length > 8000) raw.take(4000) + "\n[truncated]\n" + raw.takeLast(4000) else raw)
    fields["schemaVersion"] = JsonPrimitive(3)
    fields["projection"] = JsonPrimitive("diagnostic_minimum_no_conversation_bodies")
    fields["truncated"] = JsonPrimitive(raw.length > 8000)
    var result = exportJson.encodeToString(redact(JsonObject(fields)))
    if(result.length > 24000) {
        val detail = (fields["details"] as JsonPrimitive).content
        fields["details"] = JsonPrimitive(detail.take(1000) + "\n[truncated]\n" + detail.takeLast(1000))
        fields.keys.filter { it !in setOf("id", "details", "schemaVersion", "projection", "truncated") }.forEach { key ->
            if(fields[key] is JsonPrimitive) fields[key] = JsonPrimitive((fields[key] as JsonPrimitive).content.take(512))
        }
        fields.remove("diagnostic")
        fields["truncated"] = JsonPrimitive(true)
        result = exportJson.encodeToString(redact(JsonObject(fields)))
    }
    return result
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

private fun diagnosticExportFields(log: LogEntry, mode: LogExportMode): JsonObject {
    val encoded = exportJson.encodeToJsonElement(LogEntry.serializer(), log)
    val fields = (redact(encoded) as JsonObject).toMutableMap()
    if (mode == LogExportMode.REDACTED) {
        (fields["parameters"] as? JsonObject)?.let { params ->
            fields["parameters"] = JsonObject(params.filterKeys { !it.startsWith("body.") })
        }
    }
    fields.keys.toList().forEach { key ->
        val body = (fields[key] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return@forEach
        fields[key] = JsonPrimitive(if (key in setOf("requestBody", "responseBody") && mode == LogExportMode.REDACTED) {
            me.rerere.rikkahub.data.ai.sanitizeRequestBody(body)
        } else me.rerere.rikkahub.data.ai.detailedLogBody(body))
    }
    return JsonObject(fields)
}

/** RFC 4180 quoting, UTF-8 BOM for Excel, and formula-neutralized cells. */
internal fun writeDiagnosticCsv(writer: Writer, logs: List<LogEntry>, version: String, mode: LogExportMode) {
    fun cell(value: String): String {
        val safe = if (value.trimStart().firstOrNull() in setOf('=', '+', '-', '@') ||
            value.firstOrNull() in setOf('\t', '\r', '\n')) "'$value" else value
        return "\"${safe.replace("\"", "\"\"")}\""
    }
    fun row(values: List<String>) { writer.write(values.joinToString(",", transform = ::cell)); writer.write("\r\n") }
    writer.write("\uFEFF")
    row(listOf("schema_version", "app_version", "id", "section", "provider", "model_id", "time", "clock_precision",
        "request_type", "status", "http_code", "first_token_ms", "duration_ms", "duration_phase", "technical_details_json"))
    logs.forEach { log ->
        row(listOf("2", version, log.id.toString(), log.section().name, redactText(log.providerLabel()),
            log.modelId().orEmpty(), log.diagnosticTime(java.time.ZoneOffset.UTC),
            if (log.preciseTimestamp == null) "legacy_millisecond" else "device_clock",
            log.diagnosticType(), log.statusLabel(), log.requestCode()?.toString().orEmpty(),
            log.firstTokenMillis()?.toString().orEmpty(), log.elapsedMs()?.toString().orEmpty(), log.timingPhase().orEmpty(),
            exportJson.encodeToString(diagnosticExportFields(log, mode))))
    }
}

internal fun writeDiagnosticDetail(writer: Writer, log: LogEntry, version: String, mode: LogExportMode) {
    writer.write("RIKKAHUB DIAGNOSTIC LOG v2\n")
    writer.write("app_version=$version\nexported_at=${Instant.now()}\nexport_mode=${mode.name}\n")
    writer.write("time=${log.diagnosticTime(java.time.ZoneOffset.UTC)}\n")
    writer.write("clock_precision=${if (log.preciseTimestamp == null) "legacy milliseconds" else "device clock; nine displayed digits do not imply nanosecond accuracy"}\n")
    writer.write("status=${log.statusLabel()}\n")
    writer.write("timing_note=first_token_ms is model content latency, not HTTP headers; null means unavailable. duration_phase identifies the measured interval.\n")
    diagnosticExportFields(log, mode).forEach { (key, value) ->
        val body = (value as? JsonPrimitive)?.takeIf { it.isString }?.content ?: exportJson.encodeToString(value)
        writer.write("\n--- $key (${body.length} chars) ---\n")
        writer.write(body)
        writer.write("\n--- end $key ---\n")
    }
    writer.write("\nEND OF DIAGNOSTIC LOG\n")
}
