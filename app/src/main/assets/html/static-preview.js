/* App-owned layout only. User HTML/SVG has no script/handler permissions. */
(function () {
  'use strict';
  window.__rikkaRenderStatus = 'loading';
  requestAnimationFrame(function () {
    try {
      const svg = document.body.dataset.rikkaStaticSvg === 'true' && document.querySelector('body > svg');
      if (svg) {
        if (!svg.hasAttribute('preserveAspectRatio')) svg.setAttribute('preserveAspectRatio', 'xMidYMid meet');
        const box = svg.viewBox && svg.viewBox.baseVal;
        if (!(box && box.width > 0 && box.height > 0)) {
          const dimension = name => {
            const value = (svg.getAttribute(name) || '').trim();
            return /^(?:\d+(?:\.\d+)?|\.\d+)(?:px)?$/i.test(value) ? parseFloat(value) : 0;
          };
          const width = dimension('width'), height = dimension('height');
          if (width > 0 && height > 0) svg.setAttribute('viewBox', [0, 0, width, height].join(' '));
          else {
            const bounds = svg.getBBox();
            if (bounds.width > 0 && bounds.height > 0) {
              const padding = Math.max(2, Math.min(bounds.width, bounds.height) * .01);
              svg.setAttribute('viewBox', [bounds.x - padding, bounds.y - padding, bounds.width + 2 * padding, bounds.height + 2 * padding].join(' '));
            }
          }
        }
        svg.removeAttribute('width'); svg.removeAttribute('height');
        svg.style.width = '100%'; svg.style.height = 'auto';
      }
      window.__rikkaRenderStatus = 'ready';
    } catch (error) { window.__rikkaRenderStatus = 'error'; console.warn(error); }
  });
})();
