// Validate the isolated RichTextRegressionActivity WebView with real adb swipes.
const assert = require('node:assert/strict');
const fs = require('node:fs'), path = require('node:path');
const { execFileSync } = require('node:child_process');
const port = Number(process.argv[2]), adbPath = process.argv[3], serial = process.argv[4];
assert.ok(Number.isInteger(port) && port > 1024 && port < 65536 && adbPath && serial,
  'Usage: node verify-terminal-device.cjs FORWARDED_PORT ADB SERIAL');
const reports = path.resolve(__dirname, '../build/qa-parameters/terminal-device');
const adb = (...args) => execFileSync(adbPath, ['-s', serial, ...args.map(String)], { timeout: 20000, maxBuffer: 32 * 1024 * 1024 });
const delay = ms => new Promise(resolve => setTimeout(resolve, ms));
function ui(name) {
  const raw = adb('exec-out', 'uiautomator', 'dump', '/dev/tty').toString('utf8');
  const start = raw.indexOf('<hierarchy'), end = raw.lastIndexOf('</hierarchy>');
  assert.ok(start >= 0 && end > start);
  const xml = raw.slice(start, end + '</hierarchy>'.length);
  fs.writeFileSync(path.join(reports, name + '.xml'), xml);
  return [...xml.matchAll(/<node\b[^>]*>/g)].map(match => match[0]);
}
function webBounds(nodes) {
  const node = nodes.find(n => /class="android.webkit.WebView"/.test(n));
  if (!node) return null;
  const match = node.match(/bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"/);
  assert.ok(match, 'WebView must have UI-tree bounds');
  return match.slice(1).map(Number);
}
function screenshot(name) {
  const bytes = adb('exec-out', 'screencap', '-p'), start = bytes.indexOf(Buffer.from('89504e470d0a1a0a', 'hex'));
  assert.ok(start >= 0);
  fs.writeFileSync(path.join(reports, name + '.png'), bytes.subarray(start));
}
async function main() {
  fs.mkdirSync(reports, { recursive: true });
  const focus = adb('shell', 'dumpsys', 'window').toString();
  assert.match(focus, /mCurrentFocus=.*me\.rerere\.rikkahub\.revised\.appearanceqa\/me\.rerere\.rikkahub\.RichTextRegressionActivity/);
  const targets = await (await fetch(`http://127.0.0.1:${port}/json/list`)).json();
  assert.equal(targets.length, 1, 'Only the fixed isolated WebView may be connected');
  const endpoint = new URL(targets[0].webSocketDebuggerUrl);
  endpoint.hostname = '127.0.0.1'; endpoint.port = String(port);
  const ws = new WebSocket(endpoint.href), pending = new Map(); let id = 0;
  await new Promise((resolve, reject) => { ws.addEventListener('open', resolve, { once: true }); ws.addEventListener('error', reject, { once: true }); });
  ws.addEventListener('message', event => {
    const message = JSON.parse(event.data), item = pending.get(message.id);
    if (!item) return;
    pending.delete(message.id); clearTimeout(item.timer);
    message.error ? item.reject(Error(JSON.stringify(message.error))) : item.resolve(message.result);
  });
  const send = (method, params) => new Promise((resolve, reject) => {
    const key = ++id, timer = setTimeout(() => { pending.delete(key); reject(Error('CDP timeout')); }, 15000);
    pending.set(key, { resolve, reject, timer }); ws.send(JSON.stringify({ id: key, method, params }));
  });
  const evaluate = async expression => {
    const value = await send('Runtime.evaluate', { expression, returnByValue: true });
    assert.ok(!value.exceptionDetails, JSON.stringify(value.exceptionDetails)); return value.result.value;
  };
  try {
    assert.equal(await evaluate("document.querySelector('meta[name=rikkahub-preview]').content"), 'terminal-readonly');
    assert.equal(await evaluate('window.__rikkaRenderStatus'), 'ready');
    assert.equal(await evaluate("document.querySelector('.xterm-helper-textarea').disabled"), true);
    const before = webBounds(ui('before')); assert.ok(before);
    const [left, top, right, bottom] = before, x = Math.round((left + right) / 2), y = Math.round((top + bottom) / 2);
    const range = await evaluate("document.getElementById('terminal-scroll').scrollWidth - document.getElementById('terminal-scroll').clientWidth");
    assert.ok(range > 200);
    await evaluate("document.getElementById('terminal-scroll').scrollLeft = 0");
    adb('shell', 'input', 'swipe', Math.round(left + (right - left) * .75), y, Math.round(left + (right - left) * .25), y, 450);
    await delay(250);
    const horizontalOffset = await evaluate("document.getElementById('terminal-scroll').scrollLeft");
    assert.ok(horizontalOffset > 20, 'Native horizontal touch must pan the terminal columns');
    screenshot('horizontal');
    const ime = adb('shell', 'dumpsys', 'input_method').toString();
    assert.ok(!/mInputShown=true|isInputViewShown=true/.test(ime), 'Terminal swipe must not open the IME');
    adb('shell', 'input', 'swipe', x, Math.round(top + (bottom - top) * .8), x, Math.round(top + (bottom - top) * .2), 450);
    await delay(300);
    const after = webBounds(ui('after-vertical'));
    assert.ok(after === null || after[1] < top - 50, 'Embedded vertical swipe must move the Compose parent');
    screenshot('vertical-parent');
    fs.writeFileSync(path.join(reports, 'results.json'), JSON.stringify({ range, horizontalOffset, before, after, imeOpened: false }, null, 2));
    console.log('PASS real Android ANSI horizontal pan, parent vertical scroll and no keyboard');
  } finally { ws.close(); }
}
main().catch(error => { console.error(error); process.exitCode = 1; });
