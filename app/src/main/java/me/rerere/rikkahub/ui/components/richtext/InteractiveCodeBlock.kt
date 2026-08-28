package me.rerere.rikkahub.ui.components.richtext

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.components.webview.WEB_VIEW_ASSET_URL
import me.rerere.rikkahub.ui.components.webview.WEB_VIEW_BASE_URL
import me.rerere.rikkahub.ui.components.webview.WebView
import me.rerere.rikkahub.ui.components.webview.rememberWebViewState
import me.rerere.rikkahub.utils.escapeHtml
import me.rerere.rikkahub.utils.toCssHex

private const val MAX_INTERACTIVE_RENDER_SOURCE_CHARS = 128 * 1024

internal enum class InteractiveCodeRenderer(
    val aliases: Set<String>,
) {
    ECHARTS(setOf("echarts", "chart")),
    ABC(setOf("abc", "abcjs")),
    LEAFLET(setOf("leaflet", "map", "geojson")),
    RAILROAD(setOf("railroad", "railroad-diagram", "grammar")),
    ;

    companion object {
        fun fromLanguage(language: String): InteractiveCodeRenderer? =
            entries.firstOrNull { language.lowercase() in it.aliases }
    }
}

internal fun canRenderInteractiveCodeBlock(language: String, code: String): Boolean =
    code.length <= MAX_INTERACTIVE_RENDER_SOURCE_CHARS &&
        InteractiveCodeRenderer.fromLanguage(language) != null

@Composable
internal fun InteractiveCodeBlock(
    renderer: InteractiveCodeRenderer,
    code: String,
    modifier: Modifier = Modifier,
) {
    val colorScheme = MaterialTheme.colorScheme
    val renderErrorMessage = stringResource(R.string.error_message_rich_content_render)
    val html = remember(renderer, code, colorScheme, renderErrorMessage) {
        buildInteractiveRendererHtml(renderer, code, colorScheme, renderErrorMessage)
    }
    val webViewState = rememberWebViewState(
        data = html,
        baseUrl = WEB_VIEW_BASE_URL,
        mimeType = "text/html",
        encoding = "UTF-8",
        settings = {
            builtInZoomControls = true
            displayZoomControls = false
            useWideViewPort = true
            loadWithOverviewMode = true
            allowFileAccess = false
            allowContentAccess = false
            domStorageEnabled = false
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
        },
    )
    WebView(
        state = webViewState,
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .height(240.dp),
    )
}

internal fun buildInteractiveRendererHtml(
    renderer: InteractiveCodeRenderer,
    code: String,
    colorScheme: ColorScheme,
    renderErrorMessage: String = "Unable to render this content.",
): String {
    val scriptPath = when (renderer) {
        InteractiveCodeRenderer.ECHARTS -> "renderers/echarts.min.js"
        InteractiveCodeRenderer.ABC -> "renderers/abcjs-basic-min.js"
        InteractiveCodeRenderer.LEAFLET -> "renderers/leaflet.js"
        InteractiveCodeRenderer.RAILROAD -> "renderers/railroad-diagrams.js"
    }
    val stylesheet = when (renderer) {
        InteractiveCodeRenderer.LEAFLET -> "<link rel=\"stylesheet\" href=\"$WEB_VIEW_ASSET_URL/html/renderers/leaflet.css\">"
        InteractiveCodeRenderer.RAILROAD -> "<link rel=\"stylesheet\" href=\"$WEB_VIEW_ASSET_URL/html/renderers/railroad-diagrams.css\">"
        else -> ""
    }
    val rendererScript = when (renderer) {
        InteractiveCodeRenderer.ECHARTS -> echartsRendererScript()
        InteractiveCodeRenderer.ABC -> abcRendererScript()
        InteractiveCodeRenderer.LEAFLET -> leafletRendererScript()
        InteractiveCodeRenderer.RAILROAD -> railroadRendererScript()
    }
    val sourceCode = if (renderer == InteractiveCodeRenderer.RAILROAD) {
        normalizeRailroadSource(code, renderErrorMessage)
    } else {
        code
    }
    val source = sourceCode.toJavaScriptStringLiteral()
    val background = colorScheme.surface.toCssHex()
    val foreground = colorScheme.onSurface.toCssHex()
    val error = colorScheme.error.toCssHex()
    val rendererLayoutCss = when (renderer) {
        InteractiveCodeRenderer.ABC,
        InteractiveCodeRenderer.RAILROAD -> """
            html, body { width: 100%; min-height: 100%; margin: 0; overflow: auto; }
            #renderer { width: 100%; min-height: 100%; height: auto; overflow: visible; }
        """.trimIndent()
        InteractiveCodeRenderer.ECHARTS,
        InteractiveCodeRenderer.LEAFLET -> """
            html, body, #renderer { width: 100%; height: 100%; margin: 0; overflow: hidden; }
        """.trimIndent()
    }

    return """
        <!DOCTYPE html>
        <html>
        <head>
            <meta charset="UTF-8">
            <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=4.0">
            $stylesheet
            <script src="$WEB_VIEW_ASSET_URL/html/$scriptPath"></script>
            <style>
                $rendererLayoutCss
                body { background: $background; color: $foreground; font-family: sans-serif; }
                #renderer { box-sizing: border-box; padding: 8px; }
                #render-error { display: none; white-space: pre-wrap; margin: 0; padding: 12px; color: $error; overflow: auto; }
                .leaflet-container { background: $background; color: $foreground; }
            </style>
        </head>
        <body>
            <div id="renderer"></div>
            <pre id="render-error"></pre>
            <span id="localized-render-error" hidden>${renderErrorMessage.escapeHtml()}</span>
            <script>
                (function() {
                    const source = $source;
                    const root = document.getElementById('renderer');
                    const errorView = document.getElementById('render-error');
                    const localizedRenderError = document.getElementById('localized-render-error').textContent;
                    function fail(error) {
                        console.error(error);
                        root.style.display = 'none';
                        errorView.style.display = 'block';
                        errorView.textContent = localizedRenderError;
                    }
                    try {
                        $rendererScript
                    } catch (error) {
                        fail(error);
                    }
                })();
            </script>
        </body>
        </html>
    """.trimIndent()
}

