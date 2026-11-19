package me.rerere.material3

import org.junit.Assert.*
import org.junit.Test
import javax.imageio.ImageIO

class WallpaperReferenceColorTest {
    @Test fun `reference wallpapers retain a supported representative color`() {
        for (name in listOf("pink", "night", "sunset", "pastel")) {
            val image = ImageIO.read(javaClass.getResourceAsStream("/wallpaper-reference/$name.png"))
            val pixels = image.getRGB(0, 0, image.width, image.height, null, 0, image.width)
            val analyzed = analyzeImageColors(pixels)
            val groups = pixels.groupBy { (hctColorFromArgb(it).hue / 30).toInt() }
                .mapValues { (_, colors) -> colors.count { hctColorFromArgb(it).chroma >= 5 } }
            println("$name hueGroups=$groups")
            assertNotNull(name, analyzed.seedArgb)
            val hct = hctColorFromArgb(analyzed.seedArgb!!)
            println("$name seed=${analyzed.seedArgb.toUInt().toString(16)} hue=${hct.hue} chroma=${hct.chroma}")
            assertTrue(name, hct.chroma >= 5)
            val crop = centerImageCrop(image.width, image.height, .45f)
            val cropped = image.getRGB(crop.left, crop.top, crop.width, crop.height, null, 0, crop.width)
            val portraitSeed = analyzeImageColors(cropped).seedArgb!!
            val portraitHct = hctColorFromArgb(portraitSeed)
            println("$name portrait=${portraitSeed.toUInt().toString(16)} hue=${portraitHct.hue}")
            assertTrue("$name portrait", portraitHct.chroma >= 5)
            val supported = cropped.count { color ->
                val sample = hctColorFromArgb(color)
                val d = kotlin.math.abs(sample.hue - portraitHct.hue)
                sample.chroma >= 5 && minOf(d, 360 - d) <= 20
            }
            assertTrue("$name seed must represent a source color family", supported >= cropped.size * .02)
        }
    }
    @Test fun `portrait analysis excludes the unseen sides of a landscape wallpaper`() {
        assertEquals(ImageCrop(575, 0, 450, 1000), centerImageCrop(1600, 1000, .45f))
        assertEquals(ImageCrop(0, 375, 1000, 1250), centerImageCrop(1000, 2000, .8f))
        assertEquals(ImageCrop(0, 0, 100, 100), centerImageCrop(100, 100, Float.NaN))
    }
}
