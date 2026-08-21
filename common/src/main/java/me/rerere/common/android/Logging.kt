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
        val responseCode: Int? = null,
        val responseHeaders: Map<String, String> = emptyMap(),
        val durationMs: Long? = null,
        val error: String? = null
    ) : LogEntry()

    @Serializable
    data class ErrorLog(
        override val id: Uuid = Uuid.random(),
        override val timestamp: Long = System.currentTimeMillis(),
        override val tag: String = "ERROR",
        val name: String,
        val summary: String,
        val details: String,
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
                .sortedByDescending(LogEntry.ErrorLog::timestamp)
                .take(MAX_ERROR_LOGS)
            persistErrorLogsLocked()
        }
    }

    fun log(tag: String, message: String) {
        addLog(LogEntry.TextLog(tag = tag, message = message))
    }

    fun logRequest(entry: LogEntry.RequestLog) {
        if (!requestLoggingEnabled) return
        addLog(entry)
    }

    fun logError(
        name: String,
        summary: String,
        details: String,
        tag: String = "ERROR",
    ) {
        val normalizedName = name.trim().ifEmpty { "Error" }
        val normalizedSummary = summary.trim().ifEmpty { normalizedName }
        val normalizedDetails = details.ifBlank { normalizedSummary }
        synchronized(lock) {
            errorLogs.add(
                0,
                LogEntry.ErrorLog(
                    tag = tag,
                    name = normalizedName,
                    summary = normalizedSummary,
                    details = normalizedDetails,
                )
            )
            pruneErrorLogsLocked()
            persistErrorLogsLocked()
        }
    }

    fun isRequestLoggingEnabled(): Boolean = requestLoggingEnabled

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
        }
    }

    fun getRecentLogs(): List<LogEntry> {
        synchronized(lock) {
            if (pruneErrorLogsLocked()) persistErrorLogsLocked()
            return (recentLogs + errorLogs).sortedByDescending(LogEntry::timestamp)
        }
    }

    fun getTextLogs(): List<LogEntry.TextLog> {
        synchronized(lock) {
            return recentLogs.filterIsInstance<LogEntry.TextLog>()
        }
    }

    fun getRequestLogs(): List<LogEntry.RequestLog> {
        synchronized(lock) {
            return recentLogs.filterIsInstance<LogEntry.RequestLog>()
        }
    }

    fun clear() {
        synchronized(lock) {
            recentLogs.clear()
            errorLogs.clear()
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
            destination.parentFile?.mkdirs()
            val temporaryFile = File(destination.parentFile, ".${destination.name}.tmp")
            temporaryFile.writeText(persistenceJson.encodeToString(errorLogs.toList()))
            temporaryFile.copyTo(destination, overwrite = true)
            temporaryFile.delete()
        }
    }

    private fun errorLogCutoff(nowMillis: Long = System.currentTimeMillis()): Long =
        nowMillis - ERROR_LOG_RETENTION_MILLIS
}
