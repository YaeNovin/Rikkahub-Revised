package me.rerere.rikkahub.ui.components.ui

import android.app.Activity
import android.graphics.Bitmap
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.view.drawToBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.math.roundToInt

private val MAX_HEIGHT = 10000.dp
private val MAX_WIDTH = 10000.dp
val LocalExportContext = staticCompositionLocalOf { false }

class BitmapComposer {
    suspend fun composableToBitmap(
        activity: Activity,
        width: Dp? = null,
        height: Dp? = null,
        screenDensity: Density,
        content: @Composable () -> Unit,
    ): Bitmap = withContext(Dispatchers.Main.immediate) {
        val maxWidth = (screenDensity.density * (width ?: MAX_WIDTH).value).roundToInt()
        val maxHeight = (screenDensity.density * (height ?: MAX_HEIGHT).value).roundToInt()
        val widthSpec = View.MeasureSpec.makeMeasureSpec(maxWidth, if (width == null) View.MeasureSpec.AT_MOST else View.MeasureSpec.EXACTLY)
        val heightSpec = View.MeasureSpec.makeMeasureSpec(maxHeight, if (height == null) View.MeasureSpec.AT_MOST else View.MeasureSpec.EXACTLY)
        val tracker = ExportRenderTracker()
        val container = FrameLayout(activity).apply {
            layoutParams = ViewGroup.LayoutParams(maxWidth, maxHeight)
            // INVISIBLE prevents WebView's first frame. Hide the attached parent
            // visually, then capture its child without inheriting parent alpha.
            alpha = 0f
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        }
        val composeView = ComposeView(activity).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent {
                CompositionLocalProvider(LocalExportContext provides true, LocalExportRenderTracker provides tracker) { content() }
            }
        }
        val decor = activity.window.decorView as ViewGroup
        try {
            container.addView(composeView)
            decor.addView(container)
            fun layout() {
                container.measure(widthSpec, heightSpec)
                container.layout(0, 0, container.measuredWidth, container.measuredHeight)
            }
            layout()
            withTimeout(30_000) {
                do {
                    delay(32)
                    layout()
                    tracker.failure?.let { error("图形尚未完成渲染，未导出图片：$it") }
                } while (!tracker.settled())
            }
            layout()
            check(composeView.width > 0 && composeView.height > 0) { "没有可导出的内容" }
            composeView.drawToBitmap()
        } finally {
            decor.removeView(container)
            composeView.disposeComposition()
        }
    }
}
