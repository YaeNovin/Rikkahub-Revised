package me.rerere.rikkahub.ui.components.ui

import android.animation.ValueAnimator
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import android.os.SystemClock
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import me.rerere.rikkahub.data.datastore.GradientRendererMode
import me.rerere.rikkahub.data.model.GradientBackgroundPreset
import me.rerere.rikkahub.data.model.GradientBackgroundCustomColors
import me.rerere.rikkahub.ui.theme.LocalDarkMode
import me.rerere.rikkahub.ui.theme.GradientBlobColor
import me.rerere.rikkahub.ui.theme.GradientBackgroundPalette
import me.rerere.rikkahub.ui.theme.createGradientBackgroundPalette
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

@Immutable
data class GradientBackgroundSpec(
    val opacity: Float = 1f,
    val animationEnabled: Boolean = true,
    val speed: Float = 1f,
    val followTheme: Boolean = false,
    val preset: GradientBackgroundPreset = GradientBackgroundPreset.CLASSIC,
    val customColors: GradientBackgroundCustomColors = GradientBackgroundCustomColors(),
    val intensity: Float = 1f,
    val motionScale: Float = 1f,
    val blobCount: Int = 4,
    val softness: Float = 1f,
    val angle: Float = 0f,
    val vignette: Float = 0f,
    val performanceEffectsEnabled: Boolean = true,
    val rendererMode: GradientRendererMode = GradientRendererMode.AUTO,
    val interactionInProgress: Boolean = false,
    val respectSystemReducedMotion: Boolean = true,
)

internal const val AGSL_GRADIENT_MIN_SDK = 33

internal enum class GradientRendererBackend {
    AGSL,
    KOTLIN,
}

internal fun resolveGradientRendererBackend(
    requestedMode: GradientRendererMode,
    sdkInt: Int,
    runtimeFailed: Boolean = false,
): GradientRendererBackend = when (requestedMode) {
    GradientRendererMode.AUTO,
    GradientRendererMode.AGSL -> if (sdkInt >= AGSL_GRADIENT_MIN_SDK && !runtimeFailed) {
        GradientRendererBackend.AGSL
    } else {
        GradientRendererBackend.KOTLIN
    }

    GradientRendererMode.KOTLIN -> GradientRendererBackend.KOTLIN
}

internal interface AgslGradientRenderer {
    val isUsable: Boolean

    fun draw(
        scope: DrawScope,
        elapsedMillis: Long,
        palette: GradientBackgroundPalette,
        opacity: Float,
        intensity: Float,
        motionScale: Float,
        blobCount: Int,
        softness: Float,
        angle: Float,
        vignette: Float,
    ): Boolean
}

@Immutable
internal data class GradientRenderProfile(
    val animationEnabled: Boolean,
    val frameIntervalMillis: Long,
    val blobCount: Int,
    val motionScale: Float,
)

@Immutable
internal data class GradientLayoutProfile(
    val verticalShift: Float,
    val radiusScale: Float,
    val horizontalMotionScale: Float,
    val verticalMotionScale: Float,
)

internal fun resolveGradientLayoutProfile(
    width: Float,
    height: Float,
): GradientLayoutProfile {
    if (width <= 0f || height <= 0f) {
        return GradientLayoutProfile(0f, 1f, 1f, 1f)
    }
    return when (width / height) {
        in 0f..0.72f -> GradientLayoutProfile(
            verticalShift = 0f,
            radiusScale = 1f,
            horizontalMotionScale = 1f,
            verticalMotionScale = 1f,
        )

        in 0.72f..1.30f -> GradientLayoutProfile(
            verticalShift = 0.06f,
            radiusScale = 0.86f,
            horizontalMotionScale = 0.90f,
            verticalMotionScale = 0.84f,
        )

        else -> GradientLayoutProfile(
            verticalShift = 0.14f,
            radiusScale = 0.72f,
            horizontalMotionScale = 0.78f,
            verticalMotionScale = 0.66f,
        )
    }
}

