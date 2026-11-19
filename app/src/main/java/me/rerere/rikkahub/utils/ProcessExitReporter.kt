package me.rerere.rikkahub.utils

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import androidx.core.content.edit
import me.rerere.common.android.Logging
import me.rerere.common.android.DiagnosticMetadata
import me.rerere.common.android.DiagnosticSource
import me.rerere.common.android.DiagnosticSeverity
import me.rerere.common.android.DiagnosticOutcome
import java.io.File

/** ANRs, native crashes and LMK bypass the Java uncaught-exception handler. */
internal object ProcessExitReporter {
    fun importHistory(context: Context) {
        if (Build.VERSION.SDK_INT < 30) return
        val manager = context.getSystemService(ActivityManager::class.java) ?: return
        val preferences = context.getSharedPreferences("process-exit-history", Context.MODE_PRIVATE)
        val last = preferences.getLong("last_timestamp", 0)
        val exits = manager.getHistoricalProcessExitReasons(context.packageName, 0, 16)
            .filter { it.timestamp > last && it.processName == context.packageName }.sortedBy { it.timestamp }
        for (exit in exits) {
            var logId: kotlin.uuid.Uuid? = null
            val reason = processExitCategory(exit.reason, exit.description)
            if (reason != null) {
                val traceFile = if (exit.reason == ApplicationExitInfo.REASON_ANR ||
                    exit.reason == ApplicationExitInfo.REASON_CRASH_NATIVE) runCatching {
                    exit.traceInputStream?.use { input ->
                        val directory = File(context.filesDir, "logs/process-exits").apply { mkdirs() }
                        val extension = if(exit.reason == ApplicationExitInfo.REASON_CRASH_NATIVE) "bin" else "txt"
                        File(directory, "${exit.timestamp}.$extension").also { file ->
                            // Keep evidence bounded even on ROMs returning a full system dump.
                            file.outputStream().use { output ->
                                val buffer = ByteArray(8192)
                                var remaining = 512 * 1024
                                while (remaining > 0) {
                                    val read = input.read(buffer, 0, minOf(buffer.size, remaining))
                                    if (read < 0) break
                                    output.write(buffer, 0, read)
                                    remaining -= read
                                }
                            }
                            directory.listFiles().orEmpty().sortedByDescending { it.name }.drop(3).forEach { it.delete() }
                        }
                    }
                }.getOrNull() else null
                logId = Logging.logError(name = "系统进程退出：$reason", summary = reason,
                    details = buildString {
                        appendLine("reason=${exit.reason}; status=${exit.status}; importance=${exit.importance}")
                        appendLine("PSS=${exit.pss} KB; RSS=${exit.rss} KB")
                        appendLine(exit.description.orEmpty())
                        if (exit.reason == ApplicationExitInfo.REASON_ANR && traceFile == null)
                            appendLine("系统未向应用提供 ANR 堆栈；可通过 ADB 系统 DropBox 排查。")
                        traceFile?.takeIf { exit.reason == ApplicationExitInfo.REASON_ANR }?.let { file ->
                            appendLine("Trace: logs/process-exits/${file.name}")
                            appendLine(file.bufferedReader().use { it.readText().take(48 * 1024) })
                        }
                    }, tag = "ProcessExit", timestamp = exit.timestamp,
                    diagnostic = DiagnosticMetadata(source = DiagnosticSource.PROCESS_EXIT,
                        severity = if(exit.reason in listOf(ApplicationExitInfo.REASON_ANR, ApplicationExitInfo.REASON_CRASH, ApplicationExitInfo.REASON_CRASH_NATIVE)) DiagnosticSeverity.ERROR else DiagnosticSeverity.WARNING,
                        outcome = if(exit.reason in listOf(ApplicationExitInfo.REASON_ANR, ApplicationExitInfo.REASON_CRASH, ApplicationExitInfo.REASON_CRASH_NATIVE)) DiagnosticOutcome.FAILED else DiagnosticOutcome.INTERRUPTED,
                        attributes = mapOf("reason_code" to exit.reason.toString(), "status" to exit.status.toString(),
                            "importance" to exit.importance.toString(), "pss_kb" to exit.pss.toString(), "rss_kb" to exit.rss.toString(),
                            "trace_available" to (traceFile != null).toString(),
                            "trace_format" to if(exit.reason == ApplicationExitInfo.REASON_CRASH_NATIVE) "native_system_trace" else "text",
                            "trace_path" to traceFile?.let { "logs/process-exits/${it.name}" }.orEmpty())))
            }
            Logging.flush()
            if(logId != null && !Logging.isPersisted(logId)) break
            preferences.edit { putLong("last_timestamp", exit.timestamp) }
        }
    }
}

internal fun processExitCategory(reason: Int, description: String?): String? = when (reason) {
    ApplicationExitInfo.REASON_ANR -> "输入响应超时（ANR）"
    ApplicationExitInfo.REASON_LOW_MEMORY -> "系统内存不足回收"
    ApplicationExitInfo.REASON_CRASH -> "Java 崩溃"
    ApplicationExitInfo.REASON_CRASH_NATIVE -> "原生崩溃"
    ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "系统资源限制"
    ApplicationExitInfo.REASON_SIGNALED -> "系统信号终止（原因未确认）"
    ApplicationExitInfo.REASON_OTHER -> "系统退出（原因未确认）"
    ApplicationExitInfo.REASON_USER_REQUESTED -> if (description?.contains("low memory", true) == true)
        "ROM 内存清理" else null
    else -> null
}
