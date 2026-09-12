package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.data.datastore.AdvancedAppearanceSetting
import me.rerere.rikkahub.data.datastore.BackgroundSurfaceStyle
import me.rerere.rikkahub.data.model.*
import me.rerere.rikkahub.ui.components.ui.*
import me.rerere.rikkahub.ui.context.LocalSettings
import dev.chrisbanes.haze.hazeSource

@Composable
internal fun LiquidGlassSettingsCard(appearance: AdvancedAppearanceSetting, update: ((AdvancedAppearanceSetting) -> AdvancedAppearanceSetting) -> Unit) {
    val settings = appearance.liquidGlass
    val params = settings.parameters()
    val caps = LocalAdvancedAppearanceCapabilities.current
    val agslAvailable = caps.sdkInt >= 33 && !GlassRefractionRuntime.failed
    var advanced by rememberSaveable { mutableStateOf(false) }
    CardGroup(modifier = Modifier.padding(horizontal = 8.dp), title = { Text("液态玻璃材质") }) {
        item(headlineContent = { Text("效果预设") }, supportingContent = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Select(options = LiquidGlassPreset.entries, selectedOption = settings.preset, enabled = caps.supportsRealtimeBlur, onOptionSelected = { preset ->
                    update { it.copy(liquidGlass = it.liquidGlass.copy(preset = preset,
                        custom = if (preset == LiquidGlassPreset.CUSTOM) it.liquidGlass.parameters() else it.liquidGlass.custom)) }
                }, optionToString = { when(it) { LiquidGlassPreset.SOFT -> "柔和"; LiquidGlassPreset.STANDARD -> "标准"; LiquidGlassPreset.CLEAR -> "通透"; LiquidGlassPreset.CUSTOM -> "自定义" } })
                Text("预设统一控制高光、边缘、染色和折射；各区域原有的不透明度和模糊半径独立保留。", style = MaterialTheme.typography.bodySmall)
                LiquidGlassPreview()
            }
        })
        item(headlineContent = { Text("玻璃渲染方式") }, supportingContent = {
            Column {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    LiquidGlassRenderer.entries.forEachIndexed { index, renderer ->
                        SegmentedButton(selected = settings.renderer == renderer, enabled = renderer != LiquidGlassRenderer.AGSL || agslAvailable,
                            shape = SegmentedButtonDefaults.itemShape(index, LiquidGlassRenderer.entries.size),
                            onClick = { update { it.copy(liquidGlass = it.liquidGlass.copy(renderer = renderer)) } }) {
                            Text(when(renderer) { LiquidGlassRenderer.AUTO -> "自动"; LiquidGlassRenderer.STANDARD -> "标准"; LiquidGlassRenderer.AGSL -> "AGSL" })
                        }
                    }
                }
                Text("AGSL 只扭曲背景边缘，正文不参与折射。大面积、离屏和超过同时渲染限额的组件使用标准效果。", style = MaterialTheme.typography.bodySmall)
                Text("侧边栏、底部抽屉和弹出菜单使用独立背景绘制，AGSL 折射只作用于页面内支持的组件。", style = MaterialTheme.typography.bodySmall)
                if (!agslAvailable) Text(if (caps.sdkInt < 33) "AGSL 折射需要 Android 13 及以上；本机使用兼容效果。" else "本次运行中 AGSL 折射失败，已回退标准玻璃，重启后重新检测。", color = Color(0xFFF9A825))
                if (!caps.supportsRealtimeBlur) Text("本机不支持实时背景模糊，将采用普通半透明表面。", color = Color(0xFFF9A825))
            }
        })
        item(headlineContent = { Text("自适应降低效果") }, supportingContent = { Text("滚动、输入和省电时暂停折射并降低模糊。关闭后仍保留设备兼容与面积限制。") }, trailingContent = {
            Switch(settings.adaptivePerformance, { enabled -> update { it.copy(liquidGlass = it.liquidGlass.copy(adaptivePerformance = enabled)) } })
        })
        item(onClick = { advanced = !advanced }, headlineContent = { Text(if (advanced) "收起高级选项" else "高级选项") }, supportingContent = { Text("手动调整后自动切换为自定义预设。") })
        if (advanced) {
            item(headlineContent = { Text("高光强度") }, supportingContent = { GlassParameterSlider("控制边缘反光亮度，0 表示关闭", params.highlight, 0f.. .8f, caps.supportsRealtimeBlur) { value -> update { it.copy(liquidGlass = it.liquidGlass.edit { p -> p.copy(highlight = value) }) } } })
            item(headlineContent = { Text("主题染色") }, supportingContent = { GlassParameterSlider("少量混入主题色，0 表示中性玻璃", params.tint, 0f.. .2f, caps.supportsRealtimeBlur) { value -> update { it.copy(liquidGlass = it.liquidGlass.edit { p -> p.copy(tint = value) }) } } })
            item(headlineContent = { Text("边缘厚度") }, supportingContent = { GlassParameterSlider("单位 dp，同时影响折射边缘宽度", params.edgeWidth, .5f..4f, caps.supportsRealtimeBlur) { value -> update { it.copy(liquidGlass = it.liquidGlass.edit { p -> p.copy(edgeWidth = value) }) } } })
            item(headlineContent = { Text("内侧阴影") }, supportingContent = { GlassParameterSlider("只在轮廓内侧增加阴影，0 表示关闭", params.innerShadow, 0f.. .25f, caps.supportsRealtimeBlur) { value -> update { it.copy(liquidGlass = it.liquidGlass.edit { p -> p.copy(innerShadow = value) }) } } })
            item(headlineContent = { Text("边缘折射距离") }, supportingContent = { GlassParameterSlider("单位 dp，0 表示关闭折射", params.refraction, 0f..16f, agslAvailable && settings.renderer != LiquidGlassRenderer.STANDARD) { value -> update { it.copy(liquidGlass = it.liquidGlass.edit { p -> p.copy(refraction = value) }) } } })
            item(onClick = { update { it.copy(liquidGlass = LiquidGlassSettings(applyToComposer = it.liquidGlass.applyToComposer)) } }, headlineContent = { Text("恢复标准材质") })
        }
    }
}

