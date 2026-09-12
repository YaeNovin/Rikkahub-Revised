package me.rerere.rikkahub.ui.pages.setting

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.data.datastore.TextColorMode
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.theme.LocalWallpaperTextSeed

@Composable
internal fun TextColorModeSettings(
    selected: TextColorMode,
    onSelected: (TextColorMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    val wallpaperSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1
    val wallpaper = LocalWallpaperTextSeed.current
    val options = listOf(
        Triple(TextColorMode.THEME, "跟随主题（关闭文字取色）", "使用当前主题的文字颜色，不再从背景或手机壁纸取色。"),
        Triple(TextColorMode.AUTO_CLEAR, "自动清晰", "根据当前背景和组件底色选择深色或浅色文字，优先保证可读性。"),
        Triple(TextColorMode.APP_BACKGROUND, "跟随应用背景", "从当前页面的助手背景、渐变或全局背景吸取色调，自动校正文字明暗。没有背景时沿用主题文字。"),
        Triple(TextColorMode.SYSTEM_WALLPAPER, "跟随手机壁纸", "仅从手机壁纸吸取文字色调，自动校正明暗；无需启用动态颜色，更换壁纸后自动更新。"),
    )
    CardGroup(modifier = modifier.selectableGroup(), title = { Text("文字配色") }) {
        options.forEach { (mode, label, description) ->
            val enabled = mode != TextColorMode.SYSTEM_WALLPAPER || wallpaperSupported
            item(
                modifier = Modifier.selectable(
                    selected = selected == mode,
                    enabled = enabled,
                    role = Role.RadioButton,
                    onClick = { onSelected(mode) },
                ),
                leadingContent = { RadioButton(selected = selected == mode, onClick = null, enabled = enabled) },
                headlineContent = {
                    Text(label, color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else .38f))
                },
                supportingContent = {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(description, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (enabled) 1f else .38f))
                        if (mode == TextColorMode.SYSTEM_WALLPAPER) {
                            if (!wallpaperSupported) {
                                Text("需要 Android 8.1 及以上；当前系统使用自动清晰。", color = Color(0xFFF9A825))
                            } else if (selected == mode) {
                                Text(
                                    "当前来源：${wallpaper.source}",
                                    color = if (wallpaper.argb == null) Color(0xFFF9A825) else MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                },
            )
        }
        item(
            headlineContent = { Text("保持原有背景") },
            supportingContent = {
                Text("文字配色仅影响正文、标签和配套图标；按钮填充色、描边和背景由应用主题决定。“动态颜色”和“使用背景强调色”控制组件配色，切换其中一项会退出另一项。复杂背景可能限制可用的文字色调。")
            },
        )
    }
}
