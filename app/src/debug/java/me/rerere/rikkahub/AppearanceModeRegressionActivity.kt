package me.rerere.rikkahub

import android.graphics.Bitmap
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import me.rerere.rikkahub.data.datastore.*
import me.rerere.rikkahub.ui.components.ui.*
import me.rerere.rikkahub.ui.context.*
import me.rerere.rikkahub.ui.theme.*
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.ui.components.message.MessagePartsBlock
import me.rerere.rikkahub.ui.components.message.rememberMessageTextPalette
import java.io.File

/** The production root theme + global material; no real preferences are read or written. */
class AppearanceModeRegressionActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val material = BackgroundSurfaceStyle.entries.firstOrNull { it.name == intent.getStringExtra("material") }
            ?: BackgroundSurfaceStyle.LIQUID_GLASS
        val amoled = intent.getBooleanExtra("amoled", false)
        val backdrop = intent.getStringExtra("backdrop") ?: "dark"
        if (intent.getStringExtra("scenario") == "state") {
            setContent { PaletteStateProbe(material, amoled) }
            return
        }
        val image = if (backdrop == "real") File(filesDir, "diagnostic-background").toURI().toString()
        else if (backdrop == "none") null else File(cacheDir, "palette-$backdrop.png").apply {
            val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(if (backdrop == "bright") 0xFFF9E8FF.toInt() else 0xFF17233B.toInt())
            outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }.toURI().toString()
        setContent {
            val context = LocalContext.current
            var providedSeed by remember { mutableStateOf<Int?>(null) }
            LaunchedEffect(image) {
                if (intent.getBooleanExtra("providedWallpaper", false) && image != null) {
                    providedSeed = extractBackgroundBitmapAnalysis(context, image)?.seedArgb
                }
            }
            var mode by remember { mutableStateOf(TextColorMode.THEME) }
            val settings = remember(mode, providedSeed) { Settings(dynamicColor = false).let { base ->
                base.copy(advancedAppearanceSetting = base.advancedAppearanceSetting.copy(
                    textColorMode = mode, pageSurfaceStyle = material, pageSurfaceOpacity = .35f,
                    wallpaperTextSeedArgb = providedSeed?.toLong()?.and(0xFFFF_FFFFL),
                    enableAutoAccent = backdrop == "real", autoAccentColorArgb = if (backdrop == "real") 4284510360L else null,
                    enableGlobalBackground = image != null, globalBackground = image,
                    applyGlobalBackgroundToChat = image != null, enableBubblePerformanceEffects = false))
            } }
            RikkahubThemeContent(settings, ColorMode.DARK, amoled, amoled) {
                val original = LocalBaseThemeColorScheme.current!!
                val readability = rememberBackgroundReadability(image, 1f, false, overlayTopAlpha = .28f, overlayBottomAlpha = .42f)
                val spec = image?.let { AppearanceBackgroundSpec(it, 1f, 0f, foreground = readability.foreground, readability = readability) }
                CompositionLocalProvider(LocalSettings provides settings, LocalAppearanceBackground provides spec,
                    LocalGlobalBackgroundActive provides true, LocalPageSurfaceStyle provides material,
                    LocalAppearanceSurfaceOpacityPolicy provides appearanceSurfaceOpacityPolicy(
                        .35f, .35f, 1f, 1f, image != null, material),
                    LocalToaster provides com.dokar.sonner.rememberToasterState(),
                    LocalNavController provides remember { Navigator(mutableListOf()) }) {
                    Box(Modifier.fillMaxSize().background(original.background)) {
                        GlobalAppBackground(settings, Modifier.fillMaxSize())
                        GlobalGlassTheme(true, .35f, material) {
                            Column(Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                                Text("真实主题与材质配色回归", style = MaterialTheme.typography.titleLarge)
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    TextColorMode.entries.forEach { value ->
                                        FilterChip(mode == value, { mode = value }, label = { Text(value.name) })
                                    }
                                }
                                ProbeText("page", mode, MaterialTheme.colorScheme.onBackground, "页面正文颜色 / English 123")
                                ProbeText("inherited", mode, LocalContentColor.current, "继承正文颜色 / English 123")
                                AppearanceCard {
                                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        ProbeText("card", mode, LocalContentColor.current, "设置卡片正文 / English 123")
                                        ProbeText("card-token", mode, MaterialTheme.colorScheme.onSurface, "卡片主题文字 / English 123")
                                        Text("设置说明文字", style = MaterialTheme.typography.bodyMedium)
                                    }
                                }
                                CardGroup {
                                    item(headlineContent = {
                                        ProbeText("setting-token", mode, MaterialTheme.colorScheme.onSurface, "设置项主题文字 / English 123")
                                    })
                                }
                                ProbeMessage(true, mode)
                                ProbeMessage(false, mode)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PaletteStateProbe(material: BackgroundSurfaceStyle, amoled: Boolean) {
    var mode by remember { mutableStateOf(TextColorMode.THEME) }
    var active by remember { mutableStateOf(false) }
    var dark by remember { mutableStateOf(true) }
    val settings = remember(mode) { Settings(dynamicColor = false).let { base ->
        base.copy(advancedAppearanceSetting = base.advancedAppearanceSetting.copy(textColorMode = mode))
    } }
    RikkahubThemeContent(settings, if (dark) ColorMode.DARK else ColorMode.LIGHT, amoled, amoled) {
        CompositionLocalProvider(LocalSettings provides settings) {
            GlobalGlassTheme(active, .35f, material) {
                val instance = remember { java.util.UUID.randomUUID().toString().take(8) }
                val list = rememberLazyListState()
                Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
                    .safeDrawingPadding().padding(12.dp)) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        TextColorMode.entries.forEach { value ->
                            FilterChip(mode == value, { mode = value }, label = { Text(value.name) })
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button({ active = !active }) { Text("PAGE_ACTIVE") }
                        Button({ dark = !dark }) { Text("DARK_MODE") }
                    }
                    Text("Scroll anchor", Modifier.semantics {
                        contentDescription = "state:$instance:${list.firstVisibleItemIndex}:${list.firstVisibleItemScrollOffset}"
                    })
                    LazyColumn(Modifier.weight(1f).semantics { contentDescription = "palette-scroll" }, state = list) {
                        items(100, key = { it }) { Text("Anchor row $it", Modifier.fillMaxWidth().height(60.dp)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProbeMessage(user: Boolean, mode: TextColorMode) {
    val settings = LocalSettings.current
    val theme = LocalBaseThemeColorScheme.current!!
    val backgrounds = LocalAppearanceBackground.current?.readability?.backgrounds
        ?.takeIf { settings.hasActiveChatBackground() } ?: listOf(theme.background.copy(alpha = 1f))
    val container = if (user) MaterialTheme.colorScheme.primaryContainer.copy(alpha = settings.displaySetting.bubbleOpacity)
        else Color.Transparent
    val palette = rememberMessageTextPalette(container, backgrounds, if (user) theme.onPrimaryContainer else theme.onSurface)
    val role = if (user) "message-user" else "message-body"
    val minimum = palette.backgrounds.minOf { wcagContrastRatio(palette.foreground, it) }
    val style = LocalTextStyle.current
    val shadow = style.shadow != null
    val weight = style.fontWeight?.weight ?: 400
    val neutral = readableForegroundColor(palette.backgrounds)
    val retention = palette.backgrounds.minOf { wcagContrastRatio(palette.foreground, it) / wcagContrastRatio(neutral, it) }
    Box(Modifier.semantics { contentDescription = "actual:$role:${mode.name}:${palette.foreground.toArgb().toUInt().toString(16)}:$minimum:$shadow:$weight:$retention" }) {
        MessagePartsBlock(null, if (user) MessageRole.USER else MessageRole.ASSISTANT, null, null,
            listOf(UIMessagePart.Text(if (user) "用户消息 / User 123" else "消息正文 / Body 123")), emptyList(), false)
    }
}

@Composable
private fun ProbeText(role: String, mode: TextColorMode, color: Color, text: String) {
    val backgrounds = me.rerere.rikkahub.ui.components.richtext.LocalTextSurfaceBackgrounds.current
        ?: LocalAppearanceBackground.current?.readability?.backgrounds.orEmpty()
    val minimum = backgrounds.minOfOrNull { wcagContrastRatio(color, it) } ?: 21f
    val style = LocalTextStyle.current.merge(MaterialTheme.typography.bodyLarge)
    val shadow = style.shadow != null
    val weight = style.fontWeight?.weight ?: 400
    val neutral = readableForegroundColor(backgrounds)
    val retention = backgrounds.minOfOrNull { wcagContrastRatio(color, it) / wcagContrastRatio(neutral, it) } ?: 1f
    Text(text, Modifier.semantics { contentDescription = "actual:$role:${mode.name}:${color.toArgb().toUInt().toString(16)}:$minimum:$shadow:$weight:$retention" },
        color = color, style = style)
}
