// Production fixtures come from GeometryAppearanceTest. These are desktop/CDP
// touch checks, not proof of Android window insets or native scroll interception.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { PNG } = require('pngjs');
const { chromium } = require('playwright');
const { pinch, drag } = require('./geometry-gesture-helpers.cjs');
const root = path.resolve(__dirname, '..'), reports = path.join(root, 'app/build/reports/geometry-appearance');
const assets = path.join(root, 'app/src/main/assets');

async function emptyPixel(page) {
  const png = PNG.sync.read(await page.locator('#stage').screenshot({ omitBackground: true }));
  return Array.from(png.data.subarray(4 * (2 * png.width + 2), 4 * (2 * png.width + 2) + 4));
}
async function chromePixels(page) {
  const alpha = [];
  for (const selector of ['header', '#status', '#controls']) {
    const png = PNG.sync.read(await page.locator(selector).screenshot({ omitBackground: true }));
    alpha.push(png.data[4 * (2 * png.width + 2) + 3]);
  }
  return alpha;
}
async function main() {
  const browser = await chromium.launch({ channel: 'msedge', headless: true });
  const results = [];
  try {
    for (const name of ['math_plot', 'sigil', 'pattern', 'math_plot-inline', 'sigil-inline', 'pattern-inline', 'animated']) {
      const inline = name.endsWith('-inline'), math = name.startsWith('math_plot');
      const context = await browser.newContext({ viewport: { width: 460, height: 780 }, hasTouch: true });
      const page = await context.newPage(), errors = [], remote = [];
      await page.addInitScript(() => { window.__violations = []; document.addEventListener('securitypolicyviolation', e => window.__violations.push(e.effectiveDirective)); });
      page.on('pageerror', e => errors.push(e.message));
      await page.route('**/*', route => {
        const url = new URL(route.request().url());
        if (url.origin !== 'https://rikkahub.local') { remote.push(url.href); return route.abort(); }
        if (url.pathname === '/') return route.fulfill({ path: path.join(reports, name + '.html'), contentType: 'text/html' });
        const asset = path.resolve(root, 'app/src/main', '.' + url.pathname);
        if (!asset.startsWith(assets + path.sep)) return route.abort();
        return route.fulfill({ path: asset, contentType: asset.endsWith('.css') ? 'text/css' : 'text/javascript' });
      });
      const camera = () => page.evaluate(() => window.JXG ? Object.values(JXG.boards)[0].getBoundingBox() : window.rikkaRenderDiagnostics().camera);
      try {
        await page.goto('https://rikkahub.local/');
        await page.waitForFunction(() => window.__rikkaRenderStatus === 'ready');
        assert.equal(await page.locator('#zoom-in,#zoom-out,header button').count(), 0);
        assert.equal(await page.locator('#reset').evaluate(e => getComputedStyle(e).borderWidth), '0px');
        const controls = await page.locator('#controls').boundingBox(), stage = await page.locator('#stage').boundingBox();
        assert.ok(controls.y >= stage.y + stage.height, 'Controls are below, not over the top of the drawing');
        await page.evaluate(() => { window.savedCanvas = document.querySelector('#pattern canvas'); window.savedSvg = document.querySelector('#sigil'); });
        const initial = await camera();
        await pinch(page, 1.5, [.55, .45]);
        const enlarged = await camera();
        if (math) assert.ok(enlarged[2] - enlarged[0] < (initial[2] - initial[0]) * .8);
        else assert.ok(enlarged.zoom > 1.35);
        assert.equal(await page.evaluate(() => visualViewport.scale), 1, 'Scale content, not the WebView page/chrome');
        await pinch(page, .7);
        const smaller = await camera();
        if (math) assert.ok(smaller[2] - smaller[0] > enlarged[2] - enlarged[0]); else assert.ok(smaller.zoom < enlarged.zoom);
        await page.locator('#reset').click(); await page.waitForTimeout(140);
        const beforeDrag = await camera();
        await drag(page);
        const afterDrag = await camera();
        if (inline) assert.deepEqual(afterDrag, beforeDrag, 'Single-finger inline gestures do not pan the renderer');
        else assert.notDeepEqual(afterDrag, beforeDrag, 'Fullscreen drag pans the drawing');
        await drag(page, 10, 10, true); // Cancel must not poison the next gesture.
        await page.locator('#reset').click(); await page.waitForTimeout(140);
        await pinch(page, 1.4);
        const beforeBackground = await camera();
        const paths = await page.evaluate(() => window.rikkaRenderDiagnostics().artwork?.pathBuilds);
        await page.evaluate(() => window.rikkaSetPreviewBackground(true));
        assert.deepEqual(await camera(), beforeBackground, 'Background enable must preserve the camera');
        assert.equal((await emptyPixel(page))[3], math ? 255 : 0, 'Decorative SVG/Canvas really clears its backing pixels');
        assert.deepEqual(await chromePixels(page), [0, 0, 0].map(() => math ? 255 : 0), 'Title/status/action bars also show the host background');
        const originalColor = await page.locator('#title').evaluate(element => getComputedStyle(element).color);
        await page.evaluate(() => window.rikkaSetPreviewBackground(true, { foreground: '#123456', shadow: '#ffffff' }));
        assert.equal(await page.locator('#title').evaluate(element => getComputedStyle(element).color), math ? originalColor : 'rgb(18, 52, 86)');
        if (!math) {
          assert.notEqual(await page.locator('#reset').evaluate(element => getComputedStyle(element).textShadow), 'none');
          await page.evaluate(() => window.rikkaSetPreviewBackground(true, { foreground: 'url(https://evil.test)', shadow: 'red; background:red' }));
          assert.equal(await page.locator('#title').evaluate(element => getComputedStyle(element).color), 'rgb(18, 52, 86)');
        }
        await page.evaluate(() => window.rikkaSetPreviewBackground(false));
        assert.equal((await emptyPixel(page))[3], 255, 'Disabling inherited background restores the original paper');
        assert.deepEqual(await chromePixels(page), [255, 255, 255], 'Disabled background restores opaque chrome');
        assert.equal(await page.locator('#title').evaluate(element => getComputedStyle(element).color), originalColor);
        await page.evaluate(() => window.rikkaSetPreviewBackground(true, { foreground: '#e6edf3', shadow: '#000000' }));
        await page.evaluate(() => window.rikkaSetPreviewBackground(true));
        assert.equal(await page.evaluate(() => window.savedCanvas === document.querySelector('#pattern canvas') && window.savedSvg === document.querySelector('#sigil')), true);
        assert.equal(await page.evaluate(() => window.rikkaRenderDiagnostics().artwork?.pathBuilds), paths, 'Background switches do not regenerate paths');
        if (name === 'animated') {
          await page.evaluate(() => window.rikkaSetHostActive(true)); await page.waitForTimeout(180);
          const time = await page.evaluate(() => window.rikkaRenderDiagnostics().elapsed);
          await page.evaluate(() => window.rikkaSetPreviewBackground(false)); await page.waitForTimeout(180);
          assert.ok(await page.evaluate(() => window.rikkaRenderDiagnostics().elapsed) > time, 'Animation time survives appearance changes');
          await page.evaluate(() => window.rikkaSetPreviewBackground(true));
          await page.evaluate(() => window.rikkaPrepareExport());
          const frozen = await page.evaluate(() => window.rikkaRenderDiagnostics().elapsed);
          await page.waitForTimeout(100); assert.equal(await page.evaluate(() => window.rikkaRenderDiagnostics().elapsed), frozen);
        }
        await page.locator('#reset').click(); await page.waitForTimeout(140);
        await page.setViewportSize({ width: 800, height: 500 }); await page.waitForTimeout(150);
        await pinch(page, 1.25);
        const resized = await camera();
        assert.ok(math ? resized.every(Number.isFinite) : Number.isFinite(resized.zoom));
        await page.locator('#stage').focus();
        await page.keyboard.press('0'); await page.waitForTimeout(140);
        const reset = await camera();
        if (!math) assert.deepEqual(reset, { zoom: 1, x: 0, y: 0 });
        assert.deepEqual(await page.evaluate(() => window.__violations), []);
        await page.locator('#stage').evaluate(element => element.blur());
        await page.screenshot({ path: path.join(reports, name + '.png') });
        await page.evaluate(() => window.dispatchEvent(new PageTransitionEvent('pagehide')));
        assert.equal(await page.evaluate(() => window.__rikkaRenderStatus), 'disposed');
        assert.deepEqual(errors, []); assert.deepEqual(remote, []);
        results.push({ name, status: 'passed' });
      } catch (error) {
        console.error({ name, errors, remote });
        await page.screenshot({ path: path.join(reports, name + '-failure.png') }); throw error;
      } finally { await context.close(); }
    }
    fs.writeFileSync(path.join(reports, 'browser-results.json'), JSON.stringify(results, null, 2));
    console.log(results.length + ' background/real-touch geometry scenarios passed; Android safe-area/parent-scroll still require device verification.');
  } finally { await browser.close(); }
}
main().catch(error => { console.error(error); process.exitCode = 1; });
