package me.rerere.rikkahub

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.datastore.TextColorMode
import me.rerere.rikkahub.ui.components.ui.SwipeRevealActions
import me.rerere.rikkahub.ui.theme.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class AppearanceInteractionRegressionTest {
    @get:Rule val compose = createComposeRule()

    @Test fun textModeRetriesFailedSamplingThenReusesSuccessfulSamples() {
        var mode by mutableStateOf(TextColorMode.AUTO_CLEAR)
        var calls = 0
        var foreground: Color? = null
        var samples: List<Color>? = null
        val image = listOf(Color(0xFF172238))
        compose.setContent {
            val loaded = rememberReadabilitySamples(BackgroundSampleKey("test://wallpaper", 1f, Color.Black), mode) {
                calls++
                if (calls == 1) null else image
            }
            val seed = textPaletteSeed(mode, loaded?.let(::backgroundTextSeed), Color.Red.toArgb())
            val text = backgroundTextForeground(mode, loaded ?: listOf(Color.Black), seed, Color.Cyan, true)
            SideEffect { samples = loaded; foreground = text }
        }
        compose.waitUntil(5000) { calls == 1 }
        compose.runOnIdle { assertNull(samples); mode = TextColorMode.APP_BACKGROUND }
        compose.waitUntil(5000) { samples == image }
        val appColor = foreground
        compose.runOnIdle { mode = TextColorMode.SYSTEM_WALLPAPER }
        compose.waitForIdle()
        assertNotEquals(appColor, foreground)
        compose.runOnIdle { mode = TextColorMode.THEME }
        compose.waitForIdle()
        assertEquals(Color.Cyan, foreground)
        compose.runOnIdle { mode = TextColorMode.AUTO_CLEAR }
        compose.waitForIdle()
        assertEquals(Color.White, foreground)
        assertEquals(2, calls)
        assertEquals(image, samples)
    }

    @Test fun changingWallpaperCannotReuseOrPublishOldSamples() {
        var source by mutableStateOf("first")
        val pending = CompletableDeferred<List<Color>?>()
        var samples: List<Color>? = null
        var started = false
        compose.setContent {
            val key = BackgroundSampleKey(source, 1f, Color.Black)
            val loaded = rememberReadabilitySamples(key, TextColorMode.APP_BACKGROUND) {
                if (key.source == "first") { started = true; pending.await() } else listOf(Color.Blue)
            }
            SideEffect { samples = loaded }
        }
        compose.waitUntil(5000) { started }
        compose.runOnIdle { source = "second" }
        compose.waitUntil(5000) { samples == listOf(Color.Blue) }
        pending.complete(listOf(Color.Red))
        compose.waitForIdle()
        assertEquals(listOf(Color.Blue), samples)
    }

    @Test fun readableThemeRefreshesComponentRolesWhenForegroundDoesNotChange() {
        var alternate by mutableStateOf(false)
        var mode by mutableStateOf(TextColorMode.APP_BACKGROUND)
        var primary: Color? = null
        var text: Color? = null
        compose.setContent {
            val theme = darkColorScheme(primary = if (alternate) Color.Green else Color.Red,
                onSurface = Color.Cyan)
            MaterialTheme(colorScheme = theme) {
                CompositionLocalProvider(LocalTextColorMode provides mode) {
                    BackgroundReadabilityTheme(active = true, foreground = Color.White) {
                        val color = MaterialTheme.colorScheme.primary
                        val foreground = MaterialTheme.colorScheme.onSurface
                        SideEffect { primary = color; text = foreground }
                    }
                }
            }
        }
        compose.runOnIdle { assertEquals(Color.Red, primary); assertEquals(Color.White, text); alternate = true }
        compose.waitForIdle()
        assertEquals(Color.Green, primary)
        compose.runOnIdle { mode = TextColorMode.THEME }
        compose.waitForIdle()
        assertEquals(Color.Cyan, text)
        assertEquals(Color.Green, primary)
    }

    @Test fun translucentForegroundDoesNotExposeActionAtRestAndResetRemovesIt() {
        lateinit var state: SwipeToDismissBoxState
        lateinit var scope: CoroutineScope
        var clicked = false
        compose.setContent {
            MaterialTheme {
                state = rememberSwipeToDismissBoxState()
                scope = rememberCoroutineScope()
                SwipeToDismissBox(state = state, enableDismissFromStartToEnd = false,
                    backgroundContent = { SwipeRevealActions(state) { TextButton(onClick = { clicked = true }, modifier = Modifier.testTag("delete")) { Text("Delete") } } },
                    modifier = Modifier.width(300.dp).height(90.dp)) {
                    Card(colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = .35f)), modifier = Modifier.fillMaxSize()) { Text("Foreground") }
                }
            }
        }
        compose.onNodeWithTag("delete").assertDoesNotExist()
        compose.runOnIdle { scope.launch { state.snapTo(SwipeToDismissBoxValue.EndToStart) } }
        compose.waitForIdle()
        compose.onNodeWithTag("delete").assertIsDisplayed().performClick()
        assertTrue(clicked)
        compose.runOnIdle { scope.launch { state.reset() } }
        compose.waitForIdle()
        compose.onNodeWithTag("delete").assertDoesNotExist()
    }
}
