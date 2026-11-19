package me.rerere.rikkahub.data.model

import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.BackgroundSurfaceStyle

/** One UI selection backed by existing persisted fields, preserving the user's
 * opacity, blur strength and disabled performance effects. */
internal typealias ChatComposerMaterial = BackgroundSurfaceStyle

internal fun Settings.chatComposerMaterial(): ChatComposerMaterial = advancedAppearanceSetting.composerSurfaceStyle ?: when {
    advancedAppearanceSetting.liquidGlass.applyToComposer -> ChatComposerMaterial.LIQUID_GLASS
    displaySetting.enableBlurEffect -> ChatComposerMaterial.FROSTED
    else -> ChatComposerMaterial.TRANSLUCENT
}

internal fun Settings.withChatComposerMaterial(material: ChatComposerMaterial): Settings = copy(
    displaySetting = displaySetting.withChatComposerMaterial(material),
    advancedAppearanceSetting = advancedAppearanceSetting.withChatComposerMaterial(material),
)

/** An explicit preset changes materials only; background sources and all effect parameters survive. */
internal fun Settings.withMicaAcrylicAppearance(): Settings {
    val composer = withChatComposerMaterial(BackgroundSurfaceStyle.ACRYLIC)
    return composer.copy(advancedAppearanceSetting = composer.advancedAppearanceSetting.copy(
        pageSurfaceStyle = BackgroundSurfaceStyle.MICA,
        navigationSurfaceStyle = BackgroundSurfaceStyle.MICA,
        enableNavigationGlass = true,
        overlaySurfaceStyle = BackgroundSurfaceStyle.ACRYLIC,
    ))
}

internal fun me.rerere.rikkahub.data.datastore.DisplaySetting.withChatComposerMaterial(material: ChatComposerMaterial) =
    copy(enableBlurEffect = material.usesBlur)

internal fun me.rerere.rikkahub.data.datastore.AdvancedAppearanceSetting.withChatComposerMaterial(material: ChatComposerMaterial) =
    copy(composerSurfaceStyle = material,
        liquidGlass = liquidGlass.copy(applyToComposer = material == ChatComposerMaterial.LIQUID_GLASS))
