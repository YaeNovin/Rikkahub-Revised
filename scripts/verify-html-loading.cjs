const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { chromium } = require('playwright');
const root = path.resolve(__dirname, '..');
const file = path.join(root, 'app/build/reports/html-loading/preview.html');
const html = fs.readFileSync(file, 'utf8');

async function main() {
  const browser = await chromium.launch({ channel: 'msedge', headless: true });
  let passed = 0;
  try {
    for (const test of [
      { name: 'offline local dependencies' },
      { name: 'old WebView array APIs', legacy: true },
      { name: 'missing Markdown library', missing: 'markdown-it.min.js', error: true },
      { name: 'missing math plugin', missing: 'markdown-it-katex.js', error: true },
      { name: 'invalid Mermaid preserves rest of message', invalidDiagram: true },
    ]) {
      const page = await browser.newPage({ viewport: { width: 393, height: 780 } });
      const failures = [], external = [];
      page.on('pageerror', error => failures.push(error.message));
      await page.route('**/*', async route => {
        const url = new URL(route.request().url());
        if (url.host !== 'rikkahub.local') { external.push(url.href); return route.abort(); }
        if (url.pathname === '/') {
          let source = html;
          if (test.invalidDiagram) source = source.replace(/const markdownBase64 = `[^`]+`;/,
            'const markdownBase64 = `' + Buffer.from('# Still readable\n\n```mermaid\ninvalid graph\n```\n\nAfter diagram').toString('base64') + '`;');
          return route.fulfill({ contentType: 'text/html', body: source });
        }
        const asset = path.resolve(root, 'app/src/main', '.' + url.pathname);
        if (!asset.startsWith(path.join(root, 'app/src/main/assets') + path.sep)) return route.abort();
        if ((test.missing && asset.endsWith(test.missing)) || !fs.existsSync(asset)) return route.fulfill({ status:404, body:'Missing' });
        return route.fulfill({ path:asset, contentType: asset.endsWith('.js') ? 'text/javascript' : asset.endsWith('.css') ? 'text/css' : 'font/woff2' });
      });
      try {
        if (test.legacy) await page.addInitScript(() => {
          Array.prototype.at = undefined;
          Object.defineProperty(window, 'TextDecoder', { configurable: true, value: undefined });
          Object.defineProperty(window, 'matchMedia', { configurable: true, value: undefined });
        });
        await page.goto('https://rikkahub.local/');
        await page.waitForFunction(() => window.__rikkaRenderStatus === 'ready' || window.__rikkaRenderStatus === 'error');
        assert.equal(await page.evaluate(() => window.__rikkaRenderStatus), test.error ? 'error' : 'ready');
        if (test.error) {
          assert.ok(await page.locator('#preview-fallback').isVisible());
          assert.ok((await page.locator('#preview-status').innerText()).includes('保留可读内容'));
        } else if (test.invalidDiagram) {
          assert.ok((await page.locator('#content').innerText()).includes('After diagram'));
        } else {
          assert.equal(await page.locator('h1').innerText(), 'Offline 中文预览');
          assert.equal(await page.locator('input[type=checkbox]').count(), 2);
          assert.ok(await page.locator('.katex').count() >= 2);
          assert.equal(await page.locator('.katex-error').count(), 0);
          assert.ok(await page.locator('code .hljs-keyword').count() > 0);
          assert.ok(await page.locator('.mermaid svg').isVisible());
          assert.ok((await page.locator('#content').innerText()).includes('HTML preserved'));
        }
        assert.deepEqual(external, []);
        assert.deepEqual(failures, []);
        passed++;
        console.log('PASS ' + test.name);
      } finally { await page.close(); }
    }
    console.log(`${passed} HTML loading browser regressions passed; native Android timeout is not exercised here.`);
  } finally { await browser.close(); }
}
main().catch(error => { console.error(error); process.exitCode = 1; });
