package me.rerere.common.android

import android.content.Context
import android.content.SharedPreferences
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.uuid.Uuid

private const val MAX_RECENT_LOGS = 100
private const val MAX_ERROR_LOGS = 200
private const val ERROR_LOG_RETENTION_MILLIS = 7L * 24L * 60L * 60L * 1_000L
private const val ERROR_LOG_DIRECTORY = "logs"
private const val ERROR_LOG_FILE = "error-logs.json"
private const val LOGGING_PREFERENCES_FILE = "logging-preferences"
private const val REQUEST_LOGGING_ENABLED_KEY = "request-logging-enabled"

@Serializable
sealed class LogEntry {
    abstract val id: Uuid
    abstract val timestamp: Long
    abstract val tag: String
    // Null for legacy records. ISO-8601 retains clock resolution without fabricating precision.
    abstract val preciseTimestamp: String?
    abstract val diagnostic: DiagnosticMetadata?

    @Serializable
    data class TextLog(
        override val id: Uuid = Uuid.random(),
        override val timestamp: Long = System.currentTimeMillis(),
        override val tag: String,
        val message: String,
        override val preciseTimestamp: String? = null,
        override val diagnostic: DiagnosticMetadata? = null,
    ) : LogEntry()

    @Serializable
    data class RequestLog(
        override val id: Uuid = Uuid.random(),
        override val timestamp: Long = System.currentTimeMillis(),
        override val tag: String,
        val url: String,
        val method: String,
        val requestHeaders: Map<String, String> = emptyMap(),
        val requestBody: String? = null,
        val responseBody: String? = null,
        val responseCode: Int? = null,
        val responseHeaders: Map<String, String> = emptyMap(),
        val durationMs: Long? = null,
        val error: String? = null,
        override val preciseTimestamp: String? = null,
        val firstTokenMs: Double? = null,
        val durationPhase: String? = null,
        override val diagnostic: DiagnosticMetadata? = null,
    ) : LogEntry()

    @Serializable
    data class ProviderRequestLog(
        override val id: Uuid = Uuid.random(),
        override val timestamp: Long = System.currentTimeMillis(),
        override val tag: String = "PROVIDER_REQUEST",
        val provider: String,
        val model: String,
        val channel: String,
        val operation: String,
        val endpointOrigin: String? = null,
        val parameters: Map<String, String> = emptyMap(),
        val responseCode: Int? = null,
        val durationMs: Long? = null,
        val error: String? = null,
        val url: String? = null,
        val method: String? = null,
        val responseBody: String? = null,
        val requestHeaders: Map<String, String> = emptyMap(),
        val requestBody: String? = null,
        val responseHeaders: Map<String, String> = emptyMap(),
        override val preciseTimestamp: String? = null,
        val firstTokenMs: Double? = null,
        val durationPhase: String? = null,
        override val diagnostic: DiagnosticMetadata? = null,
    ) : LogEntry()

    @Serializable
    data class ErrorLog(
        override val id: Uuid = Uuid.random(),
        override val timestamp: Long = System.currentTimeMillis(),
        override val tag: String = "ERROR",
        val name: String,
        val summary: String,
        val details: String,
        val reason: String? = null,
        override val preciseTimestamp: String? = null,
        override val diagnostic: DiagnosticMetadata? = null,
    ) : LogEntry()
}

object Logging {
    private val writer = DiagnosticWriter()
    private val session = Uuid.random().toString()
    private val healthId = Uuid.random()
    private val recent = linkedMapOf<Uuid, LogEntry>()
    private val details = object : LinkedHashMap<Uuid, LogEntry>(16, .75f, true) {}
    private var requestStore: RequestLogStore? = null
    private var eventStore: RequestLogStore? = null
    private var logDirectory: File? = null
    private var loggingPreferences: SharedPreferences? = null
    @Volatile private var requestLoggingEnabled = false
    @Volatile private var clientIdentity: DiagnosticClient? = null

