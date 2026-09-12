// Run after WebRendererRegressionTest generates app/build/reports/webview-renderers.
// This verifies JavaScript syntax and renderer API contracts, not browser pixels.
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const assert = require('node:assert/strict');
const directory = path.resolve(__dirname, '../app/build/reports/webview-renderers');
function scripts(html) {
  return [...html.matchAll(/<script\b[^>]*>([\s\S]*?)<\/script>/gi)].map(match => match[1]).filter(text => text.trim());
}
const files = fs.readdirSync(directory).filter(file => file.endsWith('.html'));
assert.equal(files.length, 12);
for (const file of files) for (const text of scripts(fs.readFileSync(path.join(directory, file), 'utf8'))) new vm.Script(text, { filename: file });

function harness() {
  const errors = [], events = [], points = [];
  const node = () => ({ style: {}, clientWidth: 360, clientHeight: 280, scrollWidth: 360, scrollHeight: 280,
    textContent: '', setAttribute() {}, getAttribute() { return ''; }, hasAttribute() { return false; }, removeAttribute() {}, appendChild() {}, querySelectorAll() { return []; },
    classList: { remove() {} }, getBBox() { return { x: 0, y: 0, width: 300, height: 100 }; },
    getBoundingClientRect() { return { width: 2000, height: 20000 }; }, viewBox: { baseVal: { x: 0, y: 0, width: 300, height: 100 } } });
  const root = node(), error = node(), svg = node(), diagram = node();
  const canvas = { width: 0, height: 0, getContext() { return { fillRect() {}, drawImage() {}, fillText() {} }; }, toDataURL() { return 'data:image/png;base64,cG5n'; } };
  const context = {
    console: { error(error) { errors.push(String(error)); }, warn() {}, log() {} },
    document: { documentElement: { clientWidth: 360 }, body: node(),
      getElementById(id) { return id === 'renderer' ? root : id === 'render-error' ? error : { textContent: 'Render failed' }; },
      querySelector(selector) { return selector === '.mermaid' ? diagram : svg; },
      createElement(tag) { return tag === 'canvas' ? canvas : node(); }, createElementNS() { return node(); } },
    window: { devicePixelRatio: 4, addEventListener(type, fn) { events.push({ type, fn }); } },
    requestAnimationFrame(fn) { fn(); }, setTimeout(fn, delay) { if (delay < 20000) fn(); }, clearTimeout() {},
    getComputedStyle() { return { color: '#eeeeee' }; },
    XMLSerializer: class { serializeToString() { return '<svg></svg>'; } },
    Image: class { set src(value) { this.onload(); } },
    btoa(text) { return Buffer.from(text, 'binary').toString('base64'); },
    AndroidInterface: { exportImage(data) { assert.equal(data, 'cG5n'); } },
  };
  return { context, root, error, errors, events, points, canvas };
}
async function run(name, h) {
  const context = vm.createContext(h.context);
  for (const script of scripts(fs.readFileSync(path.join(directory, `${name}.html`), 'utf8'))) await vm.runInContext(script, context, { timeout: 2000 });
  assert.deepEqual(h.errors, [], `${name} renderer failed`);
  return context;
}

(async () => {
const map = harness();
let offlineCalls = 0;
map.context.window.rikkaOfflineBasemap = async () => { offlineCalls++; };
let markerCount = 0;
const bounds = { extend(point) { map.points.push(point); return this; }, isValid() { return map.points.length > 0; } };
const instance = { setView() { return this; }, invalidateSize() {}, fitBounds() {}, on() {}, getPane() { return {}; } };
map.context.L = { map() { return instance; }, latLngBounds() { return bounds; },
  // Match Leaflet CircleMarker: it has getLatLng, not getBounds.
  circleMarker(position) { markerCount++; return { addTo() { return this; }, getLatLng() { return position; }, bindPopup() {} }; } };
await run('LEAFLET', map);
assert.equal(offlineCalls, 1);
assert.equal(markerCount, 1);
assert.equal(map.points.length, 1);
assert.equal(map.events[0].type, 'resize');
map.events[0].fn();

const chart = harness();
let setOptions = 0, resizeCalls = 0;
chart.context.echarts = { init() { return { setOption() { setOptions++; }, resize() { resizeCalls++; } }; } };
await run('ECHARTS', chart);
assert.equal(setOptions, 1);
chart.events.find(event => event.type === 'resize').fn();
assert.equal(resizeCalls, 1);

await run('JIANPU', harness());
await run('SVG', harness());

  const mermaid = harness();
  mermaid.context.mermaid = { initialize() {}, run() { return Promise.resolve(); } };
  await run('MERMAID', mermaid);
  await Promise.resolve();
  mermaid.context.window.exportSvgToPng();
  assert.ok(mermaid.canvas.width * mermaid.canvas.height <= 4000000);
  assert.ok(mermaid.canvas.width <= 4096 && mermaid.canvas.height <= 4096);
  console.log('12 renderer pages passed syntax checks; map, chart, Jianpu, SVG and Mermaid export contracts passed. Browser rendering is not covered.');
})().catch(error => { console.error(error); process.exitCode = 1; });
