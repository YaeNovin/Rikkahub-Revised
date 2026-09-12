package me.rerere.rikkahub.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.Assert.*
import me.rerere.rikkahub.data.datastore.AdvancedAppearanceSetting
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.TextColorMode
import me.rerere.rikkahub.data.datastore.isAutoAccentActive

class ThemeColorSourceTest {
    private val configured = Settings(dynamicColor = true, advancedAppearanceSetting = AdvancedAppearanceSetting(
        enableGlobalBackground = true, globalBackground = "file:///background.jpg", enableAutoAccent = true,
        autoAccentColorArgb = 0xFF336699, textColorMode = TextColorMode.APP_BACKGROUND,
    ))

    @Test fun `explicit manual theme selection exits both overriding sources and preserves text and background settings`() {
        val selected = configured.selectTheme("sakura")
        assertFalse(selected.dynamicColor)
        assertFalse(selected.isAutoAccentActive())
        assertEquals(configured.advancedAppearanceSetting.copy(enableAutoAccent = false), selected.advancedAppearanceSetting)
        assertEquals("sakura", selected.themeId)
        assertEquals(ThemeColorSource.SelectedTheme, resolveThemeColorSource(
            selected.advancedAppearanceSetting.autoAccentColorArgb.takeIf { selected.isAutoAccentActive() }, selected.dynamicColor, true))
    }

    @Test fun `dynamic color and background accent can be switched and disabled without reverting text mode`() {
        val dynamic = configured.selectDynamicColors(true)
        assertTrue(dynamic.dynamicColor)
        assertFalse(dynamic.isAutoAccentActive())
        val accent = dynamic.selectBackgroundAccent(true)
        assertFalse(accent.dynamicColor)
        assertTrue(accent.isAutoAccentActive())
        val disabled = accent.selectBackgroundAccent(false)
        assertTrue(disabled.dynamicColor)
        assertFalse(disabled.isAutoAccentActive())
        assertEquals(TextColorMode.APP_BACKGROUND, disabled.advancedAppearanceSetting.textColorMode)
        assertEquals(configured.advancedAppearanceSetting.globalBackground, disabled.advancedAppearanceSetting.globalBackground)
        val restoredAccent = configured.selectDynamicColors(false)
        assertTrue(restoredAccent.isAutoAccentActive())
        assertFalse(restoredAccent.dynamicColor)
    }
    @Test
    fun `active auto accent wins over supported system dynamic color`() {
        assertEquals(
            ThemeColorSource.AutoAccent(0xFF336699),
            resolveThemeColorSource(
                activeAutoAccentColorArgb = 0xFF336699,
                dynamicColorEnabled = true,
                systemDynamicColorSupported = true,
            ),
        )
    }

    @Test
    fun `system dynamic color wins when enabled and supported without auto accent`() {
        assertEquals(
            ThemeColorSource.SystemDynamic,
            resolveThemeColorSource(
                activeAutoAccentColorArgb = null,
                dynamicColorEnabled = true,
                systemDynamicColorSupported = true,
            ),
        )
    }

    @Test
    fun `selected theme is used when dynamic color is disabled`() {
        assertEquals(
            ThemeColorSource.SelectedTheme,
            resolveThemeColorSource(
                activeAutoAccentColorArgb = null,
                dynamicColorEnabled = false,
                systemDynamicColorSupported = true,
            ),
        )
    }

    @Test
    fun `selected theme is used when system dynamic color is unsupported`() {
        assertEquals(
            ThemeColorSource.SelectedTheme,
            resolveThemeColorSource(
                activeAutoAccentColorArgb = null,
                dynamicColorEnabled = true,
                systemDynamicColorSupported = false,
            ),
        )
    }

    @Test
    fun `inactive auto accent cannot override the selected theme`() {
        assertEquals(
            ThemeColorSource.SelectedTheme,
            resolveThemeColorSource(
                activeAutoAccentColorArgb = null,
                dynamicColorEnabled = false,
                systemDynamicColorSupported = false,
            ),
        )
    }
}
