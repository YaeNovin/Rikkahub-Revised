// Generate production fixtures with GeometryPreviewTest first. No network or models.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { chromium } = require('playwright');
const { pinch } = require('./geometry-gesture-helpers.cjs');
const root = path.resolve(__dirname, '..');
const reports = path.join(root, 'app/build/reports/geometry-preview');
const assets = path.join(root, 'app/src/main/assets');
const math = require('../app/src/main/assets/html/renderers/math-plot-runtime.js');

function numericChecks() {
  const spec = JSON.parse(fs.readFileSync(path.join(reports, 'special.json'), 'utf8'));
  const box = [-8, 5, 8, -5];
  for (const [index, pole] of [[0, 0], [1, Math.PI / 2], [1, -Math.PI / 2]]) {
    const data = math.sample(spec.curves[index], {}, box, { remaining: 250000 });
    assert.ok(data.y.some(Number.isNaN), 'Poles create gaps');
    assert.ok(data.y.some(Number.isFinite), 'Valid branches remain');
    for (let i = 1; i < data.x.length; i++) {
      if (Number.isFinite(data.y[i - 1]) && Number.isFinite(data.y[i])) {
        assert.ok(!(data.x[i - 1] < pole && data.x[i] > pole), 'No false segment across a pole');
      }
    }
  }
  const sqrt = math.sample(spec.curves[2], {}, box, { remaining: 250000 });
  assert.ok(sqrt.x.some(x => x > 1));
  sqrt.x.forEach((x, i) => { if (Number.isFinite(sqrt.y[i])) assert.ok(x >= 0); });
  const circle = math.sample(spec.curves[3], {}, box, { remaining: 250000 });
  circle.x.forEach((x, i) => { if (Number.isFinite(x)) assert.ok(Math.abs(x * x + circle.y[i] ** 2 - 4) < 1e-8); });
  const budget = { remaining: 5 };
  assert.equal(math.sample(spec.curves[0], {}, box, budget).limited, true);
  assert.ok(budget.exhausted);
  console.log('PASS numerical AST, singularities, domain gaps, parametric circle and budget');
}

