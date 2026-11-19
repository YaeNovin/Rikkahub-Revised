# JSXGraph 数理绘图与 sigil 法阵

2026-09-24 开发新增，尚未发布。Android 聊天专用渲染，不开放任意脚本，不改动 Ktor/Web UI。math_plot/sigil 本身不加载 p5.js；后续新增的[程序化纹样](procedural-patterns.md)独立使用 p5.js，并复用这里的几何基础和预览流程。

## 入口

最新界面改动见[背景、双指手势和安全区](geometry-background-and-gestures.md)：缩放按钮已移除，法阵适配已有背景设置，函数图保留独立底色。

娱乐几何计算升级见[共享数学曲线计算层](decorative-curve-math.md)：sigil 与 p5 共用 `curve` 公式图层，不改变这里的 JSXGraph 数理接口。

- 数理：`math_plot`；兼容 `math_plan`、`math-plot`、`jsxgraph`，都接受相同的 JSON，不接受 JSXGraph JavaScript/JessieCode。
- 图案：`sigil`；兼容 `magic_circle`、`magic-circle`。
- 可直接使用对应 Markdown 代码块，或调用 `render_content(renderer=..., source=JSON字符串)`。代码块未闭合时不创建预览。
- `list_renderers` 列出两项；指定 `renderer` 可获取示例和完整字段说明。此次接入时目录版本升为 2，后续纹样接入升为 3；旧列表缺少或使用旧版本号时，工具提示模型重新查询。
- 保留源码、复制、源码下载、全屏及当前预览 PNG 保存。滑块和视图调整仅影响当前预览，复制的源码不被修改；重新打开预览/全屏会按源码初始化。

## 数理绘图

```math_plot
{
  "title": "正弦曲线比较",
  "xRange": [-8, 8],
  "yRange": [-4, 4],
  "equalAspect": false,
  "parameters": [{"name": "a", "min": 0, "max": 3, "value": 1, "step": 0.1}],
  "curves": [
    {"expression": "a*sin(x)", "label": "a sin(x)", "color": "#7c3aed"},
    {"expression": "cos(x)", "label": "cos(x)", "color": "#0284c7"}
  ]
}
```

支持三种曲线：

| `type` | 表达式字段 | 自变量 | 默认范围 |
| --- | --- | --- | --- |
| `function`（默认） | `expression` | `x` | 当前可见 x 范围，可用 `range` 限定定义区间 |
| `parametric` | `x`、`y` | `t` | `[0, 2π]`，可指定 `range` |
| `polar` | `r` | `theta` | `[0, 2π]`，可指定 `range` |

例如圆：`{"type":"parametric","x":"2*cos(t)","y":"2*sin(t)"}`；玫瑰线：`{"type":"polar","r":"3*cos(3*theta)"}`。

表达式支持 `+ - * / ^`、括号、科学记数法、常量 `pi`/`PI`/`e`、已声明参数，以及 `sin cos tan asin acos atan atan2 sqrt abs exp ln log log10 floor ceil round min max pow`。角度单位是弧度，`ln`/`log` 是自然对数。乘法必须显式写 `*`，不接受变量赋值、属性访问、数组、用户函数或脚本。

参数省略 `step` 时使用连续滑块，保留精确初始值；显式设置 `step` 且 `value` 在从 `min` 起算的网格上时使用离散步长，否则保留 `value` 并改用连续滑块，在状态行提示。仍拒绝负数、零、超范围步长和非数字。参数支持可选纯文字 `label`（最多 64 字符）。

还支持 `primitives` 中的静态图元：

```json
[
  {"type":"point","at":[1,2],"label":"P"},
  {"type":"vector","from":[0,0],"to":[2,3]},
  {"type":"segment","from":[-2,0],"to":[2,0]},
  {"type":"circle","center":[0,0],"radius":2}
]
```

可组合简单几何/物理示意；所有图元均支持可选纯文字 `label`（最多 32 字符），显示在图例中，不作为 HTML 执行。提供缩放、重置、参数滑块和指针坐标读取；聊天内优先保留对话纵向滚动，拖动交互建议打开全屏。

`jsxgraph` 代码块若不是 JSON 对象开头，仅按 JavaScript 源码高亮展示，不创建失败的 JSON 图形预览。工具接口仍只接收 JSON；这不是原生 JSXGraph/JessieCode 脚本执行入口。以上兼容规则由工具和代码块共享，当前目录版本为 7。

限制：最多 8 条曲线、4 个参数、32 个图元；公式最多 512 字符、256 个 AST 节点和 32 层解析嵌套；坐标/参数范围为 ±1e6，公式常量/求值结果限制为 ±1e12。每次刷新有全局求值预算，每条曲线最多 4096 个采样点。超出预算提示细节可能省略，不无限计算。

这是受限数值采样，不是符号求解器；无定义值分段处理，渐近线测试覆盖 `1/x` 和 `tan(x)`。任意高频函数、极窄特征或特殊不连续函数不保证被采样完整。隐式曲线、三维、积分/微分求解、完整物理模拟和自定义 JSXGraph 元素不在首期范围。

## sigil 图案

动态增强支持共享 `effects`：星尘、粒子拖尾及逐层展开；图层还支持 pulse/draw/sequence/flicker/orbit 编排、对称复制、多色线条和受限自由点列路径，详见[特效参数与 Android 性能策略](decorative-effects-and-performance.md)。

