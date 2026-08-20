package me.rerere.ai.provider

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
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

private val RETRYABLE_HTTP_STATUS_CODES = setOf(408, 429, 500, 502, 503, 504)
const val MAX_PROVIDER_RETRIES = 7
private const val DEFAULT_MAX_RETRIES = 3
private const val DEFAULT_INITIAL_RETRY_DELAY_MILLIS = 1_000L
private const val NANOS_PER_MILLISECOND = 1_000_000L

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
    val chain = generateSequence(this) { it.cause }.take(16).toList()
    if (chain.any { it is CancellationException }) return false

    chain.filterIsInstance<ProviderRequestException>().forEach { error ->
        val statusCode = error.statusCode ?: return@forEach
        if (statusCode in RETRYABLE_HTTP_STATUS_CODES) return true
        if (statusCode !in 200..299) return false
    }

    return chain.any { error ->
        when (error) {
            is SocketTimeoutException,
            is ConnectException,
            is NoRouteToHostException,
            is PortUnreachableException,
            is UnknownHostException,
            is SocketException,
            is EOFException,
            is InterruptedIOException -> true

            is IOException -> error.message.orEmpty().containsRetryableNetworkMessage()
            else -> false
        }
    }
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
        "timeout",
        "timed out",
    ).any(normalized::contains)
}

fun Throwable.providerRetryAfterMillis(): Long? =
    generateSequence(this) { it.cause }
        .filterIsInstance<ProviderRequestException>()
        .mapNotNull(ProviderRequestException::retryAfterMillis)
        .firstOrNull()

/** A shared retry budget for every reconnect phase of one provider request. */
class ProviderRetryController(
    val maxRetries: Int = DEFAULT_MAX_RETRIES,
    val initialDelayMillis: Long = DEFAULT_INITIAL_RETRY_DELAY_MILLIS,
    val maxDurationMillis: Long = Long.MAX_VALUE,
    private val nanoTime: () -> Long = System::nanoTime,
) {
    private val startedAtNanos = nanoTime()

    var retryCount: Int = 0
        private set

    init {
        require(maxRetries in 0..MAX_PROVIDER_RETRIES) {
            "maxRetries must be between 0 and $MAX_PROVIDER_RETRIES"
        }
        require(initialDelayMillis >= 0L) { "initialDelayMillis must not be negative" }
        require(maxDurationMillis >= 0L) { "maxDurationMillis must not be negative" }
    }

    suspend fun waitBeforeRetry(
        error: Throwable,
        onRetry: suspend (retryNumber: Int, delayMillis: Long) -> Unit = { _, _ -> },
        delayBeforeRetry: suspend (delayMillis: Long) -> Unit = { delay(it) },
    ): Boolean {
        if (retryCount >= maxRetries) return false

        val retryDelay = error.providerRetryAfterMillis()?.coerceAtLeast(0L)
            ?: exponentialDelayMillis(retryCount)
        val elapsedMillis = ((nanoTime() - startedAtNanos).coerceAtLeast(0L) /
            NANOS_PER_MILLISECOND)
        val remainingMillis = (maxDurationMillis - elapsedMillis).coerceAtLeast(0L)
        if (retryDelay > remainingMillis) return false

        val retryNumber = retryCount + 1
        retryCount = retryNumber
        onRetry(retryNumber, retryDelay)
        delayBeforeRetry(retryDelay)
        return true
    }

    fun remainingDurationMillis(): Long {
        val elapsedMillis = ((nanoTime() - startedAtNanos).coerceAtLeast(0L) /
            NANOS_PER_MILLISECOND)
        return (maxDurationMillis - elapsedMillis).coerceAtLeast(0L)
    }

    private fun exponentialDelayMillis(retryIndex: Int): Long {
        val multiplier = 1L shl retryIndex
        return if (initialDelayMillis > Long.MAX_VALUE / multiplier) {
            Long.MAX_VALUE
        } else {
            initialDelayMillis * multiplier
        }
    }
}

suspend fun <T> retryProviderRequest(
    enabled: Boolean,
    maxRetries: Int = DEFAULT_MAX_RETRIES,
    initialDelayMillis: Long = DEFAULT_INITIAL_RETRY_DELAY_MILLIS,
    maxDurationMillis: Long = Long.MAX_VALUE,
    retryController: ProviderRetryController? = null,
    canRetry: () -> Boolean = { true },
    onRetry: suspend (retryNumber: Int, delayMillis: Long) -> Unit = { _, _ -> },
    delayBeforeRetry: suspend (delayMillis: Long) -> Unit = { delay(it) },
    block: suspend (attempt: Int) -> T,
): T {
    val controller = retryController ?: ProviderRetryController(
        maxRetries = maxRetries,
        initialDelayMillis = initialDelayMillis,
        maxDurationMillis = maxDurationMillis,
    )
    var attempt = 0
    while (true) {
        try {
            return block(attempt)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            if (!enabled || !canRetry() || !error.isRetryableProviderFailure()) {
                throw error
            }
            if (!controller.waitBeforeRetry(error, onRetry, delayBeforeRetry)) throw error
            attempt++
        }
    }
}

fun StreamChunk.crossesRequestReplayBoundary(): Boolean = when (this) {
    is StreamChunk.TextDelta -> text.isNotEmpty()
    is StreamChunk.ReasoningDelta -> text.isNotEmpty()
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
    is StreamChunk.Usage -> true
    is StreamChunk.TextStart,
    is StreamChunk.TextEnd,
    is StreamChunk.ReasoningStart,
    is StreamChunk.ReasoningEnd,
    is StreamChunk.Finish -> false
}
