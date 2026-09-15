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

    @Serializable
    data class TextLog(
        override val id: Uuid = Uuid.random(),
        override val timestamp: Long = System.currentTimeMillis(),
        override val tag: String,
        val message: String
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
        val error: String? = null
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
    ) : LogEntry()
}

object Logging {
    private val lock = Any()
    private val recentLogs = arrayListOf<LogEntry>()
    private val errorLogs = arrayListOf<LogEntry.ErrorLog>()
    private val persistenceJson = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private var errorLogFile: File? = null
    private var requestLogStore: RequestLogStore? = null
    private var loggingPreferences: SharedPreferences? = null

    @Volatile
    private var requestLoggingEnabled = false

    fun initialize(context: Context) {
        synchronized(lock) {
            loggingPreferences = context.getSharedPreferences(
                LOGGING_PREFERENCES_FILE,
                Context.MODE_PRIVATE,
            )
            requestLoggingEnabled = loggingPreferences?.getBoolean(
                REQUEST_LOGGING_ENABLED_KEY,
                false,
            ) ?: false
            errorLogFile = File(
                File(context.filesDir, ERROR_LOG_DIRECTORY),
                ERROR_LOG_FILE,
            )
            requestLogStore = RequestLogStore(File(context.filesDir, "logs/requests")) { error ->
                android.util.Log.w("Logging", "Request log persistence failed", error)
            }
            errorLogs.clear()
            val restored = errorLogFile
                ?.takeIf(File::isFile)
                ?.let { file ->
                    runCatching {
                        persistenceJson.decodeFromString<List<LogEntry.ErrorLog>>(file.readText())
                    }.getOrDefault(emptyList())
                }
                .orEmpty()
            errorLogs += restored
                .asSequence()
                .filter { it.timestamp >= errorLogCutoff() }
                .map { log ->
                    if (log.reason != null) log else log.copy(
                        reason = extractErrorReason(log.details, log.summary),
                    )
                }
                .sortedByDescending(LogEntry.ErrorLog::timestamp)
                .take(MAX_ERROR_LOGS)
            persistErrorLogsLocked()
        }
    }

    fun log(tag: String, message: String) {
        addLog(LogEntry.TextLog(tag = tag, message = message))
    }

    fun recordEvent(tag: String, message: String): Uuid {
        val entry = LogEntry.TextLog(tag = tag, message = message)
        addLog(entry)
        return entry.id
    }

    fun logRequest(entry: LogEntry.RequestLog) {
        if (!requestLoggingEnabled) return
        addLog(entry)
    }

    fun logProviderRequest(entry: LogEntry.ProviderRequestLog) {
        addLog(entry)
    }

    fun updateResponseBody(id: Uuid, body: String) {
        synchronized(lock) {
            val index = recentLogs.indexOfFirst { it.id == id }
            val original = recentLogs.getOrNull(index) ?: requestLogStore?.read()?.firstOrNull { it.id == id } ?: return
            val updated = when (val entry = original) {
                is LogEntry.RequestLog -> entry.copy(responseBody = body)
                is LogEntry.ProviderRequestLog -> entry.copy(responseBody = body)
                else -> return
            }
            if (index >= 0) recentLogs[index] = updated
            requestLogStore?.save(updated)
        }
    }

    fun logError(
        name: String,
        summary: String,
        details: String,
        reason: String? = null,
        tag: String = "ERROR",
        timestamp: Long = System.currentTimeMillis(),
    ) {
        val normalizedName = name.trim().ifEmpty { "Error" }
        val normalizedSummary = summary.trim().ifEmpty { normalizedName }
        val normalizedDetails = details.ifBlank { normalizedSummary }
        val normalizedReason = reason
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?: extractErrorReason(normalizedDetails, normalizedSummary)
        synchronized(lock) {
            errorLogs.add(
                0,
                LogEntry.ErrorLog(
                    timestamp = timestamp,
                    tag = tag,
                    name = normalizedName,
                    summary = normalizedSummary,
                    details = normalizedDetails,
                    reason = normalizedReason,
                )
            )
            pruneErrorLogsLocked()
            persistErrorLogsLocked()
        }
    }

    fun isRequestLoggingEnabled(): Boolean = requestLoggingEnabled

    fun logSoftwareError(tag: String, operation: String, error: Throwable) {
        if (error is java.util.concurrent.CancellationException) return
        logError(
            name = operation,
            summary = error.javaClass.simpleName,
            details = error.stackTraceToString().take(32_000),
            tag = tag,
        )
    }

    fun setRequestLoggingEnabled(enabled: Boolean) {
        requestLoggingEnabled = enabled
        loggingPreferences?.edit()
            ?.putBoolean(REQUEST_LOGGING_ENABLED_KEY, enabled)
            ?.apply()
    }

    private fun addLog(entry: LogEntry) {
        synchronized(lock) {
            recentLogs.add(0, entry)
            if (recentLogs.size > MAX_RECENT_LOGS) {
                recentLogs.removeLastOrNull()
            }
            if (entry is LogEntry.RequestLog || entry is LogEntry.ProviderRequestLog) requestLogStore?.save(entry)
        }
    }

    fun getRecentLogs(): List<LogEntry> {
        synchronized(lock) {
            if (pruneErrorLogsLocked()) persistErrorLogsLocked()
            return (recentLogs + requestLogStore?.read().orEmpty() + errorLogs)
                .distinctBy { it.id }.sortedByDescending(LogEntry::timestamp)
        }
    }

    fun getTextLogs(): List<LogEntry.TextLog> {
        synchronized(lock) {
            return recentLogs.filterIsInstance<LogEntry.TextLog>()
        }
    }

    fun getRequestLogs(): List<LogEntry.RequestLog> {
        synchronized(lock) {
            return getRecentLogs().filterIsInstance<LogEntry.RequestLog>()
        }
    }

    fun getProviderRequestLogs(): List<LogEntry.ProviderRequestLog> {
        synchronized(lock) {
            return getRecentLogs().filterIsInstance<LogEntry.ProviderRequestLog>()
        }
    }

    fun clear() {
        synchronized(lock) {
            recentLogs.clear()
            errorLogs.clear()
            requestLogStore?.clear()
            persistErrorLogsLocked()
        }
    }

    private fun pruneErrorLogsLocked(nowMillis: Long = System.currentTimeMillis()): Boolean {
        val originalSize = errorLogs.size
        val cutoff = errorLogCutoff(nowMillis)
        errorLogs.removeAll { it.timestamp < cutoff }
        while (errorLogs.size > MAX_ERROR_LOGS) errorLogs.removeLastOrNull()
        return errorLogs.size != originalSize
    }

    private fun persistErrorLogsLocked() {
        val destination = errorLogFile ?: return
        runCatching {
            atomicWrite(destination, persistenceJson.encodeToString(errorLogs.toList()).toByteArray(Charsets.UTF_8))
        }.onFailure { android.util.Log.w("Logging", "Error log persistence failed", it) }
    }

    private fun errorLogCutoff(nowMillis: Long = System.currentTimeMillis()): Long =
        nowMillis - ERROR_LOG_RETENTION_MILLIS
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