@Composable
private fun GlassParameterSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, enabled: Boolean, onChange: (Float) -> Unit) {
    AdvancedAppearanceSlider(description = label, value = value, valueRange = range, steps = 0, enabled = enabled,
        valueLabel = { "%.2f".format(it) }, onValueChange = onChange)
}

@Composable
private fun LiquidGlassPreview() {
    val backdrop = rememberGlassBackdrop()
    val haze = dev.chrisbanes.haze.rememberHazeState()
    CompositionLocalProvider(LocalGlassBackdrop provides backdrop,
        LocalGlobalBackgroundHazeState provides haze,
        LocalInsideGlassSurface provides false,
        LocalAppearanceBackground provides AppearanceBackgroundSpec(null, 1f, 0f, useGradientBackground = true, gradientAnimation = false, gradientFollowTheme = true)) {
        Box(Modifier.fillMaxWidth().height(150.dp).clip(RoundedCornerShape(16.dp))) {
            AnimatedGradientBackground(GradientBackgroundSpec(animationEnabled = false, followTheme = true), Modifier.fillMaxSize().hazeSource(haze).glassBackdropSource(backdrop))
            IsolatedAppearanceSurface(BackgroundSurfaceStyle.LIQUID_GLASS, .48f, 8f,
                Modifier.align(Alignment.Center).padding(20.dp).fillMaxWidth(), RoundedCornerShape(24.dp)) {
                Column(Modifier.padding(20.dp)) { Text("液态玻璃", style = MaterialTheme.typography.titleMedium); Text("清晰正文 · 柔和边缘", style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}
