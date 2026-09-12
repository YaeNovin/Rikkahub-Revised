package me.rerere.rikkahub.ui.theme

import androidx.compose.material3.ColorScheme
import kotlinx.serialization.Serializable
import me.rerere.material3.DynamicColorSchemeOptions
import me.rerere.material3.MaterialColorVariant
import me.rerere.material3.createDynamicColorScheme
import me.rerere.rikkahub.data.datastore.AppearanceColorStyle
import kotlin.uuid.Uuid

@Serializable
data class CustomTheme(
    val id: String = Uuid.random().toString(),
    val name: String = "",
    val primaryColorArgb: Long = 0xFF6750A4,
    val secondaryColorArgb: Long? = null,
    val tertiaryColorArgb: Long? = null,
) {
    fun generateColorScheme(
        dark: Boolean,
        options: DynamicColorSchemeOptions = DynamicColorSchemeOptions(),
    ): ColorScheme = createDynamicColorScheme(
        primaryColorArgb = primaryColorArgb,
        secondaryColorArgb = secondaryColorArgb,
        tertiaryColorArgb = tertiaryColorArgb,
        dark = dark,
        options = options,
    )
}

internal fun AppearanceColorStyle.toMaterialColorVariant(): MaterialColorVariant = when (this) {
    AppearanceColorStyle.TONAL_SPOT -> MaterialColorVariant.TONAL_SPOT
    AppearanceColorStyle.NEUTRAL -> MaterialColorVariant.NEUTRAL
    AppearanceColorStyle.VIBRANT -> MaterialColorVariant.VIBRANT
    AppearanceColorStyle.EXPRESSIVE -> MaterialColorVariant.EXPRESSIVE
    AppearanceColorStyle.MONOCHROME -> MaterialColorVariant.MONOCHROME
}
