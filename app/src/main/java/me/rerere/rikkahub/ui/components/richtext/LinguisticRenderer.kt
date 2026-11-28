package me.rerere.rikkahub.ui.components.richtext

import androidx.compose.material3.ColorScheme
import androidx.compose.foundation.layout.fillMaxWidth
import kotlinx.serialization.json.*
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.data.render.compileLinguisticSpec
import me.rerere.rikkahub.ui.components.webview.WEB_VIEW_ASSET_URL
import me.rerere.rikkahub.utils.toCssHex

/** Author text is compiled to canonical data; only this app-owned script creates DOM/SVG. */
internal fun buildLinguisticRendererHtml(id: String, code: String, colors: ColorScheme, errorMessage: String, fontScale: Float = 1f): String {
    val textScale = if (fontScale.isFinite()) fontScale.coerceIn(0.5f, 3f) else 1f
    val data = runCatching { compileLinguisticSpec(id, code) }.getOrElse { error ->
        buildJsonObject { put("error", error.message ?: "Invalid linguistic input") }
    }.toString().toJavaScriptStringLiteral()
    return """
        <!DOCTYPE html><html><head><meta charset="UTF-8">
        <meta name="rikkahub-preview" content="linguistic-v1">
        <meta name="viewport" content="width=device-width,initial-scale=1,minimum-scale=0.25,maximum-scale=8,user-scalable=yes">
        <meta http-equiv="Content-Security-Policy" content="default-src 'none'; script-src 'unsafe-inline' $WEB_VIEW_ASSET_URL/; style-src 'unsafe-inline'; font-src $WEB_VIEW_ASSET_URL/; img-src 'none'; connect-src 'none'; base-uri 'none'">
        <style>
        @font-face{font-family:RikkaIPA;src:url('$WEB_VIEW_ASSET_URL/html/renderers/fonts/DoulosSIL-Regular.woff2') format('woff2');font-display:block}
        :root{color-scheme:light dark;--fg:${colors.onSurface.toCssHex()};--bg:${colors.surface.toCssHex()};--muted:${colors.onSurfaceVariant.toCssHex()};--accent:${colors.primary.toCssHex()};--line:${colors.outline.toCssHex()}}
        *{box-sizing:border-box}html,body{margin:0;min-height:100%;background:transparent;color:var(--fg);font-family:system-ui,sans-serif;font-size:16px}
        #renderer{padding:12px;overflow:auto;min-height:100%;line-height:1.65;zoom:$textScale}h2{font-size:1.05em;margin:0 0 12px;overflow-wrap:anywhere}
        .ipa{font-family:RikkaIPA,serif;font-size:1.5em;font-weight:400;line-height:2;white-space:pre-wrap;overflow-wrap:anywhere;padding:.2em .1em;font-synthesis:none}
        .ruby-line{font-size:1.3em;line-height:2.8;overflow-wrap:anywhere}ruby{ruby-position:over;ruby-align:center}rt{font-size:.6em;color:var(--fg)}
        .vertical-scroll{overflow:auto;direction:rtl}.vertical-rl,.vertical-lr{writing-mode:vertical-rl;text-orientation:mixed;direction:ltr;height:22em;max-height:520px;width:max-content;line-break:strict;font-feature-settings:'vert' 1,'vrt2' 1;white-space:pre-wrap}.vertical-lr{writing-mode:vertical-lr}
        .word-list{display:flex;flex-wrap:wrap;gap:16px 22px;align-items:start}.word{display:flex;flex-direction:column;flex:none;max-width:none;white-space:nowrap;unicode-bidi:isolate}
        .word>span{display:block;line-height:1.9;font-family:RikkaIPA,system-ui,sans-serif}.word .gloss{color:var(--fg)}
        .translation{margin:14px 0 6px;white-space:pre-wrap;overflow-wrap:anywhere}.meta,.legend{font-size:.85em;color:var(--muted);white-space:pre-wrap;overflow-wrap:anywhere}
        .graph-scroll{overflow:auto;margin-block:12px;direction:ltr}.ling-graph{display:block;max-width:none!important;flex:none;color:var(--fg);font-family:RikkaIPA,system-ui,sans-serif;font-size:18px}
        .ling-graph text{fill:currentColor;unicode-bidi:plaintext}.ling-graph .edge{stroke:currentColor;fill:none;stroke-width:1.5}.ling-graph .enhanced{stroke-dasharray:5 4}
        .ling-graph .relation{font:14px system-ui,sans-serif}
        details{font-size:.85em;margin-block:12px}summary{cursor:pointer}table{border-collapse:collapse;font-size:.9em;white-space:pre}td,th{border:1px solid var(--line);padding:4px 8px;text-align:start}
        #render-error{white-space:pre-wrap;padding:12px;color:${colors.error.toCssHex()};margin:0}#fallback{white-space:pre-wrap;overflow-wrap:anywhere;max-height:240px;overflow:auto}
        </style></head><body><main id="renderer" aria-label="语言学内容"></main><pre id="render-error" hidden></pre>
        <script src="$WEB_VIEW_ASSET_URL/html/renderers/linguistics-v1.js"></script>
        <script>
        window.__rikkaRenderStatus='loading';
        (async function(){try{const data=JSON.parse($data);if(data.error)throw new Error(data.error);
        await window.rikkaRenderLinguistics(document.getElementById('renderer'),data);
        window.__rikkaRenderStatus='ready';
        }catch(e){window.__rikkaRenderStatus='error';document.getElementById('renderer').hidden=true;
        const error=document.getElementById('render-error');error.hidden=false;error.textContent=${errorMessage.toJavaScriptStringLiteral()}+'\n'+String(e.message||e).slice(0,300)+'\n请切换至源码查看原始内容。';}})();
        </script></body></html>
    """.trimIndent()
}

