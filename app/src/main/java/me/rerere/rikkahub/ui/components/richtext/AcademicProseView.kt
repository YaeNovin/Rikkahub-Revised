package me.rerere.rikkahub.ui.components.richtext

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.*
import me.rerere.rikkahub.data.render.LinguisticRenderingSettings
import me.rerere.rikkahub.ui.components.ui.LocalExportContext
import me.rerere.rikkahub.ui.components.webview.*
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.utils.toCssHex
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node

// WebView invokes annotated methods reflectively; the declaring class must be public on the JVM.
internal class ProseHeightBridge(private val update: (Float) -> Unit) {
    @Volatile var active = true
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    @android.webkit.JavascriptInterface fun resized(value: Float) {
        if (value.isFinite() && value in 1f..24000f) handler.post { if (active) update(value) }
    }
}

internal fun buildAcademicProsePage(
    nodes: List<Node>, settings: LinguisticRenderingSettings, math: Boolean,
    colors: ColorScheme, foreground: Color = colors.onSurface, fontSize: Float = 16f,
    preserveLines: Boolean = false, fontWeight: Int = 400, decorations: ChatInlineOptions = ChatInlineOptions(),
    quoteColor: Color = colors.primary,
): String {
    val markup = academicProseHtml(nodes, settings, math, decorations)
    val assets = "$WEB_VIEW_ASSET_URL/html/renderers"
    val size = fontSize.takeIf { it.isFinite() }?.coerceIn(8f, 96f) ?: 16f
    return """
        <!doctype html><html><head><meta charset="utf-8">
        <meta name="rikkahub-preview" content="academic-prose-v1">
        <meta name="viewport" content="width=device-width,initial-scale=1">
        <meta http-equiv="Content-Security-Policy" content="default-src 'none'; script-src $assets/; style-src 'unsafe-inline' $assets/; font-src $assets/; img-src 'none'; connect-src 'none'; frame-src 'none'; base-uri 'none'; form-action 'none'">
        <link rel="stylesheet" href="$assets/katex.min.css"><link rel="stylesheet" href="$assets/academic-prose.css">
        <style>:root{--fg:${foreground.toCssHex()};--bg:${colors.surface.toCssHex()};--accent:${colors.primary.toCssHex()};--quote:${quoteColor.toCssHex()};--panel:${colors.surfaceContainerHigh.toCssHex()};font-size:${size}px;font-weight:${fontWeight.coerceIn(100, 900)}}
        #academic{white-space:${if (preserveLines) "pre-wrap" else "normal"}}</style>
        </head><body><article id="academic">$markup</article>
        <script src="$assets/katex.min.js"></script><script src="$assets/mhchem.min.js"></script>
        <script src="$assets/linguistics-v1.js"></script><script src="$assets/academic-prose.js"></script></body></html>
    """.trimIndent()
}

@Composable
internal fun AcademicProse(nodes: List<Node>, modifier: Modifier = Modifier, onClickCitation: (String) -> Unit = {}) {
    val display = LocalSettings.current.displaySetting
    val colors = MaterialTheme.colorScheme
    val density = LocalDensity.current
    val style = LocalTextStyle.current
    val textColor = style.color.takeUnless { it == Color.Unspecified } ?: LocalContentColor.current
    val fontSize = style.fontSize.takeIf { it != androidx.compose.ui.unit.TextUnit.Unspecified } ?: 16.sp
    val cssFontSize = with(density) { fontSize.toPx() / density.density }
    val preserve = LocalPreserveSoftLineBreaks.current
    val decorations = LocalChatInlineOptions.current
    val quoteColor = me.rerere.rikkahub.ui.theme.chatQuoteAccent(colors.primary,
        LocalTextSurfaceBackgrounds.current ?: listOf(colors.surface), textColor)
    val markup = nodes.joinToString("") { it.outerHtml() }
    val html = remember(markup, display.linguisticRendering, display.enableLatexRendering, colors, textColor, cssFontSize, preserve, style.fontWeight, decorations, quoteColor) {
        buildAcademicProsePage(nodes, display.linguisticRendering, display.enableLatexRendering, colors, textColor, cssFontSize, preserve,
            style.fontWeight?.weight ?: 400, decorations, quoteColor)
    }
    AcademicProsePage(html, modifier, onClickCitation)
}

@Composable
internal fun AcademicProsePage(html: String, modifier: Modifier = Modifier, onClickCitation: (String) -> Unit = {}) {
    val density = LocalDensity.current
    val exporting = LocalExportContext.current
    val uriHandler = LocalUriHandler.current
    // Remember measured height across theme reloads and streaming updates.
    var height by remember { mutableStateOf(48.dp) }
    val bridge = remember(html) { ProseHeightBridge { value -> height = value.coerceIn(24f, 6000f).dp } }
    DisposableEffect(bridge) { onDispose { bridge.active = false } }
    val state = rememberWebViewState(html, baseUrl = WEB_VIEW_BASE_URL, interfaces = mapOf("RikkaProse" to bridge), settings = {
        blockNetworkLoads = true; allowContentAccess = false; allowFileAccess = false
        domStorageEnabled = false; builtInZoomControls = false; displayZoomControls = false
        textZoom = 100; useWideViewPort = true; loadWithOverviewMode = false
    })
    LaunchedEffect(state.webView, state.isLoading, html, density) {
        val view = state.webView ?: return@LaunchedEffect
        if (state.isLoading || exporting) return@LaunchedEffect
        val deadline = android.os.SystemClock.elapsedRealtime() + 10_000
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            val value = withTimeoutOrNull(1000) { suspendCancellableCoroutine<String> { continuation ->
                view.evaluateJavascript("window.__rikkaRenderStatus==='ready'?Math.ceil(document.getElementById('academic').getBoundingClientRect().height):0") {
                    if (continuation.isActive) continuation.resumeWith(Result.success(it))
                }
            } }?.toFloatOrNull() ?: 0f
            if (value.isFinite() && value > 0f) {
                height = value.coerceIn(24f, 6000f).dp
                return@LaunchedEffect
            }
            kotlinx.coroutines.delay(100)
        }
    }
    WebView(state, modifier.fillMaxWidth().testTag("academic-prose"), deferUntilVisible = !exporting,
        preferParentVerticalScroll = true, transparentBackground = true, nativeZoomEnabled = false,
        previewHeight = height, onNavigate = { url ->
            if (url.startsWith("rikka-citation:")) onClickCitation(url.removePrefix("rikka-citation:"))
            else me.rerere.rikkahub.utils.webLinkUrl(url)?.let(uriHandler::openUri)
        })
}