async function main() {
  numericChecks();
  const browser = await chromium.launch({ channel: 'msedge', headless: true });
  const results = [];
  try {
    for (const name of ['math', 'math-dark', 'math-fullscreen', 'special', 'sigil', 'sigil-advanced', 'sigil-light', 'reduced-motion', 'invalid', 'missing-library']) {
      const context = await browser.newContext({ viewport: { width: 460, height: 410 }, reducedMotion: name === 'reduced-motion' ? 'reduce' : 'no-preference' });
      const page = await context.newPage(), errors = [], external = [], dialogs = [], scripts = [], violations = [];
      await page.addInitScript(() => {
        window.__violations = [];
        document.addEventListener('securitypolicyviolation', e => window.__violations.push(e.effectiveDirective));
      });
      page.on('pageerror', e => errors.push(e.message));
      page.on('dialog', async d => { dialogs.push(d.message()); await d.dismiss(); });
      await page.route('**/*', async route => {
        const url = new URL(route.request().url());
        if (url.origin !== 'https://rikkahub.local') { external.push(url.href); return route.abort(); }
        const fixture = name === 'missing-library' ? 'math' : name === 'reduced-motion' ? 'sigil' : name;
        if (url.pathname === '/') return route.fulfill({ contentType: 'text/html; charset=utf-8', path: path.join(reports, fixture + '.html') });
        const file = path.resolve(root, 'app/src/main', '.' + url.pathname);
        if (!file.startsWith(assets + path.sep)) return route.abort();
        scripts.push(path.basename(file));
        if (name === 'missing-library' && file.endsWith('jsxgraph-1.13.3.js')) return route.fulfill({ status: 404, body: 'missing' });
        return route.fulfill({ path: file, contentType: file.endsWith('.css') ? 'text/css' : 'text/javascript' });
      });
      try {
        await page.goto('https://rikkahub.local/');
        await page.waitForFunction(() => ['ready', 'error'].includes(window.__rikkaRenderStatus));
        const failed = name === 'invalid' || name === 'missing-library';
        assert.equal(await page.evaluate(() => window.__rikkaRenderStatus), failed ? 'error' : 'ready');
        if (failed) {
          assert.ok(await page.locator('#error').isVisible());
          assert.ok((await page.locator('#error').textContent()).includes('无法绘图'));
        } else {
          await page.evaluate(() => window.rikkaSetHostActive(true));
          if (name.startsWith('math') || name === 'special') {
            assert.ok(await page.locator('#board svg').isVisible());
            assert.ok(await page.locator('#board svg path').count() > 0);
            const box = () => page.evaluate(() => Object.values(JXG.boards)[0].getBoundingBox());
            const before = await box();
            assert.ok(before[0] <= -8 + .01 && before[2] >= 8 - .01, 'Initial x range stays visible with equal units');
            assert.equal(await page.locator('#zoom-in,#zoom-out').count(), 0);
            await pinch(page);
            const after = await box();
            assert.ok(after[2] - after[0] < before[2] - before[0]);
            await page.locator('#reset').click();
            if (name !== 'special') {
              await page.locator('input[type=range]').fill('2');
              await page.waitForTimeout(150);
              assert.ok((await page.locator('#parameters').textContent()).includes('2.000'));
              const curves = await page.evaluate(() => Object.values(Object.values(JXG.boards)[0].objects).filter(o => o.elType === 'curve' && o.dataY).map(o => Math.max(...o.dataY.filter(Number.isFinite))));
              // Real pixel/geometry checks below do not depend on serializing board objects.
              assert.ok(curves.some(max => max > 1.98 && max <= 2.001), 'Slider changes the evaluated curve, not only its label');
            } else {
              assert.ok((await page.locator('#title').textContent()).startsWith('<img'));
              assert.equal(await page.locator('img,[onload],[onerror]').count(), 0);
              // JSXGraph creates one empty, hidden foreignObject host itself.
              // No model label may populate it or create an active HTML element.
              assert.ok(await page.locator('foreignObject').evaluateAll(nodes => nodes.every(node => node.children.length === 0 && node.getAttribute('display') === 'none')));
            }
            await page.setViewportSize({ width: 800, height: 560 });
            await page.waitForTimeout(180);
            const bounds = await page.locator('#board svg').boundingBox();
            assert.ok(bounds.width > 700 && bounds.height > 200, JSON.stringify(bounds));
          } else {
            assert.ok(await page.locator('#sigil').isVisible());
            if (await page.locator('[data-layer-type=star]').count()) {
              assert.equal(await page.locator('[data-layer-type=star]').first().locator('polygon').count(), 2);
            } else {
              assert.ok(await page.locator('[data-layer-type=path]').count() > 0, 'Advanced sigil path layer renders');
            }
            assert.equal(scripts.some(s => s.startsWith('jsxgraph')), false);
            const layer = (await page.locator('[data-layer-type=star]').count())
              ? page.locator('[data-layer-type=star]').first()
              : page.locator('[data-layer-type=path]').first();
            const before = await layer.getAttribute('transform');
            await page.waitForTimeout(180);
            if (name === 'reduced-motion') {
              assert.equal(await layer.getAttribute('transform'), before);
              assert.ok(await page.locator('#play').isDisabled());
            } else {
              const drawBefore = name === 'sigil-advanced' ? await layer.evaluate(node => node.style.strokeDashoffset) : null;
              await page.waitForTimeout(180);
              const drawAfter = name === 'sigil-advanced' ? await layer.evaluate(node => node.style.strokeDashoffset) : null;
              assert.ok(name === 'sigil-advanced' ? drawAfter !== drawBefore : (await layer.getAttribute('transform')) !== before);
              await page.evaluate(() => window.rikkaSetHostActive(false));
              const paused = name === 'sigil-advanced' ? await layer.evaluate(node => node.style.strokeDashoffset) : await layer.getAttribute('transform');
              await page.waitForTimeout(150); assert.equal(name === 'sigil-advanced' ? await layer.evaluate(node => node.style.strokeDashoffset) : await layer.getAttribute('transform'), paused);
              await page.evaluate(() => window.rikkaSetHostActive(true));
              await page.waitForTimeout(150); assert.notEqual(name === 'sigil-advanced' ? await layer.evaluate(node => node.style.strokeDashoffset) : await layer.getAttribute('transform'), paused);
              await page.evaluate(() => window.rikkaPrepareExport());
              const snapshot = name === 'sigil-advanced' ? await layer.evaluate(node => node.style.strokeDashoffset) : await layer.getAttribute('transform');
              await page.waitForTimeout(150); assert.equal(name === 'sigil-advanced' ? await layer.evaluate(node => node.style.strokeDashoffset) : await layer.getAttribute('transform'), snapshot);
              assert.equal(await page.locator('#play').innerText(), '播放');
            }
          }
          violations.push(...await page.evaluate(() => window.__violations));
          assert.deepEqual(violations, [], 'Renderer runs under strict CSP without eval');
          assert.equal(await page.evaluate(async () => { try { await fetch('https://evil.test/probe'); return false; } catch (_) { return true; } }), true);
          await page.screenshot({ path: path.join(reports, name + '.png') });
          await page.evaluate(() => window.dispatchEvent(new PageTransitionEvent('pagehide')));
          assert.equal(await page.evaluate(() => window.__rikkaRenderStatus), 'disposed');
        }
        assert.deepEqual(errors, []); assert.deepEqual(external, []); assert.deepEqual(dialogs, []);
        results.push({ name, status: 'passed' });
      } catch (error) {
        console.error({ name, errors, external, dialogs, violations });
        await page.screenshot({ path: path.join(reports, name + '-failure.png') });
        throw error;
      } finally { await context.close(); }
    }
    fs.writeFileSync(path.join(reports, 'browser-results.json'), JSON.stringify(results, null, 2));
    console.log(results.length + ' JSXGraph/sigil browser scenarios passed; Android device behavior is not covered.');
  } finally { await browser.close(); }
}
main().catch(error => { console.error(error); process.exitCode = 1; });
