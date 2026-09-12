package me.rerere.rikkahub.ui.components.ui

import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LargeFlexibleTopAppBar as MaterialLargeFlexibleTopAppBar
import androidx.compose.material3.TopAppBar as MaterialTopAppBar
import androidx.compose.material3.TopAppBarColors
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.blur.HazeBlurStyle
import dev.chrisbanes.haze.blur.hazeBlur
import dev.chrisbanes.haze.blur.material3.Material3
import me.rerere.rikkahub.ui.context.LocalGlobalBackgroundActive
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.ui.theme.CustomColors

val LocalGlobalBackgroundHazeState = compositionLocalOf<HazeState?> { null }

internal fun shouldApplyTopBarBlur(
    backgroundActive: Boolean,
    blurEnabled: Boolean,
    performanceEffectsEnabled: Boolean,
    blurSupported: Boolean,
    hasHazeSource: Boolean,
): Boolean = backgroundActive &&
    blurEnabled &&
    performanceEffectsEnabled &&
    blurSupported &&
    hasHazeSource

@Composable
private fun Modifier.appearanceTopBarBlur(): Modifier {
    val settings = LocalSettings.current
    val capabilities = LocalAdvancedAppearanceCapabilities.current
    val hazeState = LocalGlobalBackgroundHazeState.current
    val enabled = shouldApplyTopBarBlur(
        backgroundActive = LocalGlobalBackgroundActive.current,
        blurEnabled = settings.displaySetting.enableTopBarBlur,
        performanceEffectsEnabled = settings.advancedAppearanceSetting.enableTopBarPerformanceEffects,
        blurSupported = capabilities.supportsRealtimeBlur,
        hasHazeSource = hazeState != null,
    )
    if (!enabled || hazeState == null) return this

    val blurRadius = capabilities.limitLiveBlur(
        settings.displaySetting.topBarBlurRadius.coerceIn(0f, 40f)
    )
    if (blurRadius <= 0f) return this
    val hazeStyle = backgroundOnlyBlurStyle(blurRadius)
    return hazeBlur(
        input = HazeInput.Sources(hazeState),
        style = hazeStyle,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TopAppBar(
    title: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
    expandedHeight: Dp = TopAppBarDefaults.TopAppBarExpandedHeight,
    windowInsets: WindowInsets = TopAppBarDefaults.windowInsets,
    colors: TopAppBarColors = CustomColors.topBarColors,
    scrollBehavior: TopAppBarScrollBehavior? = null,
) {
    MaterialTopAppBar(
        title = title,
        modifier = modifier.appearanceTopBarBlur(),
        navigationIcon = navigationIcon,
        actions = actions,
        expandedHeight = expandedHeight,
        windowInsets = windowInsets,
        colors = colors,
        scrollBehavior = scrollBehavior,
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun LargeFlexibleTopAppBar(
    title: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
    colors: TopAppBarColors = CustomColors.topBarColors,
    scrollBehavior: TopAppBarScrollBehavior? = null,
) {
    MaterialLargeFlexibleTopAppBar(
        title = title,
        modifier = modifier.appearanceTopBarBlur(),
        navigationIcon = navigationIcon,
        actions = actions,
        colors = colors,
        scrollBehavior = scrollBehavior,
    )
}
