// Uses the existing bundled ECharts world GeoJSON (Apache-2.0; see html/licenses).
// Overview geography only: no street/address data and no remote requests.
(function () {
  let worldPromise;
  window.rikkaOfflineBasemap = async function (map, root) {
    if (map._rikkaOfflineBasemap) return map._rikkaOfflineBasemap;
    if (!worldPromise) worldPromise = fetch('https://rikkahub.local/assets/html/maps/world.json')
      .then(function (response) {
        if (!response.ok) throw new Error('离线底图资源不可用');
        return response.json();
      }).catch(function (error) { worldPromise = null; throw error; });
    const world = await worldPromise;
    if (map._rikkaOfflineBasemap) return map._rikkaOfflineBasemap;
    const pane = map.getPane('rikkaOfflineBasemap') || map.createPane('rikkaOfflineBasemap');
    pane.style.zIndex = '150';
    pane.style.pointerEvents = 'none';
    root.classList.add('rikka-offline-map');
    const colors = getComputedStyle(root);
    const land = colors.getPropertyValue('--rikka-map-land').trim() || '#e5e9df';
    const border = colors.getPropertyValue('--rikka-map-border').trim() || '#7b8779';
    // Canvas clips geometry to the viewport and avoids thousands of SVG DOM nodes.
    const renderer = L.canvas({ pane: 'rikkaOfflineBasemap', padding: .1 });
    // Legacy ECharts map files omit Feature.type; Leaflet requires standard GeoJSON.
    const featureCollection = { type: 'FeatureCollection', features: world.features.map(function (feature) {
      return { type: 'Feature', geometry: feature.geometry, properties: feature.properties || {} };
    }) };
    const layer = L.geoJSON(featureCollection, {
      pane: 'rikkaOfflineBasemap', renderer: renderer, interactive: false,
      style: { fillColor: land, fillOpacity: 1, color: border, opacity: .65, weight: .7, smoothFactor: 1.5 },
    }).addTo(map);
    map._rikkaOfflineBasemap = layer;
    map.attributionControl.addAttribution('离线概览 · 无街道数据 · ECharts');
    return layer;
  };
})();
