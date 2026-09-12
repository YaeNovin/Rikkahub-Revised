package me.rerere.rikkahub.ui.components.ai

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import androidx.compose.material3.MaterialTheme
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.blur.hazeBlur
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.ChatComposerMaterial
import me.rerere.rikkahub.data.model.chatComposerMaterial
import me.rerere.rikkahub.ui.components.ui.LocalAdvancedAppearanceCapabilities
import me.rerere.rikkahub.ui.components.ui.backgroundOnlyBlurStyle
import me.rerere.rikkahub.ui.components.ui.rememberTintedSurfaceForeground
import me.rerere.rikkahub.ui.theme.BackgroundReadabilityTheme
import androidx.compose.runtime.CompositionLocalProvider

internal data class ChatComposerStyle(val opacity: Float, val blurRadius: Float)

internal fun resolveChatComposerStyle(settings: Settings, blurSupported: Boolean, maxBlur: Float): ChatComposerStyle {
    val display = settings.displaySetting
    val effects = settings.advancedAppearanceSetting.enableInputPerformanceEffects
    // Tint is a cheap static layer, not a performance effect. Turning off blur
    // must not silently make a deliberately transparent composer opaque.
    val opacity = display.inputSurfaceOpacity.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 1f
    val radius = if (effects && settings.chatComposerMaterial() != ChatComposerMaterial.TRANSLUCENT && blurSupported)
        display.inputBlurRadius.takeIf { it.isFinite() }?.coerceIn(0f, maxBlur.takeIf { it.isFinite() }?.coerceAtLeast(0f) ?: 0f) ?: 0f else 0f
    return ChatComposerStyle(opacity, radius)
}

/** The tint and optical layer share the real chat scene behind this surface,
 * including messages. Input text and buttons never enter the sampled scene. */
@Composable
internal fun ChatComposerSurface(settings: Settings, hazeState: HazeState, shape: Shape, modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val capabilities = LocalAdvancedAppearanceCapabilities.current
    val style = resolveChatComposerStyle(settings, capabilities.supportsRealtimeBlur, capabilities.maxLiveBlurRadius)
    val glass = settings.chatComposerMaterial() == ChatComposerMaterial.LIQUID_GLASS &&
        settings.advancedAppearanceSetting.enableInputPerformanceEffects && capabilities.supportsRealtimeBlur
    val baseColor = MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 1f)
    val tint = baseColor.copy(alpha = style.opacity)
    val blurStyle = backgroundOnlyBlurStyle(style.blurRadius)
    Box(modifier.clip(shape)) {
        if (glass) me.rerere.rikkahub.ui.components.ui.InlineBackdropBlur(
            blurRadius = style.blurRadius, shape = shape, modifier = Modifier.matchParentSize(),
            sourceOverride = hazeState,
        )
        Box(Modifier.matchParentSize().then(if (!glass && style.blurRadius > 0f) Modifier.hazeBlur(input = HazeInput.Sources(hazeState), style = blurStyle) else Modifier)
            .background(tint))
        val foreground = rememberTintedSurfaceForeground(baseColor, tint.alpha, MaterialTheme.colorScheme.onSurface,
            me.rerere.rikkahub.ui.components.ui.LocalAppearanceBackground.current?.readability?.backgrounds,
            me.rerere.rikkahub.ui.theme.currentTextPaletteSeed())
        BackgroundReadabilityTheme(active = true, foreground = foreground) {
            CompositionLocalProvider(
                androidx.compose.material3.LocalContentColor provides foreground,
                me.rerere.rikkahub.ui.components.ui.LocalInsideGlassSurface provides true,
                me.rerere.rikkahub.ui.theme.LocalChatBackgroundForeground provides foreground,
            ) { content() }
        }
        if (glass) me.rerere.rikkahub.ui.components.ui.LiquidGlassSurfaceLayers(
            modifier = Modifier.matchParentSize(), shape = shape, drawTint = false,
        ) else Box(Modifier.matchParentSize().border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .5f), shape))
    }
}
