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
import me.rerere.ai.provider.ImageGenerationConstraints
import me.rerere.rikkahub.data.datastore.*
import me.rerere.rikkahub.data.model.ImageGenerationRequestState
import me.rerere.rikkahub.data.model.withChatComposerMaterial
import me.rerere.rikkahub.ui.components.ai.ChatComposerSurface
import me.rerere.rikkahub.ui.components.ai.ModelSearchField
import me.rerere.rikkahub.ui.components.ui.*
import me.rerere.rikkahub.ui.context.*
import me.rerere.rikkahub.ui.pages.imggen.ImageGenerationSettingsBottomSheet
import me.rerere.rikkahub.ui.theme.LocalDarkMode

/** Fixed samples using production surfaces and settings content, never user data. */
class MaterialSettingsPreviewActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val mode = intent.getStringExtra("mode") ?: "surfaces"
        val dark = intent.getBooleanExtra("dark", false)
        val material = runCatching { BackgroundSurfaceStyle.valueOf(intent.getStringExtra("material") ?: "ACRYLIC") }
            .getOrDefault(BackgroundSurfaceStyle.ACRYLIC)
        val radius = intent.getFloatExtra("radius", 20f)
        val settings = Settings(displaySetting = DisplaySetting(inputSurfaceOpacity = .45f, inputBlurRadius = radius),
            advancedAppearanceSetting = AdvancedAppearanceSetting(overlaySurfaceStyle = material, overlaySurfaceOpacity = .48f))
            .withChatComposerMaterial(material)
        setContent {
            MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                val navigator = remember { Navigator(mutableListOf()) }
                val toaster = com.dokar.sonner.rememberToasterState()
                CompositionLocalProvider(LocalSettings provides settings, LocalDarkMode provides dark,
                    LocalNavController provides navigator, LocalToaster provides toaster) {
                    val haze = rememberHazeState()
                    var visible by remember { mutableStateOf(true) }
                    var text by remember { mutableStateOf("") }
                    var enabled by remember { mutableStateOf(true) }
                    var parameters by remember { mutableStateOf(ImageGenerationRequestState()) }
                    Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                        Canvas(Modifier.fillMaxSize().hazeSource(haze)) {
                            val stripe = 12.dp.toPx()
                            repeat((size.width / stripe).toInt() + 1) {
                                drawRect(if (it % 2 == 0) Color(0xFF347CA4) else Color(0xFFC4D9DF),
                                    Offset(it * stripe, 0f), Size(stripe, size.height))
                            }
                        }
                        if (mode == "surfaces") {
                            IsolatedAppearanceSurface(BackgroundSurfaceStyle.MICA, .2f, 20f,
                                Modifier.padding(16.dp).fillMaxWidth().height(240.dp),
                                backgroundRendering = IsolatedBackgroundRendering.INDEPENDENT) {
                                Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Text("云母侧栏 · 独立底层")
                                    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(12.dp)) {
                                        Text("卡片继承云母配色", Modifier.fillMaxWidth().padding(12.dp))
                                    }
                                    ModelSearchField(text) { text = it }
                                }
                            }
                            ChatComposerSurface(settings, haze, RoundedCornerShape(24.dp),
                                Modifier.align(Alignment.BottomCenter).padding(16.dp).fillMaxWidth().height(150.dp)) {
                                Text("$material · 输入区前景", Modifier.padding(20.dp))
                            }
                        }
                        if (mode == "search" && visible) AppearanceModalBottomSheet(onDismissRequest = { visible = false }) {
                            Column(Modifier.fillMaxWidth().height(280.dp).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                Text("聊天 / 图片模型共用搜索框")
                                ModelSearchField(text) { text = it }
                                Text("固定模型 A / B，不进行联网")
                            }
                        }
                        if (mode == "settings" && visible) ImageGenerationSettingsBottomSheet(
                            state = parameters, onStateChange = { parameters = it },
                            constraints = ImageGenerationConstraints(true, true, false, maxOutputImages = 4,
                                supportedQualityValues = setOf("low", "medium", "high"),
                                supportedOutputFormats = setOf("png", "jpeg", "webp"), supportsNegativePrompt = true,
                                seedRange = 0L..999L, stepsRange = 1..50, supportsWatermark = true),
                            geminiModelId = null, geminiRequestChannel = null, referenceImageCount = 0,
                            sheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden,
                                enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded)),
                            onDismiss = { visible = false },
                            headerContent = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("允许聊天模型调用生图", Modifier.weight(1f))
                                    Switch(enabled, { enabled = it })
                                }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("模型自行调用（无需逐次确认）", Modifier.weight(1f))
                                    Switch(enabled, { enabled = it })
                                }
                                Text("固定权限说明。所有设置和参数导航必须一起滚动；不会生成图片或发送模型请求。".repeat(3))
                                TextButton(onClick = {}) { Text("选择图片模型（手动生图与模型调用共用）") }
                            },
                            footerContent = { TextButton(onClick = { visible = false }) { Text("确认全部设置") } },
                        )
                    }
                }
            }
        }
    }
}