    fun initialize(context: Context, client: DiagnosticClient? = null) {
        clientIdentity = client ?: clientIdentity?.takeIf { it.packageName == context.packageName }
        loggingPreferences = context.getSharedPreferences(LOGGING_PREFERENCES_FILE, Context.MODE_PRIVATE)
        requestLoggingEnabled = loggingPreferences?.getBoolean(REQUEST_LOGGING_ENABLED_KEY, false) ?: false
        val directory = File(context.filesDir, "logs")
        writer.write {
            recent.clear(); details.clear()
            logDirectory = directory
            requestStore = RequestLogStore(File(directory, "requests"), onFailure = { writer.failures.incrementAndGet() })
            eventStore = RequestLogStore(File(directory, "events"), maxEntries = 300, maxBytes = 8L * 1024 * 1024,
                onFailure = { writer.failures.incrementAndGet() }, acceptEvents = true,
                retentionMillis = ERROR_LOG_RETENTION_MILLIS)
            val legacy = File(directory, ERROR_LOG_FILE)
            if (legacy.isFile && legacy.length() <= 8L * 1024 * 1024) {
                runCatching {
                    Json { ignoreUnknownKeys = true }.decodeFromString<List<LogEntry.ErrorLog>>(legacy.readText())
                        .filter { it.timestamp >= System.currentTimeMillis() - ERROR_LOG_RETENTION_MILLIS }
                        .take(MAX_ERROR_LOGS).forEach { eventStore?.save(normalize(it, captureClient = null)) }
                    if (writer.failures.get() == 0L) legacy.delete()
                }.onFailure { writer.failures.incrementAndGet() }
            }
            requestStore?.summaries()
            eventStore?.summaries()
        }
    }

    private fun normalize(entry: LogEntry, captureClient: DiagnosticClient?): LogEntry {
        val now = java.time.Instant.ofEpochMilli(entry.timestamp).toString()
        val base = entry.diagnostic ?: DiagnosticMetadata(
            source = when(entry) {
                is LogEntry.RequestLog -> DiagnosticSource.HTTP
                is LogEntry.ProviderRequestLog -> DiagnosticSource.PROVIDER
                else -> DiagnosticSource.APPLICATION
            }, severity = if(entry is LogEntry.ErrorLog) DiagnosticSeverity.ERROR else DiagnosticSeverity.INFO,
            outcome = when(entry) {
                is LogEntry.ErrorLog -> DiagnosticOutcome.FAILED
                is LogEntry.TextLog -> DiagnosticOutcome.COMPLETED
                else -> DiagnosticOutcome.IN_PROGRESS
            }, sessionId = session)
        // Imported crash/exit records can predate the build importing them.
        val metadata = base.copy(client = base.client ?: captureClient?.takeUnless {
            base.source == DiagnosticSource.JAVA_CRASH || base.source == DiagnosticSource.PROCESS_EXIT
        }, sessionId = base.sessionId ?: session)
        val requestFailed = when(entry) {
            is LogEntry.RequestLog -> !entry.error.isNullOrBlank() || (entry.responseCode ?: 0) >= 400
            is LogEntry.ProviderRequestLog -> !entry.error.isNullOrBlank() || (entry.responseCode ?: 0) >= 400
            else -> false
        }
        val effective = if(requestFailed && metadata.outcome != DiagnosticOutcome.CANCELED)
            metadata.copy(outcome=DiagnosticOutcome.FAILED,severity=DiagnosticSeverity.ERROR) else metadata
        return when(entry) {
            is LogEntry.TextLog -> entry.copy(diagnostic=metadata, preciseTimestamp=entry.preciseTimestamp ?: now)
            is LogEntry.ErrorLog -> entry.copy(diagnostic=metadata, preciseTimestamp=entry.preciseTimestamp ?: now,
                reason=entry.reason ?: extractErrorReason(entry.details,entry.summary))
            is LogEntry.RequestLog -> entry.copy(diagnostic=effective)
            is LogEntry.ProviderRequestLog -> entry.copy(diagnostic=effective.copy(requestId=entry.parameters["request_id"] ?: effective.requestId))
        }.sanitized()
    }

