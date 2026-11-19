package me.rerere.common.android

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.FutureTask
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** Ordinary callers never wait for disk. Reads/clear are explicit IO barriers.
 * A bounded byte budget prevents queued HTTP bodies from becoming an unbounded heap cache.
 */
internal class DiagnosticWriter {
    val dropped = AtomicLong()
    val failures = AtomicLong()
    private val bytes = AtomicLong()
    @Volatile private var thread: Thread? = null
    private val executor = ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS, ArrayBlockingQueue(128),
        { task -> Thread(task, "diagnostic-io").apply { isDaemon = true; thread = this } },
        ThreadPoolExecutor.AbortPolicy())

    fun write(weight: Long = 1024, task: () -> Unit) {
        if (bytes.addAndGet(weight) > 8L * 1024 * 1024) { bytes.addAndGet(-weight); dropped.incrementAndGet(); return }
        try {
            executor.execute {
                try { task() } catch (_: Exception) { failures.incrementAndGet() }
                finally { bytes.addAndGet(-weight) }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) { bytes.addAndGet(-weight); dropped.incrementAndGet() }
    }
    fun <T> read(task: () -> T): T {
        if (Thread.currentThread() == thread) return task()
        val future = FutureTask<T> { task() }
        // IO consumer backpressure is allowed; producers above always remain nonblocking.
        try { executor.execute(future) }
        catch (_: java.util.concurrent.RejectedExecutionException) {
            check(executor.queue.offer(future, 15, TimeUnit.SECONDS)) { "Diagnostic writer is busy" }
        }
        return future.get(15, TimeUnit.SECONDS)
    }
}
