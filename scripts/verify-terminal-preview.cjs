// Run TerminalPreviewTest first: exercise the production HTML and pinned assets, without a shell.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { chromium } = require('playwright');
const root = path.resolve(__dirname, '..');
const reports = path.join(root, 'app/build/reports/terminal-preview');
const assets = path.join(root, 'app/src/main/assets');

async function touchDrag(page, dx, dy) {
  const box = await page.locator('#terminal-scroll').boundingBox();
  const x = box.x + box.width * .75, y = box.y + box.height * .75;
  const cdp = await page.context().newCDPSession(page);
  try {
    await cdp.send('Input.dispatchTouchEvent', { type: 'touchStart', touchPoints: [{ x, y, id: 1 }] });
    for (let i = 1; i <= 8; i++) {
      await cdp.send('Input.dispatchTouchEvent', { type: 'touchMove', touchPoints: [{ x: x + dx * i / 8, y: y + dy * i / 8, id: 1 }] });
      await page.waitForTimeout(16);
    }
    await cdp.send('Input.dispatchTouchEvent', { type: 'touchEnd', touchPoints: [] });
    await page.waitForTimeout(100);
  } finally { await cdp.detach(); }
}

function contrast(a, b) {
  const luminance = hex => {
    const rgb = hex.slice(1).match(/../g).map(value => parseInt(value, 16) / 255)
      .map(value => value <= .04045 ? value / 12.92 : ((value + .055) / 1.055) ** 2.4);
    return rgb[0] * .2126 + rgb[1] * .7152 + rgb[2] * .0722;
  };
  const values = [luminance(a), luminance(b)].sort((x, y) => y - x);
  return (values[0] + .05) / (values[1] + .05);
}

