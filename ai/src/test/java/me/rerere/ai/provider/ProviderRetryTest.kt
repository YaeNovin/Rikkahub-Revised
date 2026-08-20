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
import java.io.IOException
import java.net.SocketTimeoutException
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

class ProviderRetryTest {
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
        assertTrue(IOException("Software caused connection abort").isRetryableProviderFailure())
        assertTrue(IOException("Stream failed").isRetryableProviderFailure())
        assertTrue(IOException("socket closed while switching networks").isRetryableProviderFailure())
        assertTrue(IOException("Network is unreachable").isRetryableProviderFailure())
        assertTrue(IOException("stream was reset: CANCEL").isRetryableProviderFailure())
        assertTrue(IOException("HTTP/2 stream 3 was reset").isRetryableProviderFailure())
        assertFalse(CancellationException("cancelled by user").isRetryableProviderFailure())
    }

    @Test
    fun `retries temporary HTTP errors with exponential backoff`() = runBlocking {
        var attempts = 0
        val delays = mutableListOf<Long>()

        val result = retryProviderRequest(
            enabled = true,
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
    fun `prefers Retry-After seconds and HTTP date`() = runBlocking {
        val now = Instant.parse("2026-08-18T12:00:00Z")
        val retryDate = DateTimeFormatter.RFC_1123_DATE_TIME.format(now.plusSeconds(9).atZone(ZoneOffset.UTC))
        assertEquals(5_000L, parseRetryAfterMillis("5", now.toEpochMilli()))
        assertEquals(9_000L, parseRetryAfterMillis(retryDate, now.toEpochMilli()))

        val delays = mutableListOf<Long>()
        var attempts = 0
        retryProviderRequest(
            enabled = true,
            maxRetries = 1,
            delayBeforeRetry = { delays += it },
        ) {
            attempts++
            if (attempts == 1) throw ProviderRequestException(429, 12_000L, "rate limited")
            Unit
        }
        assertEquals(listOf(12_000L), delays)
    }

    @Test
    fun `does not replay after content or tool output`() = runBlocking {
        assertFalse(StreamChunk.TextStart("text").crossesRequestReplayBoundary())
        assertFalse(StreamChunk.TextDelta("text", "").crossesRequestReplayBoundary())
        assertTrue(StreamChunk.TextDelta("text", "hello").crossesRequestReplayBoundary())
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
