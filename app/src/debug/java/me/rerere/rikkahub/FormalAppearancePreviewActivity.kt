package me.rerere.rikkahub

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.data.datastore.*
import me.rerere.rikkahub.data.model.withMicaAcrylicAppearance
import me.rerere.rikkahub.ui.components.richtext.HighlightCodeBlock
import me.rerere.rikkahub.ui.components.ui.*
import me.rerere.rikkahub.ui.context.*
import me.rerere.rikkahub.ui.pages.setting.canAdjustMaterialBlur
import me.rerere.rikkahub.ui.theme.*

/** Fixed-content device fixture using the real page material and code block components. */
class FormalAppearancePreviewActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dark = intent.getBooleanExtra("dark", false)
        val wrapped = intent.getBooleanExtra("wrapped", true)
        val material = if (intent.getBooleanExtra("glass", false)) BackgroundSurfaceStyle.FROSTED else BackgroundSurfaceStyle.MICA
        val configured = Settings(displaySetting = DisplaySetting(showLineNumbers = true,
            codeBlockAutoWrap = wrapped, codeBlockAutoCollapse = false)).withMicaAcrylicAppearance()
        val settings = configured.copy(advancedAppearanceSetting = configured.advancedAppearanceSetting.copy(pageSurfaceStyle = material))
        val code = "/* 多行注释\n * val fake = true\n */\ndata class Star(val count: Int)\nfun draw(name: String): Boolean {\n    val color = \"violet\"\n    val count = 42\n    return count > 0\n}"
        setContent {
            MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                val navigator = remember { Navigator(mutableListOf()) }
                val toaster = com.dokar.sonner.rememberToasterState()
                CompositionLocalProvider(LocalSettings provides settings, LocalDarkMode provides dark,
                    LocalToaster provides toaster,
                    LocalPageSurfaceStyle provides material, LocalNavController provides navigator,
                    LocalAppearanceSurfaceOpacityPolicy provides appearanceSurfaceOpacityPolicy(.68f, .65f, 1f, 1f, false, material)) {
                    GlobalGlassTheme(active = true, style = material) {
                        Box(Modifier.fillMaxSize()) {
                            GlobalAppBackground(settings, Modifier.fillMaxSize())
                            Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                Text("正式云母 · ${if (dark) "深色" else "浅色"}", style = MaterialTheme.typography.titleLarge)
                                CardGroup(title = { Text("材质与卡片") }) {
                                    item(headlineContent = { Text("云母底层与原有卡片组件") }, supportingContent = { Text("没有壁纸也可使用；前景保持独立。") })
                                    item(headlineContent = { Text("背景虚化（保留 20）") }, supportingContent = {
                                        Slider(value = 20f, onValueChange = {}, valueRange = 0f..40f,
                                            enabled = canAdjustMaterialBlur(material, true))
                                    })
                                }
                                HighlightCodeBlock(code, "kotlin", renderVisuals = false)
                            }
                        }
                    }
                }
            }
        }
    }
}