internal fun resolveGradientRenderProfile(
    support: AdvancedAppearanceSupport,
    rendererBackend: GradientRendererBackend = GradientRendererBackend.KOTLIN,
    animationRequested: Boolean,
    performanceEffectsEnabled: Boolean,
    respectSystemReducedMotion: Boolean,
    systemMotionAllowed: Boolean,
    requestedMotionScale: Float,
    interactionInProgress: Boolean = false,
    requestedBlobCount: Int = 4,
): GradientRenderProfile {
    val (kotlinFrameInterval, supportedBlobCount, deviceMotionScale) = when (support) {
        AdvancedAppearanceSupport.FULL -> Triple(66L, 4, 1f)
        AdvancedAppearanceSupport.REDUCED -> Triple(100L, 3, 0.68f)
        AdvancedAppearanceSupport.UNSUPPORTED -> Triple(140L, 2, 0.42f)
    }
    val safeMotionScale = finiteAppearanceValue(requestedMotionScale, 0.25f, 1.5f, 1f) * deviceMotionScale
    val canAnimate = animationRequested &&
        performanceEffectsEnabled &&
        (!respectSystemReducedMotion || systemMotionAllowed)
    return GradientRenderProfile(
        animationEnabled = canAnimate,
        frameIntervalMillis = if (interactionInProgress) {
            if (rendererBackend == GradientRendererBackend.AGSL) 66L else maxOf(100L, kotlinFrameInterval)
        } else if (rendererBackend == GradientRendererBackend.AGSL) {
            33L
        } else {
            kotlinFrameInterval
        },
        blobCount = requestedBlobCount.coerceIn(
            0,
            if (performanceEffectsEnabled) supportedBlobCount else minOf(2, supportedBlobCount),
        ),
        motionScale = safeMotionScale,
    )
}

@Composable
fun AnimatedGradientBackground(
    spec: GradientBackgroundSpec,
    modifier: Modifier = Modifier,
    baseColor: Color = MaterialTheme.colorScheme.background,
    content: @Composable BoxScope.() -> Unit = {},
) {
    val dark = LocalDarkMode.current
    val colorScheme = MaterialTheme.colorScheme
    val capabilities = LocalAdvancedAppearanceCapabilities.current
    val requestedRendererBackend = remember(spec.rendererMode, capabilities.sdkInt, AgslGradientRuntime.failed) {
        resolveGradientRendererBackend(spec.rendererMode, capabilities.sdkInt, AgslGradientRuntime.failed)
    }
    val agslRenderer = remember(requestedRendererBackend) {
        if (requestedRendererBackend == GradientRendererBackend.AGSL) {
            createAgslGradientRendererOrNull()
        } else {
            null
        }
    }
    val rendererBackend = if (agslRenderer?.isUsable == true) {
        GradientRendererBackend.AGSL
    } else {
        GradientRendererBackend.KOTLIN
    }
    val systemMotionAllowed = if (
        spec.respectSystemReducedMotion
    ) {
        rememberSystemMotionAllowed()
    } else {
        true
    }
    val profile = remember(
        capabilities.blurSupport,
        rendererBackend,
        spec.animationEnabled,
        spec.performanceEffectsEnabled,
        spec.respectSystemReducedMotion,
        systemMotionAllowed,
        spec.motionScale,
        spec.interactionInProgress,
        spec.blobCount,
    ) {
        resolveGradientRenderProfile(
            support = capabilities.blurSupport,
            rendererBackend = rendererBackend,
            animationRequested = spec.animationEnabled,
            performanceEffectsEnabled = spec.performanceEffectsEnabled,
            respectSystemReducedMotion = spec.respectSystemReducedMotion,
            systemMotionAllowed = systemMotionAllowed,
            requestedMotionScale = spec.motionScale,
            interactionInProgress = spec.interactionInProgress,
            requestedBlobCount = spec.blobCount,
        )
    }
    val targetPalette = remember(colorScheme, dark, spec.followTheme, spec.preset, spec.customColors) {
        createGradientBackgroundPalette(
            colorScheme = colorScheme,
            dark = dark,
            followTheme = spec.followTheme,
            preset = spec.preset,
            customColors = spec.customColors,
        )
    }
    val palette = if (systemMotionAllowed && spec.performanceEffectsEnabled) animateGradientPalette(targetPalette) else targetPalette
    val elapsedMillis = rememberGradientAnimationElapsedMillis(
        enabled = profile.animationEnabled,
        frameIntervalMillis = profile.frameIntervalMillis,
    )
    val safeSpeed = finiteAppearanceValue(spec.speed, 0.5f, 2f, 1f)
    val safeOpacity = finiteAppearanceValue(spec.opacity, 0f, 1f, 1f)
    val safeIntensity = finiteAppearanceValue(spec.intensity, 0.5f, 1.5f, 1f)
    val safeSoftness = finiteAppearanceValue(spec.softness, 0.55f, 1.5f, 1f)
    val safeAngle = finiteAppearanceValue(spec.angle, -180f, 180f, 0f)
    val safeVignette = finiteAppearanceValue(spec.vignette, 0f, 1f, 0f)
    val baseColorStops = remember(palette, safeOpacity) {
        palette.baseStops
            .map { (position, color) -> position to color.copy(alpha = safeOpacity) }
            .toTypedArray()
    }
    val visibleBlobs = remember(palette, profile.blobCount) {
        palette.blobs.take(profile.blobCount)
    }
    val blobColors = remember(visibleBlobs) { visibleBlobs.map { it.color } }
    val blobAlphas = remember(visibleBlobs, safeIntensity, safeOpacity) {
        visibleBlobs.map {
            (it.alpha * safeIntensity * safeOpacity).coerceIn(0f, 0.88f)
        }
    }
    val gradientDrawModifier = remember(
        elapsedMillis,
        safeSpeed,
        baseColor,
        baseColorStops,
        safeAngle,
        safeVignette,
        rendererBackend,
        agslRenderer,
        palette,
        safeOpacity,
        safeIntensity,
        profile,
        safeSoftness,
        blobColors,
        blobAlphas,
    ) {
        Modifier
            .fillMaxSize()
            .drawWithCache {
                if (size.width <= 0f || size.height <= 0f) return@drawWithCache onDrawBehind { }
                // Cache size-dependent brushes; elapsed time is read only by the draw block.
                val baseGradientBrush = Brush.linearGradient(
                    colorStops = baseColorStops,
                    start = size.gradientAxisPoint(0f, safeAngle),
                    end = size.gradientAxisPoint(1f, safeAngle),
                )
                val vignetteBrush = if (safeVignette > 0f) {
                    Brush.radialGradient(
                        colorStops = arrayOf(
                            0f to Color.Transparent,
                            0.58f to Color.Transparent,
                            1f to Color.Black.copy(alpha = safeVignette * safeOpacity * 0.28f),
                        ),
                        center = Offset(size.width / 2f, size.height / 2f),
                        radius = maxOf(size.width, size.height) * 0.78f,
                    )
                } else {
                    null
                }
                onDrawBehind {
                    if (baseColor != Color.Transparent) {
                        drawRect(baseColor.copy(alpha = 1f))
                    }
                    val elapsed = (elapsedMillis.value.toDouble() * safeSpeed).toLong()
                    val renderedWithAgsl = rendererBackend == GradientRendererBackend.AGSL &&
                        agslRenderer?.draw(
                            scope = this,
                            elapsedMillis = elapsed,
                            palette = palette,
                            opacity = safeOpacity,
                            intensity = safeIntensity,
                            motionScale = profile.motionScale,
                            blobCount = profile.blobCount,
                            softness = safeSoftness,
                            angle = safeAngle,
                            vignette = safeVignette,
                        ) == true
                    if (!renderedWithAgsl) {
                        drawRect(brush = baseGradientBrush)
                        drawGradientBlobs(
                            elapsedMillis = elapsed,
                            blobColors = blobColors,
                            blobAlphas = blobAlphas,
                            motionScale = profile.motionScale,
                            softness = safeSoftness,
                        )
                        vignetteBrush?.let { drawRect(brush = it) }
                    }
                }
            }
    }

    Box(modifier = modifier.fillMaxSize()) {
        Box(modifier = gradientDrawModifier)
        content()
    }
}

