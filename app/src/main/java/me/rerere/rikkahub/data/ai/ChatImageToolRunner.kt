package me.rerere.rikkahub.data.ai

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.db.dao.ChatImageToolTaskDAO
import me.rerere.rikkahub.data.db.entity.ChatImageToolTaskEntity
import me.rerere.rikkahub.utils.JsonInstant
import java.util.concurrent.ConcurrentHashMap

/** The persisted claim is made before the paid request. Completed or uncertain work is never replayed. */
internal class ChatImageToolRunner(private val dao: ChatImageToolTaskDAO) {
    private val locks = ConcurrentHashMap<String, Mutex>()

    suspend fun run(
        id: String,
        generate: suspend (saveImage: suspend (String) -> Unit) -> Unit,
    ): ChatImageToolTaskEntity = locks.getOrPut(id) { Mutex() }.withLock {
        val initial = requireNotNull(dao.get(id)) { "原对话或生图任务已删除" }
        if (initial.status != "approved") return@withLock initial
        if (dao.transition(id, listOf("approved"), "running", null, System.currentTimeMillis()) == 0) {
            return@withLock requireNotNull(dao.get(id))
        }
        val images = JsonInstant.decodeFromString<List<String>>(initial.imagesJson).toMutableList()
        try {
            generate { url ->
                withContext(NonCancellable) {
                    images += url
                    check(dao.saveImages(id, JsonInstant.encodeToString(images), System.currentTimeMillis()) == 1) {
                        "原对话或生图任务已删除"
                    }
                }
            }
            check(images.isNotEmpty()) { "供应商未返回最终图片" }
            dao.transition(id, listOf("running"), "succeeded", null, System.currentTimeMillis())
        } catch (error: Exception) {
            withContext(NonCancellable) {
                dao.transition(id, listOf("running"), if (images.isEmpty()) "unknown" else "partial",
                    if (error is CancellationException) "已停止等待；供应商可能仍在生成，不会自动重新提交"
                    else (error.message ?: "生成失败，供应商处理结果未知").take(500), System.currentTimeMillis())
            }
            if (error is CancellationException) throw error
        }
        requireNotNull(dao.get(id)) { "原对话或生图任务已删除" }
    }
}
