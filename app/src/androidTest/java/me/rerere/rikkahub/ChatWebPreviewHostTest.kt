package me.rerere.rikkahub

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavKey
import me.rerere.rikkahub.ui.components.webview.ChatWebPreviewHost
import me.rerere.rikkahub.ui.components.webview.LocalChatWebPreview
import me.rerere.rikkahub.ui.components.webview.WebViewContentCache
import me.rerere.rikkahub.ui.components.webview.rememberWebPreviewLauncher
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.context.Navigator
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ChatWebPreviewHostTest {
    @get:Rule val compose = createComposeRule()

    @Test fun realPreviewKeepsListAndOffsetWithoutNavigating() {
        lateinit var list: LazyListState
        lateinit var open: (() -> String) -> Unit
        var previewId by mutableStateOf<String?>(null)
        var created = 0
        var disposed = 0
        val stack = mutableStateListOf<NavKey>(Screen.Chat("test-chat"))
        compose.setContent {
            val state = rememberLazyListState()
            SideEffect { list = state }
            CompositionLocalProvider(LocalNavController provides remember { Navigator(stack) },
                me.rerere.rikkahub.ui.context.LocalToaster provides com.dokar.sonner.rememberToasterState(),
                me.rerere.rikkahub.ui.context.LocalSettings provides me.rerere.rikkahub.data.datastore.Settings(),
                LocalChatWebPreview provides { id -> previewId = id }) {
                ChatWebPreviewHost(previewId) { previewId = null }
                val launch = rememberWebPreviewLauncher()
                SideEffect { open = launch }
                LazyColumn(Modifier.fillMaxSize(), state = state) {
                    items((0..20).toList(), key = { it }) {
                        Box(Modifier.height(1200.dp)) {
                            DisposableEffect(Unit) { created++; onDispose { disposed++ } }
                            BasicText("Message $it")
                        }
                    }
                }
            }
        }
        compose.runOnIdle { list.requestScrollToItem(7, 850) }
        compose.waitForIdle()
        var savedCreated = 0
        var savedDisposed = 0
        lateinit var sameList: LazyListState
        compose.runOnIdle { savedCreated = created; savedDisposed = disposed; sameList = list }
        repeat(3) {
            compose.runOnIdle { open { "<html><body><svg width='200' height='100'><rect width='200' height='100' fill='teal'/></svg></body></html>" } }
            compose.waitUntil(10_000) { previewId != null }
            compose.waitForIdle()
            compose.runOnIdle {
                assertEquals(1, stack.size)
                assertSame(sameList, list)
                previewId = null
            }
            compose.waitForIdle()
            compose.runOnIdle {
                assertEquals(7, list.firstVisibleItemIndex)
                assertEquals(850, list.firstVisibleItemScrollOffset)
                assertEquals(savedCreated, created)
                assertEquals(savedDisposed, disposed)
            }
        }
    }
}
