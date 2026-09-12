import { Graphviz } from './graphviz.min.js';
self.onmessage = async function(event) {
  try {
    const graphviz = await Graphviz.load();
    const svg = graphviz.dot(event.data);
    if (!svg || svg.length > 8 * 1024 * 1024) throw new Error('Graphviz 输出为空或过大');
    self.postMessage({ svg });
  } catch (error) {
    self.postMessage({ error: String(error.message || error).slice(0, 240) });
  }
};
