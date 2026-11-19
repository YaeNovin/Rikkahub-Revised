/* Fixed, bounded evaluator for Kotlin-produced mathematical ASTs. No source evaluation. */
(function (root) {
  'use strict';
  const geometry = typeof module === 'object' && module.exports ? require('./geometry-core.js') : root.RikkaGeometry;
  const functions = Object.freeze({ sin: Math.sin, cos: Math.cos, tan: Math.tan, asin: Math.asin,
    acos: Math.acos, atan: Math.atan, atan2: Math.atan2, sqrt: Math.sqrt, abs: Math.abs,
    exp: Math.exp, ln: Math.log, log: Math.log, log10: Math.log10, floor: Math.floor,
    ceil: Math.ceil, round: Math.round, min: Math.min, max: Math.max, pow: Math.pow });
  const finite = value => Number.isFinite(value) && Math.abs(value) <= 1e12;
  function evaluate(node, variables, budget) {
    if (--budget.remaining < 0) { budget.exhausted = true; return NaN; }
    let value;
    switch (node[0]) {
      case 'num': value = node[1]; break;
      case 'var': value = Object.prototype.hasOwnProperty.call(variables, node[1]) ? variables[node[1]] : NaN; break;
      case 'neg': value = -evaluate(node[1], variables, budget); break;
      case 'bin': {
        const a = evaluate(node[2], variables, budget), b = evaluate(node[3], variables, budget);
        switch (node[1]) {
          case '+': value = a + b; break; case '-': value = a - b; break;
          case '*': value = a * b; break; case '/': value = b === 0 ? NaN : a / b; break;
          case '^': value = Math.pow(a, b); break; default: value = NaN;
        }
        break;
      }
      case 'call': {
        if (!Object.prototype.hasOwnProperty.call(functions, node[1])) return NaN;
        const args = node[2].map(arg => evaluate(arg, variables, budget));
        value = args.every(finite) ? functions[node[1]].apply(null, args) : NaN;
        break;
      }
      default: value = NaN;
    }
    return finite(value) ? value : NaN;
  }
  function sample(curve, parameters, box, budget, width = 600, height = 400) {
    const xs = [], ys = [], variables = Object.assign(Object.create(null), parameters);
    let range = curve.range || [box[0], box[2]], limited = false;
    if (curve.type === 'function') range = [Math.max(range[0], box[0]), Math.min(range[1], box[2])];
    const xScale = width / Math.max(1e-6, box[2] - box[0]);
    const yScale = height / Math.max(1e-6, box[1] - box[3]);
    function point(t) {
      if (curve.type === 'function') { variables.x = t; return [t, evaluate(curve.expression, variables, budget)]; }
      if (curve.type === 'parametric') { variables.t = t; return [evaluate(curve.x, variables, budget), evaluate(curve.y, variables, budget)]; }
      variables.theta = t;
      const r = evaluate(curve.r, variables, budget);
      return geometry.polar(r, t);
    }
    function add(p) {
      if (xs.length >= 4096) { limited = true; return; }
      xs.push(p[0]); ys.push(p[1]);
    }
    function gap() { if (ys.length && Number.isFinite(ys[ys.length - 1])) add([NaN, NaN]); }
    function refine(a, b, pa, pb, depth) {
      if (budget.exhausted || limited) { gap(); return; }
      const mid = (a + b) / 2, pm = point(mid);
      const valid = [pa, pm, pb].map(p => p.every(finite));
      if (!valid.every(Boolean)) {
        if (depth < 7 && valid.some(Boolean)) { refine(a, mid, pa, pm, depth + 1); refine(mid, b, pm, pb, depth + 1); }
        else { gap(); if (valid[2]) add(pb); }
        return;
      }
      const error = Math.hypot((pm[0] - (pa[0] + pb[0]) / 2) * xScale, (pm[1] - (pa[1] + pb[1]) / 2) * yScale);
      const spanY = box[1] - box[3];
      const sameOffscreenSide = [pa, pm, pb].every(p => p[1] > box[1] + spanY * 2) || [pa, pm, pb].every(p => p[1] < box[3] - spanY * 2);
      if (error > 1.5 && depth < 7 && !sameOffscreenSide) {
        refine(a, mid, pa, pm, depth + 1); refine(mid, b, pm, pb, depth + 1);
      } else {
        if (error > 20 && !sameOffscreenSide) { gap(); limited = limited || depth === 7; }
        add(pb);
      }
    }
    if (!(range[1] > range[0])) return { x: [], y: [], limited: false };
    let a = range[0], pa = point(a);
    if (pa.every(finite)) add(pa);
    for (let i = 1; i <= 160 && !budget.exhausted && xs.length < 4096; i++) {
      const b = range[0] + (range[1] - range[0]) * i / 160, pb = point(b);
      refine(a, b, pa, pb, 0); a = b; pa = pb;
      // A local singularity must not prevent the remainder of the curve being sampled.
      if (xs.length < 4096) limited = false;
    }
    return { x: xs, y: ys, limited: budget.exhausted || xs.length >= 4096 };
  }
  const api = Object.freeze({ evaluate, sample });
  if (typeof module === 'object' && module.exports) module.exports = api;
  else root.RikkaMath = api;
})(typeof window === 'object' ? window : globalThis);
