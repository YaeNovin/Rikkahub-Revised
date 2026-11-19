/* Bounded decorative generators; no author programs, expression evaluation or assets. */
(function (root) {
  'use strict';
  const G = typeof module === 'object' && module.exports ? require('./geometry-core.js') : root.RikkaGeometry;
  const TAU = Math.PI * 2;
  function random(seed) {
    let state = seed >>> 0;
    return () => { state = (Math.imul(state, 1664525) + 1013904223) >>> 0; return state / 4294967296; };
  }
  function build(spec) {
    if (!Array.isArray(spec.layers) || spec.layers.length > 12) throw new Error('纹样图层超出限制');
    G.validateCurveBudget(spec.layers);
    const rng = random(spec.seed), layers = [];
    let vertices = 0, paths = 0;
    const bound = (value, min, max) => {
      if (!Number.isInteger(value) || value < min || value > max) throw new Error('纹样细节参数超出限制');
      return value;
    };
    for (const layer of spec.layers) {
      const shapes = [];
      const colors = Array.isArray(layer.palette) && layer.palette.length ? layer.palette : [layer.color];
      const add = (points, closed = false, color = colors[(paths + shapes.length) % colors.length], fill = 'none', dotRadius = 0, fillOpacity = layer.fillOpacity) => {
        vertices += points.length; paths++;
        if (vertices > 24000 || paths > 8192) throw new Error('纹样达到几何预算上限');
        if (!points.every(p => p.length === 2 && p.every(Number.isFinite))) throw new Error('纹样坐标无效');
        shapes.push({ points, closed, color, fill, dotRadius, fillOpacity: Number.isFinite(fillOpacity) ? fillOpacity : .22 });
      };
      const ring = radius => Array.from({ length: 129 }, (_, i) => G.radialPoint(radius, TAU * i / 128));
      if (layer.background && layer.background !== 'none') add(ring(layer.radius), true, layer.background, layer.background, 0, 1);
      if (layer.type === 'ring') add(ring(layer.radius), true);
      else if (layer.type === 'star' || layer.type === 'polygon') {
        G.starPolygons(layer.radius, layer.points, layer.step).forEach((points, index) => add(points, true, colors[index % colors.length], layer.fill));
      } else if (layer.type === 'ticks' || layer.type === 'rays') {
        G.radialSegments(layer.radius, layer.type === 'ticks' ? layer.radius - layer.length : layer.innerRadius, layer.count).forEach(points => add(points));
      } else if (layer.type === 'arc') {
        const points = Array.from({ length: 33 }, (_, i) => G.radialPoint(layer.radius,
          (layer.startAngle + (layer.endAngle - layer.startAngle) * i / 32) * Math.PI / 180));
        add(points);
      } else if (layer.type === 'dot') {
        add([G.radialPoint(layer.radius, 0), G.radialPoint(layer.radius, 0)], true, colors[0], layer.fill, layer.size);
      } else if (layer.type === 'orbit') {
        add(ring(layer.radius), true);
        for (let i = 0; i < layer.count; i++) {
          const point = G.radialPoint(layer.innerRadius, TAU * i / layer.count);
          add([point, point], true, colors[i % colors.length], layer.color, layer.dotSize);
        }
      } else if (layer.type === 'curve') {
        add(G.decorativeCurve(layer.curve, layer.radius), true, colors[0], layer.fill);
      } else if (layer.type === 'mandala') {
        const petals = bound(layer.petals, 3, 32), rings = bound(layer.rings, 1, 6);
        const band = (layer.radius - layer.innerRadius) / rings;
        for (let r = 0; r < rings; r++) {
          const outer = layer.innerRadius + (r + 1) * band, inner = Math.max(layer.innerRadius, outer - band * 1.5);
          const offset = (r % 2) * Math.PI / petals + (rng() - .5) * .35;
          const width = Math.PI / petals * layer.petalWidth * (.85 + rng() * .15);
          const color = layer.palette[r % layer.palette.length];
          for (let petal = 0; petal < petals; petal++) {
            const points = Array.from({ length: 49 }, (_, i) => {
              const t = i <= 24 ? i / 24 : (48 - i) / 24, side = i <= 24 ? 1 : -1;
              return G.radialPoint(inner + (outer - inner) * t, TAU * petal / petals + offset + side * width * Math.sin(Math.PI * t));
            });
            add(points, true, color);
          }
          add(ring(outer), true, color);
        }
      } else if (layer.type === 'rose') {
        const n = bound(layer.numerator, 1, 24), d = bound(layer.denominator, 1, 12);
        const steps = Math.max(256, n * 192 + d * 64), period = n % 2 && d % 2 ? Math.PI * d : TAU * d;
        const phase = layer.phase * Math.PI / 180;
        add(Array.from({ length: steps + 1 }, (_, i) => {
          const angle = period * i / steps;
          return G.radialPoint(layer.radius * Math.cos(n / d * angle + phase), angle);
        }), true);
        } else if (layer.type === 'path') {
          add(layer.points, !!layer.closed, colors[0], layer.fill);
        } else if (layer.type === 'fractal') {
        const depth = bound(layer.depth, 0, layer.variant === 'koch' ? 5 : 7);
        const triangle = [0, 1, 2].map(i => G.radialPoint(layer.radius, TAU * i / 3));
        if (layer.variant === 'koch') {
          let points = triangle.concat([triangle[0]]);
          for (let level = 0; level < depth; level++) {
            const next = [];
            for (let i = 1; i < points.length; i++) {
              const a = points[i - 1], b = points[i], dx = (b[0] - a[0]) / 3, dy = (b[1] - a[1]) / 3;
              next.push(a, [a[0] + dx, a[1] + dy], [a[0] + 1.5 * dx + Math.sqrt(3) / 2 * dy, a[1] + 1.5 * dy - Math.sqrt(3) / 2 * dx], [a[0] + 2 * dx, a[1] + 2 * dy]);
            }
            next.push(points[points.length - 1]); points = next;
          }
          add(points, true);
        } else if (layer.variant === 'sierpinski') {
          const mid = (a, b) => [(a[0] + b[0]) / 2, (a[1] + b[1]) / 2];
          function divide(a, b, c, remaining) {
            if (!remaining) { add([a, b, c], true); return; }
            const ab = mid(a, b), bc = mid(b, c), ca = mid(c, a);
            divide(a, ab, ca, remaining - 1); divide(ab, b, bc, remaining - 1); divide(ca, bc, c, remaining - 1);
          }
          divide(...triangle, depth);
        } else if (layer.variant === 'tree') {
          const angle = layer.angle * Math.PI / 180;
          function branch(start, direction, length, remaining) {
            const offset = G.polar(length, direction), end = [start[0] + offset[0], start[1] + offset[1]];
            add([start, end]);
            if (remaining) { branch(end, direction - angle, length * layer.shrink, remaining - 1); branch(end, direction + angle, length * layer.shrink, remaining - 1); }
          }
          branch([0, layer.radius], -Math.PI / 2, layer.radius * 2 * (1 - layer.shrink), depth);
          // Fit the complete branching silhouette to the same radius contract.
          const points = shapes.flatMap(shape => shape.points);
          const minX = Math.min(...points.map(p => p[0])), maxX = Math.max(...points.map(p => p[0]));
          const minY = Math.min(...points.map(p => p[1])), maxY = Math.max(...points.map(p => p[1]));
          const cx = (minX + maxX) / 2, cy = (minY + maxY) / 2;
          const scale = layer.radius / Math.max(...points.map(p => Math.hypot(p[0] - cx, p[1] - cy)), 1e-6);
          // Segment endpoints share arrays, so replace coordinates rather than mutating twice.
          shapes.forEach(shape => { shape.points = shape.points.map(p => [(p[0] - cx) * scale, (p[1] - cy) * scale]); });
        } else throw new Error('未知分形纹样');
      } else throw new Error('未知纹样图层');
      layers.push({ spec: layer, shapes });
    }
    return { layers, vertices, paths };
  }
  const api = Object.freeze({ build });
  if (typeof module === 'object' && module.exports) module.exports = api;
  else root.RikkaPatternGeometry = api;
})(typeof window === 'object' ? window : globalThis);
