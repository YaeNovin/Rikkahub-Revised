package me.rerere.rikkahub

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.ui.components.richtext.MarkdownBlock
import me.rerere.rikkahub.ui.components.richtext.ZoomableAsyncImage
import me.rerere.rikkahub.ui.components.ui.LocalExportContext
import me.rerere.rikkahub.ui.context.LocalSettings
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

class BadgeRenderingRegressionTest {
    @get:Rule(order = 0) val timeout = org.junit.rules.Timeout.seconds(30)
    @get:Rule(order = 1) val compose = createComposeRule()

    @Test fun markdownImagesRemainVisibleInNestedInlineContainersListsTablesAndCallouts() {
        BadgeServer().use { server ->
            compose.setContent {
                CompositionLocalProvider(LocalSettings provides Settings(), LocalExportContext provides true) {
                    MaterialTheme {
                        Column(Modifier.width(320.dp)) {
                            MarkdownBlock("""
                                ![plain](${server.url})

                                **[![nested](${server.url})](https://example.com)**

                                - ![list](${server.url})

                                | Status |
                                | --- |
                                | ![table](${server.url}) |

                                > [!NOTE]
                                > ![callout](${server.url})
                            """.trimIndent())
                        }
                    }
                }
            }
            compose.waitUntil(15_000) {
                compose.onAllNodes(hasStateDescription("图片已加载")).fetchSemanticsNodes().size == 5
            }
            listOf("plain", "nested", "list", "table", "callout").forEach {
                compose.onNodeWithContentDescription(it).assertIsDisplayed()
            }
            // Check a real green pixel, not just an <img> node or a gray placeholder.
            val pixels = compose.onNodeWithContentDescription("plain").captureToImage().toPixelMap()
            assertTrue(pixels[pixels.width / 2, pixels.height / 2].green > 0.5f)
        }
    }

    @Test fun failedImageCanRetrySameUrlAndBecomeVisible() {
        BadgeServer(failFirst = true).use { server ->
            compose.setContent {
                CompositionLocalProvider(LocalExportContext provides true) {
                    MaterialTheme { ZoomableAsyncImage(server.url, "retry-badge", respectIntrinsicSize = true) }
                }
            }
            compose.waitUntil(15_000) { compose.onAllNodes(hasText("点击重试", substring = true)).fetchSemanticsNodes().isNotEmpty() }
            compose.onNode(hasText("HTTP 503", substring = true)).performClick()
            compose.waitUntil(15_000) { compose.onAllNodes(hasStateDescription("图片已加载")).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithContentDescription("retry-badge").assertIsDisplayed()
            assertEquals(2, server.requests.get())
        }
    }
}

internal class BadgeServer(private val failFirst: Boolean = false) : AutoCloseable {
    private val server = ServerSocket(0, 8, java.net.InetAddress.getByName("127.0.0.1"))
    val url = "http://127.0.0.1:${server.localPort}/badge.svg"
    val requests = AtomicInteger()
    private val worker = thread(isDaemon = true, name = "badge-test-server") {
        while (!server.isClosed) {
            val socket = try { server.accept() } catch (_: java.io.IOException) { break }
            socket.use {
                it.soTimeout = 5_000
                val reader = it.getInputStream().bufferedReader()
                while (!reader.readLine().isNullOrEmpty()) { /* consume request headers */ }
                val fail = requests.incrementAndGet() == 1 && failFirst
                val body = if (fail) "unavailable" else """<svg xmlns="http://www.w3.org/2000/svg" width="88" height="20"><rect width="88" height="20" fill="#44bb00"/></svg>"""
                val bytes = body.toByteArray()
                val header = "HTTP/1.1 ${if (fail) "503 Service Unavailable" else "200 OK"}\r\nContent-Type: image/svg+xml\r\nContent-Length: ${bytes.size}\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n"
                it.getOutputStream().apply { write(header.toByteArray()); write(bytes); flush() }
            }
        }
    }
    override fun close() { server.close(); worker.join(1_000) }
}
