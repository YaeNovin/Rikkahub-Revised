package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import me.rerere.material3.createDynamicColorScheme
import me.rerere.rikkahub.data.datastore.ResolvedChatBackground
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.isGlobalBackgroundActive
import me.rerere.rikkahub.data.datastore.resolveChatBackground
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.MicaBaseSurface
import me.rerere.rikkahub.ui.components.ui.MicaCard
import me.rerere.rikkahub.ui.theme.LocalBaseThemeColorScheme
import me.rerere.rikkahub.ui.theme.LocalDarkMode
import me.rerere.rikkahub.ui.theme.MicaPalette
import me.rerere.rikkahub.ui.theme.createGradientBackgroundPalette
import me.rerere.rikkahub.ui.theme.extractBackgroundSamples
import me.rerere.rikkahub.ui.theme.gradientReadabilitySamples
import me.rerere.rikkahub.ui.theme.micaPalette
import kotlin.math.roundToInt

/** This only chooses a preview color source; it never enables or rewrites a background. */
internal fun Settings.micaPreviewBackground(): ResolvedChatBackground? {
    val appearance = advancedAppearanceSetting
    val candidate = if (isGlobalBackgroundActive() && appearance.globalBackgroundOpacity > 0f) {
        ResolvedChatBackground(appearance.globalBackground, appearance.globalBackgroundOpacity,
            blurRadius = 0f, useGradientBackground = false, usesGlobalBackground = true)
    } else resolveChatBackground()
    return candidate.takeIf { it.isActive && it.opacity.isFinite() && it.opacity > 0f }
}

internal enum class MicaPreviewSource(val label: String) {
    THEME("无背景"), DEMO("示例色调"), CURRENT("当前背景")
}

private enum class MicaPreviewMode(val label: String) { FOLLOW("跟随"), LIGHT("浅色"), DARK("深色") }

internal val MicaDemoSamples = listOf(Color(0xFF517CBA), Color(0xFF8872B3), Color(0xFF75A6AE))

private data class MicaImageSamples(
    val colors: List<Color> = emptyList(),
    val loading: Boolean = false,
    val failed: Boolean = false,
)

@Composable
private fun rememberMicaImageSamples(source: String?, retry: Int): MicaImageSamples {
    val context = LocalContext.current.applicationContext
    // New sources get a fresh holder immediately. A cancelled old request cannot paint a new preview.
    var result by remember(source, retry) { mutableStateOf(MicaImageSamples(loading = source != null)) }
    LaunchedEffect(source, retry) {
        if (source == null) return@LaunchedEffect
        result = try {
            val colors = extractBackgroundSamples(context, source, 1f, Color.White).orEmpty()
            MicaImageSamples(colors = colors, failed = colors.isEmpty())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            MicaImageSamples(failed = true)
        }
    }
    return result
}

@Composable
internal fun MicaAppearancePreviewCard(settings: Settings) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    CardGroup(modifier = Modifier.padding(horizontal = 8.dp), title = { Text("云母式外观 · 小范围预览") }) {
        item(
            onClick = { expanded = !expanded },
            headlineContent = { Text(if (expanded) "收起云母预览" else "打开云母预览") },
            supportingContent = { Text("有无背景均可使用。先对照底色与卡片层次，预览调整仅在此处生效。") },
        )
        if (expanded) item(headlineContent = { Text("底层与卡片") }, supportingContent = {
            MicaAppearancePreview(settings)
        })
    }
}

