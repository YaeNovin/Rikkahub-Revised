package me.rerere.rikkahub.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import me.rerere.rikkahub.data.datastore.AdvancedAppearanceSetting
import org.junit.Assert.assertEquals
import org.junit.Test

class ChatTypographyTest {
    @Test
    fun `readable chat style uses configured line height and zero letter spacing`() {
        val style = resolveChatBodyTextStyle(
            baseStyle = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
            fontSizeRatio = 1.25f,
            fontFamily = FontFamily.SansSerif,
            color = Color.Black,
            appearance = AdvancedAppearanceSetting(chatTextLineHeightRatio = 1.6f),
        )

        assertEquals(20.sp, style.fontSize)
        assertEquals(32.sp, style.lineHeight)
        assertEquals(0.sp, style.letterSpacing)
        assertEquals(FontFamily.SansSerif, style.fontFamily)
    }

    @Test
    fun `disabled readability keeps material line-height scaling`() {
        val style = resolveChatBodyTextStyle(
            baseStyle = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
            fontSizeRatio = 1.5f,
            fontFamily = FontFamily.Default,
            color = Color.Black,
            appearance = AdvancedAppearanceSetting(enableChatTextReadability = false),
        )

        assertEquals(24.sp, style.fontSize)
        assertEquals(36.sp, style.lineHeight)
    }

    @Test
    fun `chat typography clamps unsupported size and line-height ratios`() {
        val style = resolveChatBodyTextStyle(
            baseStyle = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
            fontSizeRatio = 4f,
            fontFamily = FontFamily.Default,
            color = Color.Black,
            appearance = AdvancedAppearanceSetting(chatTextLineHeightRatio = 9f),
        )

        assertEquals(32.sp, style.fontSize)
        assertEquals(57.6.sp, style.lineHeight)
    }
}
