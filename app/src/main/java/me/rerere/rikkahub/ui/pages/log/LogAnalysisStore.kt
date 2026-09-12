package me.rerere.rikkahub.ui.pages.log

import android.content.Context
import androidx.core.util.AtomicFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
    suspend fun save(record: SavedLogAnalysis) = withContext(Dispatchers.IO) {
        check(directory.exists() || directory.mkdirs())
        val atomic = AtomicFile(File(directory, "${Uuid.parse(record.id)}.json"))
        val output = atomic.startWrite()
        try {
            output.write(JsonInstant.encodeToString(record).toByteArray(Charsets.UTF_8))
            atomic.finishWrite(output)
        } catch (e: Throwable) { atomic.failWrite(output); throw e }
    }
    suspend fun list(): List<SavedLogAnalysis> = withContext(Dispatchers.IO) {
        directory.listFiles().orEmpty().filter { it.extension == "json" }.mapNotNull { file ->
            runCatching { JsonInstant.decodeFromString<SavedLogAnalysis>(AtomicFile(file).readFully().toString(Charsets.UTF_8)) }.getOrNull()
        }.sortedByDescending { it.createdAt }
    }
}
