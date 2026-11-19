// Fixed MaterialSettingsPreviewActivity captures. No production conversation data.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { PNG } = require('pngjs');
const dir = path.resolve(__dirname, '../app/build/outputs/material-settings');
const xml = name => fs.readFileSync(path.join(dir, name + '.xml'), 'utf8');
const png = name => PNG.sync.read(fs.readFileSync(path.join(dir, name + '.png')));
const node = (source, text) => source.match(new RegExp('<node[^>]*text="' + text + '"[^>]*>'))?.[0];
const bounds = n => n.match(/bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"/).slice(1).map(Number);
const top = xml('settings-top'), scrolled = xml('settings-scrolled');
assert.ok(node(top, '允许聊天模型调用生图'));
assert.ok(node(top, '基础选项') && node(top, '高级选项'));
assert.ok(!node(scrolled, '允许聊天模型调用生图'), 'Header scrolls with parameters');
assert.ok(!node(scrolled, '图片生成设置'), 'Title is not a pinned height cost');
for (const name of ['settings-bottom', 'settings-advanced-bottom', 'settings-ime-bottom']) {
  const source = xml(name), button = node(source, '确认全部设置');
  assert.ok(button, `${name}: footer reachable`);
  const viewport = source.match(/<node[^>]*scrollable="true"[^>]*>/)?.[0];
  const b = bounds(button), v = bounds(viewport);
  assert.ok(b[1] >= v[1] && b[3] <= v[3], `${name}: footer fully within viewport`);
}
assert.ok(node(xml('settings-advanced-bottom'), '图片水印'));
assert.ok(xml('settings-ime-bottom').includes('fixed'), 'Edited state survives scroll with IME');
const before = png('surfaces-blur0'), after = png('surfaces-blur20');
assert.equal(before.width, after.width); assert.equal(before.height, after.height);
const composerNode = node(xml('surfaces-blur20'), 'ACRYLIC · 输入区前景');
const b = bounds(composerNode);
// The test composer is 150dp high. Sample a row below the text, away from borders.
const y = Math.round(b[3] + (b[3] - b[1]) * 1.5);
function deviation(image, y) {
  const values = [];
  for (let x = Math.round(image.width * .2); x < image.width * .8; x++) {
    const i = (y * image.width + x) * 4;
    values.push(.2126 * image.data[i] + .7152 * image.data[i + 1] + .0722 * image.data[i + 2]);
  }
  const mean = values.reduce((s, v) => s + v, 0) / values.length;
  return Math.sqrt(values.reduce((s, v) => s + (v - mean) ** 2, 0) / values.length);
}
const rawContrast = deviation(before, y), blurredContrast = deviation(after, y);
assert.ok(rawContrast > 15, 'Unblurred control shows striped scene through the composer');
assert.ok(blurredContrast < rawContrast * .45, 'Acrylic blurs the scene, not only its tint');
const micaNode = node(xml('surfaces-blur20'), '云母侧栏 · 独立底层');
const mica = bounds(micaNode);
const micaRow = Math.round(mica[1] - (mica[3] - mica[1]) * .35);
assert.ok(deviation(after, micaRow) < 3, 'Opaque Mica foundation hides stripes');
const search = xml('search-acrylic');
assert.ok(search.includes('android.widget.EditText') && search.includes('输入模型名称搜索'));
const result = { passed: true, headerScrolls: true, basicAdvancedAndImeFooterReachable: true,
  rawContrast, blurredContrast, blurRatio: blurredContrast / rawContrast, opaqueMica: true };
fs.writeFileSync(path.join(dir, 'verification.json'), JSON.stringify(result, null, 2));
console.log(JSON.stringify(result));
