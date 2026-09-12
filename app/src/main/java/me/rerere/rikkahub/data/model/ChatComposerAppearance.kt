package me.rerere.rikkahub.data.model

import me.rerere.rikkahub.data.datastore.Settings

/** One UI selection backed by existing persisted fields, preserving the user's
 * opacity, blur strength and disabled performance effects. */
internal enum class ChatComposerMaterial { TRANSLUCENT, FROSTED, LIQUID_GLASS }

internal fun Settings.chatComposerMaterial(): ChatComposerMaterial = when {
    advancedAppearanceSetting.liquidGlass.applyToComposer -> ChatComposerMaterial.LIQUID_GLASS
    displaySetting.enableBlurEffect -> ChatComposerMaterial.FROSTED
    else -> ChatComposerMaterial.TRANSLUCENT
}

internal fun Settings.withChatComposerMaterial(material: ChatComposerMaterial): Settings = copy(
    displaySetting = displaySetting.withChatComposerMaterial(material),
    advancedAppearanceSetting = advancedAppearanceSetting.withChatComposerMaterial(material),
)

internal fun me.rerere.rikkahub.data.datastore.DisplaySetting.withChatComposerMaterial(material: ChatComposerMaterial) =
    copy(enableBlurEffect = material != ChatComposerMaterial.TRANSLUCENT)

internal fun me.rerere.rikkahub.data.datastore.AdvancedAppearanceSetting.withChatComposerMaterial(material: ChatComposerMaterial) =
    copy(liquidGlass = liquidGlass.copy(applyToComposer = material == ChatComposerMaterial.LIQUID_GLASS))
