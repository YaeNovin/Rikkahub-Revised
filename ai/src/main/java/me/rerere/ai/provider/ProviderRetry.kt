package me.rerere.ai.provider

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import me.rerere.ai.ui.StreamChunk
import okhttp3.Response
import java.io.EOFException
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.PortUnreachableException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlin.random.Random

private val RETRYABLE_HTTP_STATUS_CODES = setOf(408, 429, 500, 502, 503, 504)
const val MAX_PROVIDER_RETRIES = 7
private const val DEFAULT_MAX_RETRIES = 3
private const val DEFAULT_INITIAL_RETRY_DELAY_MILLIS = 1_000L
private const val DEFAULT_MAX_RETRY_DELAY_MILLIS = 30_000L
private const val DEFAULT_RETRY_JITTER_RATIO = 0.15
private const val MAX_RETRY_AFTER_JITTER_MILLIS = 1_000L
private const val NANOS_PER_MILLISECOND = 1_000_000L

enum class ProviderFailureKind {
    RATE_LIMIT,
    SERVER_TEMPORARY,
    NETWORK,
    TIMEOUT,
    AUTHENTICATION,
    REQUEST,
    UNKNOWN,
}

class ProviderRequestException(
    val statusCode: Int?,
    val retryAfterMillis: Long?,
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)

fun providerRequestFailure(
    response: Response?,
    cause: Throwable?,
    detail: String?,
    nowMillis: Long = System.currentTimeMillis(),
): Throwable {
    if (response == null && cause != null) return cause

    return ProviderRequestException(
        statusCode = response?.code,
        retryAfterMillis = parseRetryAfterMillis(response?.header("Retry-After"), nowMillis),
        message = detail?.takeIf { it.isNotBlank() }
            ?: cause?.message?.takeIf { it.isNotBlank() }
            ?: response?.let { "Provider request failed: HTTP ${it.code}" }
            ?: "Provider request failed",
        cause = cause,
    )
}

fun parseRetryAfterMillis(value: String?, nowMillis: Long = System.currentTimeMillis()): Long? {
    val normalized = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    normalized.toLongOrNull()?.let { seconds ->
        return seconds.coerceAtLeast(0).let { safeSeconds ->
            if (safeSeconds > Long.MAX_VALUE / 1_000L) Long.MAX_VALUE else safeSeconds * 1_000L
        }
    }

    return runCatching {
        val retryAt = ZonedDateTime.parse(normalized, DateTimeFormatter.RFC_1123_DATE_TIME)
            .toInstant()
            .toEpochMilli()
        (retryAt - nowMillis).coerceAtLeast(0L)
    }.getOrNull()
}

fun Throwable.isRetryableProviderFailure(): Boolean {
    return when (providerFailureKind()) {
        ProviderFailureKind.RATE_LIMIT,
        ProviderFailureKind.SERVER_TEMPORARY,
        ProviderFailureKind.NETWORK,
        ProviderFailureKind.TIMEOUT -> true

        ProviderFailureKind.AUTHENTICATION,
        ProviderFailureKind.REQUEST,
        ProviderFailureKind.UNKNOWN -> false
    }
}

fun Throwable.providerFailureKind(): ProviderFailureKind {
    val chain = generateSequence(this) { it.cause }.take(16).toList()

    val statusCodes = chain.filterIsInstance<ProviderRequestException>()
        .mapNotNull(ProviderRequestException::statusCode)
    val messages = chain.mapNotNull { it.message?.takeIf(String::isNotBlank) }
    if (statusCodes.any { it == 401 || it == 403 }) {
        return ProviderFailureKind.AUTHENTICATION
    }
    if (messages.any(String::containsNonRetryableQuotaMessage)) {
        return ProviderFailureKind.REQUEST
    }
    if (statusCodes.any { it == 429 }) return ProviderFailureKind.RATE_LIMIT
    if (statusCodes.any { it == 408 }) return ProviderFailureKind.TIMEOUT
    if (statusCodes.any { it in RETRYABLE_HTTP_STATUS_CODES }) {
        return ProviderFailureKind.SERVER_TEMPORARY
    }
    if (statusCodes.any { it in SEMANTIC_RETRY_BLOCKING_STATUS_CODES }) {
        return ProviderFailureKind.REQUEST
    }

    if (messages.any(String::containsRateLimitMessage)) {
        return ProviderFailureKind.RATE_LIMIT
    }
    if (messages.any(String::containsTemporaryServerMessage)) {
        return ProviderFailureKind.SERVER_TEMPORARY
    }

    if (chain.any { it is SocketTimeoutException || it is InterruptedIOException } ||
        messages.any(String::containsTimeoutMessage)
    ) {
        return ProviderFailureKind.TIMEOUT
    }

    if (chain.any { error ->
            when (error) {
                is ConnectException,
                is NoRouteToHostException,
                is PortUnreachableException,
                is UnknownHostException,
                is SocketException,
                is EOFException -> true

                is IOException -> error.message.orEmpty().containsRetryableNetworkMessage()
                else -> false
            }
        } || messages.any(String::containsRetryableNetworkMessage)
    ) {
        return ProviderFailureKind.NETWORK
    }

    if (statusCodes.any { it !in 200..299 }) return ProviderFailureKind.REQUEST
    return ProviderFailureKind.UNKNOWN
}

