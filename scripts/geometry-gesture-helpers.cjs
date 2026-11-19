// Real Chromium touch input, rather than invoking renderer camera functions.
async function pinch(page, factor = 1.2, anchor = [.5, .5]) {
  const bounds = await page.locator('#stage').boundingBox();
  const x = bounds.x + bounds.width * anchor[0], y = bounds.y + bounds.height * anchor[1];
  const radius = Math.min(50, bounds.width * .12, bounds.height * .2);
  const cdp = await page.context().newCDPSession(page);
  const points = scale => [{ x: x - radius * scale, y, id: 1 }, { x: x + radius * scale, y, id: 2 }];
  try {
    await cdp.send('Input.dispatchTouchEvent', { type: 'touchStart', touchPoints: points(1) });
    for (let i = 1; i <= 6; i++) {
      await cdp.send('Input.dispatchTouchEvent', { type: 'touchMove', touchPoints: points(1 + (factor - 1) * i / 6) });
      await page.waitForTimeout(18);
    }
    await cdp.send('Input.dispatchTouchEvent', { type: 'touchEnd', touchPoints: [] });
    await page.waitForTimeout(120);
  } finally { await cdp.detach(); }
}
async function drag(page, dx = 40, dy = 30, cancel = false) {
  const bounds = await page.locator('#stage').boundingBox(), x = bounds.x + bounds.width / 2, y = bounds.y + bounds.height / 2;
  const cdp = await page.context().newCDPSession(page);
  try {
    await cdp.send('Input.dispatchTouchEvent', { type: 'touchStart', touchPoints: [{ x, y, id: 1 }] });
    for (let i = 1; i <= 4; i++) await cdp.send('Input.dispatchTouchEvent', { type: 'touchMove', touchPoints: [{ x: x + dx * i / 4, y: y + dy * i / 4, id: 1 }] });
    await cdp.send('Input.dispatchTouchEvent', { type: cancel ? 'touchCancel' : 'touchEnd', touchPoints: [] });
    await page.waitForTimeout(120);
  } finally { await cdp.detach(); }
}
module.exports = { pinch, drag };
