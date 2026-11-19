package me.rerere.rikkahub

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.ui.components.ui.BitmapComposer
import me.rerere.rikkahub.ui.components.webview.WebView
import me.rerere.rikkahub.ui.components.webview.rememberWebViewState
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Run only in the isolated debug test application; synthetic content, no model calls. */
class ChatImageExportDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun pagedCaptureKeepsMiddleAndLastPixelsAfterSuspendingForSave() = runBlocking {
        var total = 0; var count = 0; var last = 0
        val warnings = BitmapComposer().composableToImages(compose.activity, 160.dp, Density(1f),
            onPage = { bitmap, index, pages ->
                count++; total += bitmap.height
                assertEquals(2, pages)
                if (index == 0) assertEquals(android.graphics.Color.RED, bitmap.getPixel(80,40))
                if (index == pages - 1) last = bitmap.getPixel(80, bitmap.height - 40)
                delay(200) // A window traversal while PNG encoding would be on IO.
            }) {
            Column {
                Box(Modifier.fillMaxWidth().height(100.dp).background(Color.Red))
                Box(Modifier.fillMaxWidth().height(9000.dp).background(Color.Green))
                Box(Modifier.fillMaxWidth().height(100.dp).background(Color.Blue))
            }
        }
        assertEquals(9200, total); assertEquals(2, count)
        assertEquals(android.graphics.Color.BLUE, last)
        assertTrue(warnings.isEmpty())
    }

    @Test fun badPlotDoesNotSuppressLaterContentAndTallSvgExpands() = runBlocking {
        var height = 0; var last = 0
        val warnings = BitmapComposer().composableToImages(compose.activity, 160.dp, Density(1f),
            onPage = { bitmap, _, _ -> height += bitmap.height; last = bitmap.getPixel(80,bitmap.height-20) }) {
            Column {
                val bad = rememberWebViewState(data = """<html><body><pre id="error">无法绘图：测试错误</pre><script>window.__rikkaRenderStatus='error';</script></body></html>""", mimeType = "text/html")
                WebView(bad, previewHeight = 120.dp)
                val good = rememberWebViewState(data = """<html><body style="margin:0"><svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 160 1200" style="width:100%;height:auto"><rect width="160" height="1200" fill="red"/></svg></body></html>""", mimeType = "text/html")
                WebView(good, previewHeight = 120.dp)
                Box(Modifier.fillMaxWidth().height(100.dp).background(Color.Blue))
            }
        }
        assertTrue(warnings.any { "测试错误" in it })
        assertTrue(height >= 1300)
        assertEquals(android.graphics.Color.BLUE, last)
    }
}
