package me.rerere.rikkahub

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.*
import me.rerere.rikkahub.ui.components.message.MessagePartsBlock
import me.rerere.rikkahub.ui.components.message.messageTextPalette
import me.rerere.rikkahub.ui.components.ui.*
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.ui.theme.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import kotlin.math.abs

class TextPaletteStateRegressionTest {
    @get:Rule val compose = createComposeRule()

    @Test fun paletteAndPageHandoffsPreserveRememberedStateAndScroll() {
        var mode by mutableStateOf(TextColorMode.THEME)
        var active by mutableStateOf(false)
        var material by mutableStateOf(BackgroundSurfaceStyle.LIQUID_GLASS)
        var mounts = 0
        var disposals = 0
        lateinit var list: LazyListState
        compose.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                CompositionLocalProvider(LocalSettings provides Settings(), LocalTextColorMode provides mode) {
                    GlobalGlassTheme(active, .35f, material) {
                        DisposableEffect(Unit) { mounts++; onDispose { disposals++ } }
                        val state = rememberLazyListState()
                        SideEffect { list = state }
                        LazyColumn(Modifier.fillMaxSize(), state = state) {
                            items(100, key = { it }) { Text("Row $it", Modifier.height(60.dp)) }
                        }
                    }
                }
            }
        }
        compose.runOnIdle { list.requestScrollToItem(35, 12) }
        compose.waitForIdle()
        val original = list
        for (style in listOf(BackgroundSurfaceStyle.LIQUID_GLASS, BackgroundSurfaceStyle.MICA, BackgroundSurfaceStyle.OPAQUE)) {
            for (next in TextColorMode.entries + TextColorMode.THEME) {
                compose.runOnIdle { material = style; mode = next; active = !active }
                compose.waitForIdle()
                compose.runOnIdle {
                    assertSame(original, list)
                    assertEquals(35, list.firstVisibleItemIndex)
                    assertEquals(12, list.firstVisibleItemScrollOffset)
                    assertEquals(1, mounts)
                    assertEquals(0, disposals)
                }
            }
        }
    }

    @Test fun actualMessageBodyAndUserBubbleFollowBothSeedsInDarkMode() {
        var mode by mutableStateOf(TextColorMode.APP_BACKGROUND)
        val theme = darkColorScheme(background = Color(0xFF10131A),
            primaryContainer = Color(0xFF26304A), onPrimaryContainer = Color.White, onSurface = Color.White)
        val background = listOf(theme.background)
        val appSeed = 0xFF7755CC.toInt()
        val wallpaperSeed = 0xFF008855.toInt()
        compose.setContent {
            val settings = Settings().let { it.copy(advancedAppearanceSetting = it.advancedAppearanceSetting.copy(
                enableGlobalBackground = true, globalBackground = "fixture", applyGlobalBackgroundToChat = true,
                enableBubblePerformanceEffects = false, textColorMode = mode)) }
            MaterialTheme(colorScheme = theme) {
                CompositionLocalProvider(LocalSettings provides settings, LocalTextColorMode provides mode,
                    LocalBaseThemeColorScheme provides theme,
                    LocalWallpaperTextSeed provides WallpaperTextSeed(wallpaperSeed),
                    LocalAppearanceBackground provides AppearanceBackgroundSpec("fixture", 1f, 0f,
                        readability = BackgroundReadability(Color.White, background, seedArgb = appSeed))) {
                    Column(Modifier.fillMaxSize().background(theme.background)) {
                        MessagePartsBlock(null, MessageRole.USER, null, null,
                            listOf(UIMessagePart.Text("User palette 123")), emptyList(), false)
                        MessagePartsBlock(null, MessageRole.ASSISTANT, null, null,
                            listOf(UIMessagePart.Text("Body palette 123")), emptyList(), false)
                    }
                }
            }
        }
        for (next in listOf(TextColorMode.APP_BACKGROUND, TextColorMode.SYSTEM_WALLPAPER, TextColorMode.THEME)) {
            compose.runOnIdle { mode = next }
            for ((text, container) in listOf("User palette 123" to theme.primaryContainer, "Body palette 123" to Color.Transparent)) {
                val expected = messageTextPalette(next, container, background,
                    if (next == TextColorMode.APP_BACKGROUND) appSeed else wallpaperSeed, Color.White).foreground
                compose.waitUntil(5000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
                val pixels = compose.onNodeWithText(text).captureToImage().toPixelMap()
                var matches = 0
                for (y in 0 until pixels.height) for (x in 0 until pixels.width) {
                    val c = pixels[x, y]
                    if (abs(c.red - expected.red) < .025f && abs(c.green - expected.green) < .025f && abs(c.blue - expected.blue) < .025f) matches++
                }
                assertTrue("$next / $text: no selected text color in rendered pixels", matches > 6)
            }
        }
    }
}