    private fun remember(entry: LogEntry) {
        recent[entry.id] = if(requestStore == null && eventStore == null) entry else entry.summaryOnly()
        while(recent.size > MAX_RECENT_LOGS) recent.remove(recent.keys.first())
        details[entry.id] = entry
        // Byte-weighted cache; one very large response must not pin 100 full bodies.
        fun weight(value: LogEntry): Int = when(value) {
            is LogEntry.RequestLog -> value.requestBody.orEmpty().length + value.responseBody.orEmpty().length
            is LogEntry.ProviderRequestLog -> value.requestBody.orEmpty().length + value.responseBody.orEmpty().length
            is LogEntry.ErrorLog -> value.details.length
            is LogEntry.TextLog -> value.message.length
        }
        while(details.size > 16 || details.values.sumOf { weight(it).toLong()*2 } > 1024*1024) details.remove(details.keys.first())
    }
    private fun persist(entry: LogEntry) {
        val related = if(entry.diagnostic?.source == DiagnosticSource.PROCESS_EXIT &&
            entry.diagnostic?.attributes?.get("reason_code") == "4") {
            eventStore?.summaries().orEmpty().firstOrNull { it.diagnostic?.source == DiagnosticSource.JAVA_CRASH &&
                kotlin.math.abs(it.timestamp-entry.timestamp)<10000 }?.id?.toString()
        } else null
        val linked = if(related != null && entry is LogEntry.ErrorLog) entry.copy(
            diagnostic=entry.diagnostic?.copy(attributes=entry.diagnostic.attributes+("related_event_id" to related))) else entry
        remember(linked)
        if(linked is LogEntry.RequestLog || linked is LogEntry.ProviderRequestLog) requestStore?.save(linked)
        else eventStore?.save(linked)
    }
    private fun record(entry: LogEntry) {
        val weight = when(entry) {
            is LogEntry.RequestLog -> entry.requestBody.orEmpty().length + entry.responseBody.orEmpty().length
            is LogEntry.ProviderRequestLog -> entry.requestBody.orEmpty().length + entry.responseBody.orEmpty().length
            is LogEntry.ErrorLog -> entry.details.length
            is LogEntry.TextLog -> entry.message.length
        }
        val captureClient = clientIdentity
        writer.write(weight.toLong()*2+1024) { persist(normalize(entry, captureClient)) }
    }
    fun log(tag: String, message: String) { recordEvent(tag,message) }
    fun recordEvent(tag: String, message: String): Uuid {
        val entry=LogEntry.TextLog(tag=tag,message=message,preciseTimestamp=java.time.Instant.now().toString())
        record(entry)
        return entry.id
    }
    fun logRequest(entry: LogEntry.RequestLog) { if(requestLoggingEnabled) record(entry) }
    fun logProviderRequest(entry: LogEntry.ProviderRequestLog) = record(entry)
    private fun find(id: Uuid): LogEntry? = details[id] ?: requestStore?.detail(id) ?: eventStore?.detail(id) ?: recent[id]?.let {
        when(it) {
            is LogEntry.RequestLog -> it.copy(responseBody="[日志详情已过期或无法读取；不影响会话文件]")
            is LogEntry.ProviderRequestLog -> it.copy(responseBody="[日志详情已过期或无法读取；不影响会话文件]")
            is LogEntry.ErrorLog -> it.copy(details="日志详情已过期或无法读取")
            is LogEntry.TextLog -> it
        }
    }
    /** Must be called on an IO dispatcher. Null means evicted/unavailable, not an application file error. */
    fun getLog(id: Uuid): LogEntry? = runCatching { writer.read { find(id) } }.getOrNull()
    fun updateResponseBody(id: Uuid, body: String) {
        writer.write(body.length.toLong()*2) {
            val updated=when(val original=find(id)) {
                is LogEntry.RequestLog -> original.copy(responseBody=DiagnosticSanitizer.body(body))
                is LogEntry.ProviderRequestLog -> original.copy(responseBody=DiagnosticSanitizer.body(body))
                else -> return@write
            }
            persist(updated)
        }
    }
    fun updateRequestTiming(id: Uuid, durationMs: Long, phase: String, error: String? = null) {
        writer.write {
            val original=find(id) ?: return@write
            if((original as? LogEntry.ProviderRequestLog)?.durationPhase=="model_complete") return@write
            val hasFailure = when(original) {
                is LogEntry.RequestLog -> !original.error.isNullOrBlank() || (original.responseCode ?: 0) >= 400
                is LogEntry.ProviderRequestLog -> !original.error.isNullOrBlank() || (original.responseCode ?: 0) >= 400
                else -> false
            }
            val outcome=if(hasFailure) DiagnosticOutcome.FAILED else when(phase) {
                "failed" -> DiagnosticOutcome.FAILED
                "body_complete" -> DiagnosticOutcome.COMPLETED
                "closed" -> DiagnosticOutcome.UNKNOWN
                else -> DiagnosticOutcome.IN_PROGRESS
            }
            val metadata=(original.diagnostic ?: DiagnosticMetadata()).copy(outcome=outcome,
                severity=if(error!=null) DiagnosticSeverity.ERROR else original.diagnostic?.severity ?: DiagnosticSeverity.INFO)
            val updated=when(original) {
                is LogEntry.RequestLog -> original.copy(durationMs=durationMs,durationPhase=phase,error=error ?: original.error,diagnostic=metadata)
                is LogEntry.ProviderRequestLog -> original.copy(durationMs=durationMs,durationPhase=phase,error=error ?: original.error,diagnostic=metadata)
                else -> return@write
            }
            persist(updated.sanitized())
        }
    }
    fun completeModelRequest(requestId: String, firstTokenNanos: Long?, durationNanos: Long, failed: Boolean = false,
        canceled: Boolean = false, failure: String? = null) {
        writer.write {
            val summaries=(recent.values+requestStore?.summaries().orEmpty()).filterIsInstance<LogEntry.ProviderRequestLog>()
                .filter { it.parameters["request_id"]==requestId }.distinctBy { it.id }
            summaries.forEach { summary ->
                val original=find(summary.id) as? LogEntry.ProviderRequestLog ?: return@forEach
                val outcome=if(canceled) DiagnosticOutcome.CANCELED else if(failed) DiagnosticOutcome.FAILED else DiagnosticOutcome.COMPLETED
                persist(original.copy(firstTokenMs=firstTokenNanos?.coerceAtLeast(0)?.div(1_000_000.0),
                    durationMs=(durationNanos/1_000_000).coerceAtLeast(0),durationPhase="model_complete",
                    error=if(canceled) null else original.error ?: failure?.let { DiagnosticSanitizer.text(it,2048) } ?: if(failed) "Model generation failed" else null,
                    diagnostic=(original.diagnostic ?: DiagnosticMetadata(source=DiagnosticSource.PROVIDER)).copy(outcome=outcome,
                        severity=if(failed && !canceled) DiagnosticSeverity.ERROR else DiagnosticSeverity.INFO)))
            }
        }
    }
    fun logError(name: String, summary: String, details: String, reason: String? = null, tag: String = "ERROR",
        timestamp: Long = System.currentTimeMillis(), preciseTimestamp: String? = null, diagnostic: DiagnosticMetadata? = null): Uuid {
        val entry = LogEntry.ErrorLog(timestamp=timestamp,tag=tag,name=name.trim().ifEmpty { "Error" },
            summary=summary.ifBlank { name },details=details.ifBlank { summary },reason=reason,
            preciseTimestamp=preciseTimestamp,diagnostic=diagnostic)
        record(entry)
        return entry.id
    }
    fun logSoftwareError(tag: String, operation: String, error: Throwable) {
        if(error is java.util.concurrent.CancellationException) return
        val now=java.time.Instant.now()
        logError(operation,error.javaClass.simpleName,error.stackTraceToString(),tag=tag,
            timestamp=now.toEpochMilli(),preciseTimestamp=now.toString(),
            diagnostic=DiagnosticMetadata(severity=DiagnosticSeverity.ERROR, outcome=DiagnosticOutcome.FAILED,
                errorType=error.javaClass.name))
    }
    fun isRequestLoggingEnabled() = requestLoggingEnabled
    fun setRequestLoggingEnabled(enabled: Boolean) {
        requestLoggingEnabled=enabled
        loggingPreferences?.edit()?.putBoolean(REQUEST_LOGGING_ENABLED_KEY,enabled)?.apply()
    }
    fun getRecentLogs(): List<LogEntry> = writer.read {
        val health = if(writer.dropped.get()>0 || writer.failures.get()>0) listOf(LogEntry.TextLog(id=healthId,tag="LOG_STORAGE",
            message="本进程日志采集：dropped=${writer.dropped.get()}; persistence_failures=${writer.failures.get()}。部分诊断证据可能不可用。",
            diagnostic=DiagnosticMetadata(source=DiagnosticSource.LOG_STORAGE,severity=DiagnosticSeverity.WARNING,outcome=DiagnosticOutcome.UNKNOWN,
                client=clientIdentity, attributes=mapOf("dropped" to writer.dropped.get().toString(),
                    "persistence_failures" to writer.failures.get().toString())))) else emptyList()
        (recent.values+requestStore?.summaries().orEmpty()+eventStore?.summaries().orEmpty()+health)
            .distinctBy { it.id }.sortedByDescending { it.timestamp }
    }
    fun getTextLogs() = getRecentLogs().filterIsInstance<LogEntry.TextLog>()
    fun getRequestLogs() = getRecentLogs().filterIsInstance<LogEntry.RequestLog>().mapNotNull { getLog(it.id) as? LogEntry.RequestLog }
    fun getProviderRequestLogs() = getRecentLogs().filterIsInstance<LogEntry.ProviderRequestLog>().mapNotNull { getLog(it.id) as? LogEntry.ProviderRequestLog }
    fun health(): Map<String,Long> = mapOf("dropped" to writer.dropped.get(),"persistence_failures" to writer.failures.get())
    fun flush() { writer.read { Unit } }
    fun isPersisted(id: Uuid): Boolean = writer.read { eventStore?.detail(id)!=null || requestStore?.detail(id)!=null }
    fun clear() = writer.read {
        recent.clear(); details.clear(); requestStore?.clear(); eventStore?.clear()
        writer.dropped.set(0); writer.failures.set(0)
        logDirectory?.let { File(it,ERROR_LOG_FILE).delete() }
        logDirectory?.let { root -> File(root,"process-exits").listFiles().orEmpty().filter { it.isFile &&
            it.nameWithoutExtension.toLongOrNull()!=null && it.extension in setOf("txt","bin") }.forEach { it.delete() } }
    }
}

