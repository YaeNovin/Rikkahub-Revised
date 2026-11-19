/* Shared, code-owned geometry. Cartesian polar coordinates use radians;
 * radial artwork starts at the top and rotates clockwise in screen space. */
(function (root) {
  'use strict';
  const polar = (radius, angle) => [radius * Math.cos(angle), radius * Math.sin(angle)];
  const radialPoint = (radius, angle) => polar(radius, angle - Math.PI / 2);
  function starPolygons(radius, count, step) {
    if (!Number.isInteger(count) || count < 3 || count > 32 || !Number.isInteger(step) || step < 1 || step >= count / 2) throw new Error('Invalid star geometry');
    const visited = new Set(), polygons = [];
    for (let start = 0; start < count; start++) {
      if (visited.has(start)) continue;
      const points = []; let index = start;
      do { visited.add(index); points.push(radialPoint(radius, 2 * Math.PI * index / count)); index = (index + step) % count; } while (index !== start);
      polygons.push(points);
    }
    return polygons;
  }
  function radialSegments(radius, innerRadius, count) {
    if (!Number.isInteger(count) || count < 3 || count > 128) throw new Error('Invalid radial geometry');
    return Array.from({ length: count }, (_, i) => {
      const angle = 2 * Math.PI * i / count;
      return [radialPoint(innerRadius, angle), radialPoint(radius, angle)];
    });
  }
  // Decorative formula version 1. Keep the legacy star/rose/mandala paths intact.
  // Integers give a finite common period; no formula strings or author callbacks.
  function decorativeCurvePlan(curve) {
    const number = (value, min, max) => {
      if (!Number.isFinite(value) || value < min || value > max) throw new Error('curve 参数超出范围');
      return value;
    };
    const integer = (value, min, max) => {
      number(value, min, max);
      if (!Number.isInteger(value)) throw new Error('curve 参数需要整数');
      return value;
    };
    if (!curve || curve.version !== 1) throw new Error('不支持的 curve 版本');
    const gcd = (a, b) => b === 0 ? a : gcd(b, a % b);
    let complexity, period = 2 * Math.PI, evaluate;
    if (curve.formula === 'hypotrochoid' || curve.formula === 'epitrochoid') {
      const fixed = integer(curve.fixed, 1, 24), rolling = integer(curve.rolling, 1, 12);
      const inner = curve.formula === 'hypotrochoid';
      if (inner && fixed <= rolling) throw new Error('hypotrochoid.fixed 必须大于 rolling');
      const divisor = gcd(fixed, rolling), p = fixed / divisor, q = rolling / divisor;
      const a = inner ? p - q : p + q, d = number(curve.pen, 0, 2) * q;
      period *= q;
      complexity = Math.max(q, a);
      evaluate = t => {
        const x = a * Math.cos(t) + (inner ? 1 : -1) * d * Math.cos(a / q * t);
        const y = a * Math.sin(t) - d * Math.sin(a / q * t);
        // Rotate to the artwork's top-origin convention. Analytic bound, not
        // sampled min/max normalization, so changing density never changes size.
        return [y / (a + d), -x / (a + d)];
      };
    } else if (curve.formula === 'lissajous') {
      const x = integer(curve.frequencyX, 1, 16), y = integer(curve.frequencyY, 1, 16);
      const divisor = gcd(x, y), a = x / divisor, b = y / divisor;
      const phase = number(curve.phase, -360, 360) * Math.PI / 180;
      complexity = Math.max(a, b);
      evaluate = t => [Math.sin(a * t + phase) / Math.SQRT2, Math.sin(b * t) / Math.SQRT2];
    } else if (curve.formula === 'harmonic') {
      if (!Array.isArray(curve.terms) || curve.terms.length < 1 || curve.terms.length > 6) throw new Error('harmonic.terms 数量超出范围');
      const terms = curve.terms.map(term => ({
        frequency: integer(term.frequency, 1, 16), amplitude: number(term.amplitude, -.8, .8),
        phase: number(term.phase, -360, 360) * Math.PI / 180,
      }));
      const total = terms.reduce((sum, term) => sum + Math.abs(term.amplitude), 0);
      if (total > .9 + 1e-9) throw new Error('harmonic.terms 振幅绝对值之和不能超过 0.9');
      complexity = 1 + Math.max(...terms.map(term => term.frequency));
      evaluate = t => radialPoint((1 + terms.reduce((sum, term) => sum + term.amplitude * Math.cos(term.frequency * t + term.phase), 0)) / (1 + total), t);
    } else throw new Error('不支持的 curve.formula');
    const samples = Math.max(256, complexity * 96);
    // The compiler sends its estimate; validate parity before allocating paths.
    if (curve.samples !== samples) throw new Error('curve 采样预算不匹配');
    return { samples, period, evaluate };
  }
  function decorativeCurve(curve, radius) {
    if (!Number.isFinite(radius) || radius < 1 || radius > 96) throw new Error('curve.radius 超出范围');
    const { samples, period, evaluate } = decorativeCurvePlan(curve);
    const points = Array.from({ length: samples }, (_, i) => evaluate(period * i / samples).map(value => value * radius));
    // Closed by construction; copy the first point to avoid a floating-point seam.
    points.push(points[0].slice());
    return points;
  }
  function validateCurveBudget(layers) {
    let vertices = 0;
    for (const layer of layers) {
      if (layer.type !== 'curve') continue;
      const copies = layer.symmetry === undefined ? 1 : layer.symmetry;
      if (!Number.isInteger(copies) || copies < 1 || copies > 12) throw new Error('curve.symmetry 超出范围');
      vertices += (decorativeCurvePlan(layer.curve).samples + 1) * copies;
      if (vertices > 24000) throw new Error('curve 对称复制后的顶点超过几何预算');
    }
    return vertices;
  }
  const api = Object.freeze({ polar, radialPoint, starPolygons, radialSegments, decorativeCurve, validateCurveBudget });
  if (typeof module === 'object' && module.exports) module.exports = api;
  else root.RikkaGeometry = api;
})(typeof window === 'object' ? window : globalThis);
