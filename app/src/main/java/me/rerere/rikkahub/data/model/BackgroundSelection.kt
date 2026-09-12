package me.rerere.rikkahub.data.model

import me.rerere.rikkahub.data.datastore.AdvancedAppearanceSetting
import me.rerere.rikkahub.data.datastore.BackgroundSurfaceStyle

internal fun visibleBackgroundOpacity(value: Float): Float =
    value.takeIf { it.isFinite() && it > 0f }?.coerceAtMost(1f) ?: 1f

internal fun Assistant.selectBackground(image: String?): Assistant = copy(
    background = image?.trim()?.takeIf { it.isNotEmpty() },
    useGradientBackground = if (!image.isNullOrBlank()) false else useGradientBackground,
    backgroundOpacity = if (!image.isNullOrBlank()) visibleBackgroundOpacity(backgroundOpacity) else backgroundOpacity,
)

internal fun Assistant.selectGradientBackground(enabled: Boolean): Assistant = copy(
    useGradientBackground = enabled,
    backgroundOpacity = if (enabled) visibleBackgroundOpacity(backgroundOpacity) else backgroundOpacity,
)

internal fun AdvancedAppearanceSetting.selectGlobalBackground(image: String?): AdvancedAppearanceSetting {
    val selected = image?.trim()?.takeIf { it.isNotEmpty() }
    return copy(
        globalBackground = selected,
        enableGlobalBackground = if (selected != null) true else enableGlobalBackground,
        // Explicitly selecting an image should make it visible on normal pages.
        pageSurfaceStyle = if (selected != null && pageSurfaceStyle == BackgroundSurfaceStyle.OPAQUE) BackgroundSurfaceStyle.TRANSLUCENT else pageSurfaceStyle,
        globalBackgroundOpacity = if (selected != null) visibleBackgroundOpacity(globalBackgroundOpacity) else globalBackgroundOpacity,
        applyGlobalBackgroundToChat = applyGlobalBackgroundToChat && selected != null,
        autoAccentColorArgb = if (selected == globalBackground) autoAccentColorArgb else null,
    )
}
