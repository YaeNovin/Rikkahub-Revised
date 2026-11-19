package me.rerere.rikkahub.ui.theme

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavKey
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.datastore.BackgroundSurfaceStyle
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.isGlobalBackgroundActive
import me.rerere.rikkahub.ui.components.ui.*
import me.rerere.rikkahub.ui.context.*

/** Each transitioning entry owns its appearance until its exit animation finishes. */
internal fun withRouteAppearance(settings: Settings, provider: (NavKey) -> NavEntry<NavKey>): (NavKey) -> NavEntry<NavKey> = { key ->
    val entry = provider(key)
    NavEntry(navEntry = entry) { RouteAppearance(settings, key is Screen.Chat) { entry.Content() } }
}

@Composable
internal fun RouteAppearance(settings: Settings, chat: Boolean, content: @Composable () -> Unit) {
    val appearance = settings.advancedAppearanceSetting
    val capabilities = remember { advancedAppearanceCapabilities(Build.VERSION.SDK_INT) }
    val style = capabilities.effectiveSurfaceStyle(appearance.pageSurfaceStyle)
    val active = settings.isGlobalBackgroundActive() && !chat
    val mica = style == BackgroundSurfaceStyle.MICA && !chat
    val visible = mica || (active && style != BackgroundSurfaceStyle.OPAQUE)
    val haze = rememberHazeState()
    val backdrop = rememberGlassBackdrop()
    val powerSave = rememberGlassPowerSave()
    val readability = rememberBackgroundReadability(appearance.globalBackground.takeIf { active },
        appearance.globalBackgroundOpacity, false, overlayTopAlpha = .28f, overlayBottomAlpha = .42f)
    val spec = if (active) AppearanceBackgroundSpec(appearance.globalBackground, appearance.globalBackgroundOpacity,
        appearance.globalBackgroundBlurRadius, foreground = readability.foreground, readability = readability) else null
    CompositionLocalProvider(
        LocalGlobalBackgroundActive provides visible,
        LocalGlobalGlassSurfaceOpacity provides appearance.pageSurfaceOpacity,
        LocalAppearanceSurfaceOpacityPolicy provides appearanceSurfaceOpacityPolicy(appearance.pageSurfaceOpacity,
            settings.displaySetting.topBarSurfaceOpacity, settings.displaySetting.inputSurfaceOpacity,
            appearance.chatDockGlassOpacity, visible, appearance.pageSurfaceStyle),
        LocalPageSurfaceStyle provides style,
        LocalAdvancedAppearanceCapabilities provides capabilities,
        LocalAppearanceBackground provides spec,
        LocalGlobalBackgroundHazeState provides haze,
        LocalGlassBackdrop provides backdrop,
        LocalGlassPowerSave provides powerSave,
        LocalGlassBusy provides backdrop.interacting,
    ) {
        Box(Modifier.fillMaxSize().trackGlassInteraction(backdrop).background(MaterialTheme.colorScheme.background)) {
            if (active || mica) GlobalAppBackground(settings, Modifier.fillMaxSize().hazeSource(haze), captureOnly = !visible)
            GlobalGlassTheme(visible, appearance.pageSurfaceOpacity, style, content)
        }
    }
}
