# 法阵、纹样动态特效与 Android 渲染

开发工作区新增。`sigil` 与 `pattern` 共用星尘、粒子拖尾、逐层展开和原有旋转；`math_plot` 不加载特效控制器，不加入物理或教学模拟。目录版本升为 4，仍为 21 类渲染器，模型通过既有 `list_renderers` / `render_content` 发现和使用，不增加独立供应商工具协议。

## 使用方式

```sigil
{
  "title": "星尘法阵",
  "color": "#a78bfa",
  "glow": 0.4,
  "animate": true,
  "effects": {
    "stardust": {"count": 64, "radius": 95, "size": 1, "speed": 18, "seed": 42},
    "trail": {"samples": 5, "duration": 0.6, "opacity": 0.35},
    "reveal": {"duration": 0.8, "stagger": 0.15}
  },
  "layers": [
    {"type":"ring","radius":94},
    {"type":"ticks","radius":86,"count":60,"speed":-8},
    {"type":"star","points":6,"step":2,"radius":68,"speed":18},
    {"type":"ring","radius":34}
  ]
}
```

相同 `effects` 对象可加到 `pattern`：曼陀罗、玫瑰线、分形与共享径向图层都会按图层顺序展开。保持 `animate` 默认 false；设置 true 请求自动播放，用户仍可暂停。

| 字段 | 支持范围和默认值 |
| --- | --- |
| `stardust.count` | 1–128，默认 48；自适应降质可减少实际显示数量 |
| `stardust.color` | 十六进制颜色，默认 `#a78bfa` |
| `stardust.size` / `radius` | 0.3–2.5 / 5–100，默认 1 / 95，归一化坐标 |
| `stardust.speed` / `seed` | ±60°/s / 0–2147483647，默认 12 / 42 |
| `trail.samples` | 1–8，默认 5；仅跟随星尘，不对整幅图形重复叠印 |
| `trail.duration` / `opacity` | 0.1–1.5 秒 / 0.05–0.6，默认 0.6 / 0.35；需要同时声明 stardust |
| `reveal.duration` / `stagger` | 每层展开 0.2–3 秒 / 层间延迟 0–0.5 秒，默认 0.8 / 0.15 |

展开是逐层淡入并由 72% 放大到完整大小，不是逐笔绘制路径。不循环展开；只有展开、没有旋转/星尘时，完成后停止刷新，再次播放可重播。重置恢复初始时间/视图，播放中重置会重新展开，静止时重置显示完整图案。

星尘使用固定种子和绝对时间计算位置，拖尾通过有限历史位置计算，不维护不断增长的轨迹。默认静态和减少动态效果时仍显示图案；减少动态效果禁用播放并去掉粒子拖尾。离屏/后台不累计动画时间，恢复后不补算长时间历史。

## Android 硬件与性能策略

- Manifest 显式启用硬件加速。正常 WebView 使用 `LAYER_TYPE_NONE` 继承窗口加速，而非强制创建额外的整屏硬件纹理；离屏位图导出保留 `LAYER_TYPE_SOFTWARE`。这表明窗口/视图支持加速，不保证 WebView 内每一个 Canvas/SVG 操作均由 GPU 执行。
- 宿主在加载完成、可见性和生命周期变化时传入自身观测到的硬件加速、低内存设备、省电模式、系统动画时长为零等布尔值。没有新增 JS 原生桥，模型不能控制硬件开关或预算。
- `pattern` 用 p5 实例管理画布；路径在初始化/换种子时编译成 `Path2D`，播放与缩放时复用，不逐帧创建 p5 颜色对象或遍历所有顶点。状态文字仅变化时更新。
- 星尘复用一张 32×32 光点贴图；Canvas 覆盖层不接管触摸。后备画布最多约 120 万像素，主纹样最多 200 万像素；低质模式减小尺寸及像素密度。不使用 `getImageData`、无限轨迹、逐帧创建画布、额外 p5 循环或粒子 DOM 节点。
- 法阵保留 SVG。最高目标约 30 fps；持续重帧降为约 24 / 15 fps，同时减少粒子及拖尾数量，最低档关闭纹样/法阵的模糊发光。低内存设备、省电模式、窗口未硬件加速直接使用低档。自动降档在本次预览内不反复升降，避免画质抖动。
- 单个预览共用一个动画时钟。页面不可见、宿主未激活、后台、减少动态效果或导出时停止；释放时清理观察器、帧回调、p5 实例及星尘画布。

