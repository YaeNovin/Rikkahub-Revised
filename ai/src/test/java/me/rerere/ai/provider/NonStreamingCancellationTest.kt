package me.rerere.ai.provider

import kotlinx.coroutines.*
import me.rerere.ai.provider.providers.google.GoogleProvider
import me.rerere.ai.provider.providers.openai.ChatCompletionsAPI
import me.rerere.ai.provider.providers.openai.ResponseAPI
import me.rerere.ai.ui.UIMessage
import okhttp3.*
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class NonStreamingCancellationTest {
    @Test fun `stop cancels real response body reads after headers in all text protocols`() = runBlocking {
        for (protocol in listOf("chat", "responses", "google")) {
            val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
            val bodyStarted = CompletableDeferred<Unit>()
            val disconnected = CompletableDeferred<Unit>()
            val worker = thread(isDaemon = true, name = "slow-response-$protocol") {
                try {
                    server.accept().use { socket ->
                        socket.soTimeout = 6000
                        val input = socket.getInputStream()
                        val headers = StringBuilder()
                        while (!headers.endsWith("\r\n\r\n")) {
                            val byte = input.read()
                            if (byte == -1) return@thread
                            headers.append(byte.toChar())
                        }
                        val length = Regex("(?i)Content-Length: (\\d+)").find(headers)?.groupValues?.get(1)?.toInt() ?: 0
                        repeat(length) { input.read() }
                        socket.getOutputStream().apply {
                            write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: 999999\r\n\r\n{".toByteArray())
                            flush()
                        }
                        if (input.read() == -1) disconnected.complete(Unit)
                    }
                } catch (_: Exception) { disconnected.complete(Unit) }
            }
            val client = OkHttpClient.Builder().readTimeout(30, TimeUnit.SECONDS)
                .eventListener(object : EventListener() {
                    override fun responseBodyStart(call: Call) { bodyStarted.complete(Unit) }
                }).build()
            try {
                val base = "http://127.0.0.1:${server.localPort}"
                val params = TextGenerationParams(model = Model(modelId = "gemini-3.8-flash"))
                val messages = listOf(UIMessage.user("test"))
                val job = async(Dispatchers.IO) {
                    runCatching {
                        when (protocol) {
                            "chat" -> ChatCompletionsAPI(client, me.rerere.ai.util.KeyRoulette.default()).generateText(ProviderSetting.OpenAI(baseUrl = base), messages, params)
                            "responses" -> ResponseAPI(client).generateText(ProviderSetting.OpenAI(baseUrl = base), messages, params)
                            else -> GoogleProvider(client).generateText(ProviderSetting.Google(baseUrl = base), messages, params)
                        }
                    }
                }
                withTimeout(5000) { bodyStarted.await() }
                job.cancel()
                withTimeout(2000) { job.join(); disconnected.await() }
                assertTrue("$protocol must finish on the first cancellation", job.isCancelled)
            } finally {
                server.close()
                worker.join(1000)
                client.connectionPool.evictAll()
                client.dispatcher.executorService.shutdownNow()
            }
        }
    }
}
