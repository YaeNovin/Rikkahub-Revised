package me.rerere.rikkahub.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import me.rerere.rikkahub.data.db.entity.VideoGenerationOutputEntity
import me.rerere.rikkahub.data.db.entity.VideoGenerationTaskEntity
import me.rerere.rikkahub.data.model.VideoGenerationTaskState

@Dao
interface VideoGenerationTaskDAO {
    @Query("SELECT * FROM video_generation_task ORDER BY created_at DESC")
    fun observeAll(): Flow<List<VideoGenerationTaskEntity>>

    @Query("SELECT * FROM video_generation_task WHERE id = :id")
    suspend fun getTask(id: String): VideoGenerationTaskEntity?

    @Query("SELECT * FROM video_generation_task WHERE status IN (:states) ORDER BY updated_at")
    suspend fun getTasksInStates(states: Set<VideoGenerationTaskState>): List<VideoGenerationTaskEntity>

    @Query("SELECT * FROM video_generation_output WHERE task_id = :taskId ORDER BY output_index")
    fun observeOutputs(taskId: String): Flow<List<VideoGenerationOutputEntity>>

    @Query("SELECT * FROM video_generation_output WHERE task_id = :taskId ORDER BY output_index")
    suspend fun getOutputs(taskId: String): List<VideoGenerationOutputEntity>

    @Upsert
    suspend fun upsertTask(task: VideoGenerationTaskEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertOutputs(outputs: List<VideoGenerationOutputEntity>)

    @Upsert
    suspend fun upsertOutput(output: VideoGenerationOutputEntity)

    @Query(
        """
        UPDATE video_generation_output
        SET local_relative_path = :localRelativePath,
            size_bytes = :sizeBytes,
            mime_type = :mimeType,
            display_name = :displayName
        WHERE task_id = :taskId AND output_index = :outputIndex
        """,
    )
    suspend fun updateDownloadedOutput(
        taskId: String,
        outputIndex: Int,
        localRelativePath: String,
        sizeBytes: Long,
        mimeType: String,
        displayName: String,
    ): Int

    @Query("DELETE FROM video_generation_output WHERE task_id = :taskId")
    suspend fun deleteOutputs(taskId: String)

    @Transaction
    suspend fun replaceOutputs(taskId: String, outputs: List<VideoGenerationOutputEntity>) {
        deleteOutputs(taskId)
        if (outputs.isNotEmpty()) upsertOutputs(outputs)
    }

    @Query(
        """
        UPDATE video_generation_task
        SET status = :target,
            progress_percent = :progressPercent,
            error_code = :errorCode,
            error_message = :errorMessage,
            retryable = :retryable,
            next_poll_at = :nextPollAt,
            updated_at = :updatedAt,
            completed_at = :completedAt,
            remote_task_id = CASE WHEN :clearRemoteTaskId THEN NULL ELSE remote_task_id END,
            attempt_count = attempt_count + :attemptDelta
        WHERE id = :id AND status = :expected
        """,
    )
    suspend fun compareAndSetState(
        id: String,
        expected: VideoGenerationTaskState,
        target: VideoGenerationTaskState,
        progressPercent: Int?,
        errorCode: String?,
        errorMessage: String?,
        retryable: Boolean,
        nextPollAt: Long?,
        updatedAt: Long,
        completedAt: Long?,
        clearRemoteTaskId: Boolean,
        attemptDelta: Int,
    ): Int

    @Query(
        """
        UPDATE video_generation_task
        SET remote_task_id = :remoteTaskId,
            updated_at = :updatedAt
        WHERE id = :id
          AND status = :expected
          AND (remote_task_id IS NULL OR remote_task_id = :remoteTaskId)
        """,
    )
    suspend fun compareAndSetRemoteTaskId(
        id: String,
        expected: VideoGenerationTaskState,
        remoteTaskId: String,
        updatedAt: Long,
    ): Int

    @Query("DELETE FROM video_generation_task WHERE id = :id")
    suspend fun deleteTask(id: String): Int
}
