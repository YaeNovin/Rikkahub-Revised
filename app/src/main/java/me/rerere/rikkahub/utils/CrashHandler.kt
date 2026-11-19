package me.rerere.rikkahub.utils

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import me.rerere.common.android.Logging
import me.rerere.rikkahub.R

private const val TAG = "CrashHandler"
private const val PREFS_NAME = "crash_handler"
private const val KEY_CRASHED = "crashed"
private const val KEY_STACKTRACE = "stacktrace"
private const val MAX_STACKTRACE_LENGTH = 32_000
private const val KEY_CRASH_TIME = "crash_time"
private const val KEY_IMPORTED_CRASH = "imported_crash"
private const val KEY_CLIENT = "client"

object CrashHandler {
    fun install(context: Context) {
        val appContext = context.applicationContext
        // Import on the next launch, not from a potentially compromised crashing thread.
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try { markCrashed(appContext, thread, throwable) }
            catch (_: Throwable) { /* Never suppress Android's original crash handler. */ }
            finally { defaultHandler?.uncaughtException(thread, throwable) }
        }
    }

    fun hasCrashed(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_CRASHED, false)
    }

    fun getStackTrace(context: Context): String? {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_STACKTRACE, null)
    }

    fun clearCrashed(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit { remove(KEY_CRASHED).remove(KEY_STACKTRACE).remove(KEY_CRASH_TIME).remove(KEY_CLIENT) }
    }

    fun importPreviousCrash(context: Context) {
        runCatching {
            val preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val trace = preferences.getString(KEY_STACKTRACE, null) ?: return
            val time = preferences.getLong(KEY_CRASH_TIME, 0L)
            val identity = "$time:${trace.hashCode()}"
            if (preferences.getString(KEY_IMPORTED_CRASH, null) == identity) return
            val client = preferences.getString(KEY_CLIENT, null)?.let { encoded -> runCatching {
                kotlinx.serialization.json.Json { ignoreUnknownKeys = true }.decodeFromString(
                    me.rerere.common.android.DiagnosticClient.serializer(), encoded)
            }.getOrNull() }
            val logId = Logging.logError(
                name = context.getString(R.string.log_page_software_crash),
                summary = context.getString(R.string.log_page_software_crash_desc),
                details = trace,
                tag = TAG,
                timestamp = time.takeIf { it > 0 } ?: System.currentTimeMillis(),
                diagnostic = me.rerere.common.android.DiagnosticMetadata(
                    source = me.rerere.common.android.DiagnosticSource.JAVA_CRASH,
                    severity = me.rerere.common.android.DiagnosticSeverity.FATAL,
                    outcome = me.rerere.common.android.DiagnosticOutcome.FAILED, client = client),
            )
            Logging.flush()
            if(Logging.isPersisted(logId)) preferences.edit { putString(KEY_IMPORTED_CRASH, identity) }
        }
    }

    private fun markCrashed(context: Context, thread: Thread, throwable: Throwable) {
        val stackTrace = buildString {
            appendLine("Thread: ${thread.name}")
            appendLine(throwable.stackTraceToString())
        }.let { me.rerere.common.android.DiagnosticSanitizer.text(it, MAX_STACKTRACE_LENGTH) }
        val client = runCatching { kotlinx.serialization.json.Json.encodeToString(
            me.rerere.common.android.DiagnosticClient.serializer(), diagnosticClientInfo(context)) }.getOrNull()
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit(commit = true) {
                putBoolean(KEY_CRASHED, true)
                putString(KEY_STACKTRACE, stackTrace)
                putLong(KEY_CRASH_TIME, System.currentTimeMillis())
                if (client != null) putString(KEY_CLIENT, client) else remove(KEY_CLIENT)
            } // commit() 同步写入，确保崩溃前写完
    }
}
