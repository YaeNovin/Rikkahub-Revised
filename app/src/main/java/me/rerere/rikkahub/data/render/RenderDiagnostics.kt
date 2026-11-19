package me.rerere.rikkahub.data.render

import me.rerere.common.android.Logging
import me.rerere.rikkahub.data.ai.detailedLogBody
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Bounded background writer. A broken renderer must not flood disk or block WebView's UI thread. */
object RenderDiagnostics {
    private val recent = LinkedHashMap<String, Long>()
    private val worker = ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS, ArrayBlockingQueue(32),
        { task -> Thread(task, "render-diagnostics").apply { isDaemon = true } },
        { _, _ -> dropped.incrementAndGet() }).apply { allowCoreThreadTimeOut(true) }
    private val dropped = java.util.concurrent.atomic.AtomicLong()

    fun report(renderer: String, stage: String, message: String, error: Throwable? = null) {
        if (error is java.util.concurrent.CancellationException) return
        val timestamp = java.time.Instant.now()
        val bounded = message.take(4_096)
        val key = "$renderer/$stage/${bounded.hashCode()}"
        synchronized(recent) {
            val now = System.nanoTime()
            if (recent[key]?.let { now - it < TimeUnit.SECONDS.toNanos(30) } == true) return
            recent[key] = now
            while (recent.size > 128) recent.remove(recent.keys.first())
        }
        worker.execute {
            val details = detailedLogBody(buildString {
                appendLine("renderer=$renderer")
                appendLine("stage=$stage")
                appendLine(bounded)
                error?.let { append(it.stackTraceToString().take(24_000)) }
            })
            Logging.logError(name = "$renderer: $stage", summary = detailedLogBody(bounded).take(512),
                details = details, tag = "RENDER", timestamp = timestamp.toEpochMilli(),
                preciseTimestamp = timestamp.toString(), diagnostic = me.rerere.common.android.DiagnosticMetadata(
                    source = me.rerere.common.android.DiagnosticSource.RENDERER,
                    severity = me.rerere.common.android.DiagnosticSeverity.ERROR,
                    outcome = me.rerere.common.android.DiagnosticOutcome.FAILED,
                    errorType = error?.javaClass?.name,
                    attributes = mapOf("renderer" to renderer, "stage" to stage, "dropped" to dropped.get().toString())))
        }
    }
}
