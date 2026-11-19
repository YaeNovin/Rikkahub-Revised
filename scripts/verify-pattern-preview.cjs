// First generate fixtures with PatternPreviewTest. Uses the real offline p5 distribution.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { chromium } = require('playwright');
const { pinch } = require('./geometry-gesture-helpers.cjs');
const { PNG } = require('pngjs');
const root = path.resolve(__dirname, '..');
const reports = path.join(root, 'app/build/reports/pattern-preview');
const assets = path.join(root, 'app/src/main/assets');
const geometry = require('../app/src/main/assets/html/renderers/geometry-core.js');
const patterns = require('../app/src/main/assets/html/renderers/pattern-geometry.js');
const math = require('../app/src/main/assets/html/renderers/math-plot-runtime.js');
const fixture = name => JSON.parse(fs.readFileSync(path.join(reports, name + '.json'), 'utf8'));

function numericChecks() {
  assert.deepEqual(geometry.polar(2, 0), [2, 0]);
  assert.equal(geometry.starPolygons(70, 6, 2).length, 2, 'Hexagram retains two triangles');
  assert.equal(geometry.radialSegments(80, 70, 48).length, 48);
  for (const name of ['mandala', 'rose', 'koch', 'sierpinski', 'tree', 'mixed', 'advanced', 'complex']) {
    const spec = fixture(name), built = patterns.build(spec);
    assert.ok(built.vertices > 0 && built.vertices <= 24000 && built.paths <= 8192, name);
    assert.equal(JSON.stringify(patterns.build(spec)), JSON.stringify(built), 'Deterministic geometry: ' + name);
    built.layers.forEach(layer => layer.shapes.forEach(shape => shape.points.forEach(p => {
      assert.ok(p.every(Number.isFinite));
      assert.ok(Math.hypot(...p) <= layer.spec.radius + 1e-6, name + ': radius bound');
    })));
  }
  const mandala = fixture('mandala');
  assert.notDeepEqual(patterns.build(mandala), patterns.build({ ...mandala, seed: mandala.seed + 1 }));
  const rose = patterns.build(fixture('rose')).layers[0].shapes[0].points;
  assert.ok(Math.hypot(rose[0][0] - rose.at(-1)[0], rose[0][1] - rose.at(-1)[1]) < 1e-8, 'Rational rose is closed');
  // Compare decorative polar geometry against the existing mathematical AST evaluator.
  for (const [n, d] of [[5, 1], [4, 1], [7, 3], [3, 2], [24, 11]]) {
    const layer = { ...fixture('rose').layers[0], numerator: n, denominator: d, phase: 23 };
    const points = patterns.build({ layers: [layer], seed: 42 }).layers[0].shapes[0].points;
    const period = n % 2 && d % 2 ? Math.PI * d : Math.PI * 2 * d;
    points.forEach((p, i) => {
      const theta = period * i / (points.length - 1);
      const ast = ['bin', '*', ['num', layer.radius], ['call', 'cos', [['bin', '+', ['bin', '*', ['num', n / d], ['var', 'theta']], ['num', 23 * Math.PI / 180]]]]];
      const radius = math.evaluate(ast, { theta }, { remaining: 100 });
      const expected = geometry.polar(radius, theta - Math.PI / 2);
      assert.ok(Math.hypot(p[0] - expected[0], p[1] - expected[1]) < 1e-8);
    });
  }
  const complex = fixture('complex');
  assert.equal(patterns.build(complex).vertices, 23437);
  assert.throws(() => patterns.build({ ...complex, layers: [...complex.layers, complex.layers[0]] }), /预算/);
  assert.throws(() => patterns.build({ layers: [{ ...fixture('koch').layers[0], depth: 100 }] }), /限制/);
  console.log('PASS shared polar/star geometry, deterministic seeds, closed roses, fractal bounds and vertex budget');
}

async function frame(page) {
  const canvas = page.locator('#pattern canvas');
  // Read compositor screenshots, not getImageData: repeated canvas readbacks can
  // switch Chromium from GPU to CPU rasterization and change antialiasing.
  const png = PNG.sync.read(await canvas.screenshot()), data = png.data, baseline = 4 * Math.floor(png.width / 2);
  let hash = 2166136261, colored = 0;
  for (let i = 0; i < data.length; i += 4) {
    hash = Math.imul(hash ^ data[i], 16777619);
    hash = Math.imul(hash ^ data[i + 1], 16777619);
    hash = Math.imul(hash ^ data[i + 2], 16777619);
    if (Math.abs(data[i] - data[baseline]) + Math.abs(data[i + 1] - data[baseline + 1]) + Math.abs(data[i + 2] - data[baseline + 2]) > 40) colored++;
  }
  const result = { hash: hash >>> 0, colored, pixels: await canvas.evaluate(c => c.width * c.height) };
  Object.defineProperty(result, 'data', { value: data });
  return result;
}

