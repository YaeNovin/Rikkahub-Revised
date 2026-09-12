package me.rerere.rikkahub.ui.modifier

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import me.rerere.rikkahub.ui.components.ui.LocalExportContext
import me.rerere.rikkahub.ui.components.ui.rememberSystemMotionAllowed

/** A low-opacity color band behind the summary text; never covers the reasoning body. */
@Composable
internal fun Modifier.reasoningFlow(active: Boolean): Modifier {
    if (!active || LocalExportContext.current) return this
    val motionAllowed = rememberSystemMotionAllowed()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val view = LocalView.current
    var resumed by remember(lifecycle) { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    var visible by remember { mutableStateOf(false) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, _ -> resumed = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val positioned = onGloballyPositioned { coordinates ->
        val bounds = coordinates.boundsInWindow()
        visible = bounds.width > 0 && bounds.height > 0 && bounds.bottom > 0 && bounds.top < view.height
    }
    if (!resumed || !visible || !motionAllowed) return positioned
    val transition = rememberInfiniteTransition(label = "ReasoningFlow")
    val progress = transition.animateFloat(0f, 1f,
        infiniteRepeatable(tween(2400, easing = LinearEasing)), label = "ReasoningFlowOffset")
    val scheme = MaterialTheme.colorScheme
    val colors = remember(scheme.primary, scheme.secondary, scheme.tertiary) {
        listOf(Color.Transparent, scheme.primary.copy(alpha = .13f), scheme.tertiary.copy(alpha = .23f),
            scheme.secondary.copy(alpha = .13f), Color.Transparent)
    }
    return positioned.drawBehind {
        if (size.width <= 0f || size.height <= 0f) return@drawBehind
        val band = size.width * .8f
        val x = -band + progress.value * (size.width + band)
        drawRect(Brush.linearGradient(colors, Offset(x, 0f), Offset(x + band, size.height)))
    }
}
