package me.rerere.rikkahub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "request_statistics",
    indices = [
        Index("timestamp"),
        Index("provider"),
        Index("request_id"),
        Index(value = ["message_id"], unique = true),
    ],
)
data class RequestStatEntity(
    @PrimaryKey
    val id: String,
    @ColumnInfo("request_id")
    val requestId: String? = null,
    @ColumnInfo("message_id")
    val messageId: String? = null,
    val timestamp: Long,
    val provider: String,
    val model: String,
    @ColumnInfo("reasoning_depth")
    val reasoningDepth: String = "",
    val operation: String,
    @ColumnInfo("prompt_tokens")
    val promptTokens: Long = 0,
    @ColumnInfo("completion_tokens")
    val completionTokens: Long = 0,
    @ColumnInfo("cached_tokens")
    val cachedTokens: Long = 0,
    @ColumnInfo("message_count")
    val messageCount: Int = 0,
    @ColumnInfo("status_code")
    val statusCode: Int? = null,
    @ColumnInfo("duration_ms")
    val durationMs: Long? = null,
    @ColumnInfo("first_token_nanos")
    val firstTokenNanos: Long? = null,
    @ColumnInfo("total_duration_nanos")
    val totalDurationNanos: Long? = null,
    @ColumnInfo("completed_at")
    val completedAt: Long? = null,
) {
    val totalTokens: Long
        get() = promptTokens + completionTokens

    val effectiveTotalDurationNanos: Long?
        get() = totalDurationNanos ?: durationMs?.let { it * NANOS_PER_MILLISECOND }
}

private const val NANOS_PER_MILLISECOND = 1_000_000L
