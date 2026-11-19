# 娱乐渲染：共享数学曲线计算层

2026-09-28，工作区实现。仅扩充公式和几何计算，不引入 Three.js、PixiJS、Maker.js、GPU 材质、物理模拟或任意公式脚本。

## 入口与兼容

`sigil`（SVG）和 `pattern`（p5 Canvas 2D）共同支持 `type: "curve"` 图层。保留原来的径向图元、曼陀罗、玫瑰线、分形和自由点列，不改变旧生成器的参数、种子或采样。

继续通过 `list_renderers` / `render_content` 或对应代码块使用。目录升为版本 7，不新增工具名；只有查询 sigil/pattern 详情时返回公式说明，简短目录不展开全部参数。

新图层与其他图层可混用，复用颜色、线宽、旋转、对称复制、背景、动画、缩放和现有导出入口。公式生成不处理材质、交叉上下关系、变宽描边或真实折射。

## 四类固定公式

| formula | 输入及默认值 | 用途 |
| --- | --- | --- |
| `hypotrochoid` | `fixed:7`（整数 1–24）、`rolling:3`（整数 1–12）、`pen:0.7`（0–2）；fixed 必须大于 rolling | 内旋轮线、花瓣、嵌套封印 |
| `epitrochoid` | 同上，不要求 fixed 大于 rolling | 外旋轮线、圆弧花边、复杂徽记 |
| `lissajous` | `frequencyX:3`、`frequencyY:2`（整数 1–16）、`phase:90`（±360°） | 周期交错曲线、结形视觉；不自动建立穿插拓扑 |
| `harmonic` | `terms` 为 1–6 项 `{frequency,amplitude,phase}` | 多层谐波叠加的径向花饰 |

`formula` 缺省为 hypotrochoid。`radius` 是最终几何的包围圆半径上限（1–96，默认 70），不是摆动圆的物理尺寸；`pen` 是描点与滚动圆圆心的距离除以滚动圆半径。

harmonic 每项默认 frequency=6（1–16）、amplitude=0.15（−0.8–0.8）、phase=0（±360°）。各项振幅绝对值之和不得超过 0.9，确保径向距离为正。省略整个 terms 时默认两项：6 / 0.2 / 0° 与 12 / 0.08 / 0°。不允许空数组。仅接收所选公式的字段，不悄悄忽略无效参数。

### 数学约定

内旋轮线原始坐标：

```text
a = R - r, d = pen * r
x(t) = a*cos(t) + d*cos(a/r*t)
y(t) = a*sin(t) - d*sin(a/r*t)
```

外旋轮线：`a = R + r`，x 的第二项改为负号，y 同上。R/r 先按最大公约数约分，周期取 `2πr`；坐标除以 `a+d` 并乘以 radius，再旋转到图案顶部起始的坐标约定。使用解析包围半径，不依赖采样点的极值，避免采样密度变化导致尺寸跳变。

利萨如曲线：频率先约分，在 `0..2π` 上计算 `x=radius/√2*sin(fx*t+phase)`、`y=radius/√2*sin(fy*t)`，因此所有坐标均在包围圆内。

谐波花饰：`r(t)=radius*(1+Σ ai*cos(fi*t+phasei))/(1+Σ|ai|)`，使用既有顶部起始的极坐标约定。相位输入均为度，求值时转弧度。此处是固定装饰生成器，不是数学求解器或脚本接口。

## 示例

以下同一份 JSON 可以交给 sigil 或 pattern：

```sigil
{
  "title": "谐波刻印",
  "palette": ["#67e8f9", "#fbbf24", "#a78bfa"],
  "glow": 0.3,
  "layers": [
    {"type":"ring","radius":94,"width":0.6},
    {"type":"curve","formula":"harmonic","radius":88,"width":0.7,
     "terms":[{"frequency":12,"amplitude":0.2},{"frequency":6,"amplitude":0.12,"phase":30}]},
    {"type":"curve","formula":"hypotrochoid","fixed":7,"rolling":3,"pen":0.8,"radius":65},
    {"type":"curve","formula":"lissajous","frequencyX":3,"frequencyY":2,"phase":90,"radius":24}
  ]
}
```

## 计算与预算

- Kotlin 编译层和 JS 运行时均验证公式、范围与预算；新公式编译数据包含内部版本 1。
- 采样密度由应用根据约分后的最高频率推导：至少 256 段，每个最高频周期 96 段。模型不能提供 samples、回调、自定义表达式或资源 URL。
- 末点使用首点副本，避免数值误差产生闭合缝隙；数学周期另由数值回归验证，不能用强行闭合掩盖周期错误。
- 新 curve 图层对称复制后的总顶点上限为 24000；p5 原有的全部图层基础顶点上限 24000 也继续生效。旧图层原有预算与采样不变，此次不宣称修复全部旧图层的复制预算问题。
- SVG 每个图层仅生成一次曲线，再复制路径；p5 在几何构建时生成，沿用 Path2D 缓存。旋转、缩放和脉冲不会每帧重新计算曲线。
- 不新增依赖下载、后端、工具开关或默认动画。

## 验证入口

- `DecorativeCurveSpecTest`：四类公式、共享编译结果、比例约分、参数范围、注入拒绝、复制预算与混合图层预算。
- `DecorativeCurvePreviewTest`：生产 HTML 和编译数据样例，保持离线 CSP、代码块和背景入口。
- `RenderingToolsTest`：按需说明、现有工具接受与拒绝边界。
- `scripts/verify-decorative-curves.cjs`：解析公式对照、全部整数比例、有限坐标、闭合、包围半径、两个后端及桌面浏览器显示／生命周期／导出准备。

桌面浏览器通过不等于 Android WebView、GPU 或全部手机组合通过。此轮先安装的 QA 包是升级前的快照，不能用其启动成功来证明新公式已在设备上验证。

### 本轮结果

- 升级前 QA `2.4.8-revised.9-20260928-195422` 构建与 adb 覆盖安装成功；版本、设备 base.apk 哈希、冷启动和该进程崩溃缓冲核验完成。保留应用数据，未发送模型请求。
- 新计算层及既有渲染、工具目录定向 JVM 测试：52 项通过，无失败、错误、跳过。最终源码重新编译复测通过。项目没有 `testQaUnitTest` 任务，使用已有 `:app:testDebugUnitTest`，不为测试修改构建配置。
- 新公式数值回归：1754 组公式／比例用例通过，包含全部允许的摆线整数比例、极限 pen 值、利萨如整数频率组合、解析公式对照及预算拒绝。
- 桌面 Edge / Playwright：新公式 14 个场景通过；旧 p5 16 个场景与 JSXGraph/sigil 10 个场景通过，共 40 个。覆盖非空像素、共享路径、手势、透明背景、动画暂停、减少动态、导出准备与生命周期；未覆盖所有既有动画模式组合。
- 已查看新 sigil 内旋轮线与 p5 谐波花饰截图；报告及截图位于 `app/build/reports/decorative-curves/`。安装快照及记录位于 `app/build/outputs/qa-installed-20260928-195422/`。
- `git diff --check`、相关 JavaScript 语法检查通过。新公式尚未重新打包覆盖手机，因此不宣称其完成 Android 真机渲染验证。
