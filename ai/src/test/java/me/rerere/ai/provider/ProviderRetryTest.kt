package me.rerere.ai.provider

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import me.rerere.ai.ui.StreamChunk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.EOFException
import java.io.IOException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

class ProviderRetryTest {
    @Test
    fun `extracts provider status code from wrapped stream failures`() {
        val error = IOException(
            "stream failed",
            IllegalStateException(
                "decoder failed",
                ProviderRequestException(502, null, "upstream unavailable"),
            ),
        )

        assertEquals(502, error.providerStatusCode())
        assertEquals(
            503,
            ProviderRequestException(
                200,
                null,
                "stream opened",
                ProviderRequestException(503, null, "stream event failed"),
            ).providerStatusCode(),
        )
        assertEquals(null, IOException("connection reset").providerStatusCode())
    }

    @Test
    fun `rejects retry counts above the hard limit`() {
        try {
            ProviderRetryController(maxRetries = MAX_PROVIDER_RETRIES + 1)
            fail("retry count above the hard limit should fail")
        } catch (_: IllegalArgumentException) {
            // Expected.
        }
    }

    @Test
    fun `shares retry count and exponential interval across reconnect phases`() = runBlocking {
        val delays = mutableListOf<Long>()
        val controller = ProviderRetryController(
            maxRetries = 2,
            initialDelayMillis = 500L,
            jitterRatio = 0.0,
        )
        var attempts = 0

        retryProviderRequest(
            enabled = true,
            retryController = controller,
            delayBeforeRetry = { delays += it },
        ) {
            attempts++
            if (attempts == 1) throw IOException("connection reset")
            Unit
        }
        assertTrue(
            controller.waitBeforeRetry(
                error = IOException("stream was reset: CANCEL"),
                delayBeforeRetry = { delays += it },
            )
        )
        assertFalse(
            controller.waitBeforeRetry(
                error = IOException("connection reset"),
                delayBeforeRetry = { fail("retry budget was already exhausted") },
            )
        )

        assertEquals(2, controller.retryCount)
        assertEquals(listOf(500L, 1_000L), delays)
    }

    @Test
    fun `stops before the configured retry duration is exceeded`() = runBlocking {
        var nowMillis = 0L
        var attempts = 0
        val delays = mutableListOf<Long>()
        val controller = ProviderRetryController(
            maxRetries = MAX_PROVIDER_RETRIES,
            initialDelayMillis = 1_000L,
            maxDurationMillis = 2_500L,
            jitterRatio = 0.0,
            nanoTime = { nowMillis * 1_000_000L },
        )

        try {
            retryProviderRequest(
                enabled = true,
                retryController = controller,
                delayBeforeRetry = { delayMillis ->
                    delays += delayMillis
                    nowMillis += delayMillis
                },
            ) {
                attempts++
                throw IOException("connection reset")
            }
            fail("request should fail when the retry duration is exhausted")
        } catch (_: IOException) {
            // Expected.
        }

        assertEquals(2, attempts)
        assertEquals(1, controller.retryCount)
        assertEquals(listOf(1_000L), delays)
    }

    @Test
    fun `recognizes common connection abort and HTTP2 reset failures`() {
        assertTrue(SocketException("Software caused connection abort").isRetryableProviderFailure())
        assertTrue(
            IOException(
                "provider transport failed",
                SocketException("Software caused connection abort"),
            ).isRetryableProviderFailure()
        )
        assertTrue(
            CancellationException("provider stream cancelled").apply {
                initCause(SocketException("Software caused connection abort"))
            }.isRetryableProviderFailure()
        )
        assertTrue(IOException("Stream failed").isRetryableProviderFailure())
        assertTrue(IOException("socket closed while switching networks").isRetryableProviderFailure())
        assertTrue(IOException("Network is unreachable").isRetryableProviderFailure())
        assertTrue(IOException("stream was reset: CANCEL").isRetryableProviderFailure())
        assertTrue(IOException("HTTP/2 stream 3 was reset").isRetryableProviderFailure())
        assertTrue(IOException("\u8fde\u63a5\u5df2\u91cd\u7f6e").isRetryableProviderFailure())
        assertFalse(CancellationException("cancelled by user").isRetryableProviderFailure())
    }

    @Test
    fun `provider wait before first failure does not consume reconnect duration`() = runBlocking {
        var nowMillis = 0L
        var attempts = 0
        val controller = ProviderRetryController(
            maxRetries = 1,
            initialDelayMillis = 1_000L,
            maxDurationMillis = 10_000L,
            jitterRatio = 0.0,
            nanoTime = { nowMillis * 1_000_000L },
        )

        nowMillis = 120_000L
        retryProviderRequest(
            enabled = true,
            retryController = controller,
            delayBeforeRetry = { nowMillis += it },
        ) {
            attempts++
            if (attempts == 1) throw SocketException("Software caused connection abort")
        }

        assertEquals(2, attempts)
        assertEquals(1, controller.retryCount)
    }

