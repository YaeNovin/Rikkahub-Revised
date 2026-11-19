package me.rerere.rikkahub.ui.components.ui

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.Window
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.suspendCancellableCoroutine
import me.rerere.rikkahub.utils.getActivity
import kotlin.coroutines.resume
import kotlin.math.roundToInt

internal data class AcrylicSceneSnapshot(val image: ImageBitmap, val origin: Offset, val size: IntSize, val samples: List<Color>)

internal fun acrylicSnapshotSize(width: Int, height: Int): IntSize {
    if (width <= 0 || height <= 0) return IntSize.Zero
    val scale = minOf(1f, 1280f / maxOf(width, height))
    return IntSize((width * scale).roundToInt().coerceAtLeast(1), (height * scale).roundToInt().coerceAtLeast(1))
}

/** PixelCopy reads the activity window, not the dialog/popup or the screen compositor.
 * One bounded bitmap per open overlay; no polling, disk writes, or wallpaper replay. */
private suspend fun captureAcrylicScene(window: Window): AcrylicSceneSnapshot? {
    val view = window.decorView
    if (!view.isAttachedToWindow || !view.isHardwareAccelerated ||
        window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0) return null
    val size = IntSize(view.width, view.height)
    val target = acrylicSnapshotSize(size.width, size.height)
    if (target == IntSize.Zero) return null
    val position = IntArray(2).also(view::getLocationOnScreen)
    val bitmap = Bitmap.createBitmap(target.width, target.height, Bitmap.Config.ARGB_8888)
    return suspendCancellableCoroutine { continuation ->
        try {
            PixelCopy.request(window, bitmap, { result ->
                if (result == PixelCopy.SUCCESS && continuation.isActive &&
                    window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE == 0 &&
                    view.width == size.width && view.height == size.height) {
                    val samples = List(64) { index ->
                        Color(bitmap.getPixel(((index % 8 + .5f) * bitmap.width / 8).toInt().coerceAtMost(bitmap.width - 1),
                            ((index / 8 + .5f) * bitmap.height / 8).toInt().coerceAtMost(bitmap.height - 1)))
                    }
                    continuation.resume(AcrylicSceneSnapshot(bitmap.asImageBitmap(),
                        Offset(position[0].toFloat(), position[1].toFloat()), size, samples))
                } else {
                    bitmap.recycle()
                    if (continuation.isActive) continuation.resume(null)
                }
            }, Handler(Looper.getMainLooper()))
        } catch (_: RuntimeException) {
            bitmap.recycle()
            if (continuation.isActive) continuation.resume(null)
        }
    }
}

@Composable
internal fun rememberAcrylicSceneSnapshot(enabled: Boolean): AcrylicSceneSnapshot? {
    val window = LocalContext.current.getActivity()?.window
    val configuration = LocalConfiguration.current
    // Layout/orientation changes discard the old coordinate space immediately.
    var snapshot by remember(window, configuration.screenWidthDp, configuration.screenHeightDp, enabled) {
        mutableStateOf<AcrylicSceneSnapshot?>(null)
    }
    LaunchedEffect(window, configuration.screenWidthDp, configuration.screenHeightDp, enabled) {
        if (!enabled || window == null) return@LaunchedEffect
        withFrameNanos { }
        snapshot = captureAcrylicScene(window)
        if (snapshot == null) {
            withFrameNanos { }
            snapshot = captureAcrylicScene(window)
        }
    }
    return snapshot
}