@Composable
internal fun MicaAppearancePreview(settings: Settings, modifier: Modifier = Modifier) {
    val appScheme = LocalBaseThemeColorScheme.current ?: MaterialTheme.colorScheme
    val appDark = LocalDarkMode.current
    val background = settings.micaPreviewBackground()
    var source by rememberSaveable { mutableStateOf(MicaPreviewSource.THEME) }
    var mode by rememberSaveable { mutableStateOf(MicaPreviewMode.FOLLOW) }
    var strength by rememberSaveable { mutableFloatStateOf(.45f) }
    var retry by remember { mutableIntStateOf(0) }
    val image = rememberMicaImageSamples(background?.takeUnless { it.useGradientBackground }?.background, retry)
    val dark = when (mode) { MicaPreviewMode.FOLLOW -> appDark; MicaPreviewMode.LIGHT -> false; MicaPreviewMode.DARK -> true }
    val seed = appScheme.primary.toArgb().toLong()
    // Do not run the app theme composable: its system-bar effects belong to the real page.
    val previewScheme = if (dark == appDark) appScheme else remember(seed, dark) {
        createDynamicColorScheme(primaryColorArgb = seed, dark = dark)
    }
    val gradientSamples = if (background?.useGradientBackground == true) {
        val palette = createGradientBackgroundPalette(appScheme, appDark, background.gradientFollowTheme,
            background.gradientPreset, background.gradientCustomColors)
        remember(palette, background.gradientIntensity, appScheme.background) {
            gradientReadabilitySamples(palette, 1f, background.gradientIntensity, appScheme.background)
        }
    } else emptyList()
    val samples = when (source) {
        MicaPreviewSource.THEME -> emptyList()
        MicaPreviewSource.DEMO -> MicaDemoSamples
        MicaPreviewSource.CURRENT -> if (background?.useGradientBackground == true) gradientSamples else image.colors
    }
    val effectiveStrength = strength * if (source == MicaPreviewSource.CURRENT && background != null) background.opacity.coerceIn(0f, 1f) else 1f
    val palette = remember(previewScheme.primary, previewScheme.onSurface, previewScheme.onSurfaceVariant,
        samples, dark, effectiveStrength) { micaPalette(previewScheme, dark, samples, effectiveStrength) }
    val description = when (source) {
        MicaPreviewSource.THEME -> "无背景：取当前主题色，保留柔和底色与完整卡片层次。"
        MicaPreviewSource.DEMO -> "示例色调：用蓝紫色样本模拟背景取色，不代表已设置背景图片。"
        MicaPreviewSource.CURRENT -> when {
            background == null -> "当前没有可用背景，预览已回退主题底色。"
            image.loading && !background.useGradientBackground -> "正在读取背景色调，暂用主题底色。"
            image.failed && !background.useGradientBackground -> "背景暂时无法读取，已回退主题底色。"
            background.usesGlobalBackground -> "取自全局背景：只保留色调，不显示图片细节。"
            background.useGradientBackground -> "取自当前助手的渐变配色，预览保持静态。"
            else -> "取自当前助手背景：只保留色调，不显示图片细节。"
        }
    }

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MicaPreviewSource.entries.forEach { choice ->
                FilterChip(selected = source == choice, onClick = { source = choice },
                    enabled = choice != MicaPreviewSource.CURRENT || background != null,
                    label = { Text(choice.label) })
            }
        }
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            MicaPreviewMode.entries.forEachIndexed { index, choice ->
                SegmentedButton(selected = mode == choice, onClick = { mode = choice },
                    shape = SegmentedButtonDefaults.itemShape(index, MicaPreviewMode.entries.size)) { Text(choice.label) }
            }
        }
        Text(description, style = MaterialTheme.typography.bodySmall)
        if (source == MicaPreviewSource.CURRENT && image.failed && background?.useGradientBackground == false) {
            TextButton(onClick = { retry++ }) { Text("重新读取背景") }
        }
        Text("染色强度 ${(strength * 100).roundToInt()}%", style = MaterialTheme.typography.labelLarge)
        Slider(value = strength, onValueChange = { strength = it }, modifier = Modifier.semantics { contentDescription = "云母染色强度" })
        MicaPreviewScene(palette)
        Text("背景取色更有个性；无背景更简洁。两种方式都可用，层次不依赖图片。", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
internal fun MicaPreviewScene(palette: MicaPalette, modifier: Modifier = Modifier) {
    MicaBaseSurface(palette, modifier.fillMaxWidth().testTag("mica-preview-scene")) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("RikkaHub", color = palette.foreground, style = MaterialTheme.typography.titleLarge)
            Text("留一点空间，给今天的灵感。", color = palette.secondaryForeground, style = MaterialTheme.typography.bodyMedium)
            MicaCard(palette, Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("继续对话", style = MaterialTheme.typography.titleMedium)
                    Text("整理旅行路线，也记下沿途的小发现。", color = palette.secondaryForeground, style = MaterialTheme.typography.bodyMedium)
                    Text("旅行计划 · 今天", color = palette.accent, style = MaterialTheme.typography.labelLarge)
                }
            }
            MicaCard(palette, Modifier.fillMaxWidth(), raised = true) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("阅读笔记", style = MaterialTheme.typography.titleMedium)
                    Text("把值得留下的想法，慢慢整理成篇。", color = palette.secondaryForeground, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}