@Composable
private fun animateGradientPalette(
    target: GradientBackgroundPalette,
): GradientBackgroundPalette {
    val animationSpec = tween<Color>(durationMillis = 420)
    val base0 by animateColorAsState(target.baseStops[0].second, animationSpec, label = "GradientBase0")
    val base1 by animateColorAsState(target.baseStops[1].second, animationSpec, label = "GradientBase1")
    val base2 by animateColorAsState(target.baseStops[2].second, animationSpec, label = "GradientBase2")
    val base3 by animateColorAsState(target.baseStops[3].second, animationSpec, label = "GradientBase3")
    val base4 by animateColorAsState(target.baseStops[4].second, animationSpec, label = "GradientBase4")
    val blob0 by animateColorAsState(target.blobs[0].color, animationSpec, label = "GradientBlob0")
    val blob1 by animateColorAsState(target.blobs[1].color, animationSpec, label = "GradientBlob1")
    val blob2 by animateColorAsState(target.blobs[2].color, animationSpec, label = "GradientBlob2")
    val blob3 by animateColorAsState(target.blobs[3].color, animationSpec, label = "GradientBlob3")
    return GradientBackgroundPalette(
        baseStops = listOf(
            target.baseStops[0].first to base0,
            target.baseStops[1].first to base1,
            target.baseStops[2].first to base2,
            target.baseStops[3].first to base3,
            target.baseStops[4].first to base4,
        ),
        blobs = listOf(
            GradientBlobColor(blob0, target.blobs[0].alpha),
            GradientBlobColor(blob1, target.blobs[1].alpha),
            GradientBlobColor(blob2, target.blobs[2].alpha),
            GradientBlobColor(blob3, target.blobs[3].alpha),
        ),
    )
}

