// Run HtmlRenderPolicyTest first. Uses production HTML and packaged assets only.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { chromium } = require('playwright');
const root = path.resolve(__dirname, '..');
const reports = path.join(root, 'app/build/reports/html-policy');
const assets = path.join(root, 'app/src/main/assets');

async function main() {
  const browser = await chromium.launch({ channel: 'msedge', headless: true });
  const results = [];
  try {
    for (const scenario of ['html', 'svg', 'fullscreen', 'markdown', 'map', 'chart']) {
      const page = await browser.newPage({ viewport: { width: 800, height: 600 } });
      const requests = [], dialogs = [], errors = [];
      page.on('dialog', async dialog => { dialogs.push(dialog.message()); await dialog.dismiss(); });
      page.on('pageerror', error => errors.push(error.message));
      await page.route('**/*', async route => {
        const url = new URL(route.request().url());
        if (url.origin !== 'https://rikkahub.local') { requests.push(url.href); return route.abort(); }
        if (url.pathname === '/') return route.fulfill({ contentType: 'text/html; charset=utf-8', path: path.join(reports, scenario + '.html') });
        const file = path.resolve(root, 'app/src/main', '.' + url.pathname);
        if (!file.startsWith(assets + path.sep) || !fs.existsSync(file)) return route.abort();
        const type = file.endsWith('.js') ? 'text/javascript' : file.endsWith('.css') ? 'text/css' : file.endsWith('.json') ? 'application/json' : 'font/woff2';
        return route.fulfill({ path: file, contentType: type });
      });
      try {
        await page.goto('https://rikkahub.local/');
        await page.waitForFunction(() => window.__rikkaRenderStatus === 'ready');
        if (['html', 'svg', 'fullscreen'].includes(scenario)) {
          assert.equal(await page.locator('iframe,object,form,input,button,foreignObject,[onload],[onerror],script:not([src])').count(), 0);
          assert.ok(await page.locator('svg rect').isVisible());
          if (scenario !== 'svg') assert.equal(await page.locator('h2').innerText(), 'Static content');
          // Even reintroduced handlers/inline scripts do not get script privileges.
          assert.equal(await page.evaluate(async () => {
            const script = document.createElement('script'); script.textContent = 'window.__attack=true'; document.body.appendChild(script);
            const probe = document.createElement('img'); probe.setAttribute('onerror', 'window.__attack=true'); document.body.appendChild(probe);
            probe.dispatchEvent(new Event('error')); probe.remove();
            try { await fetch('https://evil.test/csp-probe'); return false; } catch (_) { return true; }
          }), true);
        } else if (scenario === 'markdown') {
          assert.equal(await page.locator('h1').innerText(), 'Safe Markdown');
          assert.equal(await page.locator('iframe,[onload],[onerror]').count(), 0);
          assert.ok((await page.locator('#content').innerText()).includes('<body onload='));
          assert.ok(await page.locator('.mermaid svg').isVisible());
          assert.ok(await page.locator('.katex').count() > 0);
        } else if (scenario === 'map') {
          await page.locator('.leaflet-interactive').last().click({ force: true });
          assert.ok((await page.locator('.leaflet-popup-content').innerText()).includes('<img'));
          assert.equal(await page.locator('.leaflet-popup-content img').count(), 0);
        } else if (scenario === 'chart') {
          await page.evaluate(() => echarts.getInstanceByDom(document.getElementById('renderer')).dispatchAction({ type: 'showTip', seriesIndex: 0, dataIndex: 0 }));
          assert.equal(await page.evaluate(() => echarts.getInstanceByDom(document.getElementById('renderer')).getOption().tooltip[0].renderMode), 'richText');
          assert.equal(await page.locator('img[onerror]').count(), 0);
        }
        await page.screenshot({ path: path.join(reports, scenario + '.png') });
        assert.equal(await page.evaluate(() => window.__attack), undefined);
        assert.deepEqual(dialogs, []);
        assert.deepEqual(requests, []);
        assert.deepEqual(errors, []);
        results.push({ scenario, status: 'passed' });
      } catch (error) {
        console.error({ scenario, requests, dialogs, errors });
        await page.screenshot({ path: path.join(reports, scenario + '-failure.png') });
        throw error;
      } finally { await page.close(); }
    }
    fs.writeFileSync(path.join(reports, 'browser-results.json'), JSON.stringify(results, null, 2));
    console.log(`${results.length} HTML policy browser scenarios passed; Android device execution is not covered.`);
  } finally { await browser.close(); }
}
main().catch(error => { console.error(error); process.exitCode = 1; });
