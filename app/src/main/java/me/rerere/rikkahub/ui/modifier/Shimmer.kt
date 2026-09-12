package me.rerere.rikkahub.ui.modifier

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.*
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import me.rerere.rikkahub.ui.components.ui.LocalExportContext
import me.rerere.rikkahub.ui.components.ui.rememberSystemMotionAllowed
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode

/** Adds a moving highlight band while a component is loading. */
@Composable
fun Modifier.shimmer(
    isLoading: Boolean,
    shimmerColor: Color = LocalContentColor.current.copy(alpha = 0.3f),
    // Transparent edges preserve the component's own background and content.
    backgroundColor: Color = Color.Transparent,
    durationMillis: Int = 1200,
    angle: Float = 20f,
    gradientWidthRatio: Float = 0.5f,
): Modifier = composed {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val view = LocalView.current
    var resumed by remember(lifecycle) { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    var visible by remember { mutableStateOf(false) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, _ -> resumed = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val positioned = this.onGloballyPositioned {
        val bounds = it.boundsInWindow()
        visible = bounds.width > 0 && bounds.height > 0 && bounds.bottom > 0 && bounds.top < view.height
    }
    if (!isLoading || !resumed || !visible || LocalExportContext.current || !rememberSystemMotionAllowed()) {
        positioned
    } else {
        val transition = rememberInfiniteTransition(label = "ShimmerTransition")
        val translateAnimation = transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(
                    durationMillis = durationMillis.coerceAtLeast(1),
                    easing = LinearEasing,
                ),
                repeatMode = RepeatMode.Restart,
            ),
            label = "ShimmerTranslate",
        )
        val angleRad = Math.toRadians(if (angle.isFinite()) angle.toDouble() else 20.0).toFloat()
        val colors = remember(shimmerColor, backgroundColor) {
            listOf(backgroundColor, shimmerColor, backgroundColor)
        }

        positioned.drawWithContent {
            // DrawScope.size is available on the first frame and after rotations.
            val width = size.width
            val height = size.height
            drawContent()
            if (width <= 0f || height <= 0f) return@drawWithContent

            val diagonal = kotlin.math.sqrt(width * width + height * height)
            val gradientWidth = (diagonal * (gradientWidthRatio.takeIf { it.isFinite() } ?: .5f).coerceIn(.05f, 2f))
                .coerceAtLeast(1f)
            val direction = Offset(kotlin.math.cos(angleRad), kotlin.math.sin(angleRad))
            val currentOffset = shimmerBandOffset(width, height, direction, gradientWidth, translateAnimation.value)
            val center = Offset(width / 2f, height / 2f)
            val start = center + direction * currentOffset
            val end = center + direction * (currentOffset + gradientWidth)
            val shimmerBrush = Brush.linearGradient(
                colors = colors,
                start = start,
                end = end,
                tileMode = TileMode.Clamp,
            )

            // DstIn only changes alpha, making the highlight effectively invisible.
            // SrcOver overlays the animated color while retaining the skeleton below.
            drawRect(brush = shimmerBrush)
        }
    }
}

internal fun shimmerBandOffset(width: Float, height: Float, direction: Offset, bandWidth: Float, progress: Float): Float {
    val halfExtent = (kotlin.math.abs(direction.x) * width + kotlin.math.abs(direction.y) * height) / 2f
    return -halfExtent - bandWidth + progress.coerceIn(0f, 1f) * (2f * halfExtent + bandWidth)
}
