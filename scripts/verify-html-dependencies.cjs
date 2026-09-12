// Run HtmlPreviewDependenciesTest first. Exercise generated production HTML with real bundled libraries.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { chromium } = require('playwright');
const root = path.resolve(__dirname, '..');
const directory = path.join(root, 'app/build/reports/html-dependencies');
const assetRoot = path.join(root, 'app/src/main/assets');
const globals = { vega: 'vegaEmbed', smiles: 'SmilesDrawer', musicxml: 'opensheetmusicdisplay' };

async function main() {
  const browser = await chromium.launch({ channel: 'msedge', headless: true });
  const results = [];
  async function scenario(name, kinds, options = {}) {
    const page = await browser.newPage({ viewport: { width: 393, height: 780 } });
    const errors = [], external = [], scripts = [];
    page.on('pageerror', error => errors.push(error.message));
    await page.route('**/*', async route => {
      const url = new URL(route.request().url());
      if (url.host !== 'rikkahub.local') { external.push(url.href); return route.abort(); }
      if (url.pathname === '/') {
        // Match WebView.loadDataWithBaseURL(..., encoding = "utf-8"), including HTML fragments without a charset meta.
        return route.fulfill({ contentType: 'text/html; charset=utf-8', body: fs.readFileSync(path.join(directory, name + '.html'), 'utf8') });
      }
      const target = path.resolve(root, 'app/src/main', '.' + url.pathname);
      if (!target.startsWith(assetRoot + path.sep)) return route.abort();
      scripts.push(path.basename(target));
      if (!fs.existsSync(target) || (options.missing && target.endsWith(options.missing))) {
        return route.fulfill({ status: 404, contentType: 'text/plain', body: 'Missing library' });
      }
      // A slow first dependency must still finish before consumers/inline code execute.
      if (target.endsWith('vega.min.js')) await new Promise(resolve => setTimeout(resolve, 80));
      return route.fulfill({ path: target, contentType: 'text/javascript; charset=utf-8' });
    });
    try {
      await page.goto('https://rikkahub.local/');
      if (options.original) {
        assert.ok(errors.some(error => error.includes(globals[kinds[0]] + ' is not defined')), name + ' must reproduce screenshot error');
      } else if (options.missing) {
        const notice = page.locator('#rikkahub-renderer-dependency-error');
        await notice.waitFor({ state: 'visible' });
        assert.ok((await notice.innerText()).includes('vegaLite'));
        assert.equal(await page.locator('#chart').count(), 1, 'Keep author content on failure');
      } else {
        for (const kind of kinds) {
          await page.waitForFunction(key => window[key + 'Done'] === true, kind, { timeout: 15000 });
          const selector = { vega: '#chart svg', smiles: '#molecule', musicxml: '#score svg' }[kind];
          const drawing = page.locator(selector).first();
          const bounds = await drawing.boundingBox();
          assert.ok(bounds && bounds.width > 0 && bounds.height > 0, name + ' visible graphic');
          assert.ok(await drawing.locator('path,line,text').count() > 0, name + ' painted content');
          if (kind === 'vega') {
            const labels = await drawing.locator('text').allTextContents();
            assert.ok(labels.includes('甲') && labels.includes('乙'), name + ' preserves Unicode labels');
          }
        }
        assert.deepEqual(errors, [], name + ' uncaught errors');
        assert.equal(new Set(scripts).size, scripts.length, name + ' duplicate bundle loads');
        if (!kinds.length) assert.deepEqual(scripts, [], 'Do not load large renderers for plain HTML');
        const htmlBefore = await page.locator('body').innerHTML();
        await page.setViewportSize({ width: 412, height: 860 });
        assert.ok(htmlBefore.length > 0);
        await page.screenshot({ path: path.join(directory, name + '-browser.png'), fullPage: true });
      }
      assert.deepEqual(external, [], name + ' unexpected network dependency');
      results.push({ name, scenario: options, scripts, errors, passed: true });
      console.log('PASS ' + name + (options.original ? ' reproduces original error' : options.missing ? ' dependency failure notice' : ' renders offline'));
    } catch (error) {
      console.error(JSON.stringify({ name, errors, external, scripts }, null, 2));
      await page.screenshot({ path: path.join(directory, name + '-failure.png'), fullPage: true });
      throw error;
    } finally { await page.close(); }
  }
  try {
    for (const name of Object.keys(globals)) await scenario(name + '-original', [name], { original: true });
    for (const name of Object.keys(globals)) {
      await scenario(name, [name]);
      await scenario(name + '-fullscreen', [name]);
    }
    await scenario('cdn-order', ['vega']);
    await scenario('mixed', Object.keys(globals));
    await scenario('plain', []);
    await scenario('vega', ['vega'], { missing: 'vega-lite.min.js' });
    fs.writeFileSync(path.join(directory, 'browser-results.json'), JSON.stringify(results, null, 2));
    console.log(`${results.length} browser scenarios passed. Android WebView/GPU and lifecycle are not tested here.`);
  } finally { await browser.close(); }
}
main().catch(error => { console.error(error); process.exitCode = 1; });
