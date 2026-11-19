package me.rerere.rikkahub

import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import me.rerere.rikkahub.data.datastore.AdvancedAppearanceSetting
import me.rerere.rikkahub.data.datastore.DisplaySetting
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.LiquidGlassRenderer
import me.rerere.rikkahub.data.model.LiquidGlassSettings
import me.rerere.rikkahub.ui.components.ai.ChatComposerSurface
import me.rerere.rikkahub.ui.components.ui.LocalGlassBackdrop
import me.rerere.rikkahub.ui.context.LocalSettings
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import kotlin.math.abs

/** Uses synthetic content only. No wallpaper, model, network or user conversation is needed. */
class ComposerSceneBlurTest {
    @get:Rule val compose = createComposeRule()

    @Test fun glassComposerStillBlursMessagesWithNoWallpaperCapture() {
        assumeTrue(Build.VERSION.SDK_INT >= 31)
        val radius = mutableStateOf(0f)
        compose.setContent {
            val source = rememberHazeState()
            val settings = Settings(
                displaySetting = DisplaySetting(enableBlurEffect = true, inputBlurRadius = radius.value, inputSurfaceOpacity = 0f),
                advancedAppearanceSetting = AdvancedAppearanceSetting(liquidGlass = LiquidGlassSettings(
                    applyToComposer = true, renderer = LiquidGlassRenderer.STANDARD, adaptivePerformance = false)),
            )
            MaterialTheme {
                CompositionLocalProvider(LocalSettings provides settings, LocalGlassBackdrop provides null) {
                    Box(Modifier.size(200.dp, 120.dp).testTag("scene")) {
                        Canvas(Modifier.fillMaxSize().hazeSource(source)) {
                            val stripe = 8.dp.toPx()
                            repeat((size.width / stripe).toInt() + 1) { index ->
                                drawRect(if (index % 2 == 0) Color.Black else Color.White,
                                    Offset(index * stripe, 0f), Size(stripe, size.height))
                            }
                        }
                        ChatComposerSurface(settings, source, RoundedCornerShape(12.dp), Modifier.fillMaxSize()) { }
                    }
                }
            }
        }
        compose.waitForIdle()
        val sharp = edgeEnergy(compose.onNodeWithTag("scene").captureToImage())
        compose.runOnIdle { radius.value = 20f }
        compose.waitForIdle()
        val blurred = edgeEnergy(compose.onNodeWithTag("scene").captureToImage())
        assertTrue("blur should reduce stripe edges: sharp=$sharp, blurred=$blurred", blurred < sharp * .7f)
        compose.runOnIdle { radius.value = 0f }
        compose.waitForIdle()
        val restored = edgeEnergy(compose.onNodeWithTag("scene").captureToImage())
        assertTrue("strength remains adjustable without wallpaper", restored > blurred * 1.4f)
    }

    private fun edgeEnergy(image: ImageBitmap): Float {
        val pixels = image.toPixelMap()
        val y = image.height / 2
        val left = image.width / 4
        val right = image.width * 3 / 4
        return (left until right).sumOf { x -> abs(pixels[x + 1, y].red - pixels[x, y].red).toDouble() }.toFloat()
    }
}
