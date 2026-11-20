/* RikkaHub read-only terminal renderer. No input transport, native bridge, or remote resources. */
(function () {
  'use strict';
  const root = document.getElementById('terminal');
  const fallback = document.getElementById('fallback');
  const errorView = document.getElementById('error');
  const viewport = document.getElementById('terminal-scroll') || root;
  const embedded = document.body.dataset.embedded !== 'false';
  viewport.style.touchAction = embedded ? 'pan-y pinch-zoom' : 'pinch-zoom';
  let terminal, observer, onResize, frame = 0, disposed = false;
  let rowHeight = 18;
  window.__rikkaRenderStatus = 'loading';
  function dispose() {
    if (disposed) return;
    disposed = true;
    if (observer) observer.disconnect();
    else if (onResize) window.removeEventListener('resize', onResize);
    if (frame) cancelAnimationFrame(frame);
    if (terminal) terminal.dispose();
  }
  function fail(error) {
    dispose();
    viewport.hidden = true;
    document.getElementById('terminal-fallback').hidden = false;
    errorView.textContent = '终端预览加载失败，可切换到源码查看原始内容。';
    window.__rikkaRenderStatus = 'error';
    console.error('Read-only terminal preview failed', error);
  }
  try {
    const bytes = Uint8Array.from(atob(document.body.dataset.source), c => c.charCodeAt(0));
    const source = typeof TextDecoder === 'function' ? new TextDecoder('utf-8').decode(bytes)
      : decodeURIComponent(Array.from(bytes, c => '%' + c.toString(16).padStart(2, '0')).join(''));
    // This is text, never HTML or executable source, even if the transcript contains tags.
    const plain = source.replace(/\x1b\[[0-9;:]*[A-Za-z]/g, '');
    fallback.textContent = plain;
    let sourceColumns = 2;
    for (const line of plain.split(/[\r\n]/)) {
      let columns = 0;
      for (const character of line) {
        const point = character.codePointAt(0);
        columns += character === '\t' ? 8 - columns % 8 : point >= 0x1100 ? 2 : 1;
        if (columns >= 240) break;
      }
      sourceColumns = Math.max(sourceColumns, Math.min(240, columns));
    }
    const dark = document.body.dataset.dark !== 'false';
    const ansi = dark ? {
      black: '#8b949e', red: '#ff7b72', green: '#7ee787', yellow: '#e3b341',
      blue: '#79c0ff', magenta: '#d2a8ff', cyan: '#a5d6ff', white: '#e6edf3',
      brightBlack: '#b1bac4', brightRed: '#ffa198', brightGreen: '#aff5b4', brightYellow: '#f8e3a1',
      brightBlue: '#a5d6ff', brightMagenta: '#e2c5ff', brightCyan: '#b3f0ff', brightWhite: '#ffffff',
    } : {
      black: '#24292f', red: '#a31515', green: '#116329', yellow: '#633c01',
      blue: '#0550ae', magenta: '#6639ba', cyan: '#0a5e6f', white: '#57606a',
      brightBlack: '#57606a', brightRed: '#cf222e', brightGreen: '#1a7f37', brightYellow: '#7d4e00',
      brightBlue: '#0969da', brightMagenta: '#8250df', brightCyan: '#1b7c83', brightWhite: '#424a53',
    };
    terminal = new Terminal({
      disableStdin: true, cursorBlink: false, cursorInactiveStyle: 'none',
      convertEol: true, scrollback: 2000, fontSize: 13, fontFamily: 'monospace',
      screenReaderMode: true, allowProposedApi: false,
      linkHandler: { activate: function () {} },
      minimumContrastRatio: 4.5,
      theme: { ...ansi, background: document.body.dataset.background, foreground: document.body.dataset.foreground,
        selectionBackground: dark ? '#264f78' : '#add6ff', selectionForeground: document.body.dataset.foreground },
    });
    const fit = new FitAddon.FitAddon();
    terminal.loadAddon(fit);
    terminal.open(root);
    // disableStdin blocks data events; disabling the textarea also prevents the mobile IME.
    if (terminal.textarea) {
      terminal.textarea.disabled = true;
      terminal.textarea.readOnly = true;
      terminal.textarea.tabIndex = -1;
    }
    const resize = function () {
      frame = 0;
      if (disposed) return;
      root.style.width = viewport.clientWidth + 'px';
      const size = fit.proposeDimensions();
      if (size) {
        const cellWidth = Math.max(1, (viewport.clientWidth - 16) / Math.max(1, size.cols));
        const cols = Math.max(2, Math.min(500, Math.max(sourceColumns, size.cols)));
        rowHeight = Math.max(1, (viewport.clientHeight - 16) / Math.max(1, size.rows));
        root.style.width = Math.max(viewport.clientWidth, Math.ceil(cols * cellWidth) + 16) + 'px';
        terminal.resize(cols, Math.max(2, Math.min(80, size.rows)));
      }
    };
    resize();
    onResize = function () {
      if (!frame && !disposed) frame = requestAnimationFrame(resize);
    };
    if (typeof ResizeObserver === 'function') {
      observer = new ResizeObserver(onResize);
      observer.observe(viewport);
    } else window.addEventListener('resize', onResize);
    let touch = null;
    viewport.addEventListener('touchstart', function (event) {
      if (disposed || event.touches.length !== 1) { touch = null; return; }
      const point = event.touches[0];
      touch = { x: point.clientX, y: point.clientY, axis: null, remainder: 0 };
    }, { passive: true });
    viewport.addEventListener('touchmove', function (event) {
      if (disposed || !touch || event.touches.length !== 1) return;
      const point = event.touches[0], dx = point.clientX - touch.x, dy = point.clientY - touch.y;
      if (!touch.axis && Math.max(Math.abs(dx), Math.abs(dy)) >= 8) touch.axis = Math.abs(dx) > Math.abs(dy) ? 'x' : 'y';
      if (touch.axis === 'x') {
        viewport.scrollLeft -= dx;
        event.preventDefault();
      } else if (touch.axis === 'y' && !embedded) {
        touch.remainder -= dy / rowHeight;
        const lines = Math.trunc(touch.remainder);
        if (lines) { terminal.scrollLines(lines); touch.remainder -= lines; }
        event.preventDefault();
      } else return;
      touch.x = point.clientX; touch.y = point.clientY;
    }, { passive: false });
    viewport.addEventListener('touchend', function () { touch = null; }, { passive: true });
    viewport.addEventListener('touchcancel', function () { touch = null; }, { passive: true });
    terminal.write(source + '\x1b[0m', function () {
      if (disposed) return;
      terminal.scrollToTop();
      window.__rikkaRenderStatus = 'ready';
    });
    window.addEventListener('pagehide', dispose, { once: true });
  } catch (error) { fail(error); }
})();
