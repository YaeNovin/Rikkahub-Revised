package me.rerere.rikkahub

import androidx.test.platform.app.InstrumentationRegistry
import coil3.ImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.size.Size
import me.rerere.rikkahub.ui.components.richtext.IntrinsicSvgDecoderFactory
import me.rerere.rikkahub.ui.components.richtext.toInlineImageBitmap
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class BadgeDecoderTest {
    @Test fun embeddedVectorLogoIsVisible() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val loader = ImageLoader.Builder(context).build()
        val logo = """<svg xmlns="http://www.w3.org/2000/svg" fill="white" viewBox="0 0 24 24"><path d="M24 24H0V0h24L12 12Z"/></svg>"""
        val encoded = java.util.Base64.getEncoder().encodeToString(logo.toByteArray())
        try {
            val bytes = """<svg xmlns="http://www.w3.org/2000/svg" width="94" height="28"><rect width="94" height="28" fill="#7f52ff"/><image x="9" y="7" width="14" height="14" href="data:image/svg+xml;base64,$encoded"/></svg>""".toByteArray()
            val result = loader.execute(ImageRequest.Builder(context).data(bytes).size(Size.ORIGINAL)
                .decoderFactory(IntrinsicSvgDecoderFactory).build())
            assertTrue(result.toString(), result is SuccessResult)
            val bitmap = requireNotNull(result.image).toInlineImageBitmap(376, 112)
            try {
                assertEquals("logo's left filled region", android.graphics.Color.WHITE, bitmap.getPixel(11 * 4, 14 * 4))
                assertEquals("background next to the logo", android.graphics.Color.rgb(127, 82, 255), bitmap.getPixel(30 * 4, 14 * 4))
            } finally { bitmap.recycle() }
        } finally { loader.shutdown() }
    }

    @Test fun badgeDrawsInsideItsOwnBoundsOnLargerCanvas() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val loader = ImageLoader.Builder(context).build()
        try {
            val bytes = """<svg xmlns="http://www.w3.org/2000/svg" width="88" height="20"><rect width="37" height="20" fill="#555"/><rect x="37" width="51" height="20" fill="#44bb00"/></svg>""".toByteArray()
            val result = loader.execute(ImageRequest.Builder(context).data(bytes).size(Size.ORIGINAL)
                .decoderFactory(IntrinsicSvgDecoderFactory).build())
            assertTrue(result.toString(), result is SuccessResult)
            val image = requireNotNull(result.image)
            // Compose translates/clips the screen canvas; Canvas.width/height
            // remain the SCREEN size, not the badge's measured size.
            val bitmap = android.graphics.Bitmap.createBitmap(500, 300, android.graphics.Bitmap.Config.ARGB_8888)
            try {
                bitmap.eraseColor(android.graphics.Color.MAGENTA)
                val canvas = android.graphics.Canvas(bitmap)
                canvas.save()
                canvas.translate(30f, 40f)
                canvas.clipRect(0f, 0f, 88f, 20f)
                image.draw(canvas)
                canvas.restore()
                assertEquals("left badge half", android.graphics.Color.rgb(85, 85, 85), bitmap.getPixel(40, 50))
                assertEquals("right badge half must not be stretched gray", android.graphics.Color.rgb(68, 187, 0), bitmap.getPixel(100, 50))
                assertEquals("outside badge", android.graphics.Color.MAGENTA, bitmap.getPixel(200, 100))
            } finally { bitmap.recycle() }
        } finally { loader.shutdown() }
    }

    @Test fun scaledViewBoxDoesNotOverrideCssSizeAndMissingDimensionsStillWork() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val loader = ImageLoader.Builder(context).build()
        try {
            for ((dimensions, expected) in listOf(
                "width=\"88\" height=\"20\" viewBox=\"0 0 880 200\"" to (88 to 20),
                "viewBox=\"0 0 88 20\"" to (88 to 20),
                "height=\"20\" viewBox=\"0 0 880 200\"" to (88 to 20),
            )) {
                val bytes = """<svg xmlns="http://www.w3.org/2000/svg" $dimensions><rect width="100%" height="100%" fill="#4c1"/></svg>""".toByteArray()
                val result = loader.execute(ImageRequest.Builder(context).data(bytes).size(800, 600)
                    .decoderFactory(IntrinsicSvgDecoderFactory).build())
                assertTrue(result.toString(), result is SuccessResult)
                val image = requireNotNull(result.image)
                assertEquals(dimensions, expected.first, image.width)
                assertEquals(dimensions, expected.second, image.height)
            }
        } finally { loader.shutdown() }
    }

    @Test fun pngBadgeStillDecodesWithInlineSvgPolicy() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val bitmap = android.graphics.Bitmap.createBitmap(88, 20, android.graphics.Bitmap.Config.ARGB_8888)
        val bytes = java.io.ByteArrayOutputStream().use {
            bitmap.eraseColor(android.graphics.Color.GREEN)
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            it.toByteArray()
        }
        bitmap.recycle()
        val loader = ImageLoader.Builder(context).build()
        try {
            val result = loader.execute(ImageRequest.Builder(context).data(bytes).size(Size.ORIGINAL)
                .decoderFactory(IntrinsicSvgDecoderFactory).build())
            assertTrue("PNG should not be forced through SVG: $result", result is SuccessResult)
        } finally { loader.shutdown() }
    }

    @Test fun badgeKeepsCssDimensionsAndRendersAtPreviewResolution() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val loader = ImageLoader.Builder(context).build()
        try {
            val bytes = """<svg xmlns="http://www.w3.org/2000/svg" width="88" height="20"><rect width="88" height="20" fill="#4c1"/><text x="4" y="14" fill="white">passing</text></svg>""".toByteArray()
            // Unknown badge hosts get layout constraints too, not only Size.ORIGINAL.
            val request = ImageRequest.Builder(context).data(bytes).size(800, 600)
                .decoderFactory(IntrinsicSvgDecoderFactory).build()
            val result = loader.execute(request)
            assertTrue(result is SuccessResult)
            val image = requireNotNull(result.image)
            assertEquals(88, image.width); assertEquals(20, image.height)
            val bitmap = image.toInlineImageBitmap(880, 200)
            try {
                assertEquals(880, bitmap.width); assertEquals(200, bitmap.height)
                assertTrue(android.graphics.Color.green(bitmap.getPixel(850, 100)) > 150)
            } finally { bitmap.recycle() }
        } finally { loader.shutdown() }
    }
}