@Composable
internal fun rememberSystemMotionAllowed(): Boolean {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    fun currentValue(): Boolean {
        val powerManager = context.getSystemService(PowerManager::class.java)
        return ValueAnimator.areAnimatorsEnabled() && powerManager?.isPowerSaveMode != true
    }

    var allowed by remember(context) { mutableStateOf(currentValue()) }
    DisposableEffect(context, lifecycle) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                allowed = currentValue()
            }
        }
        val receiverRegistered = runCatching {
            ContextCompat.registerReceiver(
                context,
                receiver,
                IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
        }.isSuccess
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) allowed = currentValue()
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            if (receiverRegistered) runCatching { context.unregisterReceiver(receiver) }
        }
    }
    return allowed
}

@Composable
private fun rememberGradientAnimationElapsedMillis(
    enabled: Boolean,
    frameIntervalMillis: Long,
): State<Long> {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    return produceState(
        0L,
        lifecycle,
        frameIntervalMillis,
        enabled,
    ) {
        if (!enabled) return@produceState
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            var lastFrame = SystemClock.elapsedRealtime()
            while (true) {
                val now = SystemClock.elapsedRealtime()
                value += (now - lastFrame).coerceAtLeast(0L)
                lastFrame = now
                delay(frameIntervalMillis)
            }
        }
    }
}

private fun DrawScope.drawGradientBlobs(
    elapsedMillis: Long,
    blobColors: List<Color>,
    blobAlphas: List<Float>,
    motionScale: Float,
    softness: Float,
) {
    if (blobColors.isEmpty()) return
    val width = size.width
    val height = size.height
    val layout = resolveGradientLayoutProfile(width, height)
    val radius = maxOf(width, height) * layout.radiusScale
    val p1 = gradientMotionPhase(elapsedMillis, 5_500L)
    drawGradientBlob(
        center = Offset(
            width * 0.48f + sin(p1) * width * 0.38f * motionScale * layout.horizontalMotionScale,
            height * (0.08f + layout.verticalShift) +
                cos(gradientMotionPhase(elapsedMillis, 5_500L, 1.15)) * height * 0.18f * motionScale * layout.verticalMotionScale,
        ),
        radius = radius * softness * 0.36f,
        color = blobColors[0],
        alpha = blobAlphas.getOrElse(0) { 0f },
    )
    if (blobColors.size < 2) return
    val p2 = gradientMotionPhase(elapsedMillis, 7_000L)
    drawGradientBlob(
        center = Offset(
            width * 0.18f + sin(p2 + PI.toFloat() * 0.55f) * width * 0.30f *
                motionScale * layout.horizontalMotionScale,
            height * (0.24f + layout.verticalShift) +
                cos(p2) * height * 0.20f * motionScale * layout.verticalMotionScale,
        ),
        radius = radius * softness * 0.28f,
        color = blobColors[1],
        alpha = blobAlphas.getOrElse(1) { 0f },
    )
    if (blobColors.size < 3) return
    val p3 = gradientMotionPhase(elapsedMillis, 8_500L)
    drawGradientBlob(
        center = Offset(
            width * 0.82f - sin(p3 + PI.toFloat() * 0.9f) * width * 0.34f *
                motionScale * layout.horizontalMotionScale,
            height * (0.12f + layout.verticalShift) +
                cos(gradientMotionPhase(elapsedMillis, 8_500L, .9)) * height * 0.18f * motionScale * layout.verticalMotionScale,
        ),
        radius = radius * softness * 0.30f,
        color = blobColors[2],
        alpha = blobAlphas.getOrElse(2) { 0f },
    )
    if (blobColors.size < 4) return
    val p4 = gradientMotionPhase(elapsedMillis, 6_200L)
    drawGradientBlob(
        center = Offset(
            width * 0.58f + sin(p4 + PI.toFloat() * 1.25f) * width * 0.28f *
                motionScale * layout.horizontalMotionScale,
            height * (0.34f + layout.verticalShift) +
                cos(gradientMotionPhase(elapsedMillis, 6_200L, 1.1)) * height * 0.16f * motionScale * layout.verticalMotionScale,
        ),
        radius = radius * softness * 0.26f,
        color = blobColors[3],
        alpha = blobAlphas.getOrElse(3) { 0f },
    )
}

private fun DrawScope.drawGradientBlob(
    center: Offset,
    radius: Float,
    color: Color,
    alpha: Float,
) {
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(color.copy(alpha = alpha), Color.Transparent),
            center = center,
            radius = radius,
        ),
        radius = radius,
        center = center,
    )
}
