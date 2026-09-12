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
private const val MAX_STACKTRACE_LENGTH = 8000
private const val KEY_CRASH_TIME = "crash_time"
private const val KEY_IMPORTED_CRASH = "imported_crash"

object CrashHandler {
    fun install(context: Context) {
        val appContext = context.applicationContext
        // Import on the next launch, not from a potentially compromised crashing thread.
        importPreviousCrash(appContext)
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e(TAG, "Uncaught exception on thread ${thread.name}", throwable)
            markCrashed(appContext, thread, throwable)
            defaultHandler?.uncaughtException(thread, throwable)
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
            .edit { remove(KEY_CRASHED).remove(KEY_STACKTRACE).remove(KEY_CRASH_TIME) }
    }

    private fun importPreviousCrash(context: Context) {
        runCatching {
            val preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val trace = preferences.getString(KEY_STACKTRACE, null) ?: return
            val time = preferences.getLong(KEY_CRASH_TIME, 0L)
            val identity = "$time:${trace.hashCode()}"
            if (preferences.getString(KEY_IMPORTED_CRASH, null) == identity) return
            Logging.logError(
                name = context.getString(R.string.log_page_software_crash),
                summary = context.getString(R.string.log_page_software_crash_desc),
                details = trace,
                tag = TAG,
                timestamp = time.takeIf { it > 0 } ?: System.currentTimeMillis(),
            )
            preferences.edit { putString(KEY_IMPORTED_CRASH, identity) }
        }
    }

    private fun markCrashed(context: Context, thread: Thread, throwable: Throwable) {
        val stackTrace = buildString {
            appendLine("Thread: ${thread.name}")
            appendLine(throwable.stackTraceToString())
        }.take(MAX_STACKTRACE_LENGTH)
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit(commit = true) {
                putBoolean(KEY_CRASHED, true)
                putString(KEY_STACKTRACE, stackTrace)
                putLong(KEY_CRASH_TIME, System.currentTimeMillis())
            } // commit() 同步写入，确保崩溃前写完
    }
}
