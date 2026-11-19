// Validate actual adb screenshots + matching UI trees from HighlightPalettePreviewActivity.
// UI bounds select comment rows; this does not infer or perform tap coordinates.
const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');
const { PNG } = require('pngjs');
const directory = path.resolve(__dirname, '../app/build/outputs/highlight-palette');
const palettes = {
  dark: ['c678dd', '98c379', 'd19a66', '61afef'],
  light: ['a626a4', '50a14f', 'c18401', '4078f2'],
};
const cases = [
  ['light-theme-dark-background', 'dark', true],
  ['dark-theme-light-background', 'light', true],
  ['dark-theme-dark-background', 'dark', true],
  ['light-theme-light-background', 'light', true],
  ['dark-nowrap', 'dark', false],
  ['long-json-dark', 'dark', false],
  ['python-dark', 'dark', true],
];
function histogram(png, bounds) {
  const counts = new Map();
  for (let y = bounds[1]; y < bounds[3]; y++) for (let x = bounds[0]; x < bounds[2]; x++) {
    const i = 4 * (y * png.width + x);
    const hex = png.data.subarray(i, i + 3).toString('hex');
    counts.set(hex, (counts.get(hex) || 0) + 1);
  }
  return counts;
}
function luminance(hex) {
  return [0, 2, 4].map(i => parseInt(hex.slice(i, i + 2), 16) / 255)
    .map(v => v <= .04045 ? v / 12.92 : ((v + .055) / 1.055) ** 2.4)
    .reduce((sum, v, i) => sum + v * [.2126, .7152, .0722][i], 0);
}
const results = cases.map(([name, theme, checkComment]) => {
  const png = PNG.sync.read(fs.readFileSync(path.join(directory, name + '.png')));
  const xml = fs.readFileSync(path.join(directory, name + '.xml'), 'utf8');
  assert.ok(xml.includes('me.rerere.rikkahub.revised.renderperf'), 'Fixture package, not user chat');
  const colors = histogram(png, [0, Math.floor(png.height * .15), png.width, png.height]);
  const expected = name.includes('json') ? palettes[theme].slice(0, 3) : palettes[theme];
  for (const hex of expected) assert.ok(colors.get(hex) >= 8, `${name}: missing original Highlight #${hex}`);
  let commentContrast;
  if (checkComment) {
    const node = xml.match(/<node\b[^>]*text="[^"]*(?:val fake|# 注释)[^"]*"[^>]*>/)?.[0];
    assert.ok(node, `${name}: comment row must exist in UI tree`);
    const bounds = node.match(/bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"/).slice(1).map(Number);
    assert.ok(bounds[0] >= 0 && bounds[1] >= 0 && bounds[2] <= png.width && bounds[3] <= png.height);
    const entries = [...histogram(png, bounds)].sort((a, b) => b[1] - a[1]);
    const background = theme === 'dark' ? '101218' : 'f9f9ff';
    assert.equal(entries[0][0], background, 'No opaque code backing added');
    // The most common non-background colour is the solid glyph interior, not
    // antialiased edge pixels (which are intentionally partially covered).
    const ink = entries[1];
    assert.ok(ink[1] > 100, 'Enough solid comment pixels');
    const a = luminance(background), b = luminance(ink[0]);
    commentContrast = (Math.max(a, b) + .05) / (Math.min(a, b) + .05);
    assert.ok(commentContrast >= 4.5, `${name}: comment contrast ${commentContrast}`);
  }
  return { name, originalColors: expected, commentContrast, passed: true };
});
fs.writeFileSync(path.join(directory, 'pixel-results.json'), JSON.stringify(results, null, 2));
console.log(`PASS ${results.length} device screenshots; original token RGB, transparent code body, 5 comment-row contrast checks`);
