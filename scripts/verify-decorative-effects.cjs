// Generate production HTML with DecorativeEffectsTest. No model/network calls.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { chromium } = require('playwright');
const { PNG } = require('pngjs');
const effects = require('../app/src/main/assets/html/renderers/decorative-effects.js');
const root = path.resolve(__dirname, '..'), reports = path.join(root, 'app/build/reports/decorative-effects');
const assets = path.join(root, 'app/src/main/assets');
assert.equal(effects.revealAt({ duration: 1, stagger: .2 }, 2, .2, true), 0);
assert.equal(effects.revealAt({ duration: 1, stagger: .2 }, 2, 2, true), 1);
assert.equal(effects.revealAt({ duration: 1, stagger: .2 }, 2, 0, false), 1);
const budget = effects.createBudget();
for (let i = 0; i < 26; i++) budget.sample(30, 90);
assert.equal(budget.level(), 2);
assert.equal(Math.round(1000 / budget.interval()), 15);
const low = effects.createBudget(); low.constrain({ powerSave: true }); assert.equal(low.level(), 2);
low.constrain({ powerSave: false, hardwareAccelerated: true }); assert.equal(low.level(), 0);
console.log('PASS reveal timing, bounded quality degradation and native environment constraints');

async function main() {
  const browser = await chromium.launch({ channel: 'msedge', headless: true });
  const results = [];
  try {
    for (const name of ['sigil', 'pattern', 'low-power', 'reduced-motion', 'android-reduced', 'static', 'reveal-only', 'detached-export']) {
      const context = await browser.newContext({ viewport: { width: 460, height: 430 }, reducedMotion: name === 'reduced-motion' ? 'reduce' : 'no-preference' });
      const page = await context.newPage(), errors = [], remote = [], dialogs = [];
      page.on('pageerror', e => errors.push(e.message));
      page.on('dialog', async d => { dialogs.push(d.message()); await d.dismiss(); });
      await page.addInitScript(() => { window.__violations = []; document.addEventListener('securitypolicyviolation', e => window.__violations.push(e.effectiveDirective)); });
      await page.route('**/*', route => {
        const url = new URL(route.request().url());
        if (url.origin !== 'https://rikkahub.local') { remote.push(url.href); return route.abort(); }
        const source = ['pattern', 'static', 'reveal-only'].includes(name) ? name : 'sigil';
        if (url.pathname === '/') return route.fulfill({ path: path.join(reports, source + '.html'), contentType: 'text/html' });
        const asset = path.resolve(root, 'app/src/main', '.' + url.pathname);
        if (!asset.startsWith(assets + path.sep)) return route.abort();
        return route.fulfill({ path: asset, contentType: 'text/javascript' });
      });
      const diagnostic = () => page.evaluate(() => window.rikkaRenderDiagnostics());
      try {
        await page.goto('https://rikkahub.local/');
        await page.waitForFunction(() => window.__rikkaRenderStatus === 'ready');
        await page.evaluate(mode => window.rikkaSetRenderEnvironment({ hardwareAccelerated: true, powerSave: mode === 'low-power', reduceMotion: mode === 'android-reduced' }), name);
        if (name === 'detached-export') {
          await page.evaluate(() => window.rikkaPrepareExport());
          assert.ok(await page.locator('#sigil [data-layer-type]').evaluateAll(layers => layers.every(layer => Number(layer.getAttribute('opacity')) === 1)));
        } else {
          if (name === 'sigil') assert.equal(await page.locator('#sigil [data-layer-type]').last().getAttribute('opacity'), '0');
          const initial = await diagnostic();
          await page.evaluate(() => window.rikkaSetHostActive(true));
          await page.waitForTimeout(1000);
          const running = await diagnostic();
          if (['static', 'reduced-motion', 'android-reduced'].includes(name)) {
            assert.equal(running.frames, initial.frames);
            assert.ok(await page.locator('#sigil [data-layer-type]').evaluateAll(layers => layers.every(layer => Number(layer.getAttribute('opacity')) === 1)));
          } else {
            assert.ok(running.frames >= 3);
            if (name === 'pattern') assert.equal(initial.artwork.pathBuilds, running.artwork.pathBuilds);
            if (name === 'low-power') assert.equal(running.quality, 'low');
            if (name === 'reveal-only') { assert.equal(running.active, false); assert.equal(await page.locator('#play').textContent(), '播放'); }
            await page.evaluate(() => window.rikkaSetHostActive(false));
            const paused = await diagnostic(); await page.waitForTimeout(150);
            assert.equal((await diagnostic()).frames, paused.frames);
            if (name !== 'reveal-only') {
              await page.evaluate(() => window.rikkaSetHostActive(true)); await page.waitForTimeout(150);
              assert.ok((await diagnostic()).frames > paused.frames);
            }
          }
          await page.evaluate(() => window.rikkaPrepareExport());
        }
        const frozen = await diagnostic(); await page.waitForTimeout(120);
        assert.equal((await diagnostic()).frames, frozen.frames);
        if (frozen.effects) {
          assert.ok(frozen.effects.particleCount <= 128 && frozen.effects.pixels <= 1200000);
          const before = await page.locator('#stage').screenshot();
          await page.locator('#effects').evaluate(c => { c.hidden = true; });
          const after = await page.locator('#stage').screenshot();
          const a = PNG.sync.read(before).data, b = PNG.sync.read(after).data;
          assert.ok(a.some((v, i) => Math.abs(v - b[i]) > 25), 'Particle overlay contributes actual visible pixels');
          await page.locator('#effects').evaluate(c => { c.hidden = false; });
        }
        await page.setViewportSize({ width: 800, height: 560 }); await page.waitForTimeout(120);
        if (name === 'pattern') assert.equal((await diagnostic()).artwork.pathBuilds, frozen.artwork.pathBuilds);
        await page.screenshot({ path: path.join(reports, name + '.png') });
        assert.deepEqual(await page.evaluate(() => window.__violations), []);
        const metrics = await diagnostic();
        await page.evaluate(() => window.dispatchEvent(new PageTransitionEvent('pagehide')));
        assert.equal(await page.evaluate(() => window.__rikkaRenderStatus), 'disposed');
        assert.equal(await page.locator('#effects').evaluate(c => c.width * c.height), 1);
        assert.deepEqual(errors, []); assert.deepEqual(remote, []); assert.deepEqual(dialogs, []);
        results.push({ name, status: 'passed', metrics });
      } catch (error) {
        console.error({ name, errors, remote, dialogs });
        await page.screenshot({ path: path.join(reports, name + '-failure.png') }); throw error;
      } finally { await context.close(); }
    }
    fs.writeFileSync(path.join(reports, 'browser-results.json'), JSON.stringify(results, null, 2));
    console.log(results.length + ' decorative-effects browser scenarios passed; desktop timing is not Android performance evidence.');
  } finally { await browser.close(); }
}
main().catch(error => { console.error(error); process.exitCode = 1; });