async function main() {
  const browser = await chromium.launch({ channel: 'msedge', headless: true });
  const results = [];
  try {
    for (const scenario of ['dark', 'light', 'fullscreen', 'empty', 'long', 'missing']) {
      const page = await browser.newPage({ viewport: { width: 393, height: 300 }, hasTouch: true, isMobile: true });
      const requests = [], errors = [], data = [];
      page.on('pageerror', error => errors.push(error.message));
      await page.exposeFunction('__recordTerminalData', value => data.push(value));
      await page.addInitScript(() => {
        // Observe the actual production instance, without exposing an application API.
        let implementation;
        Object.defineProperty(window, 'Terminal', {
          configurable: true,
          get: () => implementation,
          set: value => {
            implementation = new Proxy(value, { construct(target, args) {
              const instance = Reflect.construct(target, args);
              window.__testTerminal = instance;
              instance.onData(data => window.__recordTerminalData(data));
              return instance;
            } });
          },
        });
      });
      await page.route('**/*', async route => {
        const url = new URL(route.request().url());
        if (url.origin !== 'https://rikkahub.local') { requests.push(url.href); return route.abort(); }
        if (url.pathname === '/') return route.fulfill({ contentType: 'text/html; charset=utf-8',
          body: fs.readFileSync(path.join(reports, (scenario === 'missing' ? 'dark' : scenario) + '.html'), 'utf8') });
        const file = path.resolve(root, 'app/src/main', '.' + url.pathname);
        if (!file.startsWith(assets + path.sep)) return route.abort();
        if (scenario === 'missing' && file.endsWith('xterm-6.0.0.js')) return route.fulfill({ status: 404, body: 'missing' });
        return route.fulfill({ path: file, contentType: file.endsWith('.css') ? 'text/css' : 'text/javascript' });
      });
      try {
        await page.goto('https://rikkahub.local/');
        await page.waitForFunction(() => ['ready', 'error'].includes(window.__rikkaRenderStatus));
        if (scenario === 'missing') {
          assert.equal(await page.evaluate(() => window.__rikkaRenderStatus), 'error');
          assert.equal(await page.locator('#fallback').isVisible(), true);
          assert.ok((await page.locator('#fallback').textContent()).includes('SUCCESS'));
        } else {
          assert.equal(await page.evaluate(() => window.__rikkaRenderStatus), 'ready');
          assert.equal(await page.locator('.xterm-helper-textarea').isDisabled(), true);
          const original = await page.evaluate(() => ({
            cols: window.__testTerminal.cols, rows: window.__testTerminal.rows,
            stdin: window.__testTerminal.options.disableStdin,
            lines: Array.from({ length: window.__testTerminal.buffer.active.length }, (_, i) =>
              window.__testTerminal.buffer.active.getLine(i).translateToString(true)),
            fg: window.__testTerminal.buffer.active.getLine(0).getCell(0).getFgColor(),
            viewport: window.__testTerminal.buffer.active.viewportY,
          }));
          assert.equal(original.stdin, true);
          assert.ok(original.cols > 10 && original.cols <= 500);
          assert.ok(original.rows <= 80);
          assert.equal(original.viewport, 0);
          if (['dark', 'light', 'fullscreen'].includes(scenario)) {
            assert.ok(original.lines.join('\n').includes('中文'));
            assert.ok(original.lines.join('\n').includes('progress 100%'));
            assert.ok(!original.lines.join('\n').includes('progress 10%'));
            assert.equal(original.fg, 2); // SGR green palette index
            assert.equal(await page.locator('a').count(), 0);
            const style = await page.evaluate(() => {
              const t = window.__testTerminal, line = t.buffer.active.getLine(2);
              return { bold: line.getCell(0).isBold(), orange: line.getCell(5).getFgColor(),
                rgb: line.getCell(12).getFgColor(), theme: t.options.theme, contrast: t.options.minimumContrastRatio };
            });
            assert.ok(style.bold);
            assert.equal(style.orange, 208);
            assert.equal(style.rgb, 0x79c0ff);
            assert.equal(style.contrast, 4.5);
            for (const name of ['black', 'red', 'green', 'yellow', 'blue', 'magenta', 'cyan', 'white',
              'brightBlack', 'brightRed', 'brightGreen', 'brightYellow', 'brightBlue', 'brightMagenta', 'brightCyan', 'brightWhite']) {
              assert.ok(contrast(style.theme[name], style.theme.background) >= 4.5, `${scenario} ${name} contrast`);
            }
            await touchDrag(page, 0, -120);
            const y = await page.evaluate(() => window.__testTerminal.buffer.active.viewportY);
            assert.ok(scenario === 'fullscreen' ? y > 0 : y === 0, 'embedded vertical belongs to parent; fullscreen scrolls output');
            await page.evaluate(() => window.__testTerminal.scrollToTop());
          }
          const horizontalRange = await page.locator('#terminal-scroll').evaluate(el => el.scrollWidth - el.clientWidth);
          if (horizontalRange > 20) {
            await touchDrag(page, -120, 0);
            const offset = await page.locator('#terminal-scroll').evaluate(el => el.scrollLeft);
            assert.ok(offset > 20, `horizontal phone pan: range=${horizontalRange}, offset=${offset}`);
          }
          if (scenario === 'long') assert.ok(horizontalRange > 200, 'wide output keeps column layout on phones');
          assert.equal(await page.evaluate(() => window.__injected), undefined);
          await page.setViewportSize({ width: 800, height: 550 });
          await page.waitForFunction(cols => window.__testTerminal.cols >= cols &&
            document.querySelector('#terminal').clientWidth >= 780, original.cols);
          await page.setViewportSize({ width: 393, height: 300 });
          await page.waitForFunction(() => document.querySelector('#terminal-scroll').clientWidth === 393);
          assert.ok(await page.locator('.xterm-screen').evaluate(el => el.getBoundingClientRect().width > 200));
          await page.keyboard.type('must not enter shell');
          assert.equal(data.length, 0);
          await page.screenshot({ path: path.join(reports, scenario + '.png') });
          const blocked = await page.evaluate(async () => {
            try { await fetch('https://example.invalid/forbidden'); return false; } catch (_) { return true; }
          });
          assert.equal(blocked, true);
          await page.evaluate(() => window.dispatchEvent(new PageTransitionEvent('pagehide')));
          assert.equal(await page.locator('.xterm').count(), 0);
        }
        assert.deepEqual(errors, []);
        assert.deepEqual(requests, []);
        results.push({ scenario, status: 'passed' });
      } catch (error) {
        await page.screenshot({ path: path.join(reports, scenario + '-failure.png') });
        console.error({ scenario, errors, requests });
        throw error;
      } finally { await page.close(); }
    }
    fs.writeFileSync(path.join(reports, 'browser-results.json'), JSON.stringify(results, null, 2));
    console.log(`${results.length} read-only terminal browser scenarios passed; Android WebView/device behavior is not covered.`);
  } finally { await browser.close(); }
}
main().catch(error => { console.error(error); process.exitCode = 1; });