    @Test
    fun `long provider attempts do not consume reconnect wait duration`() = runBlocking {
        var nowMillis = 0L
        var attempts = 0
        val delays = mutableListOf<Long>()
        val controller = ProviderRetryController(
            maxRetries = 2,
            initialDelayMillis = 1_000L,
            maxDurationMillis = 4_000L,
            jitterRatio = 0.0,
            nanoTime = { nowMillis * 1_000_000L },
        )

        retryProviderRequest(
            enabled = true,
            retryController = controller,
            delayBeforeRetry = { delayMillis ->
                delays += delayMillis
                nowMillis += delayMillis
            },
        ) {
            attempts++
            when (attempts) {
                1 -> throw IOException("connection reset")
                2 -> {
                    nowMillis += 200_000L
                    throw EOFException("stream ended during long reasoning")
                }
            }
        }

        assertEquals(3, attempts)
        assertEquals(listOf(1_000L, 2_000L), delays)
        assertEquals(2, controller.retryCount)
        assertEquals(1_000L, controller.remainingDurationMillis())
    }

    @Test
    fun `retries active cancellation wrapping a network failure`() = runBlocking {
        var attempts = 0

        retryProviderRequest(
            enabled = true,
            maxRetries = 1,
            delayBeforeRetry = {},
        ) {
            attempts++
            if (attempts == 1) {
                throw CancellationException("provider stream cancelled").apply {
                    initCause(SocketException("Software caused connection abort"))
                }
            }
        }

        assertEquals(2, attempts)
    }

    @Test
    fun `recognizes localized and wrapped rate limits from third party providers`() {
        assertTrue(ProviderRequestException(400, null, "\u8bf7\u6c42\u8fc7\u4e8e\u9891\u7e41\uff0c\u8bf7\u7a0d\u540e\u91cd\u8bd5").isRetryableProviderFailure())
        assertTrue(IllegalStateException("\u9519\u8bef\u7801\uff1a429\uff0c\u670d\u52a1\u5668\u7e41\u5fd9").isRetryableProviderFailure())
        assertTrue(IOException("\u4e0a\u6e38\u670d\u52a1\u5f02\u5e38\uff0c\u8bf7\u7a0d\u540e\u518d\u8bd5").isRetryableProviderFailure())
        assertFalse(ProviderRequestException(401, null, "rate limit").isRetryableProviderFailure())
        assertFalse(ProviderRequestException(404, null, "\u8fde\u63a5\u5df2\u91cd\u7f6e").isRetryableProviderFailure())
        assertFalse(ProviderRequestException(422, null, "request timeout").isRetryableProviderFailure())
        assertFalse(IllegalArgumentException("\u6a21\u578b\u53c2\u6570\u9519\u8bef").isRetryableProviderFailure())
    }

    @Test
    fun `does not retry account quota and billing 429 errors`() {
        assertFalse(
            ProviderRequestException(
                429,
                30_000L,
                "{\"error\":{\"code\":\"insufficient_quota\"}}",
            ).isRetryableProviderFailure()
        )
        assertFalse(
            ProviderRequestException(429, null, "\u4f59\u989d\u4e0d\u8db3\uff0c\u8bf7\u5145\u503c")
                .isRetryableProviderFailure()
        )
        assertTrue(
            ProviderRequestException(429, 2_000L, "temporary rate limit")
                .isRetryableProviderFailure()
        )
    }

    @Test
    fun `retries temporary HTTP errors with exponential backoff`() = runBlocking {
        var attempts = 0
        val delays = mutableListOf<Long>()
        val controller = ProviderRetryController(jitterRatio = 0.0)

        val result = retryProviderRequest(
            enabled = true,
            retryController = controller,
            delayBeforeRetry = { delays += it },
        ) {
            attempts++
            if (attempts <= 3) throw ProviderRequestException(503, null, "unavailable")
            "ok"
        }

        assertEquals("ok", result)
        assertEquals(4, attempts)
        assertEquals(listOf(1_000L, 2_000L, 4_000L), delays)
    }

    @Test
    fun `caps exponential backoff after applying deterministic jitter`() = runBlocking {
        val delays = mutableListOf<Long>()
        val controller = ProviderRetryController(
            maxRetries = 4,
            initialDelayMillis = 1_000L,
            maxDelayMillis = 5_000L,
            jitterRatio = 0.2,
            randomDouble = { 1.0 },
        )

        repeat(4) {
            assertTrue(
                controller.waitBeforeRetry(
                    error = IOException("connection reset"),
                    delayBeforeRetry = { delays += it },
                )
            )
        }

        assertEquals(listOf(1_200L, 2_400L, 4_800L, 5_000L), delays)
    }

