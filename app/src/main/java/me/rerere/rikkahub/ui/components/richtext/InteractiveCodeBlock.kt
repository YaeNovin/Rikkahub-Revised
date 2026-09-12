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
    RAILROAD(setOf("railroad", "railroad-diagram", "grammar", "ebnf")),
    WAVEFORM(setOf("wavedrom", "waveform", "timing", "digital-waveform")),
    GRAPHVIZ(setOf("dot", "graphviz")),
    VEGA(setOf("vega", "vega-lite", "vegalite")),
    MOLECULE(setOf("smiles", "smiles-drawer", "molecule")),
    MUSICXML(setOf("musicxml", "music-xml")),
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
    onViewCreated: (android.webkit.WebView) -> Unit = {},
) {
    val colorScheme = me.rerere.rikkahub.ui.theme.LocalBackgroundBaseColorScheme.current ?: MaterialTheme.colorScheme
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
        onCreated = onViewCreated,
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
        InteractiveCodeRenderer.WAVEFORM -> "renderers/wavedrom.min.js"
        InteractiveCodeRenderer.GRAPHVIZ -> null // ESM loaded in an isolated worker.
        InteractiveCodeRenderer.VEGA -> "renderers/vega.min.js"
        InteractiveCodeRenderer.MOLECULE -> "renderers/smiles-drawer.js"
        InteractiveCodeRenderer.MUSICXML -> "renderers/opensheetmusicdisplay.js"
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
        else -> additionalRendererScript(renderer)
    }
    val sourceCode = when (renderer) {
        InteractiveCodeRenderer.RAILROAD -> normalizeRailroadSource(code, renderErrorMessage)
        InteractiveCodeRenderer.ECHARTS -> normalizeChartSource(code)
        InteractiveCodeRenderer.LEAFLET, InteractiveCodeRenderer.VEGA,
        InteractiveCodeRenderer.WAVEFORM -> normalizeDiagramData(code, renderer)
        else -> code
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
        InteractiveCodeRenderer.ECHARTS -> """
            html, body, #renderer { width: 100%; height: 100%; margin: 0; overflow: hidden; }
            #renderer { display: flex; align-items: center; justify-content: center; }
        """.trimIndent()
        InteractiveCodeRenderer.WAVEFORM,
        InteractiveCodeRenderer.GRAPHVIZ,
        InteractiveCodeRenderer.VEGA,
        InteractiveCodeRenderer.MOLECULE,
        InteractiveCodeRenderer.MUSICXML -> """
            html, body { width: 100%; min-height: 100%; margin: 0; overflow: auto; }
            #renderer { width: 100%; min-height: 180px; height: auto; overflow: auto; background: #fff; color: #222; border-radius: 8px; }
            #renderer > svg, #renderer > canvas { display: block; max-width: 100%; height: auto; margin: auto; }
            #renderer .vega-embed { width: 100%; }
        """.trimIndent()
        InteractiveCodeRenderer.LEAFLET -> """
            html, body, #renderer { width: 100%; height: 100%; margin: 0; overflow: hidden; }
            #renderer.leaflet-container { display: block; padding: 0; }
            #renderer.rikka-offline-map {
                background-color: ${colorScheme.secondaryContainer.toCssHex()};
                --rikka-map-land: ${colorScheme.surfaceContainerHigh.toCssHex()};
                --rikka-map-border: ${colorScheme.outline.toCssHex()};
            }
            #map-warning {
                position: fixed; left: 8px; right: 8px; bottom: 24px; z-index: 1000;
                padding: 6px 8px; border-radius: 6px; pointer-events: none;
                font-size: 12px; max-height: 4em; overflow: hidden;
                background: ${colorScheme.surface.toCssHex()}; color: $foreground;
            }
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
            ${additionalRendererResources(renderer)}
            ${if (renderer == InteractiveCodeRenderer.LEAFLET) "<script src=\"$WEB_VIEW_ASSET_URL/html/renderers/offline-basemap.js\"></script>" else ""}
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
                (async function() {
                    const source = $source;
                    window.__rikkaRenderStatus = 'loading';
                    const root = document.getElementById('renderer');
                    const errorView = document.getElementById('render-error');
                    const localizedRenderError = document.getElementById('localized-render-error').textContent;
                    function fail(error) {
                        window.__rikkaRenderStatus = 'error';
                        console.error(error);
                        root.style.display = 'none';
                        errorView.style.display = 'block';
                        errorView.textContent = localizedRenderError + (error && error.message ? '\n' + String(error.message).slice(0, 240) : '');
                    }
                    try {
                        const renderWatchdog = setTimeout(function() { if (window.__rikkaRenderStatus === 'loading') fail(new Error('图形渲染超时，请减少数据量后重试')); }, 20000);
                        $rendererScript
                        requestAnimationFrame(function() {
                            if (window.__rikkaRenderStatus === 'error') return;
                            clearTimeout(renderWatchdog);
                            window.__rikkaRenderStatus = 'ready';
                            // Leaflet owns its panes' pixel dimensions and scroll offsets.
                            if (${renderer == InteractiveCodeRenderer.LEAFLET || renderer in ADDITIONAL_RENDERERS}) return;
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
    const config = JSON.parse(source);
    if (config.rikkaRenderError) throw new Error(config.rikkaRenderError);
    const option = config.option || config;
    const builtinMapCache = {};
    async function loadBuiltinMap(kind) {
        if (kind !== 'china' && kind !== 'world') throw new Error('没有内置的地图数据：' + kind);
        if (!builtinMapCache[kind]) {
            builtinMapCache[kind] = fetch('$WEB_VIEW_ASSET_URL/html/maps/' + kind + '.json').then(function(response) {
                if (!response.ok) throw new Error('内置地图资源加载失败：' + kind);
                return response.json();
            });
        }
        return builtinMapCache[kind];
    }
    if (config.maps) {
        for (const name of Object.keys(config.maps)) {
            const mapData = config.maps[name];
            echarts.registerMap(name, mapData && Object.keys(mapData).length === 1 && mapData.rikkaBuiltinMap
                ? await loadBuiltinMap(mapData.rikkaBuiltinMap) : mapData);
        }
    }
    const mapNames = [];
    (Array.isArray(option.geo) ? option.geo : [option.geo]).forEach(function(geo) { if (geo && geo.map) mapNames.push(geo.map); });
    (Array.isArray(option.series) ? option.series : [option.series]).forEach(function(series) { if (series && series.type === 'map') mapNames.push(series.map); });
    for (const name of mapNames) {
        if (!echarts.getMap(name)) {
            const key = String(name || '').toLowerCase();
            const builtin = ['china', '中国', '中国地图'].indexOf(key) >= 0 ? 'china'
                : ['world', '世界', '世界地图'].indexOf(key) >= 0 ? 'world' : null;
            if (builtin) echarts.registerMap(name, await loadBuiltinMap(builtin));
        }
        if (!echarts.getMap(name)) throw new Error('缺少地图数据：' + name + '。请在同一代码块中提供 GeoJSON 并注册地图。');
    }
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
    const onResize = function() {
        if (resizePending) return;
        resizePending = true;
        requestAnimationFrame(function() {
            resizePending = false;
            chart.resize({ animation: { duration: 0 } });
        });
    };
    if (typeof ResizeObserver === 'function') new ResizeObserver(onResize).observe(root);
    else window.addEventListener('resize', onResize);
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
    function object(value) { return value !== null && typeof value === 'object' && !Array.isArray(value); }
    if (!object(config)) throw new Error('Map configuration must be a JSON object');
    const warnings = new Set();
    const warningView = document.createElement('div');
    warningView.id = 'map-warning';
    warningView.setAttribute('role', 'status');
    warningView.hidden = true;
    document.body.appendChild(warningView);
    function warn(message) {
        warnings.add(message);
        warningView.textContent = Array.from(warnings).join('；');
        warningView.hidden = false;
        console.warn(message);
    }
    function number(value) {
        if (typeof value !== 'number' && (typeof value !== 'string' || !value.trim())) return NaN;
        return Number(value);
    }
    function latLng(value) {
        if (object(value)) value = [value.lat, value.lng];
        if (!Array.isArray(value) || value.length < 2) return null;
        const lat = number(value[0]), lng = number(value[1]);
        return Number.isFinite(lat) && Number.isFinite(lng) && Math.abs(lat) <= 90 && Math.abs(lng) <= 180 ? [lat, lng] : null;
    }
    function zoomValue(value, fallback) {
        const parsed = number(value);
        return Number.isFinite(parsed) ? Math.max(0, Math.min(22, parsed)) : fallback;
    }
    function list(value, label) {
        if (value == null) return [];
        if (Array.isArray(value)) return value;
        warn(label + '格式无效，已跳过');
        return [];
    }
    function pathOptions(value) {
        const options = object(value) ? Object.assign({}, value) : {};
        // JSON cannot supply Leaflet renderer instances or callback functions.
        delete options.renderer;
        if ('pane' in options && (typeof options.pane !== 'string' || !map.getPane(options.pane))) delete options.pane;
        ['radius', 'weight', 'opacity', 'fillOpacity', 'smoothFactor'].forEach(function(key) {
            if (!(key in options)) return;
            const value = number(options[key]);
            if (!Number.isFinite(value) || value < 0) delete options[key];
            else options[key] = key === 'opacity' || key === 'fillOpacity' ? Math.min(1, value) : value;
        });
        return options;
    }
    const center = latLng(config.center) || [0, 0];
    if (config.center != null && !latLng(config.center)) warn('中心坐标无效，已使用默认视角');
    const zoom = zoomValue(config.zoom, 2);
    const map = L.map(root, { zoomControl: config.zoomControl !== false, maxZoom: 22 }).setView(center, zoom);
    // Raw GeoJSON has no basemap information. Use the packaged offline geography;
    // only an explicitly configured tileUrl can opt into network tiles.
    const hasTileUrl = Object.prototype.hasOwnProperty.call(config, 'tileUrl');
    const basemapDisabled = config.basemap === false || config.basemap === 'none' ||
        (hasTileUrl && (config.tileUrl === null || config.tileUrl === false || config.tileUrl === ''));
    const useOffline = !hasTileUrl || config.basemap === 'offline';
    const tileUrl = config.tileUrl;
    async function offlineBasemap() {
        try { await window.rikkaOfflineBasemap(map, root); }
        catch (error) { warn('离线底图加载失败；数据图层仍可查看'); }
    }
    if (!basemapDisabled && useOffline) await offlineBasemap();
    if (!basemapDisabled && !useOffline) {
        let tiles;
        try {
            if (typeof tileUrl !== 'string' || !/^https?:\/\//i.test(tileUrl.trim())) throw new Error('Invalid tile URL');
            const tileOptions = Object.assign({}, object(config.tileOptions) ? config.tileOptions : {});
            // Reject unavailable panes and malformed JSON values before Leaflet creates tiles.
            if ('pane' in tileOptions && (typeof tileOptions.pane !== 'string' || !map.getPane(tileOptions.pane))) delete tileOptions.pane;
            ['tileSize', 'minZoom', 'maxZoom', 'minNativeZoom', 'maxNativeZoom', 'zoomOffset', 'opacity'].forEach(function(key) {
                if (!(key in tileOptions)) return;
                const value = number(tileOptions[key]);
                if (!Number.isFinite(value) || (key === 'tileSize' && value <= 0)) delete tileOptions[key];
                else tileOptions[key] = key === 'opacity' ? Math.max(0, Math.min(1, value)) : value;
            });
            tiles = L.tileLayer(tileUrl.trim(), tileOptions);
            const tileFailure = '在线底图加载失败，已显示离线概览；数据图层仍可查看';
            let failedTiles = 0;
            tiles.on('loading', function() { failedTiles = 0; });
            tiles.on('tileerror', function() { failedTiles++; warn(tileFailure); offlineBasemap(); });
            tiles.on('load', function() {
                if (!failedTiles) {
                    warnings.delete(tileFailure);
                    warningView.textContent = Array.from(warnings).join('；');
                    warningView.hidden = warnings.size === 0;
                }
            });
            tiles.addTo(map);
        } catch (error) {
            if (tiles && map.hasLayer(tiles)) map.removeLayer(tiles);
            warn('底图配置无效；数据图层仍可查看');
            await offlineBasemap();
        }
    }
    const bounds = L.latLngBounds([]);
    function extendBounds(value) {
        if (value && value.isValid && value.isValid()) bounds.extend(value);
    }
    list(config.markers, '标记列表').forEach(function(marker) {
        const position = latLng(marker);
        if (!position) { warn('部分标记坐标无效，已跳过'); return; }
        try {
            const item = L.circleMarker(position, Object.assign({ radius: 7 }, pathOptions(marker.options))).addTo(map);
            if (typeof marker.popup === 'string') item.bindPopup(marker.popup);
            bounds.extend(item.getLatLng());
        } catch (error) { warn('部分标记配置无效，已跳过'); }
    });
    list(config.polylines, '路线列表').forEach(function(line) {
        if (!object(line) || !Array.isArray(line.points) || line.points.length < 2) {
            warn('部分路线格式无效，已跳过'); return;
        }
        const points = line.points.map(latLng);
        // Do not connect points across an invalid coordinate and invent a route.
        if (points.some(function(point) { return point === null; })) {
            warn('部分路线坐标无效，已跳过'); return;
        }
        try {
            const item = L.polyline(points, pathOptions(line.options)).addTo(map);
            extendBounds(item.getBounds());
        } catch (error) { warn('部分路线配置无效，已跳过'); }
    });
    const geoJson = config.geoJson || config.geojson || (
        typeof config.type === 'string' && /^(Feature|FeatureCollection|GeometryCollection|Point|MultiPoint|LineString|MultiLineString|Polygon|MultiPolygon)$/.test(config.type)
            ? config
            : null
    );
    function positionValid(position) {
        // GeoJSON uses longitude, latitude (the reverse of Leaflet's arrays).
        return Array.isArray(position) && position.length >= 2 &&
            typeof position[0] === 'number' && Number.isFinite(position[0]) && Math.abs(position[0]) <= 180 &&
            typeof position[1] === 'number' && Number.isFinite(position[1]) && Math.abs(position[1]) <= 90;
    }
    function positionsValid(positions, minimum) {
        return Array.isArray(positions) && positions.length >= minimum && positions.every(positionValid);
    }
    function ringValid(ring) {
        return positionsValid(ring, 4) && ring[0][0] === ring[ring.length - 1][0] && ring[0][1] === ring[ring.length - 1][1];
    }
    function polygonValid(polygon) { return Array.isArray(polygon) && polygon.length > 0 && polygon.every(ringValid); }
    function geometryValid(geometry) {
        if (!object(geometry)) return false;
        const coordinates = geometry.coordinates;
        switch (geometry.type) {
            case 'Point': return positionValid(coordinates);
            case 'MultiPoint': return positionsValid(coordinates, 0);
            case 'LineString': return positionsValid(coordinates, 2);
            case 'MultiLineString': return Array.isArray(coordinates) && coordinates.every(function(line) { return positionsValid(line, 2); });
            case 'Polygon': return polygonValid(coordinates);
            case 'MultiPolygon': return Array.isArray(coordinates) && coordinates.every(polygonValid);
            case 'GeometryCollection': return Array.isArray(geometry.geometries) && geometry.geometries.every(geometryValid);
            default: return false;
        }
    }
    if (geoJson) {
        const options = pathOptions(config.geoJsonOptions);
        ['filter', 'coordsToLatLng', 'onEachFeature'].forEach(function(key) { delete options[key]; });
        if ('style' in options && !object(options.style)) delete options.style;
        if (object(options.style)) options.style = pathOptions(options.style);
        // Default GeoJSON points otherwise request marker-icon.png, which is not
        // bundled. Use local vector markers for Point and MultiPoint alike.
        options.pointToLayer = function(_feature, position) {
            return L.circleMarker(position, Object.assign({ radius: 7 }, options, options.style || {}));
        };
        function addGeoJson(value) {
            if (Array.isArray(value)) { value.forEach(addGeoJson); return; }
            if (object(value) && value.type === 'FeatureCollection') {
                list(value.features, 'GeoJSON 要素列表').forEach(addGeoJson); return;
            }
            if (object(value) && value.type === 'GeometryCollection') {
                list(value.geometries, 'GeoJSON 几何列表').forEach(addGeoJson); return;
            }
            if (object(value) && value.type === 'Feature' && value.geometry === null) return;
            const geometry = object(value) && value.type === 'Feature' ? value.geometry : value;
            if (object(value) && value.type === 'Feature' && object(geometry) && geometry.type === 'GeometryCollection') {
                list(geometry.geometries, 'GeoJSON 几何列表').forEach(function(member) {
                    addGeoJson(Object.assign({}, value, { geometry: member }));
                });
                return;
            }
            if (!geometryValid(geometry)) { warn('部分 GeoJSON 几何数据无效，已跳过'); return; }
            try {
                const item = L.geoJSON(value, options).addTo(map);
                extendBounds(item.getBounds());
            } catch (error) { warn('部分 GeoJSON 配置无效，已跳过'); }
        }
        addGeoJson(geoJson);
    }
    let fitted = false;
    let userMoved = false;
    map.on('dragstart zoomstart', function() { userMoved = true; });
    function refreshMapViewport() {
        if (root.clientWidth <= 0 || root.clientHeight <= 0) return;
        map.invalidateSize({ animate: false, pan: true });
        if (!fitted && !userMoved && config.fitBounds !== false && bounds.isValid()) {
            // The bundled overview has no street detail. Start a lone point at regional
            // scale so users see coastline/boundaries instead of a featureless land fill.
            map.fitBounds(bounds, { padding: [16, 16], maxZoom: zoomValue(config.maxFitZoom, useOffline && !basemapDisabled ? 6 : 14), animate: false });
            fitted = true;
        }
    }
    requestAnimationFrame(refreshMapViewport);
    setTimeout(refreshMapViewport, 120);
    let resizePending = false;
    const onResize = function() {
        if (resizePending) return;
        resizePending = true;
        requestAnimationFrame(function() {
            resizePending = false;
            refreshMapViewport();
        });
    };
    if (typeof ResizeObserver === 'function') new ResizeObserver(onResize).observe(root);
    else window.addEventListener('resize', onResize);
""".trimIndent()

private fun railroadRendererScript(): String = """
    const spec = JSON.parse(source);
    if (spec.rikkaRenderError) throw new Error(spec.rikkaRenderError);
    function node(value) {
        if (typeof value === 'string') return Terminal(value);
        if (!value || typeof value !== 'object') return Skip();
        const items = Array.isArray(value.items) ? value.items : [];
        switch (value.type) {
            case 'nonterminal': return NonTerminal(value.text || '');
            case 'comment': return Comment(value.text || '');
            case 'skip': return Skip();
            case 'sequence': return Sequence(...items.map(node));
            case 'stack':
                if (!items.length) throw new Error('Stack 至少需要一个子节点');
                return Stack(...items.map(node));
            case 'choice': return Choice(Number.isInteger(value.normal) ? value.normal : 0, ...items.map(node));
            case 'optional': return Optional(node(items[0]), value.skip ? 'skip' : undefined);
            case 'oneOrMore': return OneOrMore(node(items[0]), items[1] ? node(items[1]) : undefined);
            case 'zeroOrMore': return ZeroOrMore(node(items[0]), items[1] ? node(items[1]) : undefined);
            default: return Terminal(value.text || '');
        }
    }
    const items = Array.isArray(spec) ? spec : [spec];
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
