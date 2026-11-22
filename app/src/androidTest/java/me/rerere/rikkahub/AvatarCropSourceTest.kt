package me.rerere.rikkahub

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.core.net.toUri
import androidx.exifinterface.media.ExifInterface
import androidx.test.platform.app.InstrumentationRegistry
import com.yalantis.ucrop.task.BitmapCropTask
import me.rerere.rikkahub.utils.ImageUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AvatarCropSourceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Suppress("DEPRECATION")
    @Test
    fun webpWithJpegExtensionCanBeCroppedByNativeUCrop() {
        val source = File.createTempFile("avatar_source_", ".jpg", context.cacheDir)
        val prepared = File.createTempFile("avatar_prepared_", ".png", context.cacheDir)
        val cropped = File.createTempFile("avatar_cropped_", ".png", context.cacheDir)
        val bitmap = Bitmap.createBitmap(96, 64, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(Color.TRANSPARENT)
            bitmap.setPixel(0, 0, Color.RED)
            source.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.WEBP, 100, it)) }

            ImageUtils.prepareAvatarCropSource(context, source.toUri(), prepared)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(prepared.path, bounds)
            assertEquals("image/png", bounds.outMimeType)
            val decoded = requireNotNull(BitmapFactory.decodeFile(prepared.path))
            try {
                assertEquals(0, Color.alpha(decoded.getPixel(20, 20)))
            } finally {
                decoded.recycle()
            }

            assertTrue(
                BitmapCropTask.cropCImg(
                    prepared.path, cropped.path, 16, 0, 64, 64, 0f, 1f,
                    Bitmap.CompressFormat.PNG.ordinal, 100, 0, 1,
                ),
            )
            BitmapFactory.decodeFile(cropped.path, bounds)
            assertEquals("image/png", bounds.outMimeType)
            assertEquals(64, bounds.outWidth)
            assertEquals(64, bounds.outHeight)
        } finally {
            bitmap.recycle()
            source.delete()
            prepared.delete()
            cropped.delete()
        }
    }

    @Test
    fun appliesExifOrientationBeforeWritingPng() {
        val source = File.createTempFile("avatar_source_", ".jpg", context.cacheDir)
        val prepared = File.createTempFile("avatar_prepared_", ".png", context.cacheDir)
        val bitmap = Bitmap.createBitmap(80, 40, Bitmap.Config.ARGB_8888)
        try {
            source.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 100, it)) }
            ExifInterface(source.path).apply {
                setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
                saveAttributes()
            }

            ImageUtils.prepareAvatarCropSource(context, source.toUri(), prepared)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(prepared.path, bounds)
            assertEquals(40, bounds.outWidth)
            assertEquals(80, bounds.outHeight)
        } finally {
            bitmap.recycle()
            source.delete()
            prepared.delete()
        }
    }
}
