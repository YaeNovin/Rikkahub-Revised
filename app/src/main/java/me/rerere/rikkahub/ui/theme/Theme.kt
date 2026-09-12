package me.rerere.rikkahub.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.LocalOverscrollFactory
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import kotlinx.serialization.Serializable
import me.rerere.rikkahub.data.datastore.isAutoAccentActive
import me.rerere.material3.DynamicColorSchemeOptions
import me.rerere.material3.createDynamicColorScheme
import me.rerere.rikkahub.ui.hooks.rememberAmoledDarkMode
import me.rerere.rikkahub.ui.hooks.rememberAmoledPureBlack
import me.rerere.rikkahub.ui.hooks.rememberCurrentColorMode
import me.rerere.rikkahub.ui.hooks.rememberUserSettingsState

private val ExtendLightColors = lightExtendColors()
private val ExtendDarkColors = darkExtendColors()
val LocalExtendColors = compositionLocalOf { ExtendLightColors }

val LocalDarkMode = compositionLocalOf { false }
val LocalBaseThemeColorScheme = compositionLocalOf<androidx.compose.material3.ColorScheme?> { null }

internal val AMOLED_DARK_BACKGROUND = Color(0xFF121316)
internal val AMOLED_PURE_BLACK_BACKGROUND = Color(0xFF000000)

internal fun resolveAmoledBackground(pureBlack: Boolean): Color =
    if (pureBlack) AMOLED_PURE_BLACK_BACKGROUND else AMOLED_DARK_BACKGROUND

@Serializable
enum class ColorMode {
    SYSTEM,
    LIGHT,
    DARK
}

@Composable
fun RikkahubTheme(
    colorMode: ColorMode = rememberCurrentColorMode(),
    content: @Composable () -> Unit
) {
    val settings by rememberUserSettingsState()

    val darkTheme = when (colorMode) {
        ColorMode.SYSTEM -> isSystemInDarkTheme()
        ColorMode.LIGHT -> false
        ColorMode.DARK -> true
    }
    val amoledDarkMode by rememberAmoledDarkMode()
    val amoledPureBlack by rememberAmoledPureBlack()
    val autoAccentColorArgb = settings.advancedAppearanceSetting.autoAccentColorArgb
        .takeIf { settings.isAutoAccentActive() }
    val generatedColorOptions = remember(
        settings.advancedAppearanceSetting.colorStyle,
        settings.advancedAppearanceSetting.colorContrast,
    ) {
        DynamicColorSchemeOptions(
            variant = settings.advancedAppearanceSetting.colorStyle.toMaterialColorVariant(),
            contrastLevel = settings.advancedAppearanceSetting.colorContrast
                .coerceIn(-1f, 1f)
                .toDouble(),
        )
    }

    val colorSource = resolveThemeColorSource(
        activeAutoAccentColorArgb = autoAccentColorArgb,
        dynamicColorEnabled = settings.dynamicColor,
        systemDynamicColorSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S,
    )
    val colorScheme = when (colorSource) {
        is ThemeColorSource.AutoAccent -> remember(
            colorSource.colorArgb,
            darkTheme,
            generatedColorOptions,
        ) {
            CustomTheme(primaryColorArgb = colorSource.colorArgb).generateColorScheme(
                dark = darkTheme,
                options = generatedColorOptions,
            )
        }
        ThemeColorSource.SystemDynamic -> {
            val context = LocalContext.current
            val systemScheme = if (darkTheme) {
                dynamicDarkColorScheme(context)
            } else {
                dynamicLightColorScheme(context)
            }
            if (generatedColorOptions == DynamicColorSchemeOptions()) {
                systemScheme
            } else {
                // Android owns the wallpaper seed for system dynamic color.
                // Re-seeding our generator with the system primary keeps that
                // behavior while allowing the explicit style/contrast controls
                // to take effect when the user changes them.
                remember(systemScheme.primary, darkTheme, generatedColorOptions) {
                    createDynamicColorScheme(
                        primaryColorArgb = systemScheme.primary.toArgb().toLong(),
                        dark = darkTheme,
                        options = generatedColorOptions,
                    )
                }
            }
        }
        ThemeColorSource.SelectedTheme -> {
            val customTheme = settings.customThemes.firstOrNull { it.id == settings.themeId }
            if (customTheme != null) {
                customTheme.generateColorScheme(dark = darkTheme, options = generatedColorOptions)
            } else {
                val preset = findPresetTheme(settings.themeId)
                if (generatedColorOptions == DynamicColorSchemeOptions()) {
                    // Preserve the curated colors of a preset when no explicit
                    // palette override is selected.
                    preset.getColorScheme(dark = darkTheme)
                } else {
                    // Presets ship as complete schemes rather than seed colors.
                    // Use their primary role as a stable seed so the shared
                    // style/contrast controls still apply consistently.
                    val seed = preset.getColorScheme(dark = darkTheme).primary.toArgb().toLong()
                    createDynamicColorScheme(
                        primaryColorArgb = seed,
                        dark = darkTheme,
                        options = generatedColorOptions,
                    )
                }
            }
        }
    }
    val colorSchemeConverted = remember(darkTheme, amoledDarkMode, amoledPureBlack, colorScheme) {
        if (darkTheme && amoledDarkMode) {
            val amoledBackground = resolveAmoledBackground(amoledPureBlack)
            colorScheme.copy(
                background = amoledBackground,
                surface = amoledBackground,
            )
        } else {
            colorScheme
        }
    }
    val extendColors = if (darkTheme) ExtendDarkColors else ExtendLightColors
    val textColorMode = settings.advancedAppearanceSetting.textColorMode
    val wallpaperTextSeed = rememberWallpaperTextSeed(textColorMode == me.rerere.rikkahub.data.datastore.TextColorMode.SYSTEM_WALLPAPER)
    val textColorScheme = remember(colorSchemeConverted, textColorMode, wallpaperTextSeed.argb) {
        colorSchemeConverted.withTextPalette(wallpaperTextSeed.argb.takeIf { textColorMode == me.rerere.rikkahub.data.datastore.TextColorMode.SYSTEM_WALLPAPER })
    }

    // 更新状态栏图标颜色
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    CompositionLocalProvider(
        LocalDarkMode provides darkTheme,
        LocalBaseThemeColorScheme provides textColorScheme,
        LocalTextColorMode provides textColorMode,
        LocalWallpaperTextSeed provides wallpaperTextSeed,
        androidx.compose.material3.LocalContentColor provides textColorScheme.onBackground,
        LocalExtendColors provides extendColors,
        LocalOverscrollFactory provides null
    ) {
        MaterialExpressiveTheme(
            colorScheme = textColorScheme,
            typography = Typography,
            content = content,
            motionScheme = MotionScheme.expressive()
        )
    }
}

val MaterialTheme.extendColors
    @Composable
    @ReadOnlyComposable
    get() = LocalExtendColors.current
