package me.rerere.rikkahub

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.junit4.createComposeRule
import me.rerere.rikkahub.ui.components.webview.WebView
import me.rerere.rikkahub.ui.components.webview.rememberWebViewState
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Run on Android: covers native view lifetime during overlapping page transitions. */
class WebViewNavigationRegressionTest {
    @get:Rule val compose = createComposeRule()

    @Test fun deferredPreviewHasWidthAndSurvivesRepeatedPageReplacement() {
        var screen by mutableIntStateOf(0)
        var previewState: me.rerere.rikkahub.ui.components.webview.WebViewState? = null
        compose.setContent {
            AnimatedContent(screen, label = "PreviewNavigation") { page ->
                if (page % 2 == 0) {
                    val state = rememberWebViewState(data = "<html><body><svg width='120' height='60'><text y='30'>Ready</text></svg></body></html>", mimeType = "text/html")
                    SideEffect { previewState = state }
                    Column(Modifier.fillMaxWidth()) {
                        WebView(state, Modifier.height(180.dp), deferUntilVisible = true)
                    }
                } else BasicText("Other page")
            }
        }
        compose.waitUntil(10000) { previewState?.loadingProgress == 1f }
        repeat(12) {
            compose.runOnUiThread { screen++ }
            compose.waitForIdle()
            compose.runOnUiThread { screen++ }
            compose.waitForIdle()
        }
        compose.waitUntil(10000) { previewState?.loadingProgress == 1f }
        compose.runOnIdle { assertTrue(previewState?.error == null) }
    }
}
