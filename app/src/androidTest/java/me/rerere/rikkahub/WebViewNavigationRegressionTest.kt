package me.rerere.rikkahub

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.junit4.createComposeRule
import me.rerere.rikkahub.ui.components.webview.WebView
import me.rerere.rikkahub.ui.components.webview.rememberWebViewState
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test

/** Run on Android: covers native view lifetime during overlapping page transitions. */
class WebViewNavigationRegressionTest {
    @get:Rule val compose = createComposeRule()

    @Test fun inlineAutofocusCannotReenterLazyCompositionDuringStreamingReplacement() {
        var revision by mutableIntStateOf(0)
        var nativeView: android.webkit.WebView? = null
        compose.setContent {
            AnimatedContent(revision % 2, label = "StreamingReplacement") { page ->
                LazyColumn {
                    items((0..15).toList(), key = { "$page:$it" }) { index ->
                        if (index == 0 && page == 0) {
                            val state = rememberWebViewState(data = "<html><body><input autofocus><button>Test</button></body></html>", mimeType = "text/html")
                            WebView(state, Modifier.height(180.dp), deferUntilVisible = true,
                                preferParentVerticalScroll = true, onCreated = { nativeView = it })
                        } else BasicText("Message $index revision $revision")
                    }
                }
            }
        }
        compose.waitUntil(10000) { nativeView != null }
        repeat(8) {
            compose.runOnIdle {
                assertFalse(nativeView!!.requestFocus())
                assertFalse(nativeView!!.hasFocus())
                revision++
            }
            compose.waitForIdle()
            compose.runOnIdle { nativeView = null; revision++ }
            compose.waitUntil(10000) { nativeView != null }
        }
    }

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
