package me.rerere.rikkahub.data.ai.transformers

import android.content.Context
import android.util.AtomicFile
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.*
import me.rerere.ai.ui.UIMessage
import me.rerere.common.android.Logging
import me.rerere.rikkahub.data.datastore.*
import me.rerere.rikkahub.data.model.*
import java.io.File
import kotlin.math.sqrt
import kotlin.uuid.Uuid

internal data class LorebookVectorMatches(val scores: Map<Uuid, Float> = emptyMap(), val note: String? = null)

internal fun lorebookVectorScore(left: List<Float>, right: List<Float>): Float? {
    if (left.isEmpty() || left.size != right.size || left.any { !it.isFinite() } || right.any { !it.isFinite() }) return null
    var dot = 0.0; var a = 0.0; var b = 0.0
    left.indices.forEach { i -> dot += left[i].toDouble() * right[i]; a += left[i].toDouble() * left[i]; b += right[i].toDouble() * right[i] }
    if (a == 0.0 || b == 0.0) return null
    return (dot / sqrt(a * b)).toFloat().takeIf { it.isFinite() }?.coerceIn(-1f, 1f)
}

/** Isolated from fact/episodic memory. Nothing is sent unless worldbook vectors are opted in.
 * Cached vectors are keyed by provider/model/options + content, so edits/model changes invalidate them.
 */
class LorebookVectorMatcher(private val providerManager: ProviderManager) {
    private val cacheLock = Any()
    internal suspend fun match(context: Context, settings: Settings, entries: List<PromptInjection.RegexInjection>, messages: List<UIMessage>): LorebookVectorMatches = withContext(Dispatchers.IO) {
        val options = settings.lorebookSources
        if (!options.vectorEnabled || entries.isEmpty()) return@withContext LorebookVectorMatches()
        val model = options.embeddingModelId?.let(settings::findModelById)?.takeIf { it.type == ModelType.EMBEDDING }
            ?: return@withContext LorebookVectorMatches(note = "世界书向量模型未配置或已不可用，继续使用关键词")
        val provider = model.findProvider(settings.providers)?.takeIf { it.enabled }
            ?: return@withContext LorebookVectorMatches(note = "世界书向量供应商未启用，继续使用关键词")
        val query = messages.filter { it.role == MessageRole.USER || it.role == MessageRole.ASSISTANT }
            .takeLast(options.vectorQueryMessages.coerceIn(1, 100)).joinToString("\n") { it.toText() }.takeLast(8000)
        if (query.isBlank()) return@withContext LorebookVectorMatches(note = "无可用于向量匹配的近期文本")
        val directory = File(context.filesDir, "lorebook-vector-cache")
        val key = memoryEmbeddingKey(model, provider, "lorebook-v1")
        val serializer = ListSerializer(Float.serializer())
        val scores = linkedMapOf<Uuid, Float>()
        var remaining = 0
        var note: String? = null
        fun cacheFile(text: String, kind: String) = File(directory, "$key-$kind-${memoryContentHash(text)}.json")
        fun read(text: String, kind: String = "document"): List<Float>? = synchronized(cacheLock) { runCatching {
            val file = cacheFile(text, kind)
            if (!file.isFile || file.length() > 256 * 1024) null else Json.decodeFromString(serializer, file.readText()).takeIf { it.isNotEmpty() && it.all(Float::isFinite) }
        }.getOrNull() }
        fun write(text: String, vector: List<Float>, kind: String = "document") = synchronized(cacheLock) {
            check(directory.isDirectory || directory.mkdirs())
            val file = AtomicFile(cacheFile(text, kind))
            val output = file.startWrite()
            try { output.write(Json.encodeToString(serializer, vector).toByteArray()); file.finishWrite(output) }
            catch (e: Exception) { file.failWrite(output); throw e }
        }
        suspend fun embed(text: String, task: EmbeddingTaskType): List<Float> {
            val result = providerManager.getProviderByType(provider).generateEmbedding(providerSetting = provider,
                params = EmbeddingGenerationParams(model = model, input = listOf(text), taskType = task,
                    customHeaders = model.customHeaders, customBody = model.customBodies)).embeddings.single()
            require(result.isNotEmpty() && result.size <= 16384 && result.all(Float::isFinite)) { "向量维数或数值无效" }
            return result
        }
        try {
            val completed = withTimeoutOrNull(20_000) {
                val queryVector = read(query, "query") ?: embed(query, EmbeddingTaskType.RETRIEVAL_QUERY).also { write(query, it, "query") }
                var generated = 0
                for (entry in entries) {
                    currentCoroutineContext().ensureActive()
                    val text = entry.content.take(8000)
                    var vector = read(text)
                    if (vector == null && generated < 32) {
                        vector = embed(text, EmbeddingTaskType.RETRIEVAL_DOCUMENT); write(text, vector); generated++
                    }
                    if (vector == null) remaining++
                    else lorebookVectorScore(queryVector, vector)?.let { scores[entry.id] = it }
                }
                true
            }
            if (completed != true) note = "向量匹配达到 20 秒时限，使用已完成部分和关键词；后续继续补齐缓存"
            else if (remaining > 0) note = "另有 $remaining 项待建索引；每次最多新建 32 项，已缓存项目可继续检索"
            // Only owned cache artifacts are evicted; never remove lorebook source files.
            synchronized(cacheLock) {
                directory.listFiles().orEmpty().filter { it.isFile && it.extension == "json" }
                    .sortedByDescending { it.lastModified() }.drop(1000).forEach { it.delete() }
            }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { Logging.logSoftwareError("LorebookVector", "世界书向量匹配", e); note = "向量匹配失败，继续使用关键词，详情见诊断日志" }
        val minimum = options.vectorMinScore.takeIf(Float::isFinite)?.coerceIn(0f, 1f) ?: .5f
        LorebookVectorMatches(scores.filterValues { it >= minimum }.entries.sortedByDescending { it.value }
            .take(options.vectorMaxEntries.coerceIn(1, 50)).associate { it.toPair() }, note)
    }
}
