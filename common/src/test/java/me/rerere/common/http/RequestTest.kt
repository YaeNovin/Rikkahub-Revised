package me.rerere.common.http

import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.Call
import okhttp3.Callback
import okhttp3.EventListener
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okio.Timeout
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.reflect.KClass

class RequestTest {
    @Test
    fun `cancelling await cancels the okhttp call`() = runBlocking {
        val call = PendingCall()
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            runCatching { call.await() }
        }

        job.cancelAndJoin()

        assertTrue(call.cancelled.get())
    }

    @Test
    fun `cancelling response consumption cancels the okhttp call`() = runBlocking {
        val call = ImmediateResponseCall()
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            call.awaitAndUse {
                kotlinx.coroutines.awaitCancellation()
            }
        }

        job.cancelAndJoin()

        assertTrue(call.cancelled.get())
    }

    private class PendingCall : Call {
        val cancelled = AtomicBoolean(false)
        private val request = Request.Builder().url("https://example.invalid/").build()

        override fun request(): Request = request

        override fun execute(): Response = throw IOException("Synchronous execution is not supported")

        override fun enqueue(responseCallback: Callback) = Unit

        override fun cancel() {
            cancelled.set(true)
        }

        override fun isExecuted(): Boolean = false

        override fun isCanceled(): Boolean = cancelled.get()

        override fun timeout(): Timeout = Timeout.NONE

        override fun addEventListener(eventListener: EventListener) = Unit

        override fun <T : Any> tag(type: KClass<T>): T? = null

        override fun <T> tag(type: Class<out T>): T? = null

        override fun <T : Any> tag(type: KClass<T>, computeIfAbsent: () -> T): T = computeIfAbsent()

        override fun <T : Any> tag(type: Class<T>, computeIfAbsent: () -> T): T = computeIfAbsent()

        override fun clone(): Call = PendingCall()
    }

    private class ImmediateResponseCall : Call {
        val cancelled = AtomicBoolean(false)
        private val request = Request.Builder().url("https://example.invalid/").build()

        override fun request(): Request = request

        override fun execute(): Response = throw IOException("Synchronous execution is not supported")

        override fun enqueue(responseCallback: Callback) {
            responseCallback.onResponse(
                this,
                Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .build(),
            )
        }

        override fun cancel() {
            cancelled.set(true)
        }

        override fun isExecuted(): Boolean = true

        override fun isCanceled(): Boolean = cancelled.get()

        override fun timeout(): Timeout = Timeout.NONE

        override fun addEventListener(eventListener: EventListener) = Unit

        override fun <T : Any> tag(type: KClass<T>): T? = null

        override fun <T> tag(type: Class<out T>): T? = null

        override fun <T : Any> tag(type: KClass<T>, computeIfAbsent: () -> T): T = computeIfAbsent()

        override fun <T : Any> tag(type: Class<T>, computeIfAbsent: () -> T): T = computeIfAbsent()

        override fun clone(): Call = ImmediateResponseCall()
    }
}
