package me.rerere.rikkahub

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.ui.components.ui.BitmapComposer
import me.rerere.rikkahub.ui.components.webview.WebView
import me.rerere.rikkahub.ui.components.webview.rememberWebViewState
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class SvgBitmapExportTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun exportWaitsForDelayedSvgAndCapturesItsPixels() = runBlocking {
        val bitmap = BitmapComposer().composableToBitmap(compose.activity, width = 160.dp, height = 120.dp,
            screenDensity = Density(1f)) {
            CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides Density(1f)) {
                val state = rememberWebViewState(data = """
                    <html><body style="margin:0"><script>
                    window.__rikkaRenderStatus='loading';
                    setTimeout(function(){
                      document.body.innerHTML='<svg xmlns="http://www.w3.org/2000/svg" width="160" height="120"><rect width="160" height="120" fill="red"/></svg>';
                      window.__rikkaRenderStatus='ready';
                    }, 500);
                    </script></body></html>
                """.trimIndent(), mimeType = "text/html")
                WebView(state, Modifier.height(120.dp))
            }
        }
        try {
            val pixel = bitmap.getPixel(80, 60)
            assertTrue(android.graphics.Color.red(pixel) > 200 && android.graphics.Color.green(pixel) < 80)
        } finally { bitmap.recycle() }
    }
}
