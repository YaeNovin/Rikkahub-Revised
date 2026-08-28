package me.rerere.rikkahub.ui.components.richtext

import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import me.rerere.rikkahub.data.datastore.RichContentStyle
import me.rerere.rikkahub.data.datastore.normalizedChatParagraphSpacingRatio
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.utils.toDp

@Immutable
internal data class RichContentColors(
    val container: Color,
    val toolbar: Color,
    val border: Color,
    val cellBorder: Color,
    val quoteContainer: Color,
)

@Composable
internal fun richContentColors(): RichContentColors {
    val colorScheme = MaterialTheme.colorScheme
    val appearance = LocalSettings.current.advancedAppearanceSetting
    val opacity = appearance.richContentSurfaceOpacity.coerceIn(0.2f, 0.9f)

    return when (appearance.richContentStyle) {
        RichContentStyle.TRANSLUCENT -> RichContentColors(
            container = colorScheme.surfaceContainer.copy(alpha = opacity),
            toolbar = colorScheme.primaryContainer.copy(
                alpha = (opacity * 0.82f).coerceIn(0.2f, 0.7f),
            ),
            border = colorScheme.primary.copy(alpha = 0.34f),
            cellBorder = colorScheme.outlineVariant.copy(alpha = 0.56f),
            quoteContainer = colorScheme.secondaryContainer.copy(
                alpha = (opacity * 0.38f).coerceIn(0.1f, 0.34f),
            ),
        )

        RichContentStyle.OUTLINED -> RichContentColors(
            container = Color.Transparent,
            toolbar = colorScheme.primary.copy(alpha = 0.08f),
            border = colorScheme.primary.copy(alpha = 0.56f),
            cellBorder = colorScheme.primary.copy(alpha = 0.24f),
            quoteContainer = Color.Transparent,
        )
    }
}

@Composable
internal fun markdownParagraphSpacing(): Dp {
    val appearance = LocalSettings.current.advancedAppearanceSetting
    val ratio = if (appearance.enableChatTextReadability) {
        appearance.normalizedChatParagraphSpacingRatio()
    } else {
        1f
    }
    return LocalTextStyle.current.fontSize.toDp() * ratio
}
