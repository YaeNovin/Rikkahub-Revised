package me.rerere.rikkahub.ui.components.ui

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.min
import kotlin.math.roundToInt

private val MAX_HEIGHT = 10000.dp
private val MAX_WIDTH = 10000.dp
internal const val MAX_EXPORT_BITMAP_PIXELS = 8_000_000L
private const val MAX_EXPORT_BITMAP_DIMENSION = 8192
val LocalExportContext = staticCompositionLocalOf { false }

internal fun requireExportBitmapSize(width: Int, height: Int) {
    require(width > 0 && height > 0) { "Export image has no content" }
    require(width <= MAX_EXPORT_BITMAP_DIMENSION && height <= MAX_EXPORT_BITMAP_DIMENSION) { "Export image dimension is too large" }
    require(width.toLong() * height.toLong() <= MAX_EXPORT_BITMAP_PIXELS) {
        "Export image is too large. Export fewer messages and try again."
    }
}

internal data class ExportImageSlice(val top: Int, val height: Int)

/** Exact, non-overlapping coverage; the memory limit applies per image, not to layout. */
internal fun exportImageSlices(width: Int, height: Int): List<ExportImageSlice> {
    require(width in 1..MAX_EXPORT_BITMAP_DIMENSION && height > 0)
    require(height <= 200_000) { "对话内容过长，请分批选择消息导出；未截断内容" }
    val pageHeight = min(MAX_EXPORT_BITMAP_DIMENSION, (MAX_EXPORT_BITMAP_PIXELS / width).toInt())
    return generateSequence(0) { top -> (top + pageHeight).takeIf { it < height } }
        .map { ExportImageSlice(it, min(pageHeight, height - it)) }.toList()
}

class BitmapComposer {
    suspend fun composableToBitmap(
        activity: Activity,
        width: Dp? = null,
        height: Dp? = null,
        screenDensity: Density,
        content: @Composable () -> Unit,
    ): Bitmap {
        var result: Bitmap? = null
        capture(activity, width, height, screenDensity, false, { bitmap, _, _ -> result = bitmap }, content)
        return requireNotNull(result)
    }

    /** Callback must consume the bitmap before returning; only one page is allocated at a time. */
    suspend fun composableToImages(
        activity: Activity,
        width: Dp,
        screenDensity: Density,
        onPage: suspend (bitmap: Bitmap, index: Int, count: Int) -> Unit,
        content: @Composable () -> Unit,
    ): List<String> = capture(activity, width, null, screenDensity, true, onPage, content)

    private suspend fun capture(
        activity: Activity, width: Dp?, height: Dp?, screenDensity: Density,
        paged: Boolean, onPage: suspend (Bitmap, Int, Int) -> Unit,
        content: @Composable () -> Unit,
    ): List<String> = withContext(Dispatchers.Main.immediate) {
        val requestedMaxWidth = (screenDensity.density * (width ?: MAX_WIDTH).value).roundToInt()
        val requestedMaxHeight = (screenDensity.density * (height ?: MAX_HEIGHT).value).roundToInt()
        val maxWidth = min(requestedMaxWidth, MAX_EXPORT_BITMAP_DIMENSION).coerceAtLeast(1)
        if (width != null && height != null) {
            requireExportBitmapSize(requestedMaxWidth, requestedMaxHeight)
        }
        val widthSpec = View.MeasureSpec.makeMeasureSpec(maxWidth, if (width == null) View.MeasureSpec.AT_MOST else View.MeasureSpec.EXACTLY)
        // AT_MOST with the bitmap limit silently truncated a long Column. Measure
        // its natural height first, then split drawing into bounded bitmaps.
        val heightSpec = View.MeasureSpec.makeMeasureSpec(if (height == null) 0 else requestedMaxHeight,
            if (height == null) View.MeasureSpec.UNSPECIFIED else View.MeasureSpec.EXACTLY)
        val tracker = ExportRenderTracker()
        val container = FrameLayout(activity).apply {
            layoutParams = ViewGroup.LayoutParams(maxWidth, ViewGroup.LayoutParams.WRAP_CONTENT)
            // INVISIBLE prevents WebView's first frame. Hide the attached parent
            // visually, then capture its child without inheriting parent alpha.
            alpha = 0f
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        }
        val composeView = ComposeView(activity).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent {
                CompositionLocalProvider(LocalDensity provides screenDensity, LocalExportContext provides true,
                    LocalExportRenderTracker provides tracker) { content() }
            }
        }
        val decor = activity.window.decorView as ViewGroup
        try {
            container.addView(composeView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            decor.addView(container)
            fun layout() {
                container.measure(widthSpec, heightSpec)
                container.layout(0, 0, container.measuredWidth, container.measuredHeight)
                tracker.layoutChanged(composeView.width, composeView.height)
            }
            layout()
            val ready = withTimeoutOrNull(45_000) {
                do {
                    delay(32)
                    layout()
                    tracker.failure?.let { error("图形尚未完成渲染，未导出图片：$it") }
                } while (!tracker.settled())
                true
            }
            check(ready == true) { "导出等待内容加载超时，请减少消息数量后重试" }
            layout()
            val capturedWidth = composeView.width
            val capturedHeight = composeView.height
            val slices = if (paged) exportImageSlices(composeView.width, composeView.height) else {
                requireExportBitmapSize(composeView.width, composeView.height)
                listOf(ExportImageSlice(0, composeView.height))
            }
            slices.forEachIndexed { index, slice ->
                // Encoding/saving suspends: the window may have remeasured the
                // attached host to screen height in the meantime.
                layout()
                check(composeView.width == capturedWidth && composeView.height == capturedHeight) {
                    "内容在导出期间发生变化，请等待加载完成后重新导出"
                }
                requireExportBitmapSize(composeView.width, slice.height)
                val bitmap = Bitmap.createBitmap(composeView.width, slice.height, Bitmap.Config.ARGB_8888)
                try {
                    val canvas = Canvas(bitmap)
                    canvas.translate(0f, -slice.top.toFloat())
                    composeView.draw(canvas)
                    onPage(bitmap, index, slices.size)
                } catch (error: Throwable) {
                    bitmap.recycle()
                    throw error
                } finally {
                    if (paged && !bitmap.isRecycled) bitmap.recycle()
                }
            }
            tracker.warnings.toList()
        } finally {
            decor.removeView(container)
            composeView.disposeComposition()
        }
    }
}