`sigil` 兼容 `name`/`style`/`theme`/`colors`/`backgroundColor`/`lineColor`/`description` 等常见模型字段；`circle` 归一化为 `ring`，`hexagram` 归一化为六芒星。`glow` 可写数字或 `{amount,color,spread,pulse}`。`path`/`freeform` 只接受 2–256 个归一化点 `[x,y]`、`closed` 和纯色 `fill`，不接受 SVG `d`、脚本、资源 URL 或任意节点属性。

```sigil
{
  "title": "六芒星",
  "color": "#8b5cf6",
  "glow": 0.6,
  "animate": false,
  "layers": [
    {"type":"ring","radius":94},
    {"type":"text_ring","radius":87,"text":"A E T H E R · L U X · A E T H E R · L U X ·","fontSize":5,"speed":-3},
    {"type":"ring","radius":80},
    {"type":"ticks","radius":77,"count":48,"length":3},
    {"type":"star","points":6,"step":2,"radius":67,"speed":8},
    {"type":"ring","radius":33},
    {"type":"star","points":6,"step":2,"radius":29,"speed":-12}
  ]
}
```

- 图层类型：圆环 `ring`/`circle`、星形 `star`/`hexagram`、正多边形 `polygon`、刻度 `ticks`、径向线 `rays`、弧线 `arc`、点 `dot`、轨道 `orbit`、环形文字 `text_ring`、路径 `path`/`freeform`。数组顺序即绘制顺序。
- `star` 用顶点数 `points` 与连接步长 `step` 描述拓扑。六芒星 `points=6,step=2` 会生成两个交叠三角形，不是普通六边形或六角轮廓。
- 通用字段：`radius`、十六进制 `color/lineColor`、多色 `colors`、可选 `background` 底衬圆盘、`width`、`opacity`、初始角度 `rotation`、每秒角速度 `speed`、`symmetry`/`mirror`、`lineCap`/`lineJoin` 和逐层 `animation`。星形/多边形/路径可设 `fill`，默认 `none`。
- 最多 24 层、1500 个估算图元；半径 1–96、顶点 3–32、刻度/射线 3–128、文字最多 96 个 UTF-16 代码单元、速度 ±60°/s、发光 0–3。特殊字符是否有字形取决于系统字体；不加载外部字体。
- 默认静态；有非零 `speed` 时显示播放/暂停按钮。`animate=true` 可请求自动播放，但仍受宿主可见性、前后台状态和减少动态效果控制，旋转更新限制为约 30 帧/秒。
- 单个预览保存 PNG 时暂停动画并保存当前预览帧，不导出 GIF/视频。整段聊天导出会从源码默认值重新创建预览并冻结动画，不保留已有预览中的滑块值、缩放或动画进度。离屏/后台暂停，返回继续用户选择的播放状态；释放页面时清理观察器、定时器和绘图对象。

## 实现和安全边界

`DeclarativeRenderSpec` 同时用于工具提交与聊天代码块。Kotlin 检查严格 JSON、字段允许列表、数据类型、颜色与规模，并通过 `MathExpression` 编译数学 AST。浏览器接收 Base64 JSON 数据，仅用固定运算函数求值；模型内容不进入 `eval`、`Function`、HTML 插入或 JSXGraph 脚本解析。

JSXGraph 使用固定版本 **1.13.3** 的离线浏览器构建，选择 MIT 许可，原版权声明及完整许可证随包保留，来源/哈希见[第三方归属](THIRD_PARTY_NOTICES.md#jsxgraph-math-renderer-2026-09-24)。sigil 使用项目自带 SVG 生成器，不加载 JSXGraph。

预览 CSP 仅允许固定本地脚本/样式，禁止网络连接、框架、对象、表单、任意内联脚本和动态代码求值；关闭 WebView 网络/文件/内容访问。全屏由各自控制器管理视口，不注入通用图形平移脚本。原有 HTML 默认源码、手动静态预览规则不变。

## 验证入口

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests '*DeclarativeRenderSpecTest' --tests '*GeometryPreviewTest' --tests '*RenderingToolsTest' --tests '*SessionCapabilitiesToolTest' --tests '*TerminalPreviewTest' --tests '*WebViewPageTest' --tests '*WebViewGestureTest' --tests '*WebViewAssetPolicyTest' --tests '*HtmlRenderPolicyTest'
node scripts/verify-geometry-preview.cjs
```

已通过应用 Kotlin 编译及上述 9 个测试类的 51 项 JVM 测试。新增浏览器脚本通过数值运算/采样检查及 9 个场景：数理浅色、数理深色、数理全屏、特殊曲线与图元及恶意标签、sigil 深色、sigil 浅色、减少动态效果、无效输入、依赖缺失。兼容性回归通过 xterm 6 场景、HTML 策略 6 场景、全屏平移 1 组、SVG 4 场景。新增脚本的 `node --check` 和 `git diff --check` 通过。

浏览器脚本使用 JVM 测试生成的生产 HTML、实际打包的 JSXGraph 和本地资源。报告/截图位于 `app/build/reports/geometry-preview/`。桌面浏览器与 JVM 检查不能替代 Android WebView 多指手势、前后台与离屏暂停、系统减少动画设置映射、导出和 GPU 真机验证；真实模型的工具调用也待联调。本次功能接入未重新构建或安装 QA 包。