private fun String.containsRetryableNetworkMessage(): Boolean {
    val normalized = lowercase()
    return listOf(
        "econnreset",
        "connection reset",
        "connection abort",
        "connection refused",
        "connection shutdown",
        "broken pipe",
        "unexpected end of stream",
        "stream failed",
        "stream closed",
        "stream reset",
        "stream was reset",
        "http/2 stream",
        "socket closed",
        "connection closed",
        "connection lost",
        "network is unreachable",
        "network unreachable",
        "network changed",
        "software caused connection abort",
        "timeout",
        "timed out",
        "\u8fde\u63a5\u4e2d\u65ad",
        "\u8fde\u63a5\u5df2\u4e2d\u65ad",
        "\u8fde\u63a5\u88ab\u91cd\u7f6e",
        "\u8fde\u63a5\u5df2\u91cd\u7f6e",
        "\u8fde\u63a5\u88ab\u4e2d\u6b62",
        "\u7f51\u7edc\u4e0d\u53ef\u8fbe",
        "\u7f51\u7edc\u5df2\u5207\u6362",
        "\u7f51\u7edc\u53d1\u751f\u53d8\u5316",
        "\u6d41\u5df2\u91cd\u7f6e",
        "\u6d41\u8fde\u63a5\u5931\u8d25",
        "\u6570\u636e\u6d41\u5931\u8d25",
    ).any(normalized::contains)
}

private fun String.containsRateLimitMessage(): Boolean {
    val normalized = lowercase()
    if (HTTP_429_PATTERN.containsMatchIn(normalized)) return true
    return listOf(
        "too many requests",
        "rate limit",
        "rate-limit",
        "ratelimit",
        "throttl",
        "resource exhausted",
        "\u8bf7\u6c42\u8fc7\u4e8e\u9891\u7e41",
        "\u8bf7\u6c42\u592a\u9891\u7e41",
        "\u8bbf\u95ee\u9891\u7387\u8fc7\u9ad8",
        "\u9891\u7387\u9650\u5236",
        "\u9891\u7387\u8d85\u9650",
        "\u89e6\u53d1\u9650\u6d41",
        "\u5df2\u88ab\u9650\u6d41",
        "\u5e76\u53d1\u8d85\u9650",
        "\u8d44\u6e90\u8017\u5c3d",
    ).any(normalized::contains)
}

/** 429 is also used for account limits that cannot recover by waiting. */
private fun String.containsNonRetryableQuotaMessage(): Boolean {
    val normalized = lowercase()
    return listOf(
        "credit_balance_exhausted",
        "organization_spend_limit_exceeded",
        "project_spend_limit_exceeded",
        "organization_usage_limit_exceeded",
        "insufficient_quota",
        "credit balance exhausted",
        "insufficient credit",
        "billing limit",
        "spend limit",
        "payment required",
        "\u4f59\u989d\u4e0d\u8db3",
        "\u4f59\u989d\u5df2\u7528\u5c3d",
        "\u4f59\u989d\u5df2\u8017\u5c3d",
        "\u8d26\u6237\u6b20\u8d39",
        "\u8bf7\u5148\u5145\u503c",
        "\u8bf7\u5145\u503c",
        "\u6d88\u8d39\u4e0a\u9650",
        "\u652f\u51fa\u4e0a\u9650",
        "\u9884\u7b97\u4e0a\u9650",
        "\u989d\u5ea6\u5df2\u7528\u5c3d",
        "\u914d\u989d\u5df2\u8017\u5c3d",
    ).any(normalized::contains)
}

