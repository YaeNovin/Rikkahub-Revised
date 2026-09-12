package me.rerere.rikkahub

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.ui.components.richtext.MarkdownBlock
import me.rerere.rikkahub.ui.components.ui.ExportRenderTracker
import me.rerere.rikkahub.ui.components.ui.LocalExportContext
import me.rerere.rikkahub.ui.components.ui.LocalExportRenderTracker
import me.rerere.rikkahub.ui.context.LocalSettings
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean

/** Bounded pixel check without Espresso idling (some OEMs never settle its activity rule). */
class BadgeLayoutDeviceTest {
    @Test(timeout = 30_000) fun fiveBadgeContextsPaintPixels() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, ComponentActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as ComponentActivity
        try {
            BadgeServer().use { server ->
                val tracker = ExportRenderTracker()
                val composed = AtomicBoolean()
                instrumentation.runOnMainSync {
                    activity.setContent {
                        CompositionLocalProvider(LocalSettings provides Settings(), LocalExportContext provides true,
                            LocalExportRenderTracker provides tracker) {
                            MaterialTheme {
                                Column(Modifier.width(320.dp).padding(12.dp)) {
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
                                SideEffect { composed.set(true) }
                            }
                        }
                    }
                }
                var ready = false
                val deadline = System.nanoTime() + 15_000_000_000L
                while (!ready && System.nanoTime() < deadline) {
                    Thread.sleep(100)
                    instrumentation.runOnMainSync { ready = composed.get() && server.requests.get() > 0 && tracker.settled() }
                }
                assertTrue("Image pipeline did not settle", ready)
                instrumentation.runOnMainSync {
                    val root = activity.window.decorView
                    val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
                    try {
                        root.draw(Canvas(bitmap))
                        var bands = 0
                        var previous = false
                        val row = IntArray(bitmap.width)
                        for (y in 0 until bitmap.height) {
                            bitmap.getPixels(row, 0, row.size, 0, y, row.size, 1)
                            val green = row.count {
                                android.graphics.Color.green(it) > 150 && android.graphics.Color.red(it) < 100 && android.graphics.Color.blue(it) < 70
                            } > 50
                            if (green && !previous) bands++
                            previous = green
                        }
                        assertEquals("Every paragraph/list/table/callout must paint its own badge", 5, bands)
                    } finally { bitmap.recycle() }
                }
            }
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }
}
