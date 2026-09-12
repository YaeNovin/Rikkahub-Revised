package me.rerere.rikkahub

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ColorSpace
import android.graphics.HardwareRenderer
import android.graphics.PixelFormat
import android.graphics.RenderNode
import android.hardware.HardwareBuffer
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import coil3.ImageLoader
import coil3.Image
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.size.Size
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.ui.components.richtext.IntrinsicSvgDecoderFactory
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@SdkSuppress(minSdkVersion = 29)
class BadgeHardwareCanvasTest {
    @Test fun badgePixelsSurviveHardwareCanvasScalingAndClipping() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val loader = ImageLoader.Builder(context).build()
        try {
            val bytes = """<svg xmlns="http://www.w3.org/2000/svg" width="88" height="20"><rect width="37" height="20" fill="#555"/><rect x="37" width="51" height="20" fill="#44bb00"/></svg>""".toByteArray()
            val result = loader.execute(ImageRequest.Builder(context).data(bytes).size(Size.ORIGINAL)
                .decoderFactory(IntrinsicSvgDecoderFactory).build())
            assertTrue(result.toString(), result is SuccessResult)
            val bitmap = renderBadgeOnHardwareCanvas(requireNotNull(result.image))
            try {
                assertEquals("gray label", Color.rgb(85, 85, 85), bitmap.getPixel(65, 75))
                assertEquals("green value", Color.rgb(68, 187, 0), bitmap.getPixel(275, 75))
                assertEquals("outside clipping area", Color.MAGENTA, bitmap.getPixel(400, 150))
                saveBadgeProbeBitmap(context, bitmap, "hardware-after")
            } finally { bitmap.recycle() }
            // Capture the previous factory through exactly the same GPU pipeline.
            val oldResult = loader.execute(ImageRequest.Builder(context).data(bytes).size(Size.ORIGINAL)
                .memoryCachePolicy(coil3.request.CachePolicy.DISABLED)
                .decoderFactory(coil3.svg.SvgDecoder.Factory(scaleToDensity = false, renderToBitmap = false)).build())
            val before = renderBadgeOnHardwareCanvas(requireNotNull(oldResult.image))
            try { saveBadgeProbeBitmap(context, before, "hardware-before") } finally { before.recycle() }
        } finally { loader.shutdown() }
    }
}

internal fun renderBadgeOnHardwareCanvas(image: Image): Bitmap {
    val reader = ImageReader.newInstance(880, 200, PixelFormat.RGBA_8888, 2,
        HardwareBuffer.USAGE_GPU_COLOR_OUTPUT or HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE)
    val renderer = HardwareRenderer()
    val callbackThread = HandlerThread("badge-pixel-check").apply { start() }
    try {
        val node = RenderNode("badge-regression").apply { setPosition(0, 0, 880, 200) }
        val canvas = node.beginRecording()
        assertTrue(canvas.isHardwareAccelerated)
        canvas.drawColor(Color.MAGENTA)
        canvas.save()
        canvas.translate(30f, 40f)
        canvas.scale(3.5f, 3.5f)
        canvas.clipRect(0f, 0f, image.width.toFloat(), image.height.toFloat())
        image.draw(canvas)
        canvas.restore()
        node.endRecording()
        val ready = CountDownLatch(1)
        reader.setOnImageAvailableListener({ ready.countDown() }, Handler(callbackThread.looper))
        renderer.setSurface(reader.surface)
        renderer.setContentRoot(node)
        renderer.createRenderRequest().setWaitForPresent(true).syncAndDraw()
        assertTrue("GPU frame unavailable", ready.await(5, TimeUnit.SECONDS))
        requireNotNull(reader.acquireLatestImage()).use { frame ->
            requireNotNull(frame.hardwareBuffer).use { buffer ->
                val hardwareBitmap = requireNotNull(Bitmap.wrapHardwareBuffer(buffer, ColorSpace.get(ColorSpace.Named.SRGB)))
                try { return requireNotNull(hardwareBitmap.copy(Bitmap.Config.ARGB_8888, false)) }
                finally { hardwareBitmap.recycle() }
            }
        }
    } finally {
        renderer.destroy()
        reader.close()
        callbackThread.quitSafely()
    }
}

internal fun saveBadgeProbeBitmap(context: android.content.Context, bitmap: Bitmap, name: String) {
    val file = File(context.getExternalFilesDir(null), "badge-regression/$name.png")
    file.parentFile?.mkdirs()
    file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
}
