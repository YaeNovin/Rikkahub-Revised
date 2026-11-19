/* App-owned math/sigil/pattern controller. All model data was validated in Kotlin. */
(function () {
  'use strict';
  const byId = id => document.getElementById(id);
  const status = byId('status'), stage = byId('stage'), play = byId('play');
  const svgNS = 'http://www.w3.org/2000/svg';
  let board, artwork, observer, timer = 0, frame = 0, disposed = false, hostActive = false, visible = true;
  let wanted = false, elapsed = 0, previous = 0, zoom = 1, rotations = [], reduced;
  let effects, effectsSpec = {}, effectsObserver, revealStarted = false, sceneLayerCount = 0, hasContinuousMotion = false;
  let globalAnimation = {}, specForAnimation = null;
  let sigilScene, sigilHasGlow = false, sigilGlowBlur, sigilGlowFlood, lastSigilQuality = -1;
  let gestures, gestureOps, supportsBackground = false, transparentBackground = false;
  const pan = { x: 0, y: 0 };
  const fullscreen = document.body.dataset.fullscreen === 'true';
  const gestureHint = fullscreen ? '双指缩放 · 拖动平移' : '双指缩放 · 单指上下滑动对话';
  const budget = window.RikkaEffects ? RikkaEffects.createBudget() : null;
  let hostEnvironment = { hardwareAccelerated: null, lowMemory: false, powerSave: false, reduceMotion: false };
  const prefersReduced = () => hostEnvironment.reduceMotion || !!(reduced && reduced.matches);
  window.__rikkaRenderStatus = 'loading';
  const svgElement = (tag, attrs, parent) => {
    const node = document.createElementNS(svgNS, tag);
    Object.keys(attrs).forEach(key => node.setAttribute(key, String(attrs[key])));
    if (parent) parent.appendChild(node);
    return node;
  };
  function stopFrame() { if (frame) cancelAnimationFrame(frame); frame = 0; previous = 0; }
  function playing() { return !disposed && wanted && hostActive && visible && !document.hidden && !prefersReduced(); }
  function renderDecorations() {
    const showReveal = revealStarted && !prefersReduced();
    if (sigilScene && lastSigilQuality !== budget.level()) {
      lastSigilQuality = budget.level();
      if (sigilHasGlow && lastSigilQuality < 2) sigilScene.setAttribute('filter', 'url(#glow)');
      else sigilScene.removeAttribute('filter');
    }
    if (sigilGlowBlur && sigilGlowFlood) {
      const pulse = specForAnimation && specForAnimation.glowPulse ? 1 + Math.sin(elapsed * 2) * .2 : 1;
      sigilGlowBlur.setAttribute('stdDeviation', String((specForAnimation.glowSpread || 1) * specForAnimation.glow * pulse));
      sigilGlowFlood.setAttribute('flood-opacity', String(Math.max(.35, Math.min(.9, .75 * pulse))));
    }
    rotations.forEach((layer, i) => {
      const amount = RikkaEffects.revealAt(effectsSpec.reveal, i, elapsed, showReveal);
      const choreography = RikkaEffects.choreography(layer.animation && layer.animation.mode ? layer.animation : globalAnimation, elapsed, i);
      layer.node.setAttribute('opacity', layer.opacity * amount * choreography.opacity);
      const mirror = layer.mirror && layer.symmetryIndex % 2 ? ' scale(-1 1)' : '';
      layer.node.setAttribute('transform', 'rotate(' + (layer.rotation + elapsed * layer.speed + choreography.rotation) % 360 + ')' + mirror + ' scale(' + ((.72 + .28 * amount) * choreography.scale) + ')');
      if (layer.animation && layer.animation.mode === 'draw') {
        layer.node.style.strokeDasharray = '100000 100000';
        layer.node.style.strokeDashoffset = String(100000 * (1 - choreography.draw));
        if (!layer.dashTargets) {
          layer.dashTargets = Array.from(layer.node.querySelectorAll('path,circle,polygon,line')).flatMap(node => {
            try { return [{ node, length: node.getTotalLength() }]; } catch (_) { return []; }
          });
        }
        layer.dashTargets.forEach(target => {
          target.node.style.strokeDasharray = target.length + ' ' + target.length;
          target.node.style.strokeDashoffset = String(target.length * (1 - choreography.draw));
        });
      } else {
        layer.node.style.strokeDasharray = '';
        if (layer.dashTargets) layer.dashTargets.forEach(target => { target.node.style.strokeDasharray = ''; target.node.style.strokeDashoffset = ''; });
      }
    });
    if (artwork) artwork.render(elapsed, zoom, { quality: budget.level(), reveal: showReveal, transparentBackground, pan });
    if (effects) effects.render(elapsed, zoom, budget.level(), prefersReduced(), RikkaEffects.revealAt(effectsSpec.reveal, 0, elapsed, showReveal), pan);
  }
  function updateArtViewport() {
    if (sigilScene) {
      const edge = 110 / zoom;
      byId('sigil').setAttribute('viewBox', [-pan.x / zoom - edge, -pan.y / zoom - edge, edge * 2, edge * 2].join(' '));
    }
    renderDecorations();
  }
  function scaleArtwork(factor, before, after) {
    const unit = Math.max(1, Math.min(stage.clientWidth, stage.clientHeight)) / 220;
    const nextZoom = Math.max(.5, Math.min(3, zoom * factor)), ratio = nextZoom / zoom;
    pan.x = (after.x - stage.clientWidth / 2) / unit - ((before.x - stage.clientWidth / 2) / unit - pan.x) * ratio;
    pan.y = (after.y - stage.clientHeight / 2) / unit - ((before.y - stage.clientHeight / 2) / unit - pan.y) * ratio;
    zoom = nextZoom; pan.x = Math.max(-660, Math.min(660, pan.x)); pan.y = Math.max(-660, Math.min(660, pan.y));
    updateArtViewport();
  }
  function panArtwork(dx, dy) { scaleArtwork(1, { x: 0, y: 0 }, { x: dx, y: dy }); }
  window.rikkaSetPreviewBackground = (enabled, chrome) => {
    if (supportsBackground && chrome) {
      // Host-derived palette only; reject arbitrary CSS/URLs even if called incorrectly.
      for (const key of ['foreground', 'shadow']) if (typeof chrome[key] === 'string' && /^#[0-9a-fA-F]{6}$/.test(chrome[key])) {
        document.body.style.setProperty('--rikka-chrome-' + key, chrome[key]);
      }
    }
    transparentBackground = supportsBackground && enabled === true;
    document.documentElement.classList.toggle('rikka-preview-global-background', transparentBackground);
    document.body.classList.toggle('rikka-preview-global-background', transparentBackground);
    if (!disposed) renderDecorations();
  };
  function syncPlayback() {
    play.textContent = wanted ? '暂停' : '播放';
    if (playing()) { if (!frame) frame = requestAnimationFrame(tick); } else stopFrame();
  }
  function tick(now) {
    frame = 0;
    if (!playing()) return;
    if (!previous) previous = now;
    if (now - previous >= Math.max(budget ? budget.interval() : 1000 / 30, artwork ? artwork.frameInterval() : 0)) {
      const interval = now - previous, start = performance.now();
      elapsed += Math.min(interval / 1000, .25); previous = now;
      renderDecorations();
      if (budget) budget.sample(performance.now() - start, interval);
      if (!hasContinuousMotion && effectsSpec.reveal && elapsed >= effectsSpec.reveal.duration + Math.max(0, sceneLayerCount - 1) * effectsSpec.reveal.stagger) {
        wanted = false; syncPlayback();
      }
    }
    if (playing()) frame = requestAnimationFrame(tick);
  }
  window.rikkaSetHostActive = active => { hostActive = active === true; if (!hostActive && gestures) gestures.cancel(); syncPlayback(); };
  window.rikkaPrepareExport = () => {
    wanted = false;
    // Detached chat export never plays: export the complete diagram, not an
    // empty first reveal frame. An already-playing preview freezes its current frame.
    if (elapsed === 0) revealStarted = false;
    renderDecorations(); syncPlayback();
  };
  window.rikkaSetRenderEnvironment = environment => {
    hostEnvironment = Object.assign(hostEnvironment, environment);
    if (budget) budget.constrain(hostEnvironment);
    if (prefersReduced()) wanted = false;
    play.disabled = prefersReduced(); syncPlayback();
    if (budget && !disposed) renderDecorations();
  };
  // Read-only counters for QA. No arbitrary execution or native interface is exposed.
  window.rikkaRenderDiagnostics = () => ({ ...(budget ? budget.snapshot() : {}), elapsed, active: playing(),
    hardwareAccelerated: hostEnvironment.hardwareAccelerated, effects: effects ? effects.snapshot() : null,
    artwork: artwork ? artwork.diagnostics() : null, camera: { zoom, x: pan.x, y: pan.y }, transparentBackground });
  function dispose() {
    if (disposed) return;
    disposed = true; stopFrame(); clearTimeout(timer);
    if (gestures) gestures.dispose();
    if (observer) observer.disconnect();
    if (effectsObserver) effectsObserver.disconnect();
    window.removeEventListener('resize', renderDecorations);
    if (effects) effects.remove();
    if (artwork) artwork.remove();
    if (board) { JXG.JSXGraph.freeBoard(board); board = null; }
    if (reduced && reduced.removeEventListener) reduced.removeEventListener('change', motionChanged);
    else if (reduced && reduced.removeListener) reduced.removeListener(motionChanged);
    document.removeEventListener('visibilitychange', syncPlayback);
    window.__rikkaRenderStatus = 'disposed';
  }
  function fail(error) {
    dispose(); byId('board').hidden = true; byId('sigil').setAttribute('hidden', ''); byId('pattern').hidden = true; byId('effects').hidden = true;
    byId('error').textContent = '无法绘图：' + (error.message || String(error)); byId('error').hidden = false;
    ['reset', 'play', 'variation'].forEach(id => { byId(id).disabled = true; });
    window.__rikkaRenderStatus = 'error';
  }
  function motionChanged() {
    if (prefersReduced()) wanted = false;
    play.disabled = prefersReduced();
    syncPlayback(); renderDecorations();
  }
  function setupPlayback(moving, animate) {
    play.hidden = !moving;
    reduced = typeof matchMedia === 'function' ? matchMedia('(prefers-reduced-motion: reduce)') : null;
    if (reduced && reduced.addEventListener) reduced.addEventListener('change', motionChanged);
    else if (reduced && reduced.addListener) reduced.addListener(motionChanged);
    wanted = animate && moving && !prefersReduced();
    revealStarted = wanted;
    play.disabled = prefersReduced();
    play.onclick = () => {
      wanted = !wanted;
      if (wanted && !revealStarted) { elapsed = 0; revealStarted = true; }
      if (wanted && !hasContinuousMotion && effectsSpec.reveal) elapsed = 0;
      renderDecorations(); syncPlayback();
    };
    if (typeof IntersectionObserver === 'function') {
      observer = new IntersectionObserver(entries => { visible = entries[0].isIntersecting; syncPlayback(); }); observer.observe(stage);
    }
    syncPlayback();
  }
  function setupEffects(spec) {
    effectsSpec = spec.effects || {}; sceneLayerCount = spec.layers.length;
    hasContinuousMotion = spec.layers.some(layer => layer.speed !== 0 || (layer.animation && layer.animation.mode)) ||
      !!spec.glowPulse ||
      !!effectsSpec.stardust || !!(spec.animation && spec.animation.mode);
    if (effectsSpec.stardust) {
      const canvas = byId('effects'); canvas.hidden = false; effects = RikkaEffects.create(canvas, effectsSpec);
      if (typeof ResizeObserver === 'function') { effectsObserver = new ResizeObserver(renderDecorations); effectsObserver.observe(stage); }
      else window.addEventListener('resize', renderDecorations);
    }
    setupPlayback(hasContinuousMotion || !!effectsSpec.reveal, spec.animate);
  }
  try {
    const bytes = Uint8Array.from(atob(document.body.dataset.source), c => c.charCodeAt(0));
    const spec = JSON.parse(new TextDecoder('utf-8').decode(bytes));
    if (spec.error) throw new Error(spec.error);
    globalAnimation = spec.animation || {};
    supportsBackground = spec.kind === 'sigil' || spec.kind === 'pattern';
    specForAnimation = spec;
    transparentBackground = supportsBackground && document.body.dataset.previewBackground === 'true';
    byId('title').textContent = spec.title;
    document.addEventListener('visibilitychange', syncPlayback);
    window.addEventListener('pagehide', dispose, { once: true });

    if (spec.kind === 'math_plot') {
      if (!window.JXG || !window.RikkaMath) throw new Error('JSXGraph 离线资源未加载');
      const container = byId('board'); container.hidden = false;
      const fg = document.body.dataset.foreground;
      const initial = [spec.xRange[0], spec.yRange[1], spec.xRange[1], spec.yRange[0]];
      function fitBox(box) {
        if (!spec.equalAspect) return box;
        const aspect = Math.max(1, container.clientWidth) / Math.max(1, container.clientHeight);
        let width = box[2] - box[0], height = box[1] - box[3];
        if (width / height < aspect) width = height * aspect; else height = width / aspect;
        const x = (box[0] + box[2]) / 2, y = (box[1] + box[3]) / 2;
        return [x - width / 2, y + height / 2, x + width / 2, y - height / 2];
      }
      board = JXG.JSXGraph.initBoard('board', {
        boundingbox: fitBox(initial), keepaspectratio: spec.equalAspect, axis: true, grid: spec.grid,
        renderer: 'svg', showCopyright: false, showNavigation: false, showInfobox: false,
        resize: { enabled: false }, pan: { enabled: false },
        zoom: { enabled: false, wheel: false },
        defaultAxes: { x: { strokeColor: fg, ticks: { strokeColor: fg, label: { strokeColor: fg, display: 'internal', parse: false } } },
          y: { strokeColor: fg, ticks: { strokeColor: fg, label: { strokeColor: fg, display: 'internal', parse: false } } } },
        text: { display: 'internal', parse: false, useMathJax: false },
      });
      const parameters = Object.create(null), sliders = [];
      const curves = spec.curves.map(c => {
        const label = document.createElement('span'); label.textContent = '● ' + c.label; label.style.color = c.color; byId('legend').appendChild(label);
        return board.create('curve', [[], []], { strokeColor: c.color, strokeWidth: 2, highlight: false, fixed: true,
          curveType: 'plot', doAdvancedPlot: false, useQDT: false });
      });
      let busy = false, warning = '';
      function render() {
        timer = 0; if (disposed || busy) return;
        busy = true;
        try {
          const box = board.getBoundingBox();
          if (!box.every(Number.isFinite) || box.some(v => Math.abs(v) > 1e6) || box[2] - box[0] < 1e-6 || box[1] - box[3] < 1e-6) {
            status.textContent = '视图超出支持范围，请重置'; return;
          }
          const budget = { remaining: 250000, exhausted: false };
          let count = 0, limited = false;
          spec.curves.forEach((curve, index) => {
            const points = RikkaMath.sample(curve, parameters, box, budget, container.clientWidth, container.clientHeight);
            curves[index].dataX = points.x; curves[index].dataY = points.y;
            count += points.y.filter(Number.isFinite).length; limited = limited || points.limited;
          });
          board.update();
          warning = limited ? '达到采样上限，局部细节可能省略。' : count === 0 && spec.curves.length ? '当前范围没有可绘制的实数采样点。' : '';
          status.textContent = warning + (spec.parameters.some(p => p.continuousFallback) ? '已保留初值，非网格参数使用连续滑块。' : '') + '数值采样 · ' + gestureHint;
          window.__rikkaRenderStatus = 'ready';
        } catch (error) { fail(error); } finally { busy = false; }
      }
      function schedule() { if (disposed || busy) return; clearTimeout(timer); timer = setTimeout(render, 70); }
      spec.parameters.forEach(p => {
        parameters[p.name] = p.value;
        const label = document.createElement('label'), text = document.createElement('span'), slider = document.createElement('input');
        slider.type = 'range'; slider.min = p.min; slider.max = p.max; slider.step = p.step; slider.value = p.value; slider.setAttribute('aria-label', p.label || p.name);
        const update = () => { parameters[p.name] = Number(slider.value); text.textContent = (p.label || p.name) + ' = ' + Number(slider.value).toPrecision(4); schedule(); };
        slider.addEventListener('input', update); label.appendChild(text); label.appendChild(slider); byId('parameters').appendChild(label);
        sliders.push({ slider, p, update }); update();
      });
      spec.primitives.forEach(p => {
        const attrs = { strokeColor: p.color, fillColor: p.color, strokeWidth: 2, fixed: true, highlight: false, withLabel: false, name: '' };
        if (p.type === 'point') {
          board.create('point', p.at, Object.assign({}, attrs, { size: 3 }));
          if (p.label) { const text = document.createElement('span'); text.textContent = p.label + ' (' + p.at.join(', ') + ')'; text.style.color = p.color; byId('legend').appendChild(text); }
        } else if (p.type === 'circle') board.create('circle', [p.center, p.radius], Object.assign({}, attrs, { fillOpacity: 0 }));
        else board.create(p.type === 'vector' ? 'arrow' : 'segment', [p.from, p.to], attrs);
        if (p.type !== 'point' && p.label) {
          const text = document.createElement('span'); text.textContent = p.label; text.style.color = p.color; byId('legend').appendChild(text);
        }
      });
      byId('reset').onclick = () => { sliders.forEach(s => { s.slider.value = s.p.value; s.update(); }); board.setBoundingBox(fitBox(initial), spec.equalAspect); schedule(); };
      const moveBoard = (factor, before, after) => {
        const box = board.getBoundingBox(), width = Math.max(1, container.clientWidth), height = Math.max(1, container.clientHeight);
        const spanX = box[2] - box[0], spanY = box[1] - box[3];
        const x = box[0] + before.x / width * spanX, y = box[1] - before.y / height * spanY;
        const left = x - after.x / width * spanX / factor, top = y + after.y / height * spanY / factor;
        const next = [left, top, left + spanX / factor, top - spanY / factor];
        if (next.every(v => Number.isFinite(v) && Math.abs(v) <= 1e6) && spanX / factor >= 1e-6 && spanY / factor >= 1e-6) board.setBoundingBox(next, spec.equalAspect);
      };
      gestureOps = { scale: moveBoard, pan: (dx, dy) => moveBoard(1, { x: 0, y: 0 }, { x: dx, y: dy }), reset: () => byId('reset').click() };
      container.addEventListener('pointermove', event => {
        const rect = container.getBoundingClientRect(), box = board.getBoundingBox();
        const x = box[0] + (event.clientX - rect.left) / rect.width * (box[2] - box[0]);
        const y = box[1] - (event.clientY - rect.top) / rect.height * (box[1] - box[3]);
        status.textContent = warning + '坐标 x=' + x.toPrecision(5) + ', y=' + y.toPrecision(5) + ' · 数值采样';
      });
      board.on('boundingbox', schedule);
      // JSXGraph pins its own container to pixels; observe and measure the flex
      // parent, otherwise the first resize freezes all later viewport changes.
      observer = new ResizeObserver(() => { if (!disposed && stage.clientWidth > 0 && stage.clientHeight > 0) { board.resizeContainer(stage.clientWidth, stage.clientHeight); schedule(); } });
      observer.observe(stage); clearTimeout(timer); render();
    } else if (spec.kind === 'sigil') {
      RikkaGeometry.validateCurveBudget(spec.layers);
      const svg = byId('sigil'); svg.removeAttribute('hidden');
      const defs = svgElement('defs', {}, svg);
      if (spec.glow > 0) {
        const filter = svgElement('filter', { id: 'glow', x: '-80%', y: '-80%', width: '260%', height: '260%' }, defs);
        const blur = svgElement('feGaussianBlur', { stdDeviation: spec.glow * (spec.glowSpread || 1), result: 'blur' }, filter);
        const flood = svgElement('feFlood', { 'flood-color': spec.glowColor || spec.palette?.[0] || '#a78bfa', 'flood-opacity': '.75', result: 'color' }, filter);
        sigilGlowBlur = blur; sigilGlowFlood = flood;
        const composite = svgElement('feComposite', { in: 'color', in2: 'blur', operator: 'in', result: 'colored' }, filter);
        const merge = svgElement('feMerge', {}, filter); svgElement('feMergeNode', { in: 'colored' }, merge); svgElement('feMergeNode', { in: 'SourceGraphic' }, merge);
      }
      const scene = svgElement('g', spec.glow > 0 ? { filter: 'url(#glow)' } : {}, svg);
      sigilScene = scene; sigilHasGlow = spec.glow > 0;
      spec.layers.forEach(layer => {
        // Sample once per layer, never once per symmetry copy or animation frame.
        const curvePoints = layer.type === 'curve' ? RikkaGeometry.decorativeCurve(layer.curve, layer.radius) : null;
        const colors = layer.palette && layer.palette.length ? layer.palette : [layer.color];
        const symmetry = Math.max(1, layer.symmetry || 1);
        for (let symmetryIndex = 0; symmetryIndex < symmetry; symmetryIndex++) {
          const mirrorTransform = layer.mirror && symmetryIndex % 2 ? ' scale(-1 1)' : '';
          const group = svgElement('g', { stroke: colors[symmetryIndex % colors.length], 'stroke-width': layer.width, opacity: layer.opacity,
            fill: 'none', 'stroke-linecap': layer.lineCap || 'round', 'stroke-linejoin': layer.lineJoin || 'round',
            transform: 'rotate(' + (layer.rotation + symmetryIndex * 360 / symmetry) + ')' + mirrorTransform, 'data-layer-type': layer.type }, scene);
          rotations.push({ node: group, rotation: layer.rotation + symmetryIndex * 360 / symmetry, speed: layer.speed, opacity: layer.opacity, animation: layer.animation, mirror: !!layer.mirror, symmetryIndex });
          if (layer.background && layer.background !== 'none') {
            svgElement('circle', { r: layer.radius, fill: layer.background, 'fill-opacity': layer.fillOpacity ?? .22, stroke: 'none' }, group);
          }
          const paint = (node, index, fill = 'none') => {
            node.setAttribute('stroke', colors[index % colors.length]);
            node.setAttribute('fill', fill === 'none' ? 'none' : fill);
            if (fill !== 'none') node.setAttribute('fill-opacity', String(layer.fillOpacity ?? .22));
          };
          if (layer.type === 'ring') paint(svgElement('circle', { r: layer.radius }, group), symmetryIndex);
          else if (layer.type === 'star' || layer.type === 'polygon') {
            RikkaGeometry.starPolygons(layer.radius, layer.points, layer.step).forEach((points, index) => {
              const node = svgElement('polygon', { points: points.map(p => p.join(',')).join(' ') }, group); paint(node, index, layer.fill);
            });
          } else if (layer.type === 'ticks' || layer.type === 'rays') {
            RikkaGeometry.radialSegments(layer.radius, layer.type === 'ticks' ? layer.radius - layer.length : layer.innerRadius, layer.count).forEach(([a, b], index) => {
              paint(svgElement('line', { x1: a[0], y1: a[1], x2: b[0], y2: b[1] }, group), index);
            });
          } else if (layer.type === 'arc') {
            const points = [];
            for (let i = 0; i <= 32; i++) {
              const angle = (layer.startAngle + (layer.endAngle - layer.startAngle) * i / 32) * Math.PI / 180;
              points.push(RikkaGeometry.radialPoint(layer.radius, angle));
            }
            const d = points.map((p, i) => (i ? 'L' : 'M') + p[0] + ' ' + p[1]).join(' ');
            paint(svgElement('path', { d }, group), symmetryIndex);
          } else if (layer.type === 'dot') {
            const dot = svgElement('circle', { r: layer.size, cy: -layer.radius }, group); paint(dot, symmetryIndex, layer.fill);
          } else if (layer.type === 'orbit') {
            paint(svgElement('circle', { r: layer.radius }, group), symmetryIndex);
            for (let i = 0; i < layer.count; i++) {
              const p = RikkaGeometry.radialPoint(layer.innerRadius, 2 * Math.PI * i / layer.count);
              const dot = svgElement('circle', { cx: p[0], cy: p[1], r: layer.dotSize }, group); paint(dot, i, layer.color);
            }
          } else if (layer.type === 'path' || layer.type === 'curve') {
            const points = curvePoints || layer.points || [];
            const d = points.map((p, i) => (i ? 'L' : 'M') + p[0] + ' ' + p[1]).join(' ') + (curvePoints || layer.closed ? ' Z' : '');
            paint(svgElement('path', { d }, group), symmetryIndex, layer.fill);
          } else {
            const characters = Array.from(layer.text);
            characters.forEach((character, i) => {
              const text = svgElement('text', { x: 0, y: -layer.radius, fill: colors[i % colors.length], stroke: 'none', 'font-size': layer.fontSize,
                'text-anchor': 'middle', 'dominant-baseline': 'middle', transform: 'rotate(' + 360 * i / characters.length + ')' }, group);
              text.textContent = character;
            });
          }
        }
      });
      byId('reset').onclick = () => { elapsed = 0; revealStarted = wanted; zoom = 1; pan.x = pan.y = 0; updateArtViewport(); };
      gestureOps = { scale: scaleArtwork, pan: panArtwork, reset: () => byId('reset').click() };
      setupEffects(spec); renderDecorations();
      status.textContent = 'SVG 图案 · ' + gestureHint + (reduced && reduced.matches ? ' · 已减少动态效果' : '');
      window.__rikkaRenderStatus = 'ready';
    } else if (spec.kind === 'pattern') {
      if (!window.RikkaPattern) throw new Error('纹样离线资源未加载');
      const container = byId('pattern'); container.hidden = false;
      let seed = spec.seed;
      const initialSeed = seed;
      setupEffects(spec);
      artwork = RikkaPattern.mount(spec, container, scene => {
        if (disposed) return;
        const label = 'p5.js 纹样 · 种子 ' + seed + ' · ' + gestureHint;
        if (status.textContent !== label) status.textContent = label;
        window.__rikkaRenderStatus = 'ready';
      }, fail);
      byId('reset').onclick = () => { elapsed = 0; revealStarted = wanted; seed = initialSeed; zoom = 1; pan.x = pan.y = 0; artwork.reseed(seed); updateArtViewport(); };
      gestureOps = { scale: scaleArtwork, pan: panArtwork, reset: () => byId('reset').click() };
      const variation = byId('variation'); variation.hidden = !spec.layers.some(layer => layer.type === 'mandala');
      variation.onclick = () => { seed = (seed + 1) % 2147483648; artwork.reseed(seed); };
      renderDecorations();
    } else throw new Error('未知渲染器');
    gestures = RikkaGeometryGestures.attach(stage, gestureOps, fullscreen);
    window.rikkaResetPan = () => gestureOps.reset();
    window.rikkaSetPreviewBackground(transparentBackground);
  } catch (error) { fail(error); }
})();
