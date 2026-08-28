package me.rerere.rikkahub.ui.components.ui

import android.os.Build
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import me.rerere.rikkahub.data.datastore.BackgroundSurfaceStyle
import me.rerere.rikkahub.data.datastore.ChatBubbleStyle
import me.rerere.rikkahub.data.datastore.MAX_GLOBAL_BACKGROUND_BLUR_RADIUS
import me.rerere.rikkahub.data.datastore.MAX_LIQUID_GLASS_BLUR_RADIUS

enum class AdvancedAppearanceSupport {
    FULL,
    REDUCED,
    UNSUPPORTED,
}

@Immutable
data class AdvancedAppearanceCapabilities(
    val sdkInt: Int,
    val blurSupport: AdvancedAppearanceSupport,
    val maxBackgroundBlurRadius: Float,
    val maxLiveBlurRadius: Float,
    val opticalStrengthScale: Float,
) {
    val supportsRealtimeBlur: Boolean
        get() = blurSupport != AdvancedAppearanceSupport.UNSUPPORTED

    val usesReducedEffects: Boolean
        get() = blurSupport == AdvancedAppearanceSupport.REDUCED

    fun supportsSurfaceStyle(style: BackgroundSurfaceStyle): Boolean =
        style == BackgroundSurfaceStyle.OPAQUE ||
            style == BackgroundSurfaceStyle.TRANSLUCENT ||
            supportsRealtimeBlur

    fun supportsBubbleStyle(style: ChatBubbleStyle): Boolean =
        style == ChatBubbleStyle.OUTLINED || supportsRealtimeBlur

    fun effectiveSurfaceStyle(style: BackgroundSurfaceStyle): BackgroundSurfaceStyle =
        if (supportsSurfaceStyle(style)) style else BackgroundSurfaceStyle.TRANSLUCENT

    fun effectiveBubbleStyle(style: ChatBubbleStyle): ChatBubbleStyle =
        if (supportsBubbleStyle(style)) style else ChatBubbleStyle.OUTLINED

    fun limitBackgroundBlur(radius: Float): Float =
        radius.coerceIn(0f, maxBackgroundBlurRadius)

    fun limitLiveBlur(radius: Float): Float =
        radius.coerceIn(0f, maxLiveBlurRadius)

    fun limitOpticalStrength(strength: Float): Float =
        (strength.coerceIn(0f, 1f) * opticalStrengthScale).coerceIn(0f, 1f)
}

fun advancedAppearanceCapabilities(sdkInt: Int): AdvancedAppearanceCapabilities = when {
    sdkInt >= Build.VERSION_CODES.TIRAMISU -> AdvancedAppearanceCapabilities(
        sdkInt = sdkInt,
        blurSupport = AdvancedAppearanceSupport.FULL,
        maxBackgroundBlurRadius = MAX_GLOBAL_BACKGROUND_BLUR_RADIUS,
        maxLiveBlurRadius = MAX_GLOBAL_BACKGROUND_BLUR_RADIUS,
        opticalStrengthScale = 1f,
    )

    sdkInt >= Build.VERSION_CODES.S -> AdvancedAppearanceCapabilities(
        sdkInt = sdkInt,
        blurSupport = AdvancedAppearanceSupport.REDUCED,
        maxBackgroundBlurRadius = 20f,
        maxLiveBlurRadius = 12f,
        opticalStrengthScale = 0.55f,
    )

    else -> AdvancedAppearanceCapabilities(
        sdkInt = sdkInt,
        blurSupport = AdvancedAppearanceSupport.UNSUPPORTED,
        maxBackgroundBlurRadius = 0f,
        maxLiveBlurRadius = 0f,
        opticalStrengthScale = 0f,
    )
}

val LocalAdvancedAppearanceCapabilities = staticCompositionLocalOf {
    advancedAppearanceCapabilities(Build.VERSION.SDK_INT)
}