private fun String.containsTemporaryServerMessage(): Boolean {
    val normalized = lowercase()
    return listOf(
        "service unavailable",
        "temporarily unavailable",
        "server busy",
        "server overloaded",
        "overloaded",
        "try again later",
        "upstream error",
        "bad gateway",
        "gateway timeout",
        "internal server error",
        "\u670d\u52a1\u6682\u65f6\u4e0d\u53ef\u7528",
        "\u670d\u52a1\u4e0d\u53ef\u7528",
        "\u670d\u52a1\u5668\u7e41\u5fd9",
        "\u670d\u52a1\u7e41\u5fd9",
        "\u7cfb\u7edf\u7e41\u5fd9",
        "\u7a0d\u540e\u91cd\u8bd5",
        "\u7a0d\u540e\u518d\u8bd5",
        "\u4e0a\u6e38\u670d\u52a1\u5f02\u5e38",
        "\u7f51\u5173\u8d85\u65f6",
        "\u5185\u90e8\u670d\u52a1\u5668\u9519\u8bef",
    ).any(normalized::contains)
}

private fun String.containsTimeoutMessage(): Boolean {
    val normalized = lowercase()
    return listOf(
        "timed out",
        "timeout",
        "\u8bf7\u6c42\u8d85\u65f6",
        "\u8fde\u63a5\u8d85\u65f6",
        "\u8bfb\u53d6\u8d85\u65f6",
        "\u54cd\u5e94\u8d85\u65f6",
    ).any(normalized::contains)
}

private val HTTP_429_PATTERN = Regex(
    "(?:^|[^0-9])(?:http(?:\\s+status)?|status(?:\\s+code)?|code|\u72b6\u6001\u7801|\u9519\u8bef\u7801)?\\s*[:=\uff1a]?\\s*429(?:[^0-9]|$)"
)
private val SEMANTIC_RETRY_BLOCKING_STATUS_CODES = setOf(401, 403, 404, 405, 410, 413, 415, 422)

fun Throwable.providerRetryAfterMillis(): Long? =
    generateSequence(this) { it.cause }
        .filterIsInstance<ProviderRequestException>()
        .mapNotNull(ProviderRequestException::retryAfterMillis)
        .firstOrNull()

/** A shared retry-count and reconnect-wait budget for one provider request. */
class ProviderRetryController(
    val maxRetries: Int = DEFAULT_MAX_RETRIES,
    val initialDelayMillis: Long = DEFAULT_INITIAL_RETRY_DELAY_MILLIS,
    val maxDelayMillis: Long = DEFAULT_MAX_RETRY_DELAY_MILLIS,
    val maxDurationMillis: Long = Long.MAX_VALUE,
    val jitterRatio: Double = DEFAULT_RETRY_JITTER_RATIO,
    private val randomDouble: () -> Double = Random.Default::nextDouble,
    private val nanoTime: () -> Long = System::nanoTime,
) {
    private var waitedDurationMillis: Long = 0L

    var retryCount: Int = 0
        private set

    init {
        require(maxRetries in 0..MAX_PROVIDER_RETRIES) {
            "maxRetries must be between 0 and $MAX_PROVIDER_RETRIES"
        }
        require(initialDelayMillis >= 0L) { "initialDelayMillis must not be negative" }
        require(maxDelayMillis >= 0L) { "maxDelayMillis must not be negative" }
        require(maxDurationMillis >= 0L) { "maxDurationMillis must not be negative" }
        require(jitterRatio in 0.0..1.0) { "jitterRatio must be between 0 and 1" }
    }

    suspend fun waitBeforeRetry(
        error: Throwable,
        onRetry: suspend (retryNumber: Int, delayMillis: Long) -> Unit = { _, _ -> },
        delayBeforeRetry: suspend (delayMillis: Long) -> Unit = { delay(it) },
    ): Boolean {
        if (retryCount >= maxRetries) return false

        val retryDelay = error.providerRetryAfterMillis()
            ?.coerceAtLeast(0L)
            ?.let(::retryAfterDelayMillis)
            ?: jitteredExponentialDelayMillis(retryCount)
        val remainingMillis = remainingDurationMillis()
        if (retryDelay > remainingMillis) return false

        val retryNumber = retryCount + 1
        retryCount = retryNumber
        onRetry(retryNumber, retryDelay)
        val waitStartedAtNanos = nanoTime()
        try {
            delayBeforeRetry(retryDelay)
        } finally {
            recordWaitDuration(waitStartedAtNanos)
        }
        return remainingDurationMillis() > 0L
    }

    fun remainingDurationMillis(): Long =
        (maxDurationMillis - waitedDurationMillis).coerceAtLeast(0L)

    private fun recordWaitDuration(startedAtNanos: Long) {
        val elapsedMillis = ((nanoTime() - startedAtNanos).coerceAtLeast(0L) /
            NANOS_PER_MILLISECOND)
        waitedDurationMillis = if (elapsedMillis > Long.MAX_VALUE - waitedDurationMillis) {
            Long.MAX_VALUE
        } else {
            waitedDurationMillis + elapsedMillis
        }
    }

    private fun jitteredExponentialDelayMillis(retryIndex: Int): Long {
        val multiplier = 1L shl retryIndex
        val exponentialDelay = if (initialDelayMillis > Long.MAX_VALUE / multiplier) {
            Long.MAX_VALUE
        } else {
            initialDelayMillis * multiplier
        }
        if (exponentialDelay == 0L || maxDelayMillis == 0L) return 0L

        val random = randomDouble().coerceIn(0.0, 1.0)
        val jitterMultiplier = 1.0 + ((random * 2.0) - 1.0) * jitterRatio
        return (exponentialDelay.toDouble() * jitterMultiplier)
            .coerceAtMost(maxDelayMillis.toDouble())
            .toLong()
    }

    /** Retry-After is a minimum, so its jitter must never shorten the requested wait. */
    private fun retryAfterDelayMillis(minimumDelayMillis: Long): Long {
        if (jitterRatio == 0.0) return minimumDelayMillis
        val jitterLimit = (minimumDelayMillis.toDouble() * jitterRatio)
            .coerceAtLeast(1.0)
            .coerceAtMost(MAX_RETRY_AFTER_JITTER_MILLIS.toDouble())
            .toLong()
        val jitter = (randomDouble().coerceIn(0.0, 1.0) * jitterLimit).toLong()
        return if (minimumDelayMillis > Long.MAX_VALUE - jitter) {
            Long.MAX_VALUE
        } else {
            minimumDelayMillis + jitter
        }
    }
}

