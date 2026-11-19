package me.rerere.rikkahub.ui.components.richtext

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.rikkahub.data.render.RendererCatalog
import me.rerere.rikkahub.data.render.compileDeclarativeRenderSpec
import me.rerere.rikkahub.data.render.isDeclarativeRenderer
import me.rerere.rikkahub.ui.components.ui.LocalExportContext
import me.rerere.rikkahub.ui.components.webview.WEB_VIEW_ASSET_URL
import me.rerere.rikkahub.ui.components.webview.WEB_VIEW_BASE_URL
import me.rerere.rikkahub.ui.components.webview.WebView
import me.rerere.rikkahub.ui.components.webview.rememberWebViewState
import org.jsoup.Jsoup
import java.util.Base64

internal fun geometryRenderer(language: String, source: String? = null): String? {
    val normalized = normalizeCodeFenceLanguage(language)
    val renderer = RendererCatalog.find(normalized)?.id?.takeIf(::isDeclarativeRenderer) ?: return null
    // `jsxgraph` is a safe math-JSON alias for tools. A raw JavaScript fence
    // must remain source-only; never feed arbitrary JSXGraph code to JSON.parse.
    if (normalized == "jsxgraph" && source != null && !source.trimStart().startsWith("{")) return null
    return renderer
}

internal fun canRenderGeometryPreview(language: String, source: String, complete: Boolean): Boolean =
    complete && geometryRenderer(language, source) != null && source.length <= me.rerere.rikkahub.data.render.MAX_DECLARATIVE_SOURCE_CHARS

internal fun isGeometryPreview(content: String): Boolean = content.contains("geometry-typed") &&
    Jsoup.parse(content).head().selectFirst("meta[name=rikkahub-preview][content=geometry-typed]") != null

internal fun geometrySupportsBackground(id: String?): Boolean = id == "sigil" || id == "pattern"

internal fun isDecorativeGeometryPreview(content: String): Boolean = isGeometryPreview(content) &&
    geometrySupportsBackground(Jsoup.parse(content).head().selectFirst("meta[name=rikkahub-geometry-kind]")?.attr("content"))

internal fun prepareGeometryFullscreenHtml(content: String, useBackground: Boolean): String {
    val document = Jsoup.parse(content)
    document.outputSettings().prettyPrint(false)
    val transparent = useBackground && isDecorativeGeometryPreview(content)
    document.body().attr("data-fullscreen", "true").attr("data-preview-background", transparent.toString())
    document.select("html,body").forEach {
        if (transparent) it.addClass("rikka-preview-global-background") else it.removeClass("rikka-preview-global-background")
    }
    return document.outerHtml()
}

