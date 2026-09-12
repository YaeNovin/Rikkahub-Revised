package me.rerere.rikkahub.ui.theme

import me.rerere.rikkahub.data.datastore.Settings

/** Explicit source changes must not leave a higher-priority source silently masking them. */
internal fun Settings.selectTheme(id: String): Settings = copy(
    themeId = id,
    dynamicColor = false,
    advancedAppearanceSetting = advancedAppearanceSetting.copy(enableAutoAccent = false),
)

internal fun Settings.selectDynamicColors(enabled: Boolean): Settings = copy(
    dynamicColor = enabled,
    // The two automatic color sources are mutually exclusive mirrors. Turning
    // dynamic color off must make the background-accent source available again.
    advancedAppearanceSetting = advancedAppearanceSetting.copy(enableAutoAccent = !enabled),
)

internal fun Settings.selectBackgroundAccent(enabled: Boolean): Settings = copy(
    // Restoring the opposite source on disable prevents the previous state from
    // getting stuck with both switches off.
    dynamicColor = !enabled,
    advancedAppearanceSetting = advancedAppearanceSetting.copy(enableAutoAccent = enabled),
)

internal sealed interface ThemeColorSource {
    data class AutoAccent(val colorArgb: Long) : ThemeColorSource

    data object SystemDynamic : ThemeColorSource

    data object SelectedTheme : ThemeColorSource
}

internal fun resolveThemeColorSource(
    activeAutoAccentColorArgb: Long?,
    dynamicColorEnabled: Boolean,
    systemDynamicColorSupported: Boolean,
): ThemeColorSource = when {
    activeAutoAccentColorArgb != null -> ThemeColorSource.AutoAccent(
        colorArgb = activeAutoAccentColorArgb,
    )
    dynamicColorEnabled && systemDynamicColorSupported -> ThemeColorSource.SystemDynamic
    else -> ThemeColorSource.SelectedTheme
}
