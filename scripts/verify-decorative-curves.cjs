// Generate fixtures with DecorativeCurvePreviewTest; no model or network requests.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const root = path.resolve(__dirname, '..');
const reports = path.join(root, 'app/build/reports/decorative-curves');
const assets = path.join(root, 'app/src/main/assets');
const G = require('../app/src/main/assets/html/renderers/geometry-core.js');
const P = require('../app/src/main/assets/html/renderers/pattern-geometry.js');
const families = ['hypotrochoid', 'epitrochoid', 'lissajous', 'harmonic'];
const fixture = (id, name) => JSON.parse(fs.readFileSync(path.join(reports, `${id}-${name}.json`), 'utf8'));
const gcd = (a, b) => b ? gcd(b, a % b) : a;

function checkPoints(curve, radius) {
  const before = JSON.stringify(curve), points = G.decorativeCurve(curve, radius);
  assert.equal(JSON.stringify(curve), before, 'Pure generation does not mutate parameters');
  assert.equal(points.length, curve.samples + 1);
  assert.deepEqual(points[0], points.at(-1), 'Exact closure without a floating-point seam');
  for (const point of points) {
    assert.ok(point.every(Number.isFinite));
    assert.ok(Math.hypot(...point) <= radius + 1e-8, 'Analytic radius bound');
  }
  assert.deepEqual(points, G.decorativeCurve(curve, radius), 'Deterministic geometry');
  return points;
}

function numericChecks() {
  let cases = 0;
  for (const family of families) {
    const spec = fixture('pattern', family), layer = spec.layers[0], curve = layer.curve;
    assert.deepEqual(curve, fixture('sigil', family).layers[0].curve, 'Shared Kotlin contract');
    const points = checkPoints(curve, layer.radius);
    const built = P.build(spec);
    assert.deepEqual(built.layers[0].shapes[0].points, points, 'p5 consumes shared formula geometry');
    assert.equal(built.layers[0].shapes[0].closed, true);
    assert.equal(built.vertices, points.length);
    // Independent formula checks at every sample, including the analytical end.
    for (let i = 0; i <= curve.samples; i++) {
      const fraction = i / curve.samples;
      let expected;
      if (family === 'hypotrochoid' || family === 'epitrochoid') {
        const inside = family === 'hypotrochoid';
        const R = curve.fixed, r = curve.rolling, d = curve.pen * r;
        const centre = R + (inside ? -r : r), t = 2 * Math.PI * r * fraction;
        const x = centre * Math.cos(t) + (inside ? d : -d) * Math.cos(centre / r * t);
        const y = centre * Math.sin(t) - d * Math.sin(centre / r * t);
        expected = [y, -x].map(v => v * layer.radius / (centre + d));
      } else if (family === 'lissajous') {
        const t = 2 * Math.PI * fraction;
        expected = [Math.sin(curve.frequencyX * t + curve.phase * Math.PI / 180), Math.sin(curve.frequencyY * t)]
          .map(v => v * layer.radius / Math.SQRT2);
      } else {
        const t = 2 * Math.PI * fraction;
        const total = curve.terms.reduce((sum, term) => sum + Math.abs(term.amplitude), 0);
        const radius = layer.radius / (1 + total) * (1 + curve.terms.reduce((sum, term) =>
          sum + term.amplitude * Math.cos(term.frequency * t + term.phase * Math.PI / 180), 0));
        expected = [radius * Math.sin(t), -radius * Math.cos(t)];
      }
      assert.ok(Math.hypot(points[i][0] - expected[0], points[i][1] - expected[1]) < 1e-8, family);
    }
    cases++;
  }
  // Exercise every allowed integer ratio, including unnormalised runtime inputs.
  for (const formula of ['hypotrochoid', 'epitrochoid']) {
    for (let fixed = 1; fixed <= 24; fixed++) for (let rolling = 1; rolling <= 12; rolling++) {
      if (formula === 'hypotrochoid' && fixed <= rolling) continue;
      const divisor = gcd(fixed, rolling), p = fixed / divisor, q = rolling / divisor;
      const samples = Math.max(256, 96 * Math.max(q, formula === 'hypotrochoid' ? p - q : p + q));
      for (const pen of [0, .7, 2]) {
        const curve = { version: 1, formula, fixed, rolling, pen, samples };
        const points = checkPoints(curve, 96);
        assert.deepEqual(points, G.decorativeCurve({ ...curve, fixed: p, rolling: q }, 96));
        cases++;
      }
    }
  }
  for (let frequencyX = 1; frequencyX <= 16; frequencyX++) for (let frequencyY = 1; frequencyY <= 16; frequencyY++) {
    const divisor = gcd(frequencyX, frequencyY);
    const curve = { version: 1, formula: 'lissajous', frequencyX, frequencyY,
      phase: -360, samples: Math.max(256, 96 * Math.max(frequencyX, frequencyY) / divisor) };
    checkPoints(curve, 1); cases++;
  }
  const curve = fixture('pattern', 'hypotrochoid').layers[0].curve;
  for (const invalid of [{ ...curve, version: 2 }, { ...curve, rolling: 0 }, { ...curve, pen: NaN },
    { ...curve, pen: Infinity }, { ...curve, samples: 1e8 }, { ...curve, formula: 'alert(1)' }]) {
    assert.throws(() => G.decorativeCurve(invalid, 80));
  }
  assert.throws(() => G.decorativeCurve(curve, 1e300));
  const expensive = { ...fixture('pattern', 'epitrochoid').layers[0], symmetry: 8,
    curve: { version: 1, formula: 'epitrochoid', fixed: 24, rolling: 11, pen: .7, samples: 3360 } };
  assert.throws(() => G.validateCurveBudget([expensive]), /预算/);
  assert.throws(() => P.build({ layers: [expensive], seed: 42 }), /预算/);
  assert.throws(() => G.validateCurveBudget([{ ...expensive, symmetry: 4 }, { ...expensive, symmetry: 4 }]), /预算/);
  assert.equal(G.validateCurveBudget([{ ...expensive, symmetry: 7 }]), 23527);
  console.log(`PASS ${cases} formula/ratio cases, analytic closure, bounds, backend parity and defensive budgets`);
}

