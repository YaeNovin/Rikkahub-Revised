package me.rerere.rikkahub.ui.pages.log

import android.content.Context
import androidx.core.util.AtomicFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.rerere.common.android.DiagnosticSanitizer
import kotlinx.serialization.Serializable
import me.rerere.rikkahub.utils.JsonInstant
import java.io.File
import kotlin.uuid.Uuid

@Serializable
data class SavedLogAnalysis(
    val id: String = Uuid.random().toString(),
    val createdAt: Long = System.currentTimeMillis(),
    val payload: String,
    val model: String,
    val provider: String,
    val result: String,
    val status: String = "completed",
)

class LogAnalysisStore(context: Context) {
    private val directory = File(context.filesDir, "log-analyses")
    private val mutex = Mutex()
    private var recovered = false
    private fun files() = directory.listFiles().orEmpty().filter { it.extension == "json" && runCatching { Uuid.parse(it.nameWithoutExtension) }.isSuccess }
    private fun decode(file: File): SavedLogAnalysis? = if(file.length() > 128*1024) null else runCatching {
        JsonInstant.decodeFromString<SavedLogAnalysis>(AtomicFile(file).readFully().toString(Charsets.UTF_8))
    }.getOrNull()
    private fun write(record: SavedLogAnalysis) {
        check(directory.exists() || directory.mkdirs())
        val atomic = AtomicFile(File(directory, "${Uuid.parse(record.id)}.json"))
        val output = atomic.startWrite()
        try {
            val safe = record.copy(payload = DiagnosticSanitizer.body(record.payload, limit = 24000),
                result = DiagnosticSanitizer.body(record.result, limit = 32000))
            output.write(JsonInstant.encodeToString(safe).toByteArray(Charsets.UTF_8))
            atomic.finishWrite(output)
        } catch (e: Throwable) { atomic.failWrite(output); throw e }
    }
    private fun recover() {
        if(recovered) return
        recovered = true
        files().mapNotNull(::decode).filter { it.status == "running" }.forEach {
            write(it.copy(status = "interrupted", result = "应用进程中断，分析未完成"))
        }
    }
    private fun prune() {
        var bytes = 0L
        files().sortedByDescending { it.lastModified() }.forEachIndexed { index, file ->
            bytes += file.length()
            if(index >= 50 || bytes > 4*1024*1024 || file.lastModified() < System.currentTimeMillis()-14L*86400000) file.delete()
        }
    }
    suspend fun save(record: SavedLogAnalysis) = withContext(Dispatchers.IO) { mutex.withLock { recover(); write(record); prune() } }
    suspend fun list(): List<SavedLogAnalysis> = withContext(Dispatchers.IO) { mutex.withLock {
        recover(); prune(); files().mapNotNull(::decode).sortedByDescending { it.createdAt }
    } }
    suspend fun clear() = withContext(Dispatchers.IO) { mutex.withLock {
        directory.listFiles().orEmpty().filter { file ->
            runCatching { Uuid.parse(file.name.removeSuffix(".bak").removeSuffix(".new").removeSuffix(".json")) }.isSuccess
        }.forEach { check(it.delete() || !it.exists()) }
    } }
}
