package me.rerere.rikkahub

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.ui.pages.setting.MicaAppearancePreview
import me.rerere.rikkahub.ui.pages.setting.MicaDemoSamples
import me.rerere.rikkahub.ui.pages.setting.MicaPreviewScene
import me.rerere.rikkahub.ui.theme.micaPalette
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class MicaPreviewDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun captureLightDarkAndBackgroundComparisons() {
        var dark by mutableStateOf(false)
        var useBackground by mutableStateOf(false)
        compose.setContent {
            val scheme = if (dark) darkColorScheme() else lightColorScheme()
            MaterialTheme(colorScheme = scheme) {
                MicaPreviewScene(micaPalette(scheme, dark, if (useBackground) MicaDemoSamples else emptyList()), Modifier.width(360.dp))
            }
        }
        val folder = File(InstrumentationRegistry.getInstrumentation().targetContext.externalCacheDir, "mica-preview").apply { mkdirs() }
        val sampledPixels = mutableSetOf<Int>()
        for (darkMode in listOf(false, true)) for (background in listOf(false, true)) {
            compose.runOnIdle { dark = darkMode; useBackground = background }
            compose.waitForIdle()
            val captured = compose.onNodeWithTag("mica-preview-scene").captureToImage()
            val pixels = captured.toPixelMap()
            assertEquals(1f, pixels[captured.width / 2, 4].alpha)
            val bitmap = captured.asAndroidBitmap()
            sampledPixels += bitmap.getPixel(captured.width / 2, 4)
            val name = "mica-${if (darkMode) "dark" else "light"}-${if (background) "background" else "theme"}.png"
            File(folder, name).outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        }
        assertEquals(4, sampledPixels.size)
    }

    @Test fun noWallpaperStillAllowsPreviewAndAppearanceSwitching() {
        val assistant = Assistant(background = null, useGradientBackground = false)
        val settings = Settings(assistantId = assistant.id, assistants = listOf(assistant))
        compose.setContent {
            MaterialTheme {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) { MicaAppearancePreview(settings) }
            }
        }
        compose.onNodeWithText("当前背景").assertIsNotEnabled()
        compose.onNodeWithText("示例色调").performClick()
        compose.onNodeWithText("示例色调").assertIsSelected()
        compose.onNodeWithText("深色").performClick()
        compose.onNodeWithText("深色").assertIsSelected()
        compose.onNodeWithText("无背景").performClick()
        compose.onNodeWithText("无背景").assertIsSelected()
        compose.onNodeWithTag("mica-preview-scene").performScrollTo().assertIsDisplayed()
    }
}
