package me.rerere.rikkahub.ui.components.webview

/** App-owned snapshot preparation. Does not execute source or grant document permissions. */
internal val WEBVIEW_EXPORT_PROBE = """
    (function() {
      const result = (status, error) => JSON.stringify({status: status, error: error || '',
        height: Math.ceil(Math.max(document.documentElement.scrollHeight, document.body ? document.body.scrollHeight : 0)),
        viewport: innerHeight});
      if (window.__rikkaRenderStatus === 'error') {
        const node = document.querySelector('#error, #render-error');
        return result('error', node && node.textContent || '图形渲染失败，原始源码仍保留在对话中');
      }
      if (document.readyState !== 'complete' || window.__rikkaRenderStatus === 'loading' ||
          (document.fonts && document.fonts.status !== 'loaded') ||
          Array.from(document.images).some(image => !image.complete)) return result('loading');
      if (typeof window.rikkaPrepareExport === 'function') window.rikkaPrepareExport('message');
      document.querySelectorAll('svg').forEach(svg => { if (svg.pauseAnimations) svg.pauseAnimations(); });
      if (document.getAnimations) document.getAnimations().forEach(animation => animation.pause());
      if (!document.getElementById('rikka-export-layout')) {
        const geometry = document.querySelector('meta[name=rikkahub-preview][content=geometry-typed]');
        if (geometry) {
          const stage = document.getElementById('stage');
          stage.style.flex = 'none'; stage.style.height = Math.max(180, stage.clientHeight) + 'px';
        }
        const style = document.createElement('style'); style.id = 'rikka-export-layout';
        style.textContent = '#parameters,#legend{max-height:none!important;overflow:visible!important}' +
          '#controls{display:none!important}';
        if (geometry) style.textContent += 'html,body{height:auto!important;min-height:0!important;overflow:visible!important}';
        // Natural-height diagrams (scores, SVG, Mermaid) must not export just
        // their scrolled viewport. Canvas charts/maps keep their fixed viewport.
        const diagram = document.querySelector('#diagram-container') || document.querySelector('#renderer');
        if (diagram && !diagram.querySelector('canvas') && !diagram.classList.contains('leaflet-container')) {
          style.textContent += '#diagram-container,#renderer{overflow:visible!important;height:auto!important;min-height:0!important}' +
            '#diagram-container svg,#renderer svg{max-width:100%!important;height:auto!important}';
          diagram.scrollLeft = diagram.scrollTop = 0;
        }
        document.head.appendChild(style);
        window.scrollTo(0,0);
        return result('layout');
      }
      return result('ready');
    })()
""".trimIndent()