suspend fun <T> retryProviderRequest(
    enabled: Boolean,
    maxRetries: Int = DEFAULT_MAX_RETRIES,
    initialDelayMillis: Long = DEFAULT_INITIAL_RETRY_DELAY_MILLIS,
    maxDelayMillis: Long = DEFAULT_MAX_RETRY_DELAY_MILLIS,
    maxDurationMillis: Long = Long.MAX_VALUE,
    jitterRatio: Double = DEFAULT_RETRY_JITTER_RATIO,
    retryController: ProviderRetryController? = null,
    canRetry: () -> Boolean = { true },
    shouldRetry: suspend (Throwable) -> Boolean = { it.isRetryableProviderFailure() },
    onRetry: suspend (retryNumber: Int, delayMillis: Long) -> Unit = { _, _ -> },
    delayBeforeRetry: suspend (delayMillis: Long) -> Unit = { delay(it) },
    block: suspend (attempt: Int) -> T,
): T {
    val controller = retryController ?: ProviderRetryController(
        maxRetries = maxRetries,
        initialDelayMillis = initialDelayMillis,
        maxDelayMillis = maxDelayMillis,
        maxDurationMillis = maxDurationMillis,
        jitterRatio = jitterRatio,
    )
    var attempt = 0
    while (true) {
        try {
            return block(attempt)
        } catch (error: Throwable) {
            if (error is CancellationException) currentCoroutineContext().ensureActive()
            if (!enabled || !canRetry() || !shouldRetry(error)) {
                throw error
            }
            if (!controller.waitBeforeRetry(error, onRetry, delayBeforeRetry)) throw error
            attempt++
        }
    }
}

fun StreamChunk.crossesRequestReplayBoundary(
    reasoningIsReplaySafe: Boolean = false,
): Boolean = when (this) {
    is StreamChunk.TextDelta -> text.isNotEmpty()
    is StreamChunk.ReasoningDelta -> text.isNotEmpty() && !reasoningIsReplaySafe
    is StreamChunk.ToolCallStart,
    is StreamChunk.ToolCallDelta,
    is StreamChunk.ToolCallEnd,
    is StreamChunk.ServerToolStart,
    is StreamChunk.ServerToolInputDelta,
    is StreamChunk.ServerToolInputEnd,
    is StreamChunk.ServerToolEnd,
    is StreamChunk.ImageStart,
    is StreamChunk.ImageDelta,
    is StreamChunk.ImageSnapshot,
    is StreamChunk.ImageEnd -> true

    is StreamChunk.Annotations -> annotations.isNotEmpty()
    is StreamChunk.Usage -> false
    is StreamChunk.TextStart,
    is StreamChunk.TextEnd,
    is StreamChunk.ReasoningStart,
    is StreamChunk.ReasoningEnd,
    is StreamChunk.Finish -> false
}