@androidx.compose.runtime.Composable
internal fun LinguisticCodePreview(html: String, onViewCreated: (android.webkit.WebView) -> Unit = {}) {
    val density = androidx.compose.ui.platform.LocalDensity.current
    val exporting = me.rerere.rikkahub.ui.components.ui.LocalExportContext.current
    // Keep the last measurement while theme/content reloads; resetting it on a
    // text-color change would move the surrounding conversation twice.
    val height = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(180.dp) }
    val state = me.rerere.rikkahub.ui.components.webview.rememberWebViewState(
        data = html,
        baseUrl = me.rerere.rikkahub.ui.components.webview.WEB_VIEW_BASE_URL,
        settings = {
            builtInZoomControls = true
            displayZoomControls = false
            useWideViewPort = true
            loadWithOverviewMode = false
            blockNetworkLoads = true
            allowFileAccess = false
            allowContentAccess = false
            domStorageEnabled = false
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
        },
    )
    // Measure content, not the root's percentage min-height. One bounded measurement
    // per load avoids fixed empty boxes and does not poll while the user scrolls.
    androidx.compose.runtime.LaunchedEffect(state.webView, state.isLoading, html, density, exporting) {
        if (exporting || state.isLoading) return@LaunchedEffect
        val view = state.webView ?: return@LaunchedEffect
        val deadline = android.os.SystemClock.elapsedRealtime() + 12_000
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            val response = kotlinx.coroutines.withTimeoutOrNull(1_000) {
                kotlinx.coroutines.suspendCancellableCoroutine<String> { continuation ->
                    view.evaluateJavascript("""
                        (function(){
                          const status=window.__rikkaRenderStatus;
                          if(status!=='ready'&&status!=='error')return null;
                          const root=document.getElementById('renderer');
                          const rect=root.getBoundingClientRect(),style=getComputedStyle(root);
                          const bottom=Math.max(rect.top,...Array.from(root.children).filter(n=>!n.hidden).map(n=>n.getBoundingClientRect().bottom));
                          const height=status==='error'?document.getElementById('render-error').scrollHeight:
                            bottom-rect.top+parseFloat(style.paddingBottom)*(parseFloat(style.zoom)||1);
                          return JSON.stringify({height:height,viewport:innerHeight});
                        })()
                    """.trimIndent()) { result -> if (continuation.isActive) continuation.resumeWith(Result.success(result)) }
                }
            }
            val measurement = runCatching { Json.parseToJsonElement(Json.parseToJsonElement(response ?: "null").jsonPrimitive.content).jsonObject }.getOrNull()
            val contentHeight = measurement?.get("height")?.jsonPrimitive?.doubleOrNull ?: 0.0
            val viewport = measurement?.get("viewport")?.jsonPrimitive?.doubleOrNull ?: 0.0
            if (contentHeight.isFinite() && contentHeight > 0 && viewport > 0 && view.height > 0) {
                height.value = with(density) { (contentHeight * view.height / viewport).toFloat().toDp() }.coerceIn(88.dp, 400.dp)
                return@LaunchedEffect
            }
            kotlinx.coroutines.delay(100)
        }
    }
    me.rerere.rikkahub.ui.components.webview.WebView(
        state = state,
        onCreated = onViewCreated,
        deferUntilVisible = !exporting,
        preferParentVerticalScroll = true,
        transparentBackground = true,
        modifier = androidx.compose.ui.Modifier.fillMaxWidth(),
        previewHeight = height.value,
    )
}
