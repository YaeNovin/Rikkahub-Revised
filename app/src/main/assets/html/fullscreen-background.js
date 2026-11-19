(function () {
  'use strict';
  const initial = document.body.classList.contains('rikka-preview-global-background');
  document.documentElement.classList.remove('rikka-preview-global-background');
  document.body.classList.remove('rikka-preview-global-background');
  const container = document.getElementById('diagram-container') || document.getElementById('renderer');
  if (container && !container.classList.contains('leaflet-container')) {
    const paper = getComputedStyle(container).backgroundColor;
    if (paper !== 'rgba(0, 0, 0, 0)' && paper !== 'transparent') document.body.style.setProperty('--rikka-preview-paper', paper);
  }
  window.rikkaSetPreviewBackground = function (enabled) {
    document.documentElement.classList.toggle('rikka-preview-global-background', enabled);
    document.body.classList.toggle('rikka-preview-global-background', enabled);
  };
  window.rikkaSetPreviewBackground(initial);
})();
