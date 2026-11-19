package me.rerere.rikkahub.ui.components.richtext

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.rikkahub.ui.components.ui.LocalAppearanceBackground
import java.util.Locale

@Composable
internal fun previewBackgroundForeground(): Color =
    LocalAppearanceBackground.current?.foreground?.takeUnless { it == Color.Unspecified }
        ?: MaterialTheme.colorScheme.onSurface

/** Only host-computed colors enter the fixed hook. No image URI or author CSS. */
internal fun previewBackgroundScript(enabled: Boolean, foreground: Color): String {
    val safeForeground = foreground.takeUnless { it == Color.Unspecified } ?: Color.White
    fun hex(color: Color) = String.format(Locale.ROOT, "#%06x", color.toArgb() and 0xffffff)
    val chrome = buildJsonObject {
        put("foreground", hex(safeForeground))
        put("shadow", hex(if (safeForeground.luminance() > .5f) Color.Black else Color.White))
    }
    return "window.rikkaSetPreviewBackground && window.rikkaSetPreviewBackground($enabled,$chrome);"
}
