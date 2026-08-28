package me.rerere.rikkahub.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import me.rerere.rikkahub.data.datastore.AdvancedAppearanceSetting
import me.rerere.rikkahub.data.datastore.normalizedChatTextLineHeightRatio

internal fun resolveChatBodyTextStyle(
    baseStyle: TextStyle,
    fontSizeRatio: Float,
    fontFamily: FontFamily,
    color: Color,
    appearance: AdvancedAppearanceSetting,
): TextStyle {
    val safeFontSizeRatio = fontSizeRatio.coerceIn(0.5f, 2f)
    val fontSize = baseStyle.fontSize * safeFontSizeRatio
    val lineHeight = if (appearance.enableChatTextReadability) {
        fontSize * appearance.normalizedChatTextLineHeightRatio()
    } else {
        baseStyle.lineHeight * safeFontSizeRatio
    }
    return baseStyle.copy(
        fontSize = fontSize,
        lineHeight = lineHeight,
        fontFamily = fontFamily,
        letterSpacing = 0.sp,
        color = color,
    )
}
