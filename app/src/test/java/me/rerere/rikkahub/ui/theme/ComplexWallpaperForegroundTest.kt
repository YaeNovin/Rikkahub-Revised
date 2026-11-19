package me.rerere.rikkahub.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.toArgb
import java.io.File
import javax.imageio.ImageIO
import me.rerere.material3.analyzeImageColors
import me.rerere.material3.centerImageCrop
import me.rerere.material3.hctColorFromArgb
import org.junit.Assert.*
import org.junit.Test

class ComplexWallpaperForegroundTest {
    @Test fun `all reference images keep their own hue across photo and glass surfaces`() {
        for (name in listOf("pink", "night", "sunset", "pastel")) {
            val image = ImageIO.read(File("../material3/src/test/resources/wallpaper-reference/$name.png"))
            val full = image.getRGB(0, 0, image.width, image.height, null, 0, image.width)
            val crop = centerImageCrop(image.width, image.height, .45f)
            val displayed = image.getRGB(crop.left, crop.top, crop.width, crop.height, null, 0, crop.width)
            val raw = List(240) { index ->
                val x = ((index % 12 + .5f) * crop.width / 12).toInt().coerceAtMost(crop.width - 1)
                val y = ((index / 12 + .5f) * crop.height / 20).toInt().coerceAtMost(crop.height - 1)
                Color(displayed[y * crop.width + x])
            }
            val samples = backgroundSamplesWithOverlay(raw, Color(0xFF15111A), .28f, .42f)
            val seeds = listOf(analyzeImageColors(full).seedArgb!!, analyzeImageColors(displayed).seedArgb!!)
            for (surface in listOf(Color.Transparent, Color(0xFF413738).copy(alpha = .35f), Color(0xFF17233B))) {
                val backgrounds = samples.map { surface.compositeOver(it) }
                for (seed in seeds) {
                    val color = textForegroundFor(backgrounds, seed)
                    println("$name seed=${seed.toUInt().toString(16)} surface=$surface foreground=${color.toArgb().toUInt().toString(16)} chroma=${hctColorFromArgb(color.toArgb()).chroma}")
                    assertNotEquals("$name color was discarded", readableForegroundColor(backgrounds), color)
                    assertTrue("$name hue disappeared", hasVisiblePaletteHue(color))
                    assertTrue("$name contrast correction changed selected hue", contrastingTextColor(color, backgrounds) == color)
                }
            }
        }
    }

    @Test fun `experimental chroma is eleven percent above the previous target`() {
        for (source in listOf(6.0, 18.0, 32.0, 70.0)) {
            assertEquals((source * 1.35).coerceIn(40.0, 56.0) * 1.11, textPaletteChroma(source), .00001)
        }
        assertEquals(0.0, textPaletteChroma(0.0), 0.0)
    }
}