function sameRenderedFrame(actual, expected) {
  assert.equal(actual.data.length, expected.data.length);
  let difference = 0, changed = 0;
  for (let i = 0; i < actual.data.length; i++) {
    const delta = Math.abs(actual.data[i] - expected.data[i]);
    difference += delta; if (delta > 16) changed++;
  }
  // Re-rasterizing the same paths can differ at antialiased edge pixels. Geometry
  // equality is checked exactly above; require visual equality here, not bit identity.
  assert.ok(difference / actual.data.length < .2 && changed / actual.data.length < .001,
    'Reset visual mismatch: ' + JSON.stringify({ meanDifference: difference / actual.data.length, changedFraction: changed / actual.data.length }));
}

async function main() {
  numericChecks();
  const browser = await chromium.launch({ channel: 'msedge', headless: true });
  const results = [];
  try {
    for (const name of ['mandala', 'rose', 'koch', 'sierpinski', 'tree', 'mixed', 'advanced', 'complex', 'light', 'fullscreen', 'animated', 'reduced-motion', 'resize-fallback', 'invalid', 'missing-library', 'early-disposal']) {
      const context = await browser.newContext({ viewport: { width: 460, height: 440 }, deviceScaleFactor: 2, reducedMotion: name === 'reduced-motion' ? 'reduce' : 'no-preference' });
      const page = await context.newPage(), errors = [], external = [], dialogs = [], scripts = [];
      await page.addInitScript(({ fallback, early }) => {
        window.__violations = [];
        document.addEventListener('securitypolicyviolation', e => window.__violations.push(e.effectiveDirective));
        if (fallback) window.ResizeObserver = undefined;
        if (early) document.addEventListener('DOMContentLoaded', () => window.dispatchEvent(new PageTransitionEvent('pagehide')), { once: true });
      }, { fallback: name === 'resize-fallback', early: name === 'early-disposal' });
      page.on('pageerror', e => errors.push(e.message));
      page.on('dialog', async dialog => { dialogs.push(dialog.message()); await dialog.dismiss(); });
      await page.route('**/*', async route => {
        const url = new URL(route.request().url());
        if (url.origin !== 'https://rikkahub.local') { external.push(url.href); return route.abort(); }
        const source = name === 'reduced-motion' ? 'animated' : ['resize-fallback', 'missing-library', 'early-disposal'].includes(name) ? 'mandala' : name;
        if (url.pathname === '/') return route.fulfill({ contentType: 'text/html; charset=utf-8', path: path.join(reports, source + '.html') });
        const file = path.resolve(root, 'app/src/main', '.' + url.pathname);
        if (!file.startsWith(assets + path.sep)) return route.abort();
        scripts.push(path.basename(file));
        if (name === 'missing-library' && file.endsWith('p5-1.11.11.min.js')) return route.fulfill({ status: 404, body: 'missing' });
        return route.fulfill({ path: file, contentType: 'text/javascript' });
      });
      try {
        await page.goto('https://rikkahub.local/');
        await page.waitForFunction(() => ['ready', 'error', 'disposed'].includes(window.__rikkaRenderStatus));
        const failed = ['invalid', 'missing-library'].includes(name), early = name === 'early-disposal';
        assert.equal(await page.evaluate(() => window.__rikkaRenderStatus), early ? 'disposed' : failed ? 'error' : 'ready');
        if (early) {
          await page.waitForTimeout(150);
          assert.equal(await page.locator('canvas:not(#effects)').count(), 0, 'Late p5 setup cannot recreate a disposed canvas');
        } else if (failed) {
          assert.ok(await page.locator('#error').isVisible());
          assert.ok((await page.locator('#error').textContent()).includes('无法绘图'));
          assert.equal(await page.locator('#pattern canvas').count(), 0);
        } else {
          assert.equal(await page.locator('canvas:not(#effects)').count(), 1, 'Only one p5 instance canvas');
          assert.equal(await page.evaluate(() => p5.VERSION), '1.11.11');
          assert.equal(scripts.some(script => script.startsWith('jsxgraph')), false);
          assert.equal(await page.locator('img,iframe,[onerror],[onload]').count(), 0);
          const initial = await frame(page);
          assert.ok(initial.colored > 300, name + ': real nonblank pixels');
          assert.ok(initial.pixels <= 2000000, 'Backing canvas budget');
          await page.waitForTimeout(180);
          assert.equal((await frame(page)).hash, initial.hash, 'Host starts inactive');
          await page.evaluate(() => window.rikkaSetHostActive(true));
          if (name === 'animated' || name === 'advanced') {
            await page.waitForTimeout(300);
            assert.notEqual((await frame(page)).hash, initial.hash, 'Visible animation advances');
            await page.evaluate(() => window.rikkaSetHostActive(false));
            const paused = await frame(page);
            await page.waitForTimeout(150); assert.equal((await frame(page)).hash, paused.hash);
            await page.evaluate(() => window.rikkaSetHostActive(true));
            await page.waitForTimeout(200); assert.notEqual((await frame(page)).hash, paused.hash);
            await page.emulateMedia({ reducedMotion: 'reduce' });
            await page.waitForTimeout(60);
            const reduced = await frame(page);
            await page.waitForTimeout(150); assert.equal((await frame(page)).hash, reduced.hash);
            assert.ok(await page.locator('#play').isDisabled());
            await page.emulateMedia({ reducedMotion: 'no-preference' });
            await page.locator('#play').click();
            await page.waitForTimeout(200);
            await page.evaluate(() => window.rikkaPrepareExport());
            const exported = await frame(page);
            await page.waitForTimeout(150); assert.equal((await frame(page)).hash, exported.hash);
          } else if (name === 'reduced-motion') {
            await page.waitForTimeout(200); assert.equal((await frame(page)).hash, initial.hash);
            assert.ok(await page.locator('#play').isDisabled());
          } else {
            await page.waitForTimeout(150); assert.equal((await frame(page)).hash, initial.hash, 'Static sketches do not loop');
            assert.equal(await page.locator('#zoom-in,#zoom-out').count(), 0);
            await pinch(page);
            assert.notEqual((await frame(page)).hash, initial.hash);
            await page.locator('#reset').click();
            const resetFrame = await frame(page);
            sameRenderedFrame(resetFrame, initial);
            if (await page.locator('#variation').isVisible()) {
              await page.locator('#variation').click();
              assert.notEqual((await frame(page)).hash, initial.hash, 'Seed button changes rendered mandala');
              assert.ok((await page.locator('#status').textContent()).includes('种子 43'));
              await page.locator('#reset').click();
              sameRenderedFrame(await frame(page), initial);
            }
          }
          if (name === 'mixed') assert.ok((await page.locator('#title').textContent()).startsWith('<img'));
          assert.deepEqual(await page.evaluate(() => window.__violations), [], 'Runs under CSP without eval or extra resources');
          await page.setViewportSize({ width: 800, height: 580 });
          await page.waitForTimeout(180);
          const bounds = await page.locator('#pattern canvas').boundingBox();
          assert.ok(bounds.width > 700 && bounds.height > 400, JSON.stringify(bounds));
          assert.ok((await frame(page)).pixels <= 2000000);
          await page.screenshot({ path: path.join(reports, name + '.png') });
          assert.equal(await page.evaluate(async () => { try { await fetch('https://evil.test/probe'); return false; } catch (_) { return true; } }), true);
          await page.evaluate(() => window.dispatchEvent(new PageTransitionEvent('pagehide')));
          assert.equal(await page.evaluate(() => window.__rikkaRenderStatus), 'disposed');
          assert.equal(await page.locator('canvas:not(#effects)').count(), 0);
        }
        assert.deepEqual(errors, []); assert.deepEqual(external, []); assert.deepEqual(dialogs, []);
        results.push({ name, status: 'passed' });
      } catch (error) {
        console.error({ name, errors, external, dialogs });
        await page.screenshot({ path: path.join(reports, name + '-failure.png') });
        throw error;
      } finally { await context.close(); }
    }
    fs.writeFileSync(path.join(reports, 'browser-results.json'), JSON.stringify(results, null, 2));
    console.log(results.length + ' real p5 browser scenarios passed; Android device/GPU behavior is not covered.');
  } finally { await browser.close(); }
}
main().catch(error => { console.error(error); process.exitCode = 1; });