async function browserChecks() {
  const { chromium } = require('playwright');
  const { PNG } = require('pngjs');
  const { pinch } = require('./geometry-gesture-helpers.cjs');
  const browser = await chromium.launch({ channel: 'msedge', headless: true });
  const exportProbe = fs.readFileSync(path.join(reports, 'export-probe.js'), 'utf8');
  const results = [];
  try {
    for (const id of ['sigil', 'pattern']) for (const name of [...families, 'fullscreen', 'animated', 'reduced']) {
      const key = `${id}-${name}`, context = await browser.newContext({ viewport: { width: 480, height: 460 },
        reducedMotion: name === 'reduced' ? 'reduce' : 'no-preference' });
      const page = await context.newPage(), errors = [], external = [], dialogs = [];
      await page.addInitScript(() => {
        window.__cspErrors = [];
        document.addEventListener('securitypolicyviolation', e => window.__cspErrors.push(e.effectiveDirective));
      });
      page.on('pageerror', e => errors.push(e.message));
      page.on('dialog', async d => { dialogs.push(d.message()); await d.dismiss(); });
      await page.route('**/*', async route => {
        const url = new URL(route.request().url());
        if (url.origin !== 'https://rikkahub.local') { external.push(url.href); return route.abort(); }
        if (url.pathname === '/') return route.fulfill({ contentType: 'text/html; charset=utf-8',
          path: path.join(reports, `${id}-${name === 'reduced' ? 'animated' : name}.html`) });
        const file = path.resolve(root, 'app/src/main', '.' + url.pathname);
        if (!file.startsWith(assets + path.sep)) return route.abort();
        return route.fulfill({ path: file, contentType: 'text/javascript' });
      });
      const nonblank = async () => {
        const png = PNG.sync.read(await page.locator('#stage').screenshot({ omitBackground: true }));
        let visible = 0;
        for (let i = 0; i < png.data.length; i += 4) {
          if (png.data[i + 3] > 64 && Math.max(png.data[i], png.data[i + 1], png.data[i + 2]) > 90) visible++;
        }
        assert.ok(visible > 200, `${key}: nonblank pixels (${visible})`);
      };
      try {
        await page.goto('https://rikkahub.local/');
        await page.waitForFunction(() => ['ready', 'error'].includes(window.__rikkaRenderStatus));
        assert.equal(await page.evaluate(() => window.__rikkaRenderStatus), 'ready');
        if (id === 'sigil') {
          assert.ok(await page.locator('[data-layer-type=curve] path').count() > 0);
          assert.ok(await page.locator('[data-layer-type=curve] path').first().evaluate(p => p.getTotalLength()) > 10);
        } else assert.equal(await page.locator('#pattern canvas').count(), 1);
        await page.evaluate(() => window.rikkaSetHostActive(true));
        if (name === 'animated') {
          await page.waitForTimeout(450);
          assert.ok(await page.evaluate(() => window.rikkaRenderDiagnostics().elapsed) > 0);
          if (id === 'pattern') assert.equal(await page.evaluate(() => window.rikkaRenderDiagnostics().artwork.pathBuilds), 1);
          await page.evaluate(() => window.rikkaSetHostActive(false));
          const before = await page.evaluate(() => window.rikkaRenderDiagnostics().elapsed);
          await page.waitForTimeout(100);
          assert.equal(await page.evaluate(() => window.rikkaRenderDiagnostics().elapsed), before);
        } else if (name === 'reduced') {
          assert.ok(await page.locator('#play').isDisabled());
          assert.equal(await page.evaluate(() => window.rikkaRenderDiagnostics().elapsed), 0);
        } else {
          await nonblank();
          await pinch(page);
          assert.ok(await page.evaluate(() => window.rikkaRenderDiagnostics().camera.zoom) > 1);
          await page.locator('#reset').click();
          assert.equal(await page.evaluate(() => window.rikkaRenderDiagnostics().camera.zoom), 1);
        }
        await page.evaluate(() => window.rikkaSetPreviewBackground(true));
        assert.equal(await page.evaluate(() => window.rikkaRenderDiagnostics().transparentBackground), true);
        assert.equal(await page.evaluate(() => getComputedStyle(document.body).backgroundColor), 'rgba(0, 0, 0, 0)');
        let exported = JSON.parse(await page.evaluate(exportProbe));
        if (exported.status === 'layout') {
          await page.waitForTimeout(120);
          exported = JSON.parse(await page.evaluate(exportProbe));
        }
        assert.equal(exported.status, 'ready');
        const frozen = await page.evaluate(() => window.rikkaRenderDiagnostics().elapsed);
        await page.waitForTimeout(100);
        assert.equal(await page.evaluate(() => window.rikkaRenderDiagnostics().elapsed), frozen);
        await nonblank();
        await page.screenshot({ path: path.join(reports, key + '.png') });
        assert.deepEqual(await page.evaluate(() => window.__cspErrors), []);
        assert.deepEqual(errors, []); assert.deepEqual(external, []); assert.deepEqual(dialogs, []);
        await page.evaluate(() => window.dispatchEvent(new PageTransitionEvent('pagehide')));
        assert.equal(await page.evaluate(() => window.__rikkaRenderStatus), 'disposed');
        if (id === 'pattern') assert.equal(await page.locator('#pattern canvas').count(), 0);
        results.push({ name: key, status: 'passed' });
      } finally { await context.close(); }
    }
    fs.writeFileSync(path.join(reports, 'browser-results.json'), JSON.stringify(results, null, 2));
    console.log(`PASS ${results.length} desktop browser scenarios; not Android GPU/device coverage`);
  } finally { await browser.close(); }
}

numericChecks();
if (!process.argv.includes('--numeric')) browserChecks().catch(error => { console.error(error); process.exitCode = 1; });
