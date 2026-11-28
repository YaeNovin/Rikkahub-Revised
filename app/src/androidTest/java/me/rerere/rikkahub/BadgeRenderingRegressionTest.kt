package me.rerere.rikkahub

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.ui.components.richtext.MarkdownBlock
import me.rerere.rikkahub.ui.components.richtext.ZoomableAsyncImage
import me.rerere.rikkahub.ui.components.ui.LocalExportContext
import me.rerere.rikkahub.ui.context.LocalSettings
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.Before
import org.junit.After
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

class BadgeRenderingRegressionTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var host: ComponentActivity

    @Before fun launchHostDirectly() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(
            "am start -W -f 0x10008000 -n ${instrumentation.targetContext.packageName}/androidx.activity.ComponentActivity"
        )).use { it.readBytes() }
        val deadline = System.nanoTime() + 15_000_000_000L
        while (!::host.isInitialized && System.nanoTime() < deadline) {
            instrumentation.runOnMainSync {
                androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
                    .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED)
                    .filterIsInstance<ComponentActivity>().firstOrNull()?.let { host = it }
            }
            if (!::host.isInitialized) Thread.sleep(100)
        }
        check(::host.isInitialized) { "Image fixture did not resume" }
    }

    @After fun closeHost() {
        if (::host.isInitialized) InstrumentationRegistry.getInstrumentation().runOnMainSync { host.finish() }
    }

    @Test fun markdownImagesRemainVisibleInNestedInlineContainersListsTablesAndCallouts() {
        BadgeServer().use { server ->
            var export by mutableStateOf(false)
            compose.runOnUiThread { host.setContent {
                CompositionLocalProvider(LocalSettings provides Settings(), LocalExportContext provides export) {
                    MaterialTheme {
                        Column(Modifier.width(320.dp)) {
                            if (!export) ZoomableAsyncImage(server.url, "warm-cache", respectIntrinsicSize = true)
                            else MarkdownBlock("""
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
            } }
            compose.waitUntil(10_000) { compose.onAllNodes(hasText("点击加载图片", substring = true)).fetchSemanticsNodes().isNotEmpty() }
            assertEquals(0, server.requests.get())
            compose.onNode(hasText("点击加载图片", substring = true)).performClick()
            compose.waitUntil(10_000) { compose.onAllNodes(hasStateDescription("图片已加载")).fetchSemanticsNodes().isNotEmpty() }
            val explicitRequests = server.requests.get()
            assertEquals(1, explicitRequests)
            compose.runOnIdle { export = true }
            val containers = listOf("plain", "nested", "list", "table", "callout")
            try {
                compose.waitUntil(15_000) {
                    // A table also subcomposes cells for measurement; count visible
                    // content by its label instead of assuming one node per image.
                    containers.all { label -> compose.onAllNodes(
                        hasContentDescription(label) and hasStateDescription("图片已加载")
                    ).fetchSemanticsNodes().isNotEmpty() }
                }
            } catch (failure: Throwable) {
                throw AssertionError(compose.onRoot(useUnmergedTree = true).printToString(), failure)
            }
            containers.forEach {
                compose.onAllNodesWithContentDescription(it).onFirst().assertIsDisplayed()
            }
            // Check a real green pixel, not just an <img> node or a gray placeholder.
            val pixels = compose.onNodeWithContentDescription("plain").captureToImage().toPixelMap()
            assertTrue(pixels[pixels.width / 2, pixels.height / 2].green > 0.5f)
            assertEquals("Export must only read the image cache", explicitRequests, server.requests.get())
        }
    }

    @Test fun failedImageCanRetrySameUrlAndBecomeVisible() {
        BadgeServer(failFirst = true).use { server ->
            compose.runOnUiThread { host.setContent {
                CompositionLocalProvider(LocalExportContext provides false) {
                    MaterialTheme { ZoomableAsyncImage(server.url, "retry-badge", respectIntrinsicSize = true) }
                }
            } }
            compose.waitUntil(15_000) { compose.onAllNodes(hasText("点击加载图片", substring = true)).fetchSemanticsNodes().isNotEmpty() }
            assertEquals(0, server.requests.get())
            compose.onNode(hasText("点击加载图片", substring = true)).performClick()
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
                val header = "HTTP/1.1 ${if (fail) "503 Service Unavailable" else "200 OK"}\r\nContent-Type: image/svg+xml\r\nContent-Length: ${bytes.size}\r\nCache-Control: ${if (fail) "no-store" else "public, max-age=86400"}\r\nConnection: close\r\n\r\n"
                it.getOutputStream().apply { write(header.toByteArray()); write(bytes); flush() }
            }
        }
    }
    override fun close() { server.close(); worker.join(1_000) }
}
