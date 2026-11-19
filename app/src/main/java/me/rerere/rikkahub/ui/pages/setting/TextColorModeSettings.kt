package me.rerere.rikkahub.ui.pages.setting

import android.os.Build
import android.app.WallpaperManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.data.datastore.TextColorMode
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.theme.LocalWallpaperTextSeed
import me.rerere.rikkahub.ui.theme.extractBackgroundBitmapAnalysis
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun TextColorModeSettings(
    selected: TextColorMode,
    onSelected: (TextColorMode) -> Unit,
    modifier: Modifier = Modifier,
    providedSeed: Long? = null,
    onProvidedSeed: (Long?, Int?) -> Unit = { _, _ -> },
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var extracting by remember { mutableStateOf(false) }
    var extractionError by remember { mutableStateOf<String?>(null) }
    val saveSeed by rememberUpdatedState(onProvidedSeed)
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            extracting = true
            extractionError = null
            try {
                val seed = extractBackgroundBitmapAnalysis(context, uri.toString(), forceRefresh = true)
                    ?.seedArgb?.toLong()?.and(0xFFFF_FFFFL)
                if (seed == null) extractionError = "未能读取这张图片，请重新选择。"
                else {
                    val wallpaperId = withContext(Dispatchers.IO) { runCatching {
                        WallpaperManager.getInstance(context).getWallpaperId(WallpaperManager.FLAG_SYSTEM)
                    }.getOrNull()?.takeIf { it > 0 } }
                    saveSeed(seed, wallpaperId)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { extractionError = "壁纸原图读取失败，请重新选择。" }
            finally { extracting = false }
        }
    }
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
            val enabled = true
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
                            if (selected == mode) {
                                Text(
                                    "当前来源：${wallpaper.source}",
                                    color = if (wallpaper.argb == null) Color(0xFFF9A825) else MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                Text(if (wallpaperSupported) "ROM 取色不准时，可提供当前手机壁纸原图；只保存取色结果，不更换壁纸。"
                                    else "此系统没有壁纸配色接口，可提供当前手机壁纸原图取色。")
                                TextButton(onClick = { picker.launch(arrayOf("image/*")) }, enabled = !extracting) {
                                    Text(if (extracting) "正在分析壁纸…" else "提供壁纸原图")
                                }
                                if (providedSeed != null) TextButton(onClick = { saveSeed(null, null) }, enabled = !extracting) {
                                    Text("恢复系统自动取色")
                                }
                                extractionError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
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