internal fun buildGeometryPreviewHtml(id: String, source: String, dark: Boolean = false): String {
    require(isDeclarativeRenderer(id))
    val data = runCatching { compileDeclarativeRenderSpec(id, source) }.getOrElse { failure ->
        me.rerere.rikkahub.data.render.RenderDiagnostics.report(id, "compile", failure.message ?: "Invalid parameters", failure)
        buildJsonObject { put("kind", id); put("error", failure.message ?: "绘图参数无效") }
    }
    val encoded = Base64.getEncoder().encodeToString(data.toString().toByteArray(Charsets.UTF_8))
    val assets = "$WEB_VIEW_ASSET_URL/html/renderers"
    val background = if (dark) "#161b22" else "#ffffff"
    val foreground = if (dark) "#e6edf3" else "#24292f"
    val jsx = if (id == "math_plot") "<link rel=\"stylesheet\" href=\"$assets/jsxgraph-1.13.3.css\"><script defer src=\"$assets/jsxgraph-1.13.3.js\"></script>" else ""
    val scripts = buildList {
        add("geometry-core.js")
        add("geometry-gestures.js")
        if (id != "math_plot") add("decorative-effects.js")
        if (id == "math_plot") add("math-plot-runtime.js")
        if (id == "pattern") addAll(listOf("p5-1.11.11.min.js", "pattern-geometry.js", "pattern-preview.js"))
        add("geometry-preview.js")
    }
    val scriptPolicy = (scripts + if (id == "math_plot") listOf("jsxgraph-1.13.3.js") else emptyList()).joinToString(" ") { "$assets/$it" }
    val scriptTags = scripts.joinToString("\n") { "<script defer src=\"$assets/$it\"></script>" }
    return """
        <!doctype html><html><head><meta charset="utf-8">
        <meta name="rikkahub-preview" content="geometry-typed">
        <meta name="rikkahub-geometry-kind" content="$id">
        <meta name="viewport" content="width=device-width,initial-scale=1">
        <meta http-equiv="Content-Security-Policy" content="default-src 'none'; script-src $scriptPolicy; style-src 'unsafe-inline' $assets/jsxgraph-1.13.3.css; connect-src 'none'; frame-src 'none'; object-src 'none'; base-uri 'none'; form-action 'none'">
        <style>
        html,body{margin:0;width:100%;height:100%;background:$background;color:$foreground;font:13px system-ui,sans-serif;overflow:hidden}
        body{display:flex;flex-direction:column;box-sizing:border-box}header{padding:8px;flex-shrink:0}
        #controls{display:flex;align-items:center;gap:8px;padding:4px 8px;flex-shrink:0}
        #title{flex:1;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;font-weight:600}
        button{font:inherit;color:inherit;background:transparent;border:0;border-radius:0;padding:8px 12px;min-height:44px;min-width:44px}
        button:disabled{opacity:.5}button:focus-visible,#stage:focus-visible{outline:2px solid currentColor;outline-offset:-2px}
        #stage{position:relative;flex:1;min-height:100px;margin:0;overflow:hidden;touch-action:none}
        #board,#sigil,#pattern{width:100%;height:100%;border:0;border-radius:0;box-sizing:border-box}
        html.rikka-preview-global-background,body.rikka-preview-global-background{background:transparent!important}
        body.rikka-preview-global-background header,body.rikka-preview-global-background #controls,
        body.rikka-preview-global-background #status,body.rikka-preview-global-background #legend,
        body.rikka-preview-global-background #parameters,body.rikka-preview-global-background #error{
          background:transparent;color:var(--rikka-chrome-foreground,$foreground);
          text-shadow:0 1px 2px var(--rikka-chrome-shadow,$background),0 0 3px var(--rikka-chrome-shadow,$background)
        }
        body.rikka-preview-global-background button{text-shadow:inherit}
        #pattern{overflow:hidden}#pattern canvas{display:block;width:100%!important;height:100%!important}
        #effects{position:absolute;inset:0;width:100%;height:100%;pointer-events:none;contain:strict}
        #parameters{display:flex;flex-wrap:wrap;gap:4px 12px;padding:0 8px;max-height:84px;overflow:auto;flex-shrink:0}
        #parameters label{display:flex;align-items:center;gap:6px;font-size:12px}input[type=range]{width:110px}
        #legend{padding:4px 8px;display:flex;gap:8px;flex-wrap:wrap;max-height:40px;overflow:auto;flex-shrink:0}
        #status{margin:0;padding:4px 8px;font-size:11px;flex-shrink:0;min-height:16px}#error{white-space:pre-wrap;overflow:auto;padding:12px;color:#dc2626}
        [hidden]{display:none!important}#stage svg{display:block}.JXGtext{color:$foreground}
        </style>
        $jsx
        $scriptTags
        </head><body data-source="$encoded" data-background="$background" data-foreground="$foreground" data-fullscreen="false" data-preview-background="false">
        <header><div id="title"></div></header>
        <div id="parameters"></div><div id="stage"><div id="board" hidden></div><div id="pattern" hidden></div><svg id="sigil" xmlns="http://www.w3.org/2000/svg" viewBox="-110 -110 220 220" role="img" aria-label="几何图案" hidden></svg><canvas id="effects" width="1" height="1" aria-hidden="true" hidden></canvas><pre id="error" hidden></pre></div>
        <div id="legend"></div><p id="status" role="status"></p><div id="controls"><button id="reset">重置</button><button id="variation" hidden>变换</button><button id="play" hidden>播放</button></div></body></html>
    """.trimIndent()
}

@Composable
internal fun GeometryCodePreview(html: String, onViewCreated: (android.webkit.WebView) -> Unit) {
    val decorative = remember(html) { isDecorativeGeometryPreview(html) }
    val useBackground = decorative && me.rerere.rikkahub.ui.context.LocalGlobalBackgroundActive.current &&
        me.rerere.rikkahub.ui.context.LocalPageSurfaceStyle.current != me.rerere.rikkahub.data.datastore.BackgroundSurfaceStyle.OPAQUE
    val state = rememberWebViewState(data = html, baseUrl = WEB_VIEW_BASE_URL, mimeType = "text/html", encoding = "UTF-8",
        settings = { blockNetworkLoads = true; allowFileAccess = false; allowContentAccess = false; domStorageEnabled = false })
    val chromeForeground = previewBackgroundForeground()
    LaunchedEffect(state.webView, state.isLoading, useBackground, chromeForeground) {
        if (!state.isLoading) state.webView?.evaluateJavascript(previewBackgroundScript(useBackground, chromeForeground), null)
    }
    WebView(state, onCreated = onViewCreated, deferUntilVisible = !LocalExportContext.current,
        transparentBackground = decorative,
        preferParentVerticalScroll = true, nativeZoomEnabled = false,
        modifier = Modifier.fillMaxWidth(), previewHeight = richPreviewHeight(minHeightDp = 300, maxHeightDp = 440, widthFraction = 1f))
}