这些约束优先降低绘制压力，但不承诺所有 Android 设备、任意复杂图案或多个同时可见 WebView 恒定 30 fps。`meanDrawMs` 是 JS 提交绘制命令的耗时，不等于完整 GPU 耗时或最终显示延迟。

## 导出与旧预览

单个已播放预览导出冻结当前画面（包含星尘和尾迹）；整段聊天导出从源码重建，如果展开从未播放，则显示完整图案，避免导出空白首帧。仍然仅导出静态 PNG，不新增 GIF/视频。

预览缓存目录升级为 `webview_content_safe_v3`，避免旧 HTML 的依赖清单/CSP 与新版几何控制器不一致。旧预览链接可能提示过期，返回原对话重新打开即可；旧缓存未主动删除，聊天源码不变。

## 验证入口

JVM：`DecorativeEffectsTest` 验证共享参数、预算拒绝、默认静态、屏幕/导出图层策略；同时运行现有几何、纹样、工具、HTML、缓存和手势测试。

浏览器：先用 JVM 测试生成生产 HTML，再运行 `node scripts/verify-decorative-effects.cjs`。覆盖星尘实际像素、拖尾、展开顺序/完成停止、缓存路径不变、暂停/恢复、减少动态效果、省电降档、导出首帧和资源释放。报告为 `app/build/reports/decorative-effects/browser-results.json`。

真机：`DecorativeEffectsDeviceTest` 用真实共享 Compose/WebView 组件分别播放法阵与纹样，每个固定窗口 6 秒；记录 `gfxinfo`、`meminfo`、截图和只读渲染计数。随后验证原生 Activity 暂停/恢复与导出冻结。仅调用渲染器，不调用模型或修改聊天数据。

```powershell
.\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest -Prikkahub.performanceProbe=true
adb -s SERIAL install -r app/build/outputs/apk/debug/app-arm64-v8a-debug.apk
adb -s SERIAL install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s SERIAL shell am instrument -w -r -e class me.rerere.rikkahub.DecorativeEffectsDeviceTest me.rerere.rikkahub.revised.renderperf.test/androidx.test.runner.AndroidJUnitRunner
```

`rikkahub.performanceProbe=true` 仅将 Debug 应用隔离到 `me.rerere.rikkahub.revised.renderperf`，默认 Debug、QA 和正式版包名不变。首次尝试更新旧 Debug 因签名不同而失败，未卸载该应用；改用此专用包，保留既有应用与数据。设备报告存于专用包外部文件目录的 `decorative-effects/<renderer>-<timestamp>/`。部署前应确认 APK 元数据中的包名，避免把普通 Debug 包当作隔离包。

## 已完成的源码与浏览器验证

- 应用及测试 APK 编译通过；73 项 JVM 测试通过（包含缓存兼容性）。
- 浏览器共 49 个场景/组通过：新增特效 8、纹样 15、JSXGraph/sigil 9、xterm 6、HTML 安全策略 6、SVG 4、全屏平移 1。
- `node --check` 与 `git diff --check` 通过。SVG 回归覆盖旧截图时遇到权限错误，经授权重跑后通过。
- 特效浏览器测试中的硬件/省电字段是模拟宿主输入，仅验证策略，不是 Android GPU 证据。

真机选择 vivo V2301A、Android 16、Google WebView 150.0.7871.181，设备处于唤醒/充电状态。初次 Compose 测试规则没有进入测试主体；改为直接 Activity 挂载、带 10 秒上限的主线程回调后，运行器仍未返回有效采样报告，完整阻塞原因未确认。有效性能采样数为 **0**，没有可报告的真机 FPS、jank 百分比、CPU/GPU 耗时或内存对照，不计为渲染失败或测试通过。线程诊断受设备限制（debuggerd 需要 root，JDWP 握手失败），没有提升设备权限。

最终停止无有效产物的测试，并移除仅本次新建的 renderperf 应用及测试组件。专用测试 APK/源码保留在工作区，可重新安装；现有 QA、正式版和原 Debug 应用未被覆盖或卸载。后续最小验证步骤是在可完成该定向测试的设备上运行同一 6 秒场景，再读取对应 `report.json` 与 gfxinfo；如原生帧统计不足，再对该场景采集 Perfetto。未测量前不能宣称 Android 真机“不卡顿”或所有 Canvas 操作都经过 GPU。
