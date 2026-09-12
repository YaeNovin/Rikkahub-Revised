package me.rerere.rikkahub.ui.pages.setting

import me.rerere.rikkahub.data.datastore.*
import me.rerere.rikkahub.ui.components.ui.AdvancedAppearanceCapabilities
import kotlin.math.abs

internal fun canAdjustCardOpacity(settings: Settings, capabilities: AdvancedAppearanceCapabilities): Boolean =
    settings.hasActiveChatBackground() || (settings.isGlobalBackgroundActive() &&
        capabilities.effectiveSurfaceStyle(settings.advancedAppearanceSetting.pageSurfaceStyle) != BackgroundSurfaceStyle.OPAQUE)

internal fun canAdjustOverlayEffects(settings: Settings): Boolean =
    settings.hasActiveChatBackground() || settings.isGlobalBackgroundActive()

internal fun shouldSyncAppearanceSlider(dragging: Boolean, pending: Float?, incoming: Float, enabled: Boolean, saveFailed: Boolean = false): Boolean =
    saveFailed || !enabled || (!dragging && pending == null) || (pending != null && abs(pending - incoming) < .0001f)
