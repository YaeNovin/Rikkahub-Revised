package me.rerere.rikkahub.data.ai

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.provider.ProviderRequestDiagnostics
import me.rerere.rikkahub.data.db.dao.RequestStatDAO
import me.rerere.rikkahub.data.db.entity.RequestStatEntity
import kotlin.uuid.Uuid

class RequestStatisticsRecorder(
    private val requestStatDAO: RequestStatDAO,
) {
    fun record(
        diagnostics: ProviderRequestDiagnostics?,
        timestamp: Long,
        responseCode: Int?,
        durationMs: Long?,
        totalDurationNanos: Long?,
        completedAt: Long,
    ) {
        diagnostics ?: return
        runCatching {
            requestStatDAO.insertBlocking(
                RequestStatEntity(
                    id = Uuid.random().toString(),
                    requestId = diagnostics.requestId,
                    timestamp = timestamp,
                    provider = diagnostics.provider,
                    model = diagnostics.model,
                    reasoningDepth = diagnostics.parameters.reasoningDepth(),
                    operation = diagnostics.operation.name,
                    statusCode = responseCode,
                    durationMs = durationMs,
                    totalDurationNanos = totalDurationNanos,
                    completedAt = completedAt,
                )
            )
        }
    }

    suspend fun attachUsage(
        requestId: String,
        messageId: String,
        usage: TokenUsage?,
        firstTokenNanos: Long?,
        totalDurationNanos: Long,
        completedAt: Long,
    ) {
        runCatching {
            requestStatDAO.attachUsage(
                requestId = requestId,
                messageId = messageId,
                promptTokens = usage?.promptTokens?.toLong() ?: 0L,
                completionTokens = usage?.completionTokens?.toLong() ?: 0L,
                cachedTokens = usage?.cachedTokens?.toLong() ?: 0L,
                durationMs = totalDurationNanos.toDurationMillis(),
                firstTokenNanos = firstTokenNanos,
                totalDurationNanos = totalDurationNanos,
                completedAt = completedAt,
            )
        }
    }

    suspend fun completeAttempt(
        requestId: String,
        statusCode: Int?,
        firstTokenNanos: Long?,
        totalDurationNanos: Long,
        completedAt: Long,
    ) {
        withContext(NonCancellable) {
            runCatching {
                requestStatDAO.completeAttempt(
                    requestId = requestId,
                    statusCode = statusCode,
                    durationMs = totalDurationNanos.toDurationMillis(),
                    firstTokenNanos = firstTokenNanos,
                    totalDurationNanos = totalDurationNanos,
                    completedAt = completedAt,
                )
            }
        }
    }
}

private fun Long.toDurationMillis(): Long = (this / 1_000_000L).coerceAtLeast(0L)

internal fun Map<String, String>.reasoningDepth(): String = listOf(
    "reasoning_effort",
    "reasoning.effort",
    "output_config.effort",
    "thinkingConfig.thinkingLevel",
    "thinking.level",
    "thinking.budget_tokens",
).firstNotNullOfOrNull { key -> get(key)?.takeIf(String::isNotBlank) }.orEmpty()