    @Test
    fun `does not retry after recovery wait consumes the duration budget`() = runBlocking {
        var nowMillis = 0L
        var attempts = 0
        val controller = ProviderRetryController(
            maxRetries = 3,
            initialDelayMillis = 1_000L,
            maxDurationMillis = 2_000L,
            jitterRatio = 0.0,
            nanoTime = { nowMillis * 1_000_000L },
        )

        try {
            retryProviderRequest(
                enabled = true,
                retryController = controller,
                delayBeforeRetry = { nowMillis = 2_000L },
            ) {
                attempts++
                throw IOException("network unavailable")
            }
            fail("request should fail after the duration budget is consumed")
        } catch (_: IOException) {
            // Expected.
        }

        assertEquals(1, attempts)
    }

    @Test
    fun `allows caller to classify an unknown transport failure`() = runBlocking {
        var attempts = 0

        retryProviderRequest(
            enabled = true,
            maxRetries = 1,
            shouldRetry = { it is IOException },
            delayBeforeRetry = {},
        ) {
            attempts++
            if (attempts == 1) throw IOException("canceled")
        }

        assertEquals(2, attempts)
    }

    @Test
    fun `allows active provider cancellation to be classified separately from user cancellation`() = runBlocking {
        var attempts = 0

        retryProviderRequest(
            enabled = true,
            maxRetries = 1,
            shouldRetry = { it is CancellationException },
            delayBeforeRetry = {},
        ) {
            attempts++
            if (attempts == 1) throw CancellationException("provider stream canceled")
        }

        assertEquals(2, attempts)
    }

    @Test
    fun `does not retry authentication or request errors`() = runBlocking {
        for (status in listOf(400, 401, 403, 404)) {
            var attempts = 0
            try {
                retryProviderRequest(
                    enabled = true,
                    delayBeforeRetry = { fail("should not delay") },
                ) {
                    attempts++
                    throw ProviderRequestException(status, null, "request failed")
                }
                fail("HTTP $status should fail")
            } catch (error: ProviderRequestException) {
                assertEquals(status, error.statusCode)
            }
            assertEquals(1, attempts)
        }
    }

    @Test
    fun `treats Retry-After as a minimum and adds bounded positive jitter`() = runBlocking {
        val now = Instant.parse("2026-08-18T12:00:00Z")
        val retryDate = DateTimeFormatter.RFC_1123_DATE_TIME.format(now.plusSeconds(9).atZone(ZoneOffset.UTC))
        assertEquals(5_000L, parseRetryAfterMillis("5", now.toEpochMilli()))
        assertEquals(9_000L, parseRetryAfterMillis(retryDate, now.toEpochMilli()))

        val delays = mutableListOf<Long>()
        var attempts = 0
        val controller = ProviderRetryController(
            maxRetries = 1,
            randomDouble = { 1.0 },
        )
        retryProviderRequest(
            enabled = true,
            retryController = controller,
            delayBeforeRetry = { delays += it },
        ) {
            attempts++
            if (attempts == 1) throw ProviderRequestException(429, 12_000L, "rate limited")
            Unit
        }
        assertEquals(listOf(13_000L), delays)
    }

    @Test
    fun `does not replay after content or tool output`() = runBlocking {
        assertFalse(StreamChunk.TextStart("text").crossesRequestReplayBoundary())
        assertFalse(StreamChunk.TextDelta("text", "").crossesRequestReplayBoundary())
        assertTrue(StreamChunk.TextDelta("text", "hello").crossesRequestReplayBoundary())
        assertFalse(StreamChunk.Usage(me.rerere.ai.core.TokenUsage()).crossesRequestReplayBoundary())
        assertFalse(
            StreamChunk.ReasoningDelta("reasoning", "draft")
                .crossesRequestReplayBoundary(reasoningIsReplaySafe = true)
        )
        assertTrue(StreamChunk.ToolCallStart("tool", "search").crossesRequestReplayBoundary())
        assertTrue(StreamChunk.ImageStart("image").crossesRequestReplayBoundary())

        var attempts = 0
        var replayBoundaryCrossed = false
        try {
            retryProviderRequest(
                enabled = true,
                canRetry = { !replayBoundaryCrossed },
                delayBeforeRetry = { fail("should not delay") },
            ) {
                attempts++
                replayBoundaryCrossed = true
                throw ProviderRequestException(503, null, "failed after output")
            }
            fail("request should fail after output")
        } catch (_: ProviderRequestException) {
            // Expected.
        }
        assertEquals(1, attempts)
    }

    @Test
    fun `cancelling retry wait stops further attempts`() = runBlocking {
        val retryStarted = CompletableDeferred<Unit>()
        var attempts = 0
        val job = launch {
            retryProviderRequest(
                enabled = true,
                onRetry = { _, _ -> retryStarted.complete(Unit) },
            ) {
                attempts++
                throw SocketTimeoutException("timed out")
            }
        }

        retryStarted.await()
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
        assertEquals(1, attempts)
    }
}
