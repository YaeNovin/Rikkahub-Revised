package me.rerere.rikkahub.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RawQuery
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteQuery
import me.rerere.rikkahub.data.db.entity.RequestStatEntity

@Dao
interface RequestStatDAO {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertBlocking(entry: RequestStatEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(entries: List<RequestStatEntity>)

    @Query(
        "UPDATE request_statistics SET message_id = :messageId, " +
            "prompt_tokens = :promptTokens, completion_tokens = :completionTokens, " +
            "cached_tokens = :cachedTokens, message_count = 1, duration_ms = :durationMs, " +
            "first_token_nanos = :firstTokenNanos, total_duration_nanos = :totalDurationNanos, " +
            "completed_at = :completedAt " +
            "WHERE id = (SELECT id FROM request_statistics " +
            "WHERE request_id = :requestId AND status_code BETWEEN 200 AND 299 " +
            "ORDER BY timestamp DESC LIMIT 1)"
    )
    suspend fun attachUsage(
        requestId: String,
        messageId: String,
        promptTokens: Long,
        completionTokens: Long,
        cachedTokens: Long,
        durationMs: Long,
        firstTokenNanos: Long?,
        totalDurationNanos: Long,
        completedAt: Long,
    ): Int

    @Query(
        "UPDATE request_statistics SET status_code = COALESCE(:statusCode, status_code), " +
            "duration_ms = :durationMs, " +
            "first_token_nanos = :firstTokenNanos, total_duration_nanos = :totalDurationNanos, " +
            "completed_at = :completedAt " +
            "WHERE id = (SELECT id FROM request_statistics " +
            "WHERE request_id = :requestId ORDER BY timestamp DESC LIMIT 1)"
    )
    suspend fun completeAttempt(
        requestId: String,
        statusCode: Int?,
        durationMs: Long,
        firstTokenNanos: Long?,
        totalDurationNanos: Long,
        completedAt: Long,
    ): Int

    @Query(
        "SELECT * FROM request_statistics " +
            "WHERE timestamp >= :startMillis AND timestamp < :endMillis " +
            "AND (:allProviders OR provider IN (:providers)) " +
            "ORDER BY timestamp DESC"
    )
    suspend fun getRequests(
        startMillis: Long,
        endMillis: Long,
        allProviders: Boolean,
        providers: List<String>,
    ): List<RequestStatEntity>

    @Query("SELECT DISTINCT provider FROM request_statistics WHERE provider != '' ORDER BY provider COLLATE NOCASE")
    suspend fun getRecordedProviders(): List<String>

    @Query("SELECT COUNT(*) FROM request_statistics")
    suspend fun countAll(): Int

    @Query("UPDATE request_statistics SET status_code = NULL WHERE operation = 'HISTORICAL_TEXT'")
    suspend fun clearInferredHistoricalStatusCodes(): Int

    @RawQuery
    suspend fun getUntrackedHistoricalRequestsRaw(
        query: SupportSQLiteQuery,
    ): List<HistoricalRequestStat>
}

data class HistoricalRequestStat(
    val messageId: String,
    val createdAt: String,
    val finishedAt: String?,
    val modelId: String,
    val promptTokens: Long,
    val completionTokens: Long,
    val cachedTokens: Long,
)

private val UNTRACKED_HISTORICAL_REQUESTS = SimpleSQLiteQuery(
    "SELECT json_extract(j.value, '$.id') AS messageId, " +
        "json_extract(j.value, '$.createdAt') AS createdAt, " +
        "json_extract(j.value, '$.finishedAt') AS finishedAt, " +
        "json_extract(j.value, '$.modelId') AS modelId, " +
        "COALESCE(CAST(json_extract(j.value, '$.usage.promptTokens') AS INTEGER), 0) AS promptTokens, " +
        "COALESCE(CAST(json_extract(j.value, '$.usage.completionTokens') AS INTEGER), 0) AS completionTokens, " +
        "COALESCE(CAST(json_extract(j.value, '$.usage.cachedTokens') AS INTEGER), 0) AS cachedTokens " +
        "FROM message_node mn, json_each(mn.messages) j " +
        "WHERE json_extract(j.value, '$.role') = 'assistant' " +
        "AND json_type(j.value, '$.id') = 'text' " +
        "AND json_type(j.value, '$.modelId') = 'text' " +
        "AND json_type(j.value, '$.finishedAt') = 'text' " +
        "AND NOT EXISTS (SELECT 1 FROM request_statistics rs " +
        "WHERE rs.message_id = json_extract(j.value, '$.id'))"
)

suspend fun RequestStatDAO.getUntrackedHistoricalRequests(): List<HistoricalRequestStat> =
    getUntrackedHistoricalRequestsRaw(UNTRACKED_HISTORICAL_REQUESTS)
