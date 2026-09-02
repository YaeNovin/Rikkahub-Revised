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
import me.rerere.rikkahub.ui.components.ui.LocalExportContext
import me.rerere.rikkahub.utils.escapeHtml
import me.rerere.rikkahub.utils.toCssHex

private const val MAX_INTERACTIVE_RENDER_SOURCE_CHARS = 128 * 1024

internal enum class InteractiveCodeRenderer(
    val aliases: Set<String>,
) {
    ECHARTS(setOf("echarts", "chart")),
    ABC(setOf("abc", "abcjs")),
    JIANPU(setOf("jianpu", "numbered", "numbered-notation", "numberednotation", "numbered_notation", "简谱")),
    LEAFLET(setOf("leaflet", "map", "geojson")),
    RAILROAD(setOf("railroad", "railroad-diagram", "grammar")),
    ;

    companion object {
        fun fromLanguage(language: String): InteractiveCodeRenderer? =
            entries.firstOrNull { normalizeCodeFenceLanguage(language) in it.aliases }
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
    val previewHeight = richPreviewHeight(
        minHeightDp = when (renderer) {
            InteractiveCodeRenderer.ABC -> 200
            InteractiveCodeRenderer.JIANPU -> 180
            else -> 240
        },
        maxHeightDp = when (renderer) {
            InteractiveCodeRenderer.RAILROAD -> 420
            InteractiveCodeRenderer.JIANPU -> 360
            else -> 380
        },
        widthFraction = when (renderer) {
            InteractiveCodeRenderer.ABC -> 0.66f
            InteractiveCodeRenderer.JIANPU -> 0.9f
            else -> 0.78f
        },
    )
    WebView(
        state = webViewState,
        deferUntilVisible = !LocalExportContext.current,
        preferParentVerticalScroll = true,
        transparentBackground = true,
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .height(previewHeight),
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
        InteractiveCodeRenderer.JIANPU -> null
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
        InteractiveCodeRenderer.JIANPU -> jianpuRendererScript()
        InteractiveCodeRenderer.LEAFLET -> leafletRendererScript()
        InteractiveCodeRenderer.RAILROAD -> railroadRendererScript()
    }
    val sourceCode = if (renderer == InteractiveCodeRenderer.RAILROAD) {
        normalizeRailroadSource(code, renderErrorMessage)
    } else {
        code
    }
    val source = sourceCode.toJavaScriptStringLiteral()
    val foreground = colorScheme.onSurface.toCssHex()
    val error = colorScheme.error.toCssHex()
    val rendererLayoutCss = when (renderer) {
        InteractiveCodeRenderer.ABC -> """
            html, body { width: 100%; min-height: 100%; margin: 0; overflow: auto; }
            #renderer { width: 100%; min-height: 100%; height: auto; overflow: visible; display: flex; align-items: center; justify-content: center; }
            #renderer svg { display: block; max-width: 100%; height: auto; }
        """.trimIndent()
        InteractiveCodeRenderer.JIANPU -> """
            html, body { width: 100%; min-height: 100%; margin: 0; overflow: auto; }
            body { display: flex; align-items: center; justify-content: center; }
            #renderer { width: 100%; min-height: 100%; height: auto; overflow-x: auto; overflow-y: hidden; -webkit-overflow-scrolling: touch; }
            #renderer svg { display: block; flex: 0 0 auto; max-width: none; height: auto; margin: 0 auto; }
        """.trimIndent()
        InteractiveCodeRenderer.RAILROAD -> """
            html, body { width: 100%; min-height: 100%; margin: 0; overflow: auto; }
            #renderer { width: 100%; min-height: 100%; height: auto; overflow-x: auto; -webkit-overflow-scrolling: touch; }
            #renderer svg { display: block; flex: 0 0 auto; max-width: none; height: auto; margin: 0 auto; }
        """.trimIndent()
        InteractiveCodeRenderer.ECHARTS,
        InteractiveCodeRenderer.LEAFLET -> """
            html, body, #renderer { width: 100%; height: 100%; margin: 0; overflow: hidden; }
            #renderer { display: flex; align-items: center; justify-content: center; }
        """.trimIndent()
    }

    return """
        <!DOCTYPE html>
        <html>
        <head>
            <meta charset="UTF-8">
            <meta name="viewport" content="width=device-width, initial-scale=1.0, minimum-scale=0.25, maximum-scale=8.0, user-scalable=yes">
            $stylesheet
            ${scriptPath?.let { "<script src=\"$WEB_VIEW_ASSET_URL/html/$it\"></script>" }.orEmpty()}
            <style>
                $rendererLayoutCss
                body { background: transparent; color: $foreground; font-family: sans-serif; }
                #renderer { box-sizing: border-box; padding: 8px; }
                #render-error { display: none; white-space: pre-wrap; margin: 0; padding: 12px; color: $error; overflow: auto; }
                .leaflet-container { background: transparent; color: $foreground; }
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
                        requestAnimationFrame(function() {
                            root.querySelectorAll('svg').forEach(function(svg) {
                                svg.setAttribute('preserveAspectRatio', 'xMidYMid meet');
                                const fitToViewport = ${renderer != InteractiveCodeRenderer.JIANPU && renderer != InteractiveCodeRenderer.RAILROAD};
                                if (fitToViewport && svg.viewBox && svg.viewBox.baseVal && svg.viewBox.baseVal.width > 0) {
                                    svg.removeAttribute('width');
                                    svg.removeAttribute('height');
                                    svg.style.width = '100%';
                                    svg.style.height = 'auto';
                                    svg.style.maxWidth = '100%';
                                }
                            });
                            root.scrollLeft = Math.max(0, (root.scrollWidth - root.clientWidth) / 2);
                            root.scrollTop = Math.max(0, (root.scrollHeight - root.clientHeight) / 2);
                        });
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
    const compactViewport = document.documentElement.clientWidth <= 600;
    if (compactViewport) {
        const viewportWidth = Math.max(document.documentElement.clientWidth, root.clientWidth, 160);
        const grids = Array.isArray(option.grid) ? option.grid : [option.grid || {}];
        option.grid = grids.map(function(grid) {
            return Object.assign({ left: 12, right: 12, top: 72, bottom: 32, containLabel: true }, grid);
        });
        if (option.title) {
            const titles = Array.isArray(option.title) ? option.title : [option.title];
            option.title = titles.map(function(title) {
                const textStyle = Object.assign({}, title.textStyle || {}, {
                    width: Math.max(viewportWidth - 32, 128),
                    overflow: 'break',
                    fontSize: Math.min(Number((title.textStyle || {}).fontSize) || 16, 16),
                    lineHeight: 20
                });
                return Object.assign({ left: 'center' }, title, { textStyle: textStyle });
            });
        }
        if (option.tooltip && !Array.isArray(option.tooltip)) {
            option.tooltip = Object.assign({}, option.tooltip, { confine: true });
        }
        if (option.legend && !Array.isArray(option.legend)) {
            option.legend = Object.assign({ type: 'scroll', left: 'center', width: '92%' }, option.legend);
        }
        const radars = option.radar ? (Array.isArray(option.radar) ? option.radar : [option.radar]) : [];
        if (radars.length > 0) {
            option.radar = radars.map(function(radar) {
                return Object.assign({ center: ['50%', '56%'], radius: '58%' }, radar);
            });
        }
        ['xAxis', 'yAxis'].forEach(function(axisName) {
            if (!option[axisName]) return;
            const axes = Array.isArray(option[axisName]) ? option[axisName] : [option[axisName]];
            option[axisName] = axes.map(function(axis) {
                const axisLabel = Object.assign({ hideOverlap: true }, axis.axisLabel || {});
                return Object.assign({}, axis, { axisLabel: axisLabel });
            });
        });
    }
    const chart = echarts.init(root, null, {
        renderer: 'canvas',
        useDirtyRect: true,
        devicePixelRatio: Math.min(window.devicePixelRatio || 1, 2)
    });
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
        staffwidth: Math.max(root.clientWidth - 16, 220)
    });
""".trimIndent()

/** Renders common numbered-notation (简谱) syntax as an offline SVG. */
private fun jianpuRendererScript(): String = """
    const lines = String(source).replace(/\r/g, '').split('\n');
    const notes = [];
    const lyrics = [];
    const metadata = [];
    const tokenPattern = /(\|\||\|\]|\|)|([#b♭n♯]?[0-7](?:['`,]*)(?:[_=.\-]*))/g;
    lines.forEach(function(line) {
        const trimmed = line.trim();
        if (!trimmed || trimmed.charAt(0) === '%') return;
        if (/^(?:L|w):/i.test(trimmed)) {
            lyrics.push(trimmed.replace(/^(?:L|w):\s*/i, ''));
            return;
        }
        if (/^\/key\(/i.test(trimmed) || /^(?:title|t|bpm|tempo)\s*:/i.test(trimmed) || /^ｂｐｍ\s+/i.test(trimmed)) {
            metadata.push(trimmed);
            return;
        }
        let match;
        while ((match = tokenPattern.exec(line)) !== null) {
            notes.push({ token: match[1] || match[2], bar: Boolean(match[1]) });
        }
        tokenPattern.lastIndex = 0;
    });
    const noteWidth = 48;
    const left = 16;
    const width = Math.max(root.clientWidth - 16, left * 2 + Math.max(notes.length, 1) * noteWidth);
    const height = Math.max(132, 76 + metadata.length * 18 + (lyrics.length ? 28 : 0));
    const svgNs = 'http://www.w3.org/2000/svg';
    const svg = document.createElementNS(svgNs, 'svg');
    svg.setAttribute('viewBox', '0 0 ' + width + ' ' + height);
    svg.setAttribute('role', 'img');
    svg.setAttribute('aria-label', 'Jianpu numbered musical notation');
    svg.style.color = getComputedStyle(document.body).color;
    function text(value, x, y, size, anchor) {
        const node = document.createElementNS(svgNs, 'text');
        node.textContent = value;
        node.setAttribute('x', x);
        node.setAttribute('y', y);
        node.setAttribute('font-size', size);
        node.setAttribute('font-family', 'sans-serif');
        node.setAttribute('text-anchor', anchor || 'middle');
        node.setAttribute('fill', 'currentColor');
        svg.appendChild(node);
        return node;
    }
    metadata.slice(0, 5).forEach(function(value, index) {
        text(value, left, 20 + index * 18, 13, 'start');
    });
    const baseY = 50 + Math.min(metadata.length, 5) * 18;
    let noteIndex = 0;
    notes.forEach(function(item) {
        const x = left + noteIndex * noteWidth;
        if (item.bar) {
            const line = document.createElementNS(svgNs, 'line');
            line.setAttribute('x1', x - 14);
            line.setAttribute('x2', x - 14);
            line.setAttribute('y1', baseY - 30);
            line.setAttribute('y2', baseY + 12);
            line.setAttribute('stroke', 'currentColor');
            line.setAttribute('stroke-width', item.token === '||' || item.token === '|]' ? '2' : '1');
            svg.appendChild(line);
            return;
        }
        const raw = item.token;
        const digit = raw.replace(/^[#b♭n♯]/, '').charAt(0);
        const accidentalMatch = raw.match(/^[#b♭n♯]/);
        const octaveMatch = raw.match(/[',]+/);
        const accidental = accidentalMatch ? accidentalMatch[0] : '';
        const octave = octaveMatch ? octaveMatch[0] : '';
        const duration = raw.replace(/^[#b♭n♯]?[0-7][',]*/, '');
        if (accidental) text(accidental === '♯' ? '#' : accidental === '♭' ? 'b' : accidental, x, baseY - 18, 12);
        text(digit === '0' ? '0' : digit, x, baseY, 28);
        for (let i = 0; i < octave.length; i++) {
            const dot = document.createElementNS(svgNs, 'circle');
            dot.setAttribute('cx', x);
            dot.setAttribute('cy', octave.charAt(i) === ',' ? baseY + 8 + i * 5 : baseY - 31 - i * 5);
            dot.setAttribute('r', '2');
            dot.setAttribute('fill', 'currentColor');
            svg.appendChild(dot);
        }
        if (duration.indexOf('_') >= 0 || duration.indexOf('=') >= 0) {
            const beam = document.createElementNS(svgNs, 'line');
            beam.setAttribute('x1', x - 11);
            beam.setAttribute('x2', x + 11);
            beam.setAttribute('y1', baseY + 7);
            beam.setAttribute('y2', baseY + 7);
            beam.setAttribute('stroke', 'currentColor');
            beam.setAttribute('stroke-width', duration.indexOf('=') >= 0 ? '2' : '1');
            svg.appendChild(beam);
        }
        if (duration.indexOf('.') >= 0) {
            const dot = document.createElementNS(svgNs, 'circle');
            dot.setAttribute('cx', x + 15);
            dot.setAttribute('cy', baseY - 5);
            dot.setAttribute('r', '2');
            dot.setAttribute('fill', 'currentColor');
            svg.appendChild(dot);
        }
        noteIndex++;
    });
    if (lyrics.length) text(lyrics.join('  '), width / 2, height - 12, 13);
    while (root.firstChild) root.removeChild(root.firstChild);
    root.appendChild(svg);
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
