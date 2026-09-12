// Fullscreen only. Translate/scale the entire rendering scene, never its internal nodes.
(function () {
  if (window.__rikkaPanInstalled) return;
  window.__rikkaPanInstalled = true;
  const states = new WeakMap(), pointers = new Map();
  let scene;
  let target = null, previous = null;
  function prepare() {
    if (!scene) {
      scene = document.getElementById('renderer') || document.getElementById('diagram-container');
      if (!scene) {
        scene = document.createElement('div'); scene.id = 'rikkahub-pan-scene';
        Array.from(document.body.children).filter(n => !/^(SCRIPT|STYLE|LINK)$/.test(n.tagName)).forEach(n => scene.appendChild(n));
        document.body.appendChild(scene);
      }
      scene.style.overflow = 'visible';
      scene.style.minWidth = '100%'; scene.style.minHeight = '100%';
      scene.style.touchAction = 'none';
      document.body.style.touchAction = 'none';
      document.documentElement.style.touchAction = 'none';
      document.documentElement.style.setProperty('overflow', 'hidden', 'important');
      document.body.style.setProperty('overflow', 'hidden', 'important');
      // Preserve fixed chart dimensions but allow the transformed scene to cross former clipping boxes.
      scene.querySelectorAll('#renderer,.mermaid,.vega-embed').forEach(n => n.style.overflow = 'visible');
    }
    document.querySelectorAll('svg,canvas,img').forEach(function (node) {
      if (node.closest('.leaflet-container') || node.parentElement.closest('svg')) return;
      node.style.touchAction = 'none';
      node.style.cursor = 'grab';
      node.draggable = false;
    });
  }
  function metrics() {
    const values = Array.from(pointers.values());
    const a = values[0], b = values[1] || a;
    return { x: (a.x + b.x) / 2, y: (a.y + b.y) / 2, distance: Math.hypot(a.x - b.x, a.y - b.y) };
  }
  function end(event) {
    pointers.delete(event.pointerId);
    previous = pointers.size ? metrics() : null;
    if (!pointers.size && target) { target.style.cursor = 'grab'; target = null; }
  }
  document.addEventListener('pointerdown', function (event) {
    if (event.button !== 0 || event.target.closest('a,button,input,select,textarea,.leaflet-container')) return;
    // Drag blank space too: the entire viewport is the interaction surface.
    let node = scene;
    if (!node || document.querySelector('.leaflet-container')) return;
    if (target && target !== node) return;
    target = node;
    if (!states.has(node)) states.set(node, { x: 0, y: 0, scale: 1, original: node.style.transform });
    pointers.set(event.pointerId, { x: event.clientX, y: event.clientY });
    previous = metrics();
    node.setPointerCapture(event.pointerId);
    node.style.cursor = 'grabbing';
    event.preventDefault();
  });
  document.addEventListener('pointermove', function (event) {
    if (!target || !pointers.has(event.pointerId)) return;
    pointers.set(event.pointerId, { x: event.clientX, y: event.clientY });
    const next = metrics(), state = states.get(target);
    state.x += next.x - previous.x; state.y += next.y - previous.y;
    if (next.distance && previous.distance) state.scale = Math.max(.2, Math.min(8, state.scale * next.distance / previous.distance));
    target.style.transform = 'translate(' + state.x + 'px,' + state.y + 'px) scale(' + state.scale + ') ' + state.original;
    previous = next; event.preventDefault();
  }, { passive: false });
  ['pointerup','pointercancel','lostpointercapture'].forEach(name => document.addEventListener(name, end));
  window.rikkaResetPan = function() {
    const node = scene, state = states.get(node);
    if (!state) return;
    node.style.transform = state.original; states.delete(node);
    pointers.clear(); target = null; previous = null;
  };
  document.addEventListener('dblclick', function(event) {
    if (!event.target.closest('a,button,input,select,textarea,.leaflet-container')) window.rikkaResetPan();
  });
  const observer = new MutationObserver(prepare);
  observer.observe(document.body, { childList: true, subtree: true });
  window.addEventListener('pagehide', () => { observer.disconnect(); pointers.clear(); target = null; });
  prepare();
})();
