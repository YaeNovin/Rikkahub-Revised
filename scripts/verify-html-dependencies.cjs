// Former arbitrary HTML apps are now static; use typed renderers for executable library code.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { chromium } = require('playwright');
const root = path.resolve(__dirname, '..');
const directory = path.join(root, 'app/build/reports/html-dependencies');
const assetRoot = path.join(root, 'app/src/main/assets');

async function main() {
  const browser = await chromium.launch({ channel: 'msedge', headless: true });
  const results = [];
  try {
    for (const name of ['vega', 'vega-fullscreen', 'smiles', 'smiles-fullscreen', 'musicxml', 'musicxml-fullscreen', 'cdn-order', 'mixed', 'plain']) {
      const page = await browser.newPage({ viewport: { width: 393, height: 780 } });
      const errors = [], external = [], scripts = [];
      page.on('pageerror', error => errors.push(error.message));
      await page.route('**/*', async route => {
        const url = new URL(route.request().url());
        if (url.origin !== 'https://rikkahub.local') { external.push(url.href); return route.abort(); }
        if (url.pathname === '/') return route.fulfill({ contentType: 'text/html; charset=utf-8', path: path.join(directory, name + '.html') });
        const target = path.resolve(root, 'app/src/main', '.' + url.pathname);
        if (!target.startsWith(assetRoot + path.sep)) return route.abort();
        scripts.push(path.basename(target));
        return route.fulfill({ path: target, contentType: 'text/javascript; charset=utf-8' });
      });
      try {
        await page.goto('https://rikkahub.local/');
        await page.waitForFunction(() => window.__rikkaRenderStatus === 'ready');
        assert.deepEqual(await page.evaluate(() => ['vegaEmbed', 'SmilesDrawer', 'opensheetmusicdisplay'].map(key => typeof window[key])), ['undefined', 'undefined', 'undefined']);
        assert.equal(await page.locator('script:not([src]),script[data-rikkahub-renderer-library]').count(), 0);
        assert.ok(scripts.every(name => ['static-preview.js', 'fullscreen-pan.js', 'fullscreen-background.js'].includes(name)));
        assert.deepEqual(errors, []);
        assert.deepEqual(external, []);
        results.push({ name, status: 'passed', scripts });
      } finally { await page.close(); }
    }
    fs.writeFileSync(path.join(directory, 'browser-results.json'), JSON.stringify(results, null, 2));
    console.log(results.length + ' HTML static dependency-policy scenarios passed; Android is not covered.');
  } finally { await browser.close(); }
}
main().catch(error => { console.error(error); process.exitCode = 1; });
