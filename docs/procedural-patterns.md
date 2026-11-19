# p5.js 程序化纹样

开发工作区新增，尚未发布。本期仅实现娱乐向的曼陀罗、玫瑰线与分形装饰图案，不加入教学演示、算法可视化、数学求解或物理模拟。原有 JSXGraph 函数绘图、SVG 法阵保持独立，不迁移到 p5.js。

后续计算层升级：新增 sigil/p5 共用的 `curve` 图层，包含内旋轮线、外旋轮线、利萨如与谐波花饰，详见[共享数学曲线计算层](decorative-curve-math.md)。不新增渲染引擎。

动态增强：支持共享 `effects`、pulse/draw/sequence/flicker/orbit 编排、对称复制、多色线条、分层发光和受限自由点列路径，并缓存 Path2D、按设备环境/帧耗时降质，详见[特效与 Android 性能](decorative-effects-and-performance.md)。

## 入口与示例

新增渲染器 `pattern`，别名为 `generative_art`、`p5_pattern`、`p5-pattern`。当前目录为版本 7，共 21 类渲染器。模型沿用 `list_renderers({"renderer":"pattern"})` 查询格式，通过 `render_content` 提交原始 JSON；也可输出对应的 Markdown 代码块。未闭合、过长的代码块不启动渲染。

```pattern
{
  "title": "星瓣曼陀罗",
  "seed": 42,
  "background": "#101426",
  "palette": ["#a78bfa", "#22d3ee", "#f472b6", "#fbbf24"],
  "glow": 0.4,
  "animate": false,
  "layers": [
    {"type": "mandala", "petals": 12, "rings": 4, "radius": 86, "speed": 5},
    {"type": "rose", "numerator": 5, "radius": 32, "color": "#fbbf24", "speed": -8},
    {"type": "ring", "radius": 94}
  ]
}
```

绘图只接受声明式 JSON，不接受 p5.js JavaScript、用户函数、着色器、自定义递归规则或素材 URL。`p5` / `javascript` 代码块仍是普通源码，不会执行。p5.js 是专用纹样的内部渲染引擎，不是通用网页或脚本执行入口。

## 图层与参数

全局字段：`title/name`、`description`、`background/backgroundColor`、`color/lineColor`、`palette/colors`（1–12 个十六进制颜色）、`seed`（0–2147483647，默认 42）、`glow`（0–3 或 `{amount,color,spread,pulse}`）、`animation`、`animate`（默认 false）、`layers`（1–12 层）。数组顺序即叠放顺序。

共同图层字段：`radius`（1–96）、`color/lineColor/colors`（十六进制）、`width`（0.25–5）、`opacity`（0.05–1）、`rotation`（初始角度，±360°）、`speed`（角速度，±60°/s）、`lineCap/lineJoin`、`symmetry`（1–12）、`mirror`、`animation`。坐标以中心为原点、半径 100 左右的归一化空间表示，缩放不改源数据。

