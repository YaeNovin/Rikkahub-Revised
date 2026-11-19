/* p5 instance mode with fixed app-owned drawing code. No global sketch or author JS. */
(function () {
  'use strict';
  function mount(spec, container, onReady, onError) {
    if (!window.p5 || !window.RikkaPatternGeometry) throw new Error('p5.js 离线资源未加载');
    let scene = RikkaPatternGeometry.build(spec), instance, observer, disposed = false, elapsed = 0, zoom = 1;
    let drawMillis = 0;
    let quality = 0, reveal = false, pathBuilds = 0, draws = 0, transparentBackground = false, pan = { x: 0, y: 0 };
    function cachePaths() {
      scene.layers.forEach(layer => layer.shapes.forEach(shape => {
        const path = new Path2D();
        shape.points.forEach((point, i) => { if (i === 0) path.moveTo(point[0], point[1]); else path.lineTo(point[0], point[1]); });
        if (shape.closed) path.closePath();
        shape.pathLength = shape.points.reduce((length, point, index) => {
          if (index === 0) return length;
          return length + Math.hypot(point[0] - shape.points[index - 1][0], point[1] - shape.points[index - 1][1]);
        }, 0);
        if (shape.closed && shape.points.length > 2) {
          const first = shape.points[0], last = shape.points[shape.points.length - 1];
          shape.pathLength += Math.hypot(first[0] - last[0], first[1] - last[1]);
        }
        if (shape.dotRadius > 0) shape.pathLength = Math.PI * 2 * shape.dotRadius;
        shape.path = path; pathBuilds++;
      }));
    }
    cachePaths();
    const size = () => {
      const w = Math.max(1, container.clientWidth), h = Math.max(1, container.clientHeight), scale = Math.min(1, (quality === 2 ? 640 : 1024) / Math.max(w, h));
      return [Math.max(1, Math.round(w * scale)), Math.max(1, Math.round(h * scale))];
    };
    function resize() {
      if (disposed || !instance || !instance.canvas) return;
      const [w, h] = size();
      instance.pixelDensity(quality ? 1 : Math.max(1, Math.min(2, Math.floor(Math.sqrt(2000000 / (w * h))))));
      instance.resizeCanvas(w, h, true); instance.redraw();
    }
    p5.disableFriendlyErrors = true;
    instance = new p5(p => {
      p.setup = () => {
        if (disposed) return;
        try {
          const [w, h] = size();
          p.pixelDensity(quality ? 1 : Math.max(1, Math.min(2, Math.floor(Math.sqrt(2000000 / (w * h))))));
          const canvas = p.createCanvas(w, h, p.P2D);
          canvas.attribute('role', 'img'); canvas.attribute('aria-label', spec.title);
          p.noLoop();
          if (typeof ResizeObserver === 'function') { observer = new ResizeObserver(resize); observer.observe(container); }
          else window.addEventListener('resize', resize);
        } catch (error) { onError(error); }
      };
      p.draw = () => {
        if (disposed || !p.canvas) return;
        const start = performance.now();
        try {
          if (transparentBackground) p.clear(); else p.background(spec.background);
          const unit = Math.min(p.width, p.height) / 220;
          p.push(); p.translate(p.width / 2 + unit * pan.x, p.height / 2 + unit * pan.y); p.scale(unit * zoom);
          p.strokeJoin(p.ROUND);
          scene.layers.forEach((layer, index) => {
            const amount = RikkaEffects.revealAt(spec.effects && spec.effects.reveal, index, elapsed, reveal);
            if (amount <= 0) return;
            const choreography = RikkaEffects.choreography(layer.spec.animation && layer.spec.animation.mode ? layer.spec.animation : spec.animation, elapsed, index);
            const symmetry = Math.max(1, layer.spec.symmetry || 1);
            for (let symmetryIndex = 0; symmetryIndex < symmetry; symmetryIndex++) {
              p.push(); p.rotate((layer.spec.rotation + elapsed * layer.spec.speed + choreography.rotation + symmetryIndex * 360 / symmetry) * Math.PI / 180);
              if (layer.spec.mirror && symmetryIndex % 2) p.scale(-1, 1);
              p.scale((.72 + .28 * amount) * choreography.scale);
              const ctx = p.drawingContext;
              // Bound blur independently of viewport size; this is Canvas 2D, not WebGL.
              const pulseGlow = spec.glowPulse ? 1 + Math.sin(elapsed * 2) * .2 : 1;
              ctx.shadowBlur = quality === 2 ? 0 : spec.glow * (spec.glowSpread || 1) * pulseGlow * (quality === 1 ? 1 : 2);
              ctx.lineWidth = layer.spec.width; ctx.lineCap = layer.spec.lineCap || 'round'; ctx.lineJoin = layer.spec.lineJoin || 'round';
              ctx.globalAlpha = layer.spec.opacity * amount * choreography.opacity;
              for (const shape of layer.shapes) {
                ctx.strokeStyle = shape.color; ctx.shadowColor = spec.glowColor || shape.color;
                if (shape.dotRadius > 0) {
                  const point = shape.points[0];
                  ctx.beginPath(); ctx.arc(point[0], point[1], shape.dotRadius, 0, Math.PI * 2);
                  if (shape.fill !== 'none') { ctx.fillStyle = shape.fill; ctx.globalAlpha = layer.spec.opacity * amount * choreography.opacity * shape.fillOpacity * choreography.draw; ctx.fill(); ctx.globalAlpha = layer.spec.opacity * amount * choreography.opacity; }
                  ctx.setLineDash(choreography.draw < 1 ? [Math.max(.01, shape.pathLength * choreography.draw), Math.max(1, shape.pathLength)] : []);
                  ctx.stroke();
                  ctx.setLineDash([]);
                } else {
                  if (shape.fill !== 'none') { ctx.fillStyle = shape.fill; ctx.globalAlpha = layer.spec.opacity * amount * choreography.opacity * shape.fillOpacity * choreography.draw; ctx.fill(shape.path); ctx.globalAlpha = layer.spec.opacity * amount * choreography.opacity; }
                  ctx.setLineDash(choreography.draw < 1 ? [Math.max(.01, shape.pathLength * choreography.draw), Math.max(1, shape.pathLength)] : []);
                  ctx.stroke(shape.path);
                  ctx.setLineDash([]);
                }
              }
              p.pop();
            }
          });
          p.pop();
          drawMillis = performance.now() - start;
          draws++;
          onReady(scene);
        } catch (error) { onError(error); }
      };
    }, container);
    return {
      render(time, scale, options = {}) {
        if (disposed) return;
        elapsed = time; zoom = scale; reveal = !!options.reveal; transparentBackground = !!options.transparentBackground;
        pan = options.pan || { x: 0, y: 0 };
        const nextQuality = options.quality || 0;
        if (quality !== nextQuality) { quality = nextQuality; resize(); }
        else if (instance.canvas) instance.redraw();
      },
      // Mandala variations remain reproducible; no random state is advanced during drawing.
      reseed(seed) { if (!disposed) { spec.seed = seed; scene = RikkaPatternGeometry.build(spec); cachePaths(); if (instance.canvas) instance.redraw(); } },
      diagnostics() { return { pathBuilds, draws, vertices: scene.vertices, pixels: instance && instance.canvas ? instance.canvas.width * instance.canvas.height : 0 }; },
      frameInterval() { return Math.max(1000 / 30, Math.min(250, drawMillis * 2)); },
      remove() {
        if (disposed) return;
        disposed = true;
        if (observer) observer.disconnect();
        window.removeEventListener('resize', resize);
        if (instance) instance.remove();
      },
    };
  }
  window.RikkaPattern = Object.freeze({ mount });
})();
