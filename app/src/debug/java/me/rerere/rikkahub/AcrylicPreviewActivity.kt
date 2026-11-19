package me.rerere.rikkahub

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import me.rerere.rikkahub.data.datastore.*
import me.rerere.rikkahub.data.model.withChatComposerMaterial
import me.rerere.rikkahub.ui.components.ai.ChatComposerSurface
import me.rerere.rikkahub.ui.components.ui.AppearanceAlertDialog
import me.rerere.rikkahub.ui.components.ui.AppearanceDropdownMenu
import me.rerere.rikkahub.ui.components.ui.AppearanceModalBottomSheet
import me.rerere.rikkahub.ui.context.LocalSettings

/** Only synthetic UI; this debug-only fixture never changes persisted settings. */
class AcrylicPreviewActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val mode = intent.getStringExtra("overlay").orEmpty()
        val dark = intent.getBooleanExtra("dark", false)
        val effects = intent.getBooleanExtra("effects", true)
        val radius = intent.getFloatExtra("radius", 20f)
        val settings = Settings(displaySetting = DisplaySetting(inputSurfaceOpacity = .58f, inputBlurRadius = radius),
            advancedAppearanceSetting = AdvancedAppearanceSetting(overlaySurfaceStyle = BackgroundSurfaceStyle.ACRYLIC,
                overlaySurfaceOpacity = .58f, overlaySurfaceBlurRadius = radius,
                enableInputPerformanceEffects = effects, enableOverlayPerformanceEffects = effects))
            .withChatComposerMaterial(BackgroundSurfaceStyle.ACRYLIC)
        setContent {
            MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                CompositionLocalProvider(LocalSettings provides settings) {
                    val haze = rememberHazeState()
                    var visible by remember { mutableStateOf(false) }
                    LaunchedEffect(Unit) { repeat(3) { withFrameNanos { } }; visible = true }
                    Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                        Canvas(Modifier.fillMaxSize().hazeSource(haze)) {
                            val stripe = 12.dp.toPx()
                            repeat((size.width / stripe).toInt() + 1) { index ->
                                drawRect(if (index % 2 == 0) Color(0xFF347CA4) else Color(0xFFC4D9DF),
                                    topLeft = Offset(index * stripe, 0f), size = Size(stripe, size.height))
                            }
                            drawCircle(Color(0xFFBB607D), size.width / 4, Offset(size.width / 2, size.height / 2))
                        }
                        Text("亚克力 · 固定测试背景", color = Color.Black, modifier = Modifier.padding(16.dp))
                        ChatComposerSurface(settings, haze, RoundedCornerShape(24.dp),
                            Modifier.align(Alignment.BottomCenter).padding(16.dp).fillMaxWidth().height(140.dp)) {
                            Column(Modifier.padding(20.dp)) {
                                Text("输入区前景保持清晰", style = MaterialTheme.typography.titleMedium)
                                Text("没有壁纸，也能模糊后方页面。")
                            }
                        }
                        if (visible && mode == "dialog") AppearanceAlertDialog(onDismissRequest = { visible = false },
                            title = { Text("亚克力浮层") }, text = { Text("背景取自应用窗口快照；这些文字不参与模糊。") },
                            confirmButton = { TextButton(onClick = { visible = false }) { Text("关闭") } })
                        if (visible && mode == "sheet") AppearanceModalBottomSheet(onDismissRequest = { visible = false }) {
                            Column(Modifier.padding(24.dp)) { Text("亚克力底部面板"); Text("背景取自应用窗口快照。") }
                        }
                        Box(Modifier.align(Alignment.Center).size(1.dp)) {
                            AppearanceDropdownMenu(expanded = visible && mode == "menu", onDismissRequest = { visible = false }) {
                                DropdownMenuItem(text = { Text("打开链接") }, onClick = {})
                                DropdownMenuItem(text = { Text("复制链接") }, onClick = {})
                            }
                        }
                    }
                }
            }
        }
    }
}
