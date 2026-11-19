package me.rerere.rikkahub.ui.components.ui

import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import me.rerere.rikkahub.data.datastore.*
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.ui.context.appearanceSurfaceOpacityPolicy
import me.rerere.rikkahub.ui.theme.micaColorScheme
import me.rerere.rikkahub.ui.theme.micaPalette
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.*
import org.junit.Test

class MicaBackgroundPolicyTest {
    @Test fun `mica keeps wallpaper and adjustable surface opacity without blur`() {
        val theme = lightColorScheme()
        val scheme = micaColorScheme(theme, micaPalette(theme, false), true, .42f)
        assertEquals(Color.Transparent, scheme.background)
        assertEquals(.42f, scheme.surface.alpha, .001f)
        val policy = appearanceSurfaceOpacityPolicy(.42f, .5f, .6f, .6f, true, BackgroundSurfaceStyle.MICA)
        assertEquals(.42f, policy.card, 0f)
        assertEquals(.5f, policy.topBar, 0f)
        assertFalse(BackgroundSurfaceStyle.MICA.usesBlur)
        assertEquals(0f, resolveMaterialSurface(BackgroundSurfaceStyle.MICA, .42f, 20f, true, true, true, 40f).blurRadius)
    }

    @Test fun `mica falls back only without an effective background source`() {
        assertFalse((null as AppearanceBackgroundSpec?).hasMicaBackground())
        assertFalse(AppearanceBackgroundSpec("wallpaper", 0f, 0f).hasMicaBackground())
        assertTrue(AppearanceBackgroundSpec("wallpaper", 1f, 0f).hasMicaBackground())
        assertTrue(AppearanceBackgroundSpec(null, 1f, 0f, useGradientBackground = true).hasMicaBackground())
        assertEquals(1f, micaSurfaceOpacity(.4f, false))
        assertEquals(.4f, micaSurfaceOpacity(.4f, true))
        assertEquals(.68f, micaSurfaceOpacity(Float.NaN, true))
    }

    @Test fun `mica never changes background selection precedence`() {
        val assistant = Assistant(background = "assistant.png")
        val base = Settings(assistants = listOf(assistant), assistantId = assistant.id)
        val mica = base.copy(advancedAppearanceSetting = base.advancedAppearanceSetting.copy(pageSurfaceStyle = BackgroundSurfaceStyle.MICA))
        assertEquals("assistant.png", mica.resolveChatBackground().background)
        val global = mica.copy(advancedAppearanceSetting = mica.advancedAppearanceSetting.copy(
            enableGlobalBackground = true, globalBackground = "global.png"))
        assertEquals(base.copy(advancedAppearanceSetting = global.advancedAppearanceSetting.copy(
            pageSurfaceStyle = BackgroundSurfaceStyle.TRANSLUCENT)).resolveChatBackground().background,
            global.resolveChatBackground().background)
        assertEquals("global.png", global.advancedAppearanceSetting.globalBackground)
    }

    @Test fun `top bar cover defaults off and survives serialization`() {
        assertFalse(JsonInstant.decodeFromString<DisplaySetting>("{}").showTopBarBackground)
        val display = DisplaySetting(showTopBarBackground = true)
        assertTrue(JsonInstant.decodeFromString<DisplaySetting>(JsonInstant.encodeToString(DisplaySetting.serializer(), display)).showTopBarBackground)
        assertFalse(shouldApplyTopBarBlur(true, true, true, true, true, backgroundEnabled = false))
    }
}
