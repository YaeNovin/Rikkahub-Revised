package me.rerere.rikkahub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import me.rerere.rikkahub.data.model.VideoGenerationTaskState

@Entity(
    tableName = "video_generation_task",
    indices = [
        Index("status"),
        Index("remote_task_id"),
        Index("updated_at"),
    ],
)
data class VideoGenerationTaskEntity(
    @PrimaryKey
    val id: String,
    @ColumnInfo(name = "provider_id")
    val providerId: String,
    @ColumnInfo(name = "provider_name")
    val providerName: String,
    @ColumnInfo(name = "model_id")
    val modelId: String,
    @ColumnInfo(name = "model_api_id")
    val modelApiId: String,
    @ColumnInfo(name = "prompt")
    val prompt: String,
    @ColumnInfo(name = "request_json")
    val requestJson: String,
    @ColumnInfo(name = "remote_task_id")
    val remoteTaskId: String? = null,
    @ColumnInfo(name = "status")
    val status: VideoGenerationTaskState = VideoGenerationTaskState.CREATED,
    @ColumnInfo(name = "progress_percent")
    val progressPercent: Int? = null,
    @ColumnInfo(name = "error_code")
    val errorCode: String? = null,
    @ColumnInfo(name = "error_message")
    val errorMessage: String? = null,
    @ColumnInfo(name = "retryable")
    val retryable: Boolean = false,
    @ColumnInfo(name = "attempt_count")
    val attemptCount: Int = 0,
    @ColumnInfo(name = "next_poll_at")
    val nextPollAt: Long? = null,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
    @ColumnInfo(name = "completed_at")
    val completedAt: Long? = null,
)
