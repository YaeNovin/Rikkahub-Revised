package me.rerere.ai.provider.providers.google

import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import me.rerere.ai.provider.*
import me.rerere.ai.ui.*
import me.rerere.ai.util.json
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test

class GoogleInteractionsTransportTest {
    private val params = TextGenerationParams(model = Model(modelId = "gemini-4-hydrogen").withDetectedCapabilities())
    private class FixtureServer(private val replies: List<String>, private val streaming: Boolean = false) : AutoCloseable {
        private val server = ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"))
        val requests = CopyOnWriteArrayList<Pair<String, String>>()
        private var socket: Socket? = null
        private val done = CountDownLatch(1)
        val url = "http://127.0.0.1:${server.localPort}/v1beta"
        private val worker = thread(isDaemon = true) {
            try {
                replies.forEach { response ->
                    server.accept().use { connection ->
                        socket = connection
                        connection.soTimeout = 4000
                        val input = connection.getInputStream()
                        val header = StringBuilder()
                        while (!header.endsWith("\r\n\r\n")) {
                            val byte = input.read()
                            if (byte < 0) error("Unexpected EOF in request headers")
                            header.append(byte.toChar())
                        }
                        val length = Regex("(?im)^Content-Length: (\\d+)").find(header)?.groupValues?.get(1)?.toInt() ?: 0
                        requests.add(header.toString() to input.readNBytes(length).toString(Charsets.UTF_8))
                        val output = connection.getOutputStream()
                        val bytes = response.toByteArray(Charsets.UTF_8)
                        val contentType = if (streaming) "text/event-stream" else "application/json"
                        output.write("HTTP/1.1 200 OK\r\nContent-Type: $contentType\r\nConnection: close\r\n${if (streaming) "" else "Content-Length: ${bytes.size}\r\n"}\r\n".toByteArray())
                        output.write(bytes)
                        output.flush()
                        if (streaming) input.read() // Wait for cancellation to close the socket.
                    }
                }
            } finally { done.countDown() }
        }
        fun await() = done.await(5, TimeUnit.SECONDS)
        override fun close() { socket?.close(); server.close(); worker.join(1000) }
    }

    @Test fun `provider opt in uses interactions endpoint revision and local continuation`() = runBlocking {
        FixtureServer(listOf(
            """{"id":"h1","model":"gemini-4-hydrogen","status":"requires_continuation","continuation_token":"next+/=","steps":[{"type":"model_output","content":[{"type":"text","text":"第一段"}]}],"usage":{"total_input_tokens":10,"total_output_tokens":4,"total_tokens":14}}""",
            """{"id":"h2","model":"gemini-4-hydrogen","status":"completed","steps":[{"type":"model_output","content":[{"type":"text","text":"第二段"}]}],"usage":{"total_input_tokens":14,"total_output_tokens":4,"total_tokens":18}}""",
        )).use { server ->
            val result = GoogleProvider(OkHttpClient()).generateText(
                ProviderSetting.Google(apiKey = "fixture-key", baseUrl = server.url, useInteractionsApi = true),
                listOf(UIMessage.user("请求")), params)
            assertEquals("第一段第二段", result.message.parts.filterIsInstance<UIMessagePart.Text>().joinToString("") { it.text })
            assertEquals(8, result.usage!!.completionTokens)
            assertEquals(2, server.requests.size)
            assertTrue(server.requests.first().first.contains("POST /v1beta/interactions HTTP/1.1"))
            assertTrue(server.requests.first().first.lowercase().contains("api-revision: 2026-05-20"))
            assertTrue(server.requests.first().first.lowercase().contains("x-goog-api-key: fixture-key"))
            val body = json.parseToJsonElement(server.requests[1].second).jsonObject
            assertEquals(2, body["input"]!!.jsonArray.size)
            assertEquals(false, body["store"]!!.jsonPrimitive.boolean)
            assertEquals("next+/=", body["continuation_token"]!!.jsonPrimitive.content)
        }
    }

    @Test fun `cancelling streaming collection closes underlying SSE call`() = runBlocking {
        FixtureServer(listOf("event: step.start\ndata: {\"event_type\":\"step.start\",\"index\":0,\"step\":{\"type\":\"model_output\"}}\n\n"), true).use { server ->
            val client = OkHttpClient()
            val chunks = GoogleProvider(client).streamText(
                ProviderSetting.Google(apiKey = "fixture-key", baseUrl = server.url, useInteractionsApi = true),
                listOf(UIMessage.user("test")), params).take(1).toList()
            assertTrue(chunks.single() is StreamChunk.TextStart)
            assertTrue(server.await())
            kotlinx.coroutines.withTimeout(2000) {
                while (client.dispatcher.runningCallsCount() != 0) kotlinx.coroutines.delay(10)
            }
            assertEquals(0, client.dispatcher.runningCallsCount())
        }
    }

    @Test(expected = IllegalArgumentException::class) fun `Vertex refuses experimental Developer API route`() = runBlocking {
        GoogleProvider(OkHttpClient()).generateText(ProviderSetting.Google(vertexAI = true, useInteractionsApi = true),
            listOf(UIMessage.user("test")), params)
        Unit
    }
}
