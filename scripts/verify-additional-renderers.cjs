// Uses generated app HTML and vendored libraries. No remote assets are allowed.
const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');
const { chromium } = require('playwright');
const root = path.resolve(__dirname, '..');
const directory = path.join(root, 'app/build/reports/webview-renderers');
async function main() {
  const browser = await chromium.launch({ channel: 'msedge', headless: true });
  let count = 0;
  async function scenario(name, source, check, options = {}) {
    const page = await browser.newPage({ viewport: { width: 360, height: 560 } });
    const errors = [], external = [], consoleErrors = [];
    page.on('pageerror', error => errors.push(error.message));
    page.on('console', event => { if (event.type() === 'error') consoleErrors.push(event.text()); });
    await page.route('**/*', async route => {
      const url = new URL(route.request().url());
      if (url.host !== 'rikkahub.local') { external.push(url.href); return route.abort(); }
      if (url.pathname === '/') {
        let html = fs.readFileSync(path.join(directory, name + '.html'), 'utf8');
        if (source !== undefined) html = html.replace(/const source = [^\n]+?;(?=\r?\n)/,
          () => 'const source = ' + JSON.stringify(source).replaceAll('<', '\\u003c') + ';');
        return route.fulfill({ contentType: 'text/html', body: html });
      }
      const target = path.resolve(root, 'app/src/main', '.' + url.pathname);
      if (!target.startsWith(path.join(root, 'app/src/main/assets') + path.sep)) return route.abort();
      if (options.missing && target.endsWith(options.missing)) return route.fulfill({ status: 404, body: 'missing' });
      if (!fs.existsSync(target)) return route.fulfill({ status: 404, body: 'missing asset' });
      await route.fulfill({ path: target, contentType: target.endsWith('.js') ? 'text/javascript' : 'text/css' });
    });
    try {
      await page.goto('https://rikkahub.local/');
      await page.waitForFunction(() => ['ready','error'].includes(window.__rikkaRenderStatus), { timeout: 25000 });
      const status = await page.evaluate(() => window.__rikkaRenderStatus);
      if (options.error) {
        assert.equal(status, 'error', name + ' should reject input');
        assert.ok(await page.locator('#render-error').isVisible());
      } else {
        assert.equal(status, 'ready', name + ': ' + consoleErrors.join('\n'));
        const drawn = await page.locator('#renderer svg').first().boundingBox();
        assert.ok(drawn && drawn.width > 0 && drawn.height > 0, name + ' missing visible SVG');
        if (check) await check(page);
        await page.screenshot({ path: path.join(directory, name + '-browser.png') });
        await page.setViewportSize({ width: 412, height: 800 });
        await page.waitForTimeout(250);
      }
      assert.deepEqual(errors, [], name + ' uncaught JS errors');
      assert.deepEqual(external, [], name + ' unexpected network dependency');
      count++;
      console.log('PASS ' + name + (options.error ? ' error handling' : ' rendering'));
    } finally { await page.close(); }
  }
  try {
    await scenario('WAVEFORM');
    await scenario('WAVEFORM', JSON.stringify({ reg: [{ bits: 8, name: 'DATA' }, { bits: 1, name: 'EN' }], config: { bits: 9 } }));
    await scenario('GRAPHVIZ');
    await scenario('VEGA');
    await scenario('MOLECULE', undefined, async page => {
      assert.equal(await page.evaluate(() => SmilesDrawer.Version), '2.4.1');
    });
    await scenario('MUSICXML');
    for (const name of ['WAVEFORM','GRAPHVIZ','VEGA','MOLECULE','MUSICXML']) await scenario(name, 'invalid input (', null, { error: true });
    await scenario('VEGA', JSON.stringify({ mark:'bar', data:{url:'https://example.com/private'}, encoding:{} }), null, { error:true });
    await scenario('WAVEFORM', undefined, null, { error:true, missing:'wavedrom-skin.js' });
    console.log(count + ' additional renderer browser scenarios passed. Android GPU and export require device testing.');
  } finally { await browser.close(); }
}
main().catch(error => { console.error(error); process.exitCode = 1; });
