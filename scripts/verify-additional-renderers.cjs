// Uses generated app HTML and vendored libraries. No remote assets are allowed.
const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');
const { chromium } = require('playwright');
const root = path.resolve(__dirname, '..');
const directory = path.join(root, 'app/build/reports/webview-renderers');
async function main() {
  const executablePath = process.env.RENDERER_TEST_BROWSER;
  const browser = await chromium.launch({ headless: true, ...(executablePath ? { executablePath } : { channel: 'msedge' }) });
  let count = 0;
  async function scenario(name, source, check, options = {}) {
    const page = await browser.newPage({ viewport: { width: 360, height: 560 }, isMobile: true, hasTouch: true });
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
    await scenario('JIANPU', undefined, async page => {
      const stats = () => page.evaluate(() => {
        const svg = document.querySelector('#renderer svg');
        const note = [...svg.querySelectorAll('[data-score-system] text')].find(n => n.textContent === '1');
        const scale = note.getScreenCTM().a;
        return { font: parseFloat(note.getAttribute('font-size')) * scale, width: svg.getBoundingClientRect().width,
          rows: svg.querySelectorAll('[data-score-system]').length, sharps: [...svg.querySelectorAll('text')].some(n => n.textContent === '♯'),
          dots: svg.querySelectorAll('circle').length, lyrics: svg.textContent.includes('排版和长谱换行') };
      });
      const portrait = await stats();
      assert.ok(portrait.rows > 4 && portrait.font >= 22 && portrait.width <= 360, JSON.stringify(portrait));
      assert.ok(portrait.sharps && portrait.dots > 0 && portrait.lyrics);
      await page.setViewportSize({ width: 740, height: 460 });
      await page.waitForFunction(() => document.querySelector('#renderer svg').getBoundingClientRect().width > 650);
      const landscape = await stats();
      assert.ok(landscape.rows < portrait.rows);
      assert.equal(landscape.font, portrait.font);
    });
    await scenario('JIANPU', 'There are 7 words, not notes', null, { error: true });
    await scenario('JIANPU', '1 '.repeat(1601), null, { error: true });
    await scenario('scores/JIANPU-fullscreen', undefined, async page => {
      assert.equal(await page.locator('body').getAttribute('data-score-fullscreen'), '');
      assert.equal(await page.locator('#rikkahub-fullscreen-pan-script').count(), 0);
      await page.evaluate(() => window.scrollTo(0, document.documentElement.scrollHeight));
      await page.waitForFunction(() => window.scrollY > 400);
      const finalLyric = await page.getByText('测试歌词 14', { exact: true }).boundingBox();
      assert.ok(finalLyric && finalLyric.y >= 0 && finalLyric.y + finalLyric.height <= 560);
    });
    await scenario('ABC', undefined, async page => {
      const before = await page.locator('#renderer svg').first().boundingBox();
      assert.ok(await page.locator('.abcjs-note').count() >= 80);
      await page.setViewportSize({ width: 740, height: 460 });
      await page.waitForFunction(width => document.querySelector('#renderer svg').getBoundingClientRect().width > width + 100, before.width);
      const after = await page.locator('#renderer svg').first().boundingBox();
      assert.ok(after.height < before.height);
    });
    await scenario('ABC', 'X:1\nK:C\n', null, { error: true });
    await scenario('WAVEFORM');
    await scenario('WAVEFORM', JSON.stringify({ reg: [{ bits: 8, name: 'DATA' }, { bits: 1, name: 'EN' }], config: { bits: 9 } }));
    await scenario('GRAPHVIZ');
    await scenario('VEGA');
    await scenario('MOLECULE', undefined, async page => {
      assert.equal(await page.evaluate(() => SmilesDrawer.Version), '2.4.1');
    });
    await scenario('MUSICXML');
    await scenario('MUSICXML', '<score-partwise><part-list/></score-partwise>', null, { error: true });
    for (const name of ['WAVEFORM','GRAPHVIZ','VEGA','MOLECULE','MUSICXML']) await scenario(name, 'invalid input (', null, { error: true });
    await scenario('VEGA', JSON.stringify({ mark:'bar', data:{url:'https://example.com/private'}, encoding:{} }), null, { error:true });
    await scenario('WAVEFORM', undefined, null, { error:true, missing:'wavedrom-skin.js' });
    console.log(count + ' additional renderer browser scenarios passed. Android GPU and export require device testing.');
  } finally { await browser.close(); }
}
main().catch(error => { console.error(error); process.exitCode = 1; });
