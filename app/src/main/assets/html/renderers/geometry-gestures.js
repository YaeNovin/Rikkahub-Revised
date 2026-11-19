/* Camera gestures for fixed renderers. Native WebView zoom stays disabled.
 * Single-finger inline scrolling remains owned by the Compose parent. */
(function () {
  'use strict';
  function attach(element, ops, fullscreen) {
    let previous = null, swallowed = false, mouse = null, disposed = false;
    const local = touch => { const r = element.getBoundingClientRect(); return { x: touch.clientX - r.left, y: touch.clientY - r.top }; };
    const state = touches => {
      if (touches.length === 2) {
        const a = local(touches[0]), b = local(touches[1]);
        return { x: (a.x + b.x) / 2, y: (a.y + b.y) / 2, distance: Math.hypot(a.x - b.x, a.y - b.y), count: 2 };
      }
      return touches.length === 1 ? { ...local(touches[0]), count: 1 } : null;
    };
    const consume = event => { if (event.cancelable) event.preventDefault(); event.stopImmediatePropagation(); };
    function start(event) {
      if (disposed) return;
      previous = state(event.touches);
      if (event.touches.length >= 2) pinching = true;
      if (event.touches.length >= 2 || fullscreen) { swallowed = true; consume(event); }
    }
    function move(event) {
      if (disposed) return;
      const next = state(event.touches);
      if (event.touches.length >= 2 || fullscreen || swallowed) consume(event);
      if (previous && next && previous.count === next.count) {
        if (next.count === 2 && previous.distance >= 8 && next.distance >= 8) {
          ops.scale(Math.max(.5, Math.min(2, next.distance / previous.distance)), previous, next);
        } else if (next.count === 1 && fullscreen && !pinching) ops.pan(next.x - previous.x, next.y - previous.y);
      }
      if (event.touches.length >= 2) { swallowed = true; pinching = true; }
      previous = next;
    }
    let pinching = false;
    function end(event) {
      if (swallowed) consume(event);
      previous = state(event.touches);
      if (!event.touches.length) { swallowed = false; pinching = false; }
      // Remaining finger after a pinch never becomes an accidental drag.
    }
    function cancel() { previous = null; swallowed = false; pinching = false; mouse = null; }
    function wheel(event) {
      if (!fullscreen && !event.ctrlKey) return;
      consume(event);
      const point = local(event);
      ops.scale(Math.exp(-Math.max(-120, Math.min(120, event.deltaY)) * .003), point, point);
    }
    function key(event) {
      if (event.target !== element) return;
      const center = { x: element.clientWidth / 2, y: element.clientHeight / 2 };
      if (event.key === '+' || event.key === '=') { consume(event); ops.scale(1.2, center, center); }
      else if (event.key === '-') { consume(event); ops.scale(1 / 1.2, center, center); }
      else if (event.key === '0' || event.key === 'Home') { consume(event); ops.reset(); }
    }
    function down(event) {
      if (!fullscreen || event.pointerType !== 'mouse' || event.button !== 0) return;
      consume(event); mouse = { ...local(event), id: event.pointerId };
      element.setPointerCapture(event.pointerId);
    }
    function mouseMove(event) {
      if (!mouse || mouse.id !== event.pointerId) return;
      consume(event); const next = local(event); ops.pan(next.x - mouse.x, next.y - mouse.y); mouse = { ...next, id: event.pointerId };
    }
    function up(event) { if (mouse && mouse.id === event.pointerId) { mouse = null; if (element.hasPointerCapture(event.pointerId)) element.releasePointerCapture(event.pointerId); } }
    const handlers = { touchstart: start, touchmove: move, touchend: end, touchcancel: cancel, wheel, keydown: key,
      pointerdown: down, pointermove: mouseMove, pointerup: up, pointercancel: up, lostpointercapture: up };
    const options = { passive: false, capture: true };
    Object.entries(handlers).forEach(([name, listener]) => element.addEventListener(name, listener, options));
    window.addEventListener('resize', cancel);
    document.addEventListener('visibilitychange', cancel);
    element.tabIndex = 0;
    element.setAttribute('aria-label', '绘图：双指缩放' + (fullscreen ? '，拖动平移' : '') + '；键盘加减号缩放，0 重置');
    return { cancel, dispose() {
      disposed = true; cancel();
      window.removeEventListener('resize', cancel); document.removeEventListener('visibilitychange', cancel);
      Object.entries(handlers).forEach(([name, listener]) => element.removeEventListener(name, listener, options));
    } };
  }
  window.RikkaGeometryGestures = Object.freeze({ attach });
})();
