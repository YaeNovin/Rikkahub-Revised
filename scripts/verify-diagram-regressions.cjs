// Generated pages come from DiagramSourceTest. Requires Playwright and Edge.
const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');
const { chromium } = require('playwright');
const root = path.resolve(__dirname, '..');
const reports = path.join(root, 'app/build/reports/diagram-regressions');
const assets = path.join(root, 'app/src/main/assets');
async function main() {
  const browser = await chromium.launch({ channel: 'msedge', headless: true });
  let passed = 0;
  async function scenario(name, run, { full = false, missingLibrary = false, missingMap = false } = {}) {
    const page = await browser.newPage({ viewport: { width: 360, height: full ? 740 : 300 } });
    const errors = [];
    const remote = [];
    page.on('pageerror', error => errors.push(error.message));
    await page.route('**/*', async route => {
      const url = new URL(route.request().url());
      if (url.host !== 'rikkahub.local') { remote.push(url.href); return route.abort(); }
      if (url.pathname === '/') {
        await route.fulfill({ contentType: 'text/html; charset=utf-8', body: fs.readFileSync(path.join(reports, name + '.html'), 'utf8') });
      } else if (url.pathname.startsWith('/assets/') && !url.pathname.includes('..') && !missingLibrary) {
        const file = path.join(assets, url.pathname.slice('/assets/'.length));
        if (missingMap && file.endsWith('.json')) return route.fulfill({ status:404, body:'Missing map' });
        if (fs.existsSync(file)) return route.fulfill({ path: file, contentType: file.endsWith('.js') ? 'text/javascript' : file.endsWith('.json') ? 'application/json' : 'text/css' });
        await route.fulfill({ status: 404, body: 'Missing asset' });
      } else await route.fulfill({ status: 404, body: 'Offline regression' });
    });
    try {
      await page.goto('https://rikkahub.local/');
      await run(page);
      assert.deepEqual(errors, [], name + ' has an uncaught script error');
      assert.deepEqual(remote, [], name + ' depends on external resources');
      passed++;
      console.log('PASS ' + name + (missingLibrary ? ' (missing library)' : ''));
    } finally { await page.close(); }
  }
  try {
    for (const name of ['sequence', 'mindmap', 'flowchart', 'sequence-full', 'mindmap-full', 'flowchart-full']) {
      await scenario(name, async page => {
        await page.locator('.mermaid').waitFor({ state: 'visible' });
        const bounds = await page.locator('.mermaid svg').boundingBox();
        assert.ok(bounds && bounds.width > 50 && bounds.height > 20, 'Diagram has visible dimensions');
        const info = await page.locator('.mermaid').evaluate(element => ({
          visibility: getComputedStyle(element).visibility,
          background: getComputedStyle(document.getElementById('diagram-container')).backgroundColor,
          labels: [...element.querySelectorAll('text')].map(node => ({ text: node.textContent.trim(), fill: getComputedStyle(node).fill })).filter(x => x.text),
        }));
        assert.equal(info.visibility, 'visible');
        assert.notEqual(info.background, 'rgba(0, 0, 0, 0)');
        assert.ok(info.labels.length >= 3, 'Expected readable labels');
        assert.ok(info.labels.every(label => label.fill !== 'rgb(0, 0, 0)'), JSON.stringify(info.labels));
        await page.screenshot({ path: path.join(reports, `${name}.png`) });
      }, { full: name.endsWith('-full') });
    }
    await scenario('invalid', async page => {
      await page.waitForFunction(() => !document.querySelector('.mermaid') && document.querySelector('#diagram-container').innerText.includes('Unable'));
    });
    await scenario('sequence', async page => {
      await page.waitForFunction(() => !document.querySelector('.mermaid') && document.querySelector('#diagram-container').innerText.includes('Unable'));
    }, { missingLibrary: true });
    for (const name of ['railroad', 'ebnf', 'railroad-stack']) {
      await scenario(name, async page => {
        await page.locator('#renderer svg').waitFor({ state: 'visible' });
        assert.equal(await page.locator('#render-error').isVisible(), false);
        assert.ok(await page.locator('#renderer svg text').count() > 3);
        if (name === 'railroad-stack') {
          const positions = await page.locator('#renderer svg text').evaluateAll(nodes => Object.fromEntries(nodes.map(n => [n.textContent, n.getBoundingClientRect().y])));
          assert.ok(positions.FIRST < positions.SECOND && positions.SECOND < positions.LAST, 'Stack must connect sequential rows, not flatten to a Sequence');
        }
        await page.screenshot({ path: path.join(reports, name + '.png') });
      });
    }
    await scenario('chart-missing', async page => {
      await page.locator('#render-error').waitFor({ state: 'visible' });
      assert.ok((await page.locator('#render-error').innerText()).includes('缺少地图数据：not-provided'));
    });
    await scenario('chart', async page => {
      await page.locator('#renderer canvas').waitFor({ state: 'visible' });
      assert.equal(await page.locator('#render-error').isVisible(), false);
    });
    for (const name of ['china','world','world-lines']) {
      for (const full of [false, true]) await scenario('map-' + name + (full ? '-full' : ''), async page => {
        await page.waitForFunction(() => window.__rikkaRenderStatus === 'ready');
        const result = await page.evaluate(() => {
          const chart = echarts.getInstanceByDom(document.getElementById('renderer'));
          const map = echarts.getMap(chart.getOption().geo?.[0]?.map || chart.getOption().series[0].map);
          return { features: map.geoJSON.features.length, paths: chart.getZr().storage.getDisplayList().filter(x => x.type === 'path' || x.type === 'compound').length };
        });
        assert.ok(result.features >= 30, 'real regional boundaries loaded');
        assert.ok(result.paths >= 30, 'map boundaries actually drawn');
        assert.equal(await page.locator('#render-error').isVisible(), false);
        await page.screenshot({path:path.join(reports,'map-' + name + (full ? '-full' : '') + '.png')});
      }, {full});
    }
    await scenario('map-world', async page => {
      await page.locator('#render-error').waitFor({state:'visible'});
      assert.ok((await page.locator('#render-error').innerText()).includes('内置地图资源加载失败'));
    }, {missingMap:true});
    console.log(`${passed} diagram browser scenarios passed; Android lifecycle and GPU are not covered.`);
  } finally { await browser.close(); }
}
main().catch(error => { console.error(error); process.exitCode = 1; });
