/* Classic HTML compatibility facade for @hpcc-js/wasm-graphviz. The actual
 * WASM bundle stays an ES module and is loaded only when a page calls it. */
(function (global) {
  var modulePromise;
  function load() {
    if (!modulePromise) modulePromise = import('./graphviz.min.js');
    return modulePromise.then(function (module) { return module.Graphviz.load(); });
  }
  global.hpccWasm = global.hpccWasm || {};
  global.hpccWasm.graphviz = global.hpccWasm.graphviz || {
    layout: function (dot, format, engine, options) {
      return load().then(function (graphviz) {
        return graphviz.layout(String(dot || ''), format || 'svg', engine || 'dot', options || {});
      });
    },
    dot: function (dot, options) {
      return load().then(function (graphviz) { return graphviz.dot(String(dot || ''), options || {}); });
    },
    load: load,
  };
})(window);