private fun echartsRendererScript(): String = """
    const option = JSON.parse(source);
    const chart = echarts.init(root, null, { renderer: 'canvas', useDirtyRect: true });
    chart.setOption(option, { notMerge: true, lazyUpdate: true });
    let resizePending = false;
    new ResizeObserver(function() {
        if (resizePending) return;
        resizePending = true;
        requestAnimationFrame(function() {
            resizePending = false;
            chart.resize({ animation: { duration: 0 } });
        });
    }).observe(root);
""".trimIndent()

private fun abcRendererScript(): String = """
    ABCJS.renderAbc('renderer', source, {
        responsive: 'resize',
        add_classes: false,
        staffwidth: Math.max(document.documentElement.clientWidth - 24, 280)
    });
""".trimIndent()

private fun leafletRendererScript(): String = """
    const config = JSON.parse(source);
    const center = Array.isArray(config.center) ? config.center : [0, 0];
    const zoom = Number.isFinite(config.zoom) ? config.zoom : 2;
    const map = L.map(root, { zoomControl: config.zoomControl !== false }).setView(center, zoom);
    if (typeof config.tileUrl === 'string' && config.tileUrl.length > 0) {
        L.tileLayer(config.tileUrl, config.tileOptions || {}).addTo(map);
    }
    const bounds = L.latLngBounds([]);
    function extendBounds(value) {
        if (value && value.isValid && value.isValid()) bounds.extend(value);
    }
    (config.markers || []).forEach(function(marker) {
        if (!Number.isFinite(marker.lat) || !Number.isFinite(marker.lng)) return;
        const item = L.circleMarker([marker.lat, marker.lng], Object.assign({ radius: 7 }, marker.options || {})).addTo(map);
        if (typeof marker.popup === 'string') item.bindPopup(marker.popup);
        extendBounds(item.getBounds());
    });
    (config.polylines || []).forEach(function(line) {
        if (!Array.isArray(line.points) || line.points.length < 2) return;
        const item = L.polyline(line.points, line.options || {}).addTo(map);
        extendBounds(item.getBounds());
    });
    const geoJson = config.geoJson || (
        typeof config.type === 'string' && /^(Feature|FeatureCollection|GeometryCollection|Point|MultiPoint|LineString|MultiLineString|Polygon|MultiPolygon)$/.test(config.type)
            ? config
            : null
    );
    if (geoJson) {
        const item = L.geoJSON(geoJson, config.geoJsonOptions || {}).addTo(map);
        extendBounds(item.getBounds());
    }
    function refreshMapViewport() {
        map.invalidateSize(false);
        if (config.fitBounds !== false && bounds.isValid()) {
            map.fitBounds(bounds, { padding: [16, 16], maxZoom: config.maxFitZoom || 14 });
        }
    }
    requestAnimationFrame(refreshMapViewport);
    setTimeout(refreshMapViewport, 120);
    let resizePending = false;
    new ResizeObserver(function() {
        if (resizePending) return;
        resizePending = true;
        requestAnimationFrame(function() {
            resizePending = false;
            refreshMapViewport();
        });
    }).observe(root);
""".trimIndent()

private fun railroadRendererScript(): String = """
    const spec = JSON.parse(source);
    function node(value) {
        if (typeof value === 'string') return Terminal(value);
        if (!value || typeof value !== 'object') return Skip();
        const items = Array.isArray(value.items) ? value.items : [];
        switch (value.type) {
            case 'nonterminal': return NonTerminal(value.text || '');
            case 'comment': return Comment(value.text || '');
            case 'skip': return Skip();
            case 'sequence': return Sequence(...items.map(node));
            case 'choice': return Choice(Number.isInteger(value.normal) ? value.normal : 0, ...items.map(node));
            case 'optional': return Optional(node(items[0]), value.skip ? 'skip' : undefined);
            case 'oneOrMore': return OneOrMore(node(items[0]), items[1] ? node(items[1]) : undefined);
            case 'zeroOrMore': return ZeroOrMore(node(items[0]), items[1] ? node(items[1]) : undefined);
            default: return Terminal(value.text || '');
        }
    }
    const items = Array.isArray(spec) ? spec : (spec.items || [spec]);
    Diagram(...items.map(node)).addTo(root);
""".trimIndent()

private fun String.toJavaScriptStringLiteral(): String = buildString(length + 2) {
    append('"')
    for (character in this@toJavaScriptStringLiteral) {
        when (character) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            '\b' -> append("\\b")
            '\u000C' -> append("\\f")
            '<' -> append("\\u003c")
            '>' -> append("\\u003e")
            '&' -> append("\\u0026")
            '\u2028' -> append("\\u2028")
            '\u2029' -> append("\\u2029")
            else -> {
                if (character.code < 0x20) {
                    append("\\u")
                    append(character.code.toString(16).padStart(4, '0'))
                } else {
                    append(character)
                }
            }
        }
    }
    append('"')
}
