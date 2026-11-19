package me.rerere.rikkahub.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import me.rerere.rikkahub.data.db.entity.ChatImageToolTaskEntity

@Dao
interface ChatImageToolTaskDAO {
    @Query("SELECT * FROM chat_image_tool_task WHERE id = :id")
    suspend fun get(id: String): ChatImageToolTaskEntity?

    @Query("SELECT * FROM chat_image_tool_task WHERE id = :id")
    fun observe(id: String): Flow<ChatImageToolTaskEntity?>

    @Query("SELECT * FROM chat_image_tool_task WHERE conversationId = :conversationId AND sourceMessageId = :sourceMessageId LIMIT 1")
    suspend fun findForTurn(conversationId: String, sourceMessageId: String): ChatImageToolTaskEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(task: ChatImageToolTaskEntity): Long

    @Query("UPDATE chat_image_tool_task SET status = 'approved', requestJson = :request, updatedAt = :now WHERE id = :id AND status = 'pending'")
    suspend fun approve(id: String, request: String, now: Long): Int

    @Query("UPDATE chat_image_tool_task SET status = :status, error = :error, updatedAt = :now WHERE id = :id AND status IN (:expected)")
    suspend fun transition(id: String, expected: List<String>, status: String, error: String?, now: Long): Int

    @Query("UPDATE chat_image_tool_task SET imagesJson = :images, updatedAt = :now WHERE id = :id AND status = 'running'")
    suspend fun saveImages(id: String, images: String, now: Long): Int

    @Query("UPDATE chat_image_tool_task SET status = 'unknown', error = '应用中断，生成结果未知；不会自动重新生成', updatedAt = :now WHERE status = 'running'")
    suspend fun recoverInterrupted(now: Long)

    @Query("UPDATE chat_image_tool_task SET status = 'expired', updatedAt = :now WHERE conversationId = :conversationId AND status = 'pending'")
    suspend fun expirePending(conversationId: String, now: Long)
}