| 图层 | 参数与用途 |
| --- | --- |
| `mandala` | `petals` 3–32（默认 12）、`rings` 1–6（默认 4）、`innerRadius` 0–radius×0.8（默认 radius×0.12）、`petalWidth` 0.1–1.5（默认 0.85）；生成多层径向花瓣 |
| `rose` | `numerator` 1–24（默认 5）、`denominator` 1–12（默认 1）、`phase` ±360°（默认 0）；生成完整闭合的有理比例玫瑰线 |
| `fractal` | `variant` 为 `koch`（雪花，默认）、`sierpinski`（三角纹样）或 `tree`（枝蔓）；`depth` 默认 4，雪花 0–5，其余 0–7；树形额外接受 `angle` 10–65°（默认 28）、`shrink` 0.45–0.78（默认 0.7） |
| `ring` / `star` / `polygon` / `ticks` / `rays` | 复用 [sigil 图层参数](math-plot-and-sigil.md#sigil-图案)及验证规则；`star` 的 `points=6,step=2` 仍生成两个三角形 |
| `path` / `freeform` | 2–256 个受限点列、`closed`、纯色 `fill`，支持 `symmetry`/`mirror`；不接受 SVG 路径或代码 |

纹样首期不支持 `text_ring`，环形文字仍用 sigil。曼陀罗各圈轮换配色；图层显式 `color` 覆盖该层的全局配色。玫瑰线使用 `r=radius*cos(numerator/denominator*theta+phase)`，`phase` 输入为角度，内部转换为弧度；分子分母自动约分，不把比例参数误称为实际花瓣数量。

分形示例：

```pattern
{
  "title": "分形雪花",
  "layers": [
    {"type":"fractal","variant":"koch","depth":4,"radius":82,"color":"#22d3ee"},
    {"type":"ring","radius":94,"color":"#a78bfa"}
  ]
}
```

同一输入和种子复现同一几何。种子仅控制曼陀罗每圈的偏移与宽度变化，不影响玫瑰线或分形；动画帧不会推进随机状态。分形是固定装饰预设，不提供公式讲解、单步算法或科学仿真接口。

## 交互与资源约束

最新界面改动见[背景、双指手势和安全区](geometry-background-and-gestures.md)：缩放改用双指手势，纹样透明状态跟随已有背景设置，关闭时恢复源码底色；全屏操作避让系统栏和摄像头区域。

- 复用现有源码、复制、源码下载、全屏和 PNG 保存入口。
- 提供放大/缩小、重置；存在曼陀罗时提供“变换”，以递增种子生成变体。当前种子显示在状态栏，重置恢复源种子、视图和初始旋转角度。
- 有非零角速度时提供播放/暂停；默认静态，`animate=true` 可请求播放。离屏、后台或宿主未激活时停止绘制；系统浏览器减少动态效果时禁用播放。降低动态效果后不会自动恢复动画，需用户重新播放。
- p5 使用实例模式、Canvas 2D 和 `noLoop()`；共用宿主动画时钟按需重绘，不额外运行 p5 循环。最高约 30 fps，按实际绘制耗时降频。每帧复用已生成的路径，不重复递归生成几何。
- 每次输入最多 32768 字符、32 层 JSON 嵌套；整个纹样最多 24000 顶点，超预算报错，不静默砍掉图层。运行时也检查顶点/路径预算。
- 逻辑画布最长边 1024，像素密度不超过 2，总后备像素不超过 200 万。该上限用于控制内存，不承诺所有设备都有相同帧率。
- 页面释放时取消动画、观察器和 p5 实例；加载尚未完成时释放也不会再次创建画布。

预览内的变化不改写消息源码。打开全屏/重新打开预览会按源码初始化。单预览 PNG 保存冻结当前画面；整段聊天导出从源码默认值重建，不保留其他预览中的临时种子、视图或动画进度。不提供 GIF、视频导出或透明背景导出。

## 与现有几何能力的复用

- Kotlin：`compileDeclarativeRenderSpec` 统一处理数学、法阵、纹样；`compileRadialLayer` 共用法阵与纹样的径向图层定义及约束。
- JavaScript：`geometry-core.js` 提供极坐标转换、星形拓扑、径向线段。JSXGraph 的极坐标采样、SVG 法阵和 p5 纹样共同使用它；渲染方式仍各自独立。
- `GeometryPreview` 统一承载安全数据、聊天尺寸、全屏和导出；`geometry-preview.js` 共用播放、可见性、减少动态效果及资源释放流程。
- `pattern-geometry.js` 只生成有界几何；`pattern-preview.js` 仅用应用固定代码调用 p5 绘制。模型无法传入代码进入 `eval`、`Function` 或 HTML。

固定版本 **p5.js 1.11.11** 从 npm 分发镜像取得，离线打包；此前分享的临时文件为 1.9.0，不作为本次运行时。仅纹样加载 p5，数学和法阵预览不加载它。原有 HTML 源码优先/静态预览策略保持不变；预览继续拒绝外网、作者脚本、文件访问和原生桥接。版本、来源、哈希和 LGPL-2.1 许可见[第三方说明](THIRD_PARTY_NOTICES.md#p5js-decorative-patterns)。

## 验证

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests '*PatternRenderSpecTest' --tests '*PatternPreviewTest' --tests '*DeclarativeRenderSpecTest' --tests '*GeometryPreviewTest' --tests '*RenderingToolsTest' --tests '*SessionCapabilitiesToolTest' --tests '*TerminalPreviewTest' --tests '*WebViewPageTest' --tests '*WebViewGestureTest' --tests '*WebViewAssetPolicyTest' --tests '*HtmlRenderPolicyTest'
node scripts/verify-pattern-preview.cjs
node scripts/verify-geometry-preview.cjs
```

应用 Kotlin 编译及上述 62 项 JVM 测试通过。新增脚本通过共享几何、玫瑰线闭合与极坐标一致性、种子确定性、分形边界和预算检查；15 个真实 p5 浏览器场景通过，包含绘制、深浅色、全屏、重置、变换、动画、减少动态效果、缺少 ResizeObserver 的降级、无效数据、库缺失及加载完成前释放。

兼容性回归通过 JSXGraph/sigil 9 场景、xterm 6 场景、HTML 策略 6 场景、全屏平移 1 组、SVG 4 场景（共 26 场景/组）。SVG 截图覆盖曾因旧报告文件权限失败，经授权重跑通过；未把权限失败视为测试通过。新增/改动脚本的语法检查及 `git diff --check` 通过。

浏览器测试使用生产 HTML 和实际打包的 p5.js，不依赖 CDN。读取页面截图验证非空画面和交互结果；几何数据做精确比较，同一画面重绘允许很小的抗锯齿像素差异。截图和报告位于 `app/build/reports/pattern-preview/`。

桌面浏览器/JVM 测试不等于 Android 真机验证。原生 WebView 的 GPU、触摸、前后台映射、减少动画设置、原生 PNG/整段聊天导出，以及实际模型调用仍待联调。本轮不自动构建/安装 QA 包。
