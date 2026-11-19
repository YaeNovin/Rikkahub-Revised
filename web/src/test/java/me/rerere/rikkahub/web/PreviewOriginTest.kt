package me.rerere.rikkahub.web

import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.delete
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.net.Socket

import java.util.concurrent.atomic.AtomicInteger

class PreviewOriginTest {
    @Test fun `opaque preview origin cannot read or mutate API even without authentication`() = runBlocking {
        val mutations = AtomicInteger()
        val server = startWebServer(port = 0, host = "127.0.0.1") {
            routing {
                get("/api/probe") { call.respondText("private-response-sentinel") }
                post("/api/probe") { mutations.incrementAndGet(); call.respondText("ok") }
                delete("/api/probe") { mutations.incrementAndGet(); call.respondText("ok") }
            }
        }.start(wait = false)
        try {
            val port = server.engine.resolvedConnectors().first().port
            fun request(method: String, origin: String?): String = Socket("127.0.0.1", port).use { socket ->
                socket.soTimeout = 5000
                val request = buildString {
                    append("$method /api/probe HTTP/1.1\r\nHost: 127.0.0.1:$port\r\nConnection: close\r\n")
                    if (origin != null) append("Origin: $origin\r\n")
                    if (method == "OPTIONS") append("Access-Control-Request-Method: POST\r\nAccess-Control-Request-Headers: content-type\r\n")
                    if (method == "POST") append("Content-Type: application/json\r\nContent-Length: 2\r\n")
                    append("\r\n")
                    if (method == "POST") append("{}")
                }
                socket.getOutputStream().write(request.toByteArray())
                socket.getOutputStream().flush()
                // Read the framed response, not EOF (CIO may keep a rejected request alive).
                val reader = socket.getInputStream().bufferedReader()
                val status = reader.readLine()
                val headers = mutableMapOf<String, String>()
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) break
                    headers[line.substringBefore(':').lowercase()] = line.substringAfter(':').trim()
                }
                val body = StringBuilder()
                fun readChars(count: Int) { repeat(count) { body.append(reader.read().toChar()) } }
                if (headers["transfer-encoding"] == "chunked") {
                    while (true) {
                        val size = reader.readLine().substringBefore(';').toInt(16)
                        if (size == 0) break
                        readChars(size)
                        reader.readLine()
                    }
                } else {
                    readChars(headers["content-length"]?.toInt() ?: 0)
                }
                "$status\n$body"
            }
            for (method in listOf("GET", "POST", "DELETE", "OPTIONS")) {
                val response = request(method, "null")
                assertTrue("$method must be denied: ${response.lineSequence().first()}", response.startsWith("HTTP/1.1 403"))
                assertFalse(response.contains("private-response-sentinel"))
            }
            assertEquals(0, mutations.get())
            val normalRead = request("GET", null)
            assertTrue(normalRead.startsWith("HTTP/1.1 200"))
            assertTrue(normalRead.contains("private-response-sentinel"))
            assertTrue(request("POST", "http://127.0.0.1:$port").startsWith("HTTP/1.1 200"))
            assertEquals(1, mutations.get())
        } finally {
            server.stop(0, 1000)
        }
    }
}
