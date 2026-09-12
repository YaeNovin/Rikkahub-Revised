package me.rerere.rikkahub

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import me.rerere.rikkahub.ui.context.Navigator
import me.rerere.rikkahub.ui.components.richtext.LocalRichTextLayoutReadiness
import me.rerere.rikkahub.ui.components.richtext.ReportRichTextLayoutLoading
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import me.rerere.rikkahub.ui.pages.chat.ChatPreviewReturnState
import me.rerere.rikkahub.ui.pages.chat.rememberChatPreviewReturn
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Requires a device: recreate the navigation entry while long rich content remeasures. */
class ChatPreviewReturnRegressionTest {
    @get:Rule val compose = createComposeRule()

    @Test fun returnsToMessageAndPixelOffsetAfterPlaceholderRelayout() {
        var preview by mutableStateOf(false)
        var contentReady = false
        lateinit var list: LazyListState
        lateinit var restore: ChatPreviewReturnState
        val keys = (0..20).map { "message-$it" }
        compose.setContent {
            val holder = rememberSaveableStateHolder()
            if (!preview) holder.SaveableStateProvider("chat") {
                val state = rememberLazyListState()
                val returnState = rememberChatPreviewReturn("chat", state, keys, true)
                var loaded by remember { mutableStateOf(false) }
                LaunchedEffect(Unit) { delay(400); loaded = true }
                SideEffect { list = state; restore = returnState; contentReady = loaded }
                LazyColumn(Modifier.fillMaxSize(), state = state) {
                    items(keys, key = { it }) { Box(Modifier.height(if (loaded) 1400.dp else 80.dp)) }
                }
            } else Box(Modifier.fillMaxSize())
        }
        compose.waitForIdle()
        compose.waitUntil(3000) { contentReady }
        compose.runOnIdle { list.requestScrollToItem(7, 650) }
        compose.waitForIdle()
        compose.runOnIdle { restore.capture(list); preview = true }
        compose.waitForIdle()
        compose.runOnIdle { preview = false }
        compose.waitUntil(3000) { contentReady }
        compose.waitUntil(6000) { restore.anchor == null && !restore.restoring }
        compose.runOnIdle {
            assertEquals(7, list.firstVisibleItemIndex)
            assertEquals(650, list.firstVisibleItemScrollOffset)
        }
    }

    @Test fun navigationReturnWaitsForLongMessageParsingAndWorksRepeatedly() {
        val chat = Screen.Chat("00000000-0000-0000-0000-000000000001")
        val keys = (0..20).map { "message-$it" }
        lateinit var nav: Navigator
        lateinit var list: LazyListState
        lateinit var restore: ChatPreviewReturnState
        var rendered = false
        var visits = 0
        compose.setContent {
            val stack = rememberNavBackStack(chat)
            val navigator = remember(stack) { Navigator(stack) }
            SideEffect { nav = navigator }
            NavDisplay(
                backStack = stack,
                onBack = navigator::popBackStack,
                entryDecorators = listOf(rememberSaveableStateHolderNavEntryDecorator()),
                entryProvider = entryProvider {
                    entry<Screen.Chat> {
                        val state = rememberLazyListState()
                        val returning = rememberChatPreviewReturn("chat", state, keys, true, navigator.currentScreen == chat)
                        var loaded by remember { mutableStateOf(false) }
                        LaunchedEffect(Unit) { visits++; delay(if (visits == 1) 100 else 3500); loaded = true }
                        SideEffect { list = state; restore = returning; rendered = loaded }
                        LazyColumn(Modifier.fillMaxSize(), state = state) {
                            items(keys, key = { it }) { key ->
                                CompositionLocalProvider(LocalRichTextLayoutReadiness provides
                                    returning.takeIf { it.anchor?.key == key }?.layoutReadiness) {
                                    ReportRichTextLayoutLoading(!loaded)
                                    Box(Modifier.height(if (loaded) 1400.dp else 80.dp))
                                }
                            }
                        }
                    }
                    entry<Screen.WebView> { Box(Modifier.fillMaxSize()) }
                },
            )
        }
        compose.waitUntil(4000) { rendered }
        repeat(2) {
            compose.runOnIdle { list.requestScrollToItem(7, 1000) }
            compose.waitForIdle()
            compose.runOnIdle { restore.capture(list); nav.navigate(Screen.WebView(contentId = "test-$it")) }
            compose.waitForIdle()
            // Exit transition must finish, otherwise this exercises only retained composition.
            compose.mainClock.advanceTimeBy(600)
            compose.runOnIdle { nav.popBackStack() }
            compose.waitUntil(8000) { rendered && restore.anchor == null && !restore.restoring }
            compose.runOnIdle {
                assertEquals(7, list.firstVisibleItemIndex)
                assertEquals(1000, list.firstVisibleItemScrollOffset)
            }
        }
    }
}
