// Requires Playwright and a Chromium browser. Uses the bundled Leaflet 1.9.4
// assets and synthetic data only; all external network requests are intercepted.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { chromium } = require('playwright');
const root = path.resolve(__dirname, '..');
const html = fs.readFileSync(path.join(root, 'app/build/reports/webview-renderers/LEAFLET.html'), 'utf8');
const assetDirectory = path.join(root, 'app/src/main/assets/html/renderers');
const outputDirectory = path.join(root, 'app/build/reports/geographic-renderer');
const point = { type: 'Point', coordinates: [120, 30] };
const feature = geometry => ({ type: 'Feature', properties: {}, geometry });
const collection = features => ({ type: 'FeatureCollection', features });
const polygon = { type: 'Polygon', coordinates: [[[119, 29], [121, 29], [121, 31], [119, 29]]] };

async function main() {
  fs.mkdirSync(outputDirectory, { recursive: true });
  const browser = await chromium.launch({ headless: true,
    ...(process.env.MAP_TEST_BROWSER ? { executablePath: process.env.MAP_TEST_BROWSER } : { channel: 'msedge' }) });
  let count = 0;
  async function scenario(name, data, verify, options = {}) {
    const page = await browser.newPage({ viewport: { width: 360, height: 300 }, deviceScaleFactor: 1 });
    const errors = [], requests = [], tileRequests = [];
    page.on('pageerror', error => errors.push(error.message));
    await page.route('**/*', async route => {
      const url = new URL(route.request().url());
      requests.push(url.pathname);
      if (url.pathname.endsWith('/leaflet.js')) {
        await route.fulfill({ contentType: 'text/javascript', body: fs.readFileSync(path.join(assetDirectory, 'leaflet.js'), 'utf8') +
          '\n;const factory=L.map;L.map=function(){return window.testMap=factory.apply(this,arguments);};' });
      } else if (url.pathname.endsWith('/leaflet.css')) {
        await route.fulfill({ contentType: 'text/css', body: fs.readFileSync(path.join(assetDirectory, 'leaflet.css'), 'utf8') });
      } else if (url.host === 'rikkahub.local' && url.pathname.startsWith('/assets/')) {
        const file = path.join(root, 'app/src/main', url.pathname.slice(1));
        if (fs.existsSync(file)) await route.fulfill({ path: file, contentType: file.endsWith('.js') ? 'text/javascript' : file.endsWith('.json') ? 'application/json' : 'text/css' });
        else await route.fulfill({ status: 404, body: 'Missing asset' });
      } else if (url.host === 'rikkahub.local' && url.pathname === '/') {
        const source = JSON.stringify(JSON.stringify(data)).replaceAll('<', '\\u003c');
        const templateFile = options.fixture || (options.rawHtml ? (options.fullscreen ? 'raw-fullscreen.html' : 'raw-html.html') : 'fullscreen.html');
        const template = options.fixture || options.fullscreen || options.rawHtml ? fs.readFileSync(path.join(root, 'app/build/reports/webview-renderers/maps', templateFile), 'utf8') : html;
        const content = options.fixture ? template : template.replace(/const source = [^\n]+?;(?=\r?\n)/, () => `const source = ${source};`);
        await route.fulfill({ contentType: 'text/html; charset=utf-8', body: content });
      } else if (url.host === 'tile.openstreetmap.org' || (url.host === 'tiles.test' && !options.tileFailure)) {
        tileRequests.push(url.href);
        // Synthetic tiles verify actual image loading/layout without sending coordinates externally.
        await route.fulfill({ contentType: 'image/svg+xml', body: '<svg xmlns="http://www.w3.org/2000/svg" width="256" height="256"><rect width="256" height="256" fill="#dce9d3"/><path d="M0 90 L256 170 M140 0 L100 256" stroke="#fff" stroke-width="12"/><text x="12" y="30" fill="#375b3a">Test basemap</text></svg>' });
      } else {
        await route.fulfill({ status: 503, body: 'Synthetic unavailable tile' });
      }
    });
    if (options.legacyResize) await page.addInitScript(() => { window.ResizeObserver = undefined; });
    try {
      await page.goto('https://rikkahub.local/');
      if (!options.rawHtml) await page.waitForFunction(() => ['ready', 'error'].includes(window.__rikkaRenderStatus));
      await page.waitForTimeout(200);
      await verify(page, requests, tileRequests);
      assert.deepEqual(errors, [], `${name}: uncaught JavaScript exception`);
      count++;
      console.log(`PASS ${name}`);
    } finally { await page.close(); }
  }
  async function layerCount(page, type) {
    return page.evaluate(type => {
      let count = 0;
      window.testMap.eachLayer(layer => { if (layer instanceof L[type] && layer.options?.pane !== 'rikkaOfflineBasemap') count++; });
      return count;
    }, type);
  }
  async function warning(page, text) {
    assert.ok((await page.locator('#map-warning').innerText()).includes(text));
    assert.notEqual(await page.locator('#renderer').evaluate(node => getComputedStyle(node).display), 'none');
  }
  try {
    for (const [name, type, expected] of [['comments', 'CircleMarker', 1], ['literal', 'CircleMarker', 1], ['array', 'CircleMarker', 2], ['route', 'Polyline', 1]]) {
      await scenario('Inferred JSON ' + name + ' reaches the actual map renderer', {}, async page => {
        assert.equal(await layerCount(page, type), expected);
        assert.equal(await page.evaluate(() => window.__rikkaRenderStatus), 'ready');
        assert.ok(await page.evaluate(() => !!testMap._rikkaOfflineBasemap));
      }, { fixture: 'inferred-' + name + '.html' });
    }
    await scenario('Missing tile configuration renders bundled offline geography', { geoJson: point }, async (page, requests, tiles) => {
      assert.equal(tiles.length, 0);
      assert.equal(await layerCount(page, 'TileLayer'), 0);
      assert.ok(await page.evaluate(() => !!testMap._rikkaOfflineBasemap));
      await page.locator('.leaflet-rikkaOfflineBasemap-pane canvas').waitFor({ state: 'visible' });
      assert.ok((await page.locator('.leaflet-control-attribution').innerText()).includes('离线概览'));
      assert.ok(requests.some(url => url.endsWith('/maps/world.json')));
      await page.screenshot({ path: path.join(outputDirectory, 'default-basemap.png') });
    });
    await scenario('Offline world has real land geometry without external requests', { center: [20, 0], zoom: 2, fitBounds: false, geoJson: point }, async (page, requests, tiles) => {
      assert.equal(tiles.length, 0);
      const painted = await page.locator('.leaflet-rikkaOfflineBasemap-pane canvas').evaluate(canvas => {
        const rgba = canvas.getContext('2d').getImageData(0, 0, canvas.width, canvas.height).data;
        let count = 0; for (let i = 3; i < rgba.length; i += 4) if (rgba[i] > 0) count++;
        return count;
      });
      assert.ok(painted > 1000, 'Geographic shapes must actually be painted');
      await page.screenshot({ path: path.join(outputDirectory, 'offline-world.png') });
    });
    for (const options of [{ basemap: false }, { basemap: 'none' }, { tileUrl: '' }, { tileUrl: null }, { tileUrl: false }]) {
      await scenario('Explicit no-basemap remains offline ' + JSON.stringify(options), { ...options, geoJson: point }, async (page, requests, tiles) => {
        assert.equal(tiles.length, 0);
        assert.equal(await layerCount(page, 'TileLayer'), 0);
        assert.equal(await layerCount(page, 'CircleMarker'), 1);
      });
    }
    await scenario('Custom basemap is preserved and not silently replaced', { tileUrl: 'https://tiles.test/{z}/{x}/{y}.png', geoJson: point }, async (page, requests, tiles) => {
      assert.ok(tiles.length > 0 && tiles.every(url => url.startsWith('https://tiles.test/')));
      await page.locator('img.leaflet-tile-loaded').first().waitFor({ state: 'visible' });
    });
    await scenario('Bad tile pane and nonnumeric options do not hide valid tiles', {
      tileUrl: 'https://tiles.test/{z}/{x}/{y}.png', tileOptions: { pane: {}, tileSize: 0, opacity: 'bad' }, geoJson: point,
    }, async page => { await page.locator('img.leaflet-tile-loaded').first().waitFor({ state: 'visible' }); });
    for (const fullscreen of [false, true]) {
      await scenario('HTML basemap tiles keep dimensions and vector alignment ' + fullscreen, {}, async page => {
        await page.locator('img.leaflet-tile-loaded').first().waitFor({ state: 'visible' });
        const tile = await page.locator('img.leaflet-tile-loaded').first().boundingBox();
        assert.equal(tile.width, 256);
        assert.equal(tile.height, 256);
        const svg = await page.locator('.leaflet-overlay-pane > svg').evaluate(svg => ({
          width: Number(svg.getAttribute('width')), height: Number(svg.getAttribute('height')),
          actualWidth: svg.getBoundingClientRect().width, actualHeight: svg.getBoundingClientRect().height,
        }));
        assert.equal(svg.actualWidth, svg.width);
        assert.equal(svg.actualHeight, svg.height);
        await page.screenshot({ path: path.join(outputDirectory, 'html-basemap-' + fullscreen + '.png') });
      }, { rawHtml: true, fullscreen });
    }
    await scenario('GeoJSON points use visible local markers', point, async (page, requests) => {
      assert.equal(await layerCount(page, 'CircleMarker'), 1);
      assert.ok(!requests.some(value => value.includes('marker-icon') || value.includes('marker-shadow')), 'No missing default icon requests');
      const bounds = await page.locator('.leaflet-interactive').boundingBox();
      assert.ok(bounds && bounds.width > 0 && bounds.height > 0);
      assert.ok(bounds.x >= 0 && bounds.y >= 0 && bounds.x < 360 && bounds.y < 300);
      await page.screenshot({ path: path.join(outputDirectory, 'geojson-point.png') });
    });
    await scenario('Leaflet SVG dimensions remain owned by Leaflet', polygon, async page => {
      const size = await page.locator('.leaflet-overlay-pane > svg').evaluate(svg => ({
        width: svg.getAttribute('width'), height: svg.getAttribute('height'),
        styleWidth: svg.style.width, styleHeight: svg.style.height,
        actualWidth: svg.getBoundingClientRect().width, actualHeight: svg.getBoundingClientRect().height,
      }));
      assert.ok(Number(size.width) > 0 && Number(size.height) > 0);
      assert.ok(size.styleWidth !== '100%' && size.styleHeight !== 'auto');
      assert.equal(size.actualWidth, Number(size.width));
      assert.equal(size.actualHeight, Number(size.height));
      await page.screenshot({ path: path.join(outputDirectory, 'geojson-polygon.png') });
    });
    await scenario('Bad collection entries preserve valid geometry', collection([
      feature(point), null, feature({ type: 'Point', coordinates: [null, 20] }),
      feature({ type: 'Polygon', coordinates: [null] }), feature(polygon), feature(null),
    ]), async page => {
      assert.equal(await layerCount(page, 'CircleMarker'), 1);
      assert.equal(await layerCount(page, 'Polygon'), 1);
      await warning(page, 'GeoJSON');
    });
    await scenario('Invalid marker and line entries do not abort valid siblings', {
      markers: [null, {}, { lat: 100, lng: 120 }, { lat: '30', lng: '120' }],
      polylines: [null, { points: [[30, 120], [null, 121]] }, { points: [[30, 120], [31, 121]] }],
    }, async page => {
      assert.equal(await layerCount(page, 'CircleMarker'), 1);
      assert.equal(await layerCount(page, 'Polyline'), 1);
      await warning(page, '已跳过');
    });
    await scenario('Malformed optional lists do not suppress GeoJSON', {
      center: [null, true], markers: {}, polylines: 'invalid', geojson: point,
    }, async page => {
      assert.equal(await layerCount(page, 'CircleMarker'), 1);
      await warning(page, '格式无效');
    });
    await scenario('JSON options cannot masquerade as Leaflet callbacks or instances', {
      geoJson: point, geoJsonOptions: { filter: true, pointToLayer: 'marker', style: 'blue', renderer: {}, pane: 'missing' },
    }, async page => { assert.equal(await layerCount(page, 'CircleMarker'), 1); });
    await scenario('Malformed panes and coordinates cannot poison later map interactions', {
      markers: [{ lat: 20, lng: 1e308 }, { lat: 30, lng: 120, options: { pane: {}, renderer: 'svg', radius: 'invalid' } }],
      geoJson: point, geoJsonOptions: { style: { pane: false, weight: null, fillOpacity: '0.5' } },
    }, async page => {
      assert.equal(await layerCount(page, 'CircleMarker'), 2);
      await page.evaluate(() => { window.testMap.setView([30, 120], 8); });
      await warning(page, '坐标无效');
    });
    await scenario('GeometryCollection preserves valid feature children', feature({ type: 'GeometryCollection', geometries: [
      point, { type: 'LineString', coordinates: null }, polygon,
    ] }), async page => {
      assert.equal(await layerCount(page, 'CircleMarker'), 1);
      assert.equal(await layerCount(page, 'Polygon'), 1);
      await warning(page, 'GeoJSON');
    });
    await scenario('All standard geographic geometry types retain their layers', collection([
      feature(point), feature({ type: 'MultiPoint', coordinates: [[121, 30], [121, 31]] }),
      feature({ type: 'LineString', coordinates: [[119, 29], [120, 30]] }),
      feature({ type: 'MultiLineString', coordinates: [[[118, 28], [119, 29]], [[121, 32], [122, 33]]] }),
      feature(polygon), feature({ type: 'MultiPolygon', coordinates: [polygon.coordinates] }),
      feature({ type: 'GeometryCollection', geometries: [point, polygon] }),
    ]), async page => {
      assert.equal(await layerCount(page, 'CircleMarker'), 4);
      assert.equal(await layerCount(page, 'Polygon'), 3);
      assert.equal(await layerCount(page, 'Polyline'), 5);
      assert.equal(await page.locator('#map-warning').evaluate(node => node.hidden), true);
    });
    await scenario('Disabled fit respects explicit center and numeric string zoom', {
      center: ['35', '110'], zoom: '5', fitBounds: false, geoJson: point,
    }, async page => {
      const current = await page.evaluate(() => ({ zoom: testMap.getZoom(), center: testMap.getCenter() }));
      assert.equal(current.zoom, 5);
      assert.ok(Math.abs(current.center.lat - 35) < .01 && Math.abs(current.center.lng - 110) < .01);
    });
    await scenario('Zero fit zoom is preserved', { geoJson: point, maxFitZoom: 0 }, async page => {
      assert.equal(await page.evaluate(() => window.testMap.getZoom()), 0);
    });
    await scenario('Viewport resize preserves user pan and zoom', { geoJson: polygon }, async page => {
      await page.evaluate(() => { window.testMap.setView([45, 80], 6, { animate: false }); });
      await page.setViewportSize({ width: 480, height: 400 });
      await page.waitForTimeout(150);
      const current = await page.evaluate(() => ({ zoom: window.testMap.getZoom(), center: window.testMap.getCenter() }));
      assert.equal(current.zoom, 6);
      assert.ok(Math.abs(current.center.lat - 45) < .01 && Math.abs(current.center.lng - 80) < .01);
    });
    await scenario('Old WebView resize fallback keeps map usable', { geoJson: point }, async page => {
      await page.setViewportSize({ width: 480, height: 400 });
      await page.waitForTimeout(100);
      assert.equal(await layerCount(page, 'CircleMarker'), 1);
      assert.deepEqual(await page.evaluate(() => ({ x: testMap.getSize().x, y: testMap.getSize().y })), { x: 480, y: 400 });
    }, { legacyResize: true });
    await scenario('Fullscreen map fills viewport and retains interactive geometry', { geoJson: polygon }, async page => {
      await page.setViewportSize({ width: 393, height: 780 });
      await page.waitForTimeout(150);
      const bounds = await page.locator('#renderer').boundingBox();
      assert.deepEqual(bounds, { x: 0, y: 0, width: 393, height: 780 });
      assert.equal(await layerCount(page, 'Polygon'), 1);
      await page.screenshot({ path: path.join(outputDirectory, 'fullscreen-map.png') });
    }, { fullscreen: true });
    await scenario('Tile network errors keep vectors and show a diagnostic', {
      tileUrl: 'https://tiles.test/{z}/{x}/{y}.png', geoJson: point,
    }, async page => {
      assert.equal(await layerCount(page, 'CircleMarker'), 1);
      await warning(page, '底图加载失败');
      await page.waitForFunction(() => !!testMap._rikkaOfflineBasemap);
    }, { tileFailure: true });
    await scenario('Invalid tile template keeps data layers', { tileUrl: 'https://tiles.test/{missing}.png', geoJson: point }, async page => {
      assert.equal(await layerCount(page, 'CircleMarker'), 1);
      await warning(page, '底图配置无效');
      await page.evaluate(() => { window.testMap.setView([40, 100], 4); });
    });
    await scenario('Null root shows render error instead of uncaught exception', null, async page => {
      await page.locator('#render-error').waitFor({ state: 'visible' });
    });
    console.log(`${count} geographic renderer scenarios passed using bundled Leaflet in desktop Chromium. Android touch/GPU behavior is not covered.`);
  } finally { await browser.close(); }
}
main().catch(error => { console.error(error); process.exitCode = 1; });
