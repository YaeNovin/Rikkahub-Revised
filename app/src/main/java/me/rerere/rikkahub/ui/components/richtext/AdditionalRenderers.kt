package me.rerere.rikkahub.ui.components.richtext

import me.rerere.rikkahub.ui.components.webview.WEB_VIEW_ASSET_URL

internal val ADDITIONAL_RENDERERS = setOf(InteractiveCodeRenderer.WAVEFORM, InteractiveCodeRenderer.GRAPHVIZ,
    InteractiveCodeRenderer.VEGA, InteractiveCodeRenderer.MOLECULE, InteractiveCodeRenderer.MUSICXML, InteractiveCodeRenderer.MEI)

internal fun additionalRendererResources(renderer: InteractiveCodeRenderer): String = when (renderer) {
    InteractiveCodeRenderer.WAVEFORM -> listOf("wavedrom-skin.js")
    InteractiveCodeRenderer.VEGA -> listOf("vega-lite.min.js", "vega-embed.js")
    else -> emptyList()
}.joinToString("\n") { "<script src=\"$WEB_VIEW_ASSET_URL/html/renderers/$it\"></script>" }

/** Sources are data, never concatenated into executable code. Each async
 * renderer completes before the common ready signal/export capture. */
internal fun additionalRendererScript(renderer: InteractiveCodeRenderer): String = when (renderer) {
    InteractiveCodeRenderer.WAVEFORM -> """
        if (!window.WaveDrom || !window.WaveSkin) throw new Error('WaveDrom 或皮肤未加载');
        const wave = JSON.parse(source);
        if (!wave || (!Array.isArray(wave.signal) && !Array.isArray(wave.reg) && !Array.isArray(wave.assign))) throw new Error('需要 signal、reg 或 assign 数据');
        if (JSON.stringify(wave).length > 32000) throw new Error('波形数据过大，请拆分图形');
        const target = document.createElement('div'); target.id = 'wave-output-0'; root.appendChild(target);
        WaveDrom.RenderWaveForm(0, wave, 'wave-output-');
        if (!target.querySelector('svg')) throw new Error('波形没有生成可显示的图形');
    """
    InteractiveCodeRenderer.GRAPHVIZ -> """
        if (typeof WebAssembly !== 'object' || typeof Worker !== 'function') throw new Error('当前系统 WebView 不支持 Graphviz 所需的 WebAssembly/Worker，请更新 Android System WebView');
        if (source.length > 32000) throw new Error('DOT 图形过大，请拆分节点');
        const svgText = await new Promise(function(resolve, reject) {
            let worker;
            try { worker = new Worker('$WEB_VIEW_ASSET_URL/html/renderers/graphviz-worker.js', { type: 'module' }); }
            catch (error) { reject(new Error('当前 WebView 不支持模块 Worker，请更新系统 WebView')); return; }
            const timer = setTimeout(function() { worker.terminate(); reject(new Error('Graphviz 布局超时，请减少节点或边')); }, 15000);
            const cleanup = function() { clearTimeout(timer); worker.terminate(); };
            worker.onmessage = function(event) { cleanup(); event.data.error ? reject(new Error(event.data.error)) : resolve(event.data.svg); };
            worker.onerror = function(event) { event.preventDefault(); cleanup(); reject(new Error('Graphviz 加载失败：当前 WebView 可能不支持此 WASM 版本')); };
            worker.postMessage(source);
            window.addEventListener('pagehide', cleanup, { once: true });
        });
        const documentSvg = new DOMParser().parseFromString(svgText, 'image/svg+xml');
        if (documentSvg.querySelector('parsererror')) throw new Error('Graphviz 返回了无效 SVG');
        root.appendChild(document.importNode(documentSvg.documentElement, true));
    """
    InteractiveCodeRenderer.VEGA -> """
        if (!window.vegaEmbed || !window.vega || !window.vegaLite) throw new Error('Vega 依赖未完整加载，请更新系统 WebView');
        const spec = JSON.parse(source);
        if (!spec || typeof spec !== 'object' || Array.isArray(spec)) throw new Error('需要 Vega/Vega-Lite JSON 对象');
        function checkData(value, inData) {
            if (!value || typeof value !== 'object') return;
            if (inData && value.url) throw new Error('请在 data.values 中提供数据；图形预览不加载外部数据地址');
            Object.keys(value).forEach(function(key) { if (key !== 'values') checkData(value[key], key === 'data' || inData); });
        }
        checkData(spec, false);
        // A chat diagram is self-contained: remote URLs must not stall export,
        // read local files or send private inline values to another endpoint.
        const loader = vega.loader();
        loader.load = async function() { throw new Error('请在 data.values 中提供数据；图形预览不加载外部数据地址'); };
        loader.sanitize = async function(uri) { if (/^data:image\/(png|jpeg|gif|webp);base64,/i.test(uri)) return { href: uri }; throw new Error('不支持外部图像地址'); };
        const result = await vegaEmbed(root, spec, { actions: false, renderer: 'svg', tooltip: true, loader: loader,
            mode: spec.mark || spec.encoding || spec.layer || spec.hconcat || spec.vconcat ? 'vega-lite' : 'vega' });
        window.addEventListener('pagehide', function() { result.finalize(); }, { once: true });
        await result.view.runAsync();
    """
    InteractiveCodeRenderer.MOLECULE -> """
        if (!window.SmilesDrawer || !SmilesDrawer.SvgDrawer) throw new Error('SmilesDrawer 未加载');
        const smiles = source.trim();
        if (!smiles || smiles.length > 4000) throw new Error('SMILES 为空或过长，请拆分分子结构');
        const target = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
        target.id = 'molecule-svg'; root.appendChild(target);
        const tree = await new Promise(function(resolve, reject) { SmilesDrawer.parse(smiles, resolve, reject); });
        const drawer = new SmilesDrawer.SvgDrawer({ width: Math.max(root.clientWidth - 16, 240), height: 260, compactDrawing: false });
        drawer.draw(tree, target.id, 'light');
    """
    InteractiveCodeRenderer.MUSICXML -> """
        if (!window.opensheetmusicdisplay) throw new Error('OpenSheetMusicDisplay 未加载');
        const score = window.rikkaMusicXmlHarmony(window.rikkaParseScoreXml(source, 'musicxml'));
        const scoreSurface = document.createElement('div'); root.replaceChildren(scoreSurface);
        const osmd = new opensheetmusicdisplay.OpenSheetMusicDisplay(scoreSurface, { backend:'svg', autoResize:false, drawTitle:true, followCursor:false,
            pageFormat:'A4_P', drawUpToPageNumber:64, drawLyrics:true, drawMeasureNumbers:true, newPageFromXML:true });
        await osmd.load(score);
        function renderScore() {
            root.replaceChildren(scoreSurface);
            osmd.render();
            if (osmd.GraphicSheet.MusicPages.length > 64) throw new Error('MusicXML 超过 64 页，请拆分长谱');
            if (!root.querySelector('svg')) throw new Error('MusicXML 未生成可显示的谱面');
            window.rikkaScorePages(root, root.querySelectorAll('svg'), true);
        }
        renderScore();
        window.rikkaReflowScore(root, renderScore, fail);
    """
    InteractiveCodeRenderer.MEI -> """
        const meiDocument = window.rikkaParseScoreXml(source, 'mei');
        if (meiDocument.querySelector('neume') && meiDocument.querySelector('syllable > syl')) root.dataset.scoreNotice = '当前 MEI 纽姆歌词尚不参与谱面和 PDF 排版；歌词原文保留在源码中。';
        if (typeof WebAssembly !== 'object' || typeof Worker !== 'function') throw new Error('MEI 需要支持 WebAssembly / 模块 Worker 的系统 WebView');
        let generation = 0, cancelActive = null;
        async function renderMei() {
            const ownGeneration = ++generation;
            if (cancelActive) cancelActive();
            window.__rikkaRenderStatus = 'loading';
            const result = await new Promise(function(resolve, reject) {
                const worker = new Worker('$WEB_VIEW_ASSET_URL/html/renderers/mei-worker.js', {type:'module'});
                const cleanup = function(){ clearTimeout(timer); worker.terminate(); window.removeEventListener('pagehide', cancel); if(cancelActive === cancel) cancelActive = null; };
                const cancel = function(){ cleanup(); resolve(null); };
                cancelActive = cancel;
                const timer = setTimeout(function(){ cleanup(); reject(new Error('MEI 排版超时，请拆分长谱')); }, 15000);
                worker.onmessage = function(event){ cleanup(); event.data.error ? reject(new Error(event.data.error)) : resolve(event.data); };
                worker.onerror = function(event){ event.preventDefault(); cleanup(); reject(new Error('MEI 引擎加载失败，请更新系统 WebView')); };
                worker.postMessage({source:source,width:Math.max(240,root.clientWidth-16)});
                window.addEventListener('pagehide', cancel, {once:true});
            });
            if (ownGeneration !== generation || !result) return;
            const svgs = result.pages.map(function(text){
                const svg = new DOMParser().parseFromString(text,'image/svg+xml');
                if(svg.querySelector('parsererror') || svg.documentElement.localName !== 'svg') throw new Error('MEI 返回无效谱面');
                return document.importNode(svg.documentElement,true);
            });
            root.dataset.engine = result.version;
            window.rikkaScorePages(root,svgs,true);
            window.__rikkaRenderStatus = 'ready';
        }
        await renderMei();
        window.rikkaReflowScore(root, function(){ renderMei().catch(fail); }, fail);
        window.addEventListener('pagehide',function(){ generation++; if(cancelActive) cancelActive(); },{once:true});
    """
    else -> error("Not an additional renderer: $renderer")
}.trimIndent()
