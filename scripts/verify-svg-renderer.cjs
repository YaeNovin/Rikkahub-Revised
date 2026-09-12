const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');
const { chromium } = require('playwright');
const directory = path.resolve(__dirname, '../app/build/reports/svg-regressions');
async function main() {
  const browser = await chromium.launch({ channel: 'msedge', headless: true });
  let count = 0;
  try {
    for (const name of ['advanced', 'dimensions', 'bounds', 'animated']) {
      const page = await browser.newPage({ viewport: { width: 360, height: 300 } });
      const errors = [];
      page.on('pageerror', error => errors.push(error.message));
      try {
        await page.setContent(fs.readFileSync(path.join(directory, name + '.html'), 'utf8'));
        await page.waitForFunction(() => window.__rikkaRenderStatus === 'ready');
        const svg = page.locator('body > svg');
        const bounds = await svg.boundingBox();
        assert.ok(bounds && bounds.width > 0 && bounds.height > 0 && bounds.width <= 360);
        if (name === 'advanced') {
          assert.equal(await svg.getAttribute('viewBox'), '-20 -10 440 260');
          const nested = svg.locator('svg');
          assert.equal(await nested.getAttribute('viewBox'), '0 0 10 10');
          assert.equal(await nested.getAttribute('width'), '40');
          assert.equal(await nested.getAttribute('height'), '40');
          assert.equal(await svg.locator('linearGradient').count(), 1);
          assert.equal(await svg.locator('feDropShadow').count(), 1);
          const pixels = await svg.evaluate(async element => {
            const xml = new XMLSerializer().serializeToString(element);
            const image = new Image();
            image.src = 'data:image/svg+xml;base64,' + btoa(unescape(encodeURIComponent(xml)));
            await image.decode();
            const canvas = document.createElement('canvas');
            canvas.width = 440; canvas.height = 260;
            const context = canvas.getContext('2d');
            context.drawImage(image, 0, 0, 440, 260);
            return [100, 320].map(x => Array.from(context.getImageData(x, 140, 1, 1).data));
          });
          assert.ok(pixels.every(pixel => pixel[3] > 0));
          assert.notDeepEqual(pixels[0], pixels[1], 'Gradient must retain distinct colors');
        } else if (name === 'dimensions') {
          assert.equal(await svg.getAttribute('viewBox'), '0 0 400 200');
        } else if (name === 'bounds') {
          assert.equal(await svg.getAttribute('viewBox'), '8 8 34 34');
        } else {
          assert.equal(await svg.locator('animateMotion').count(), 1);
          await page.waitForTimeout(150);
        }
        await page.screenshot({ path: path.join(directory, name + '.png') });
        assert.deepEqual(errors, []);
        count++;
        console.log('PASS SVG ' + name);
      } finally { await page.close(); }
    }
    console.log(`${count} SVG browser scenarios passed; Android bitmap export is not covered.`);
  } finally { await browser.close(); }
}
main().catch(error => { console.error(error); process.exitCode = 1; });
