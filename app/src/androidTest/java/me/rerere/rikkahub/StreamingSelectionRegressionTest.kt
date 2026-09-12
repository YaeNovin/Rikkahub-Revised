package me.rerere.rikkahub

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.ui.components.message.StreamingSelectionContainer
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class StreamingSelectionRegressionTest {
    @get:Rule val compose = createComposeRule()

    @Test fun finishingAndResumingDoesNotRecreateLongMessageOrResetItsOffset() {
        var streaming by mutableStateOf(true)
        var text by mutableStateOf("first chunk")
        var created = 0
        var disposed = 0
        lateinit var list: LazyListState
        compose.setContent {
            val state = rememberLazyListState()
            SideEffect { list = state }
            LazyColumn(Modifier.fillMaxSize(), state = state) {
                item("message") {
                    StreamingSelectionContainer(streaming) {
                        DisposableEffect(Unit) { created++; onDispose { disposed++ } }
                        Box(Modifier.height(2500.dp)) { BasicText(text) }
                    }
                }
            }
        }
        compose.runOnIdle { list.requestScrollToItem(0, 900) }
        compose.waitForIdle()
        repeat(3) {
            compose.runOnIdle { text += " next"; streaming = false }
            compose.waitForIdle()
            compose.runOnIdle {
                assertEquals(1, created)
                assertEquals(0, disposed)
                assertEquals(900, list.firstVisibleItemScrollOffset)
                streaming = true
            }
            compose.waitForIdle()
        }
    }
}