fun extractErrorReason(details: String, summary: String = ""): String? {
    fun meaningful(value: String): String? = value
        .trim()
        .takeIf(String::isNotEmpty)
        ?.takeUnless { it.equals(summary.trim(), ignoreCase = true) }

    JSON_ERROR_REASON_PATTERN.findAll(details).forEach { match ->
        val decoded = runCatching {
            Json.decodeFromString<String>(match.groupValues[1])
        }.getOrNull()
        meaningful(decoded.orEmpty())?.let { return it }
    }

    return details.lineSequence()
        .map(String::trim)
        .filterNot { line ->
            line.isEmpty() ||
                line.startsWith("at ") ||
                line.startsWith("Suppressed:") ||
                line.matches(STACK_TRACE_REMAINDER_PATTERN)
        }
        .map { it.removePrefix("Caused by:").trim().removeThrowableClassPrefix() }
        .mapNotNull(::meaningful)
        .lastOrNull()
}

private fun String.removeThrowableClassPrefix(): String {
    val separator = indexOf(':')
    if (separator <= 0) return this
    val prefix = substring(0, separator).trim()
    val isThrowableClass = prefix.endsWith("Exception") ||
        prefix.endsWith("Error") ||
        prefix.contains('.') && prefix.none(Char::isWhitespace)
    return if (isThrowableClass) substring(separator + 1).trim() else this
}

private val JSON_ERROR_REASON_PATTERN = Regex(
    """(?i)\"(?:message|detail|error_description)\"\s*:\s*(\"(?:\\.|[^\"\\])*\")"""
)
private val STACK_TRACE_REMAINDER_PATTERN = Regex("""\.\.\.\s+\d+\s+more""")
