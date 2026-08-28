package me.rerere.rikkahub.ui.context

import androidx.compose.runtime.staticCompositionLocalOf
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.BackgroundSurfaceStyle

val LocalSettings = staticCompositionLocalOf<Settings> {
    error("No SettingsStore provided")
}

val LocalGlobalBackgroundActive = staticCompositionLocalOf { false }

val LocalGlobalGlassSurfaceOpacity = staticCompositionLocalOf { 1f }

val LocalPageSurfaceStyle = staticCompositionLocalOf { BackgroundSurfaceStyle.OPAQUE }
