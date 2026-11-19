package me.rerere.rikkahub

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import me.rerere.rikkahub.data.datastore.BackgroundSurfaceStyle
import me.rerere.rikkahub.data.datastore.TextColorMode
import me.rerere.rikkahub.ui.components.ui.GlobalGlassTheme
import me.rerere.rikkahub.ui.components.ui.LocalAppearanceBackground
import me.rerere.rikkahub.ui.theme.LocalTextColorMode
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Reuses MaterialTheme's remembered ColorScheme across mode changes. No user data or wallpaper. */
class TextPaletteSwitchTest {
    @get:Rule val compose = createComposeRule()

    @Test fun glassPaletteFollowsForegroundChangesWithoutStaleBlack() {
        val color = mutableStateOf(Color.Black)
        val mode = mutableStateOf(TextColorMode.THEME)
        var observed = Color.Unspecified
        compose.setContent {
            MaterialTheme(colorScheme = darkColorScheme(onSurface = color.value)) {
                CompositionLocalProvider(LocalTextColorMode provides mode.value, LocalAppearanceBackground provides null) {
                    GlobalGlassTheme(true, .5f, BackgroundSurfaceStyle.LIQUID_GLASS) {
                        val foreground = MaterialTheme.colorScheme.onSurface
                        SideEffect { observed = foreground }
                    }
                }
            }
        }
        for ((nextMode, nextColor) in listOf(TextColorMode.THEME to Color.Black,
            TextColorMode.SYSTEM_WALLPAPER to Color(0xFFBCCAFF),
            TextColorMode.APP_BACKGROUND to Color(0xFFFFB9C5),
            TextColorMode.AUTO_CLEAR to Color.White,
            TextColorMode.THEME to Color.Black)) {
            compose.runOnIdle { mode.value = nextMode; color.value = nextColor }
            compose.waitForIdle()
            compose.runOnIdle { assertEquals(nextColor, observed) }
        }
    }
}
