# 渲染工具与 HTML 默认安全策略

2026-09-23 开发工作区新增，尚未发布。范围为 Android 原生聊天及其预览，不修改 Ktor/Web UI 或加入 PTY。后续新增的 p5.js 仅用于[专用程序化纹样](procedural-patterns.md)，不开放 HTML 脚本执行。

## 用户可见变化

- `html` 代码块默认显示源码，不创建内容预览。用户点击预览按钮后，显示经过过滤的静态 HTML。
- 裸 HTML 文档/块级片段也显示为 HTML 源码块；行内 HTML 标签按文字显示。普通 Markdown 标题、表格、列表、图片、链接等继续按原有组件显示。
- 用户打开过某段 HTML，不等于授权后续生成内容。内容变化或页面重新打开后，HTML 回到源码模式。
- 静态 HTML 不是网页应用运行环境：作者脚本、事件属性、iframe/srcdoc、object/embed、表单、meta 跳转、外部资源和可执行链接均不保留。不能通过模型参数开启脚本。
- SVG 保留专用图形入口，但经过同一静态内容过滤；保留常见路径、文字、渐变、滤镜和视觉动画，移除脚本、事件、foreignObject、外部资源和可修改链接/事件的动画。
- 仍可复制/保存原始源码、切换预览、打开全屏。源码不被安全过滤结果覆盖；不支持的 HTML 应用逻辑应改用专用图表格式，而不是在 HTML 中初始化脚本库。
- 既有任意 HTML 脚本依赖自动补齐停止生效；`HtmlPreviewDependencies` 只保留独立辅助逻辑，不再是聊天/全屏 HTML 运行入口。旧预览缓存使用旧目录，升级后不再读取；聊天消息未删除，重新打开即可按新规则生成缓存。

## 模型接口

沿用现有 `Tool`、输入 schema、执行结果与消息工具 UI，不新建供应商协议。
具有工具调用能力的模型会收到两个短声明，并能在 `get_session_capabilities` 的 `availableTools` 与 `rendering` 中发现它们。
不支持工具调用的模型不注册这些接口，仍可输出已有 Markdown/图形代码块。

| 接口 | 参数 | 结果 |
| --- | --- | --- |
| `list_renderers` | 无参数 / 可选 `renderer` | 默认返回紧凑列表；指定名称或别名时返回格式、示例、别名、长度上限及限制 |
| `render_content` | 必填 `renderer`、`source`；可选 `language` | 校验后，自动预览渲染器直接显示在助手正文；HTML 仍仅在工具卡中提供手动静态预览；`language` 仅供代码高亮使用 |

调用示例：

```text
get_session_capabilities({})
list_renderers({})
list_renderers({"renderer":"mermaid"})
render_content({"renderer":"mermaid","source":"flowchart LR; A[请求] --> B[响应]"})
```

`source` 是原始格式内容，不额外包裹代码围栏。工具成功返回 `status=accepted`、规范化渲染器 ID、展示方式、字符数和 `runtime_verified=false`，不重复回传整段源码以节省上下文。接受请求不代表设备已经完成绘制；实际语法/依赖错误仍由原有预览显示。

自动预览结果与思考步骤分离：工具调用记录仍保留在消息数据中，便于续作、重放和导出；已接受且 `display=chat_preview` 的视觉/文本产物回到助手正文显示。等待、失败及 `manual_static_preview` 不会自动进入正文，避免把未完成或危险的 HTML 当作普通回答执行。

HTML 的展示方式固定为 `manual_static_preview`；即使工具调用中传入 `allow_scripts`，也不会取得执行权限。无效类型、未知渲染器、空输入、超长输入返回错误，不静默截断或切换到其他渲染器。

统一目录现为版本 6，包含 21 类：Markdown（含表格/图片/GIF/链接/任务列表）、LaTeX、代码高亮、Diff、HTML、SVG、Mermaid、ECharts、ABC、简谱、Leaflet/GeoJSON、铁路图/EBNF、WaveDrom、Graphviz、Vega/Vega-Lite、SMILES、MusicXML、只读 xterm、JSXGraph 数理绘图 `math_plot`（兼容 `math_plan`）、法阵图案 `sigil` 及 p5.js 程序化纹样 `pattern`。后三项使用受限声明式 JSON，格式及交互限制见[数理绘图与 sigil](math-plot-and-sigil.md)和[程序化纹样](procedural-patterns.md)。图片生成不属于渲染器，继续使用独立生图工具与既有确认规则。法阵/纹样共享动态特效参数，见[特效说明](decorative-effects-and-performance.md)。目录 6 同时包含多色调色板、镜像对称、受限自由路径、统一动画编排和对象形式的高级发光参数。

多数输入上限为 131072 个 UTF-16 代码单元；LaTeX 为 8000、SMILES 为 4000、WaveDrom/Graphviz 为 32000，math_plot/sigil/pattern 为 32768，另保留 MusicXML 最多 200 小节、Graphviz 布局超时与 xterm 滚动历史等原有限制。实际设备是否支持 WASM/Worker、用户公式显示设置和格式子集仍影响展示，目录不承诺任意 WebView 版本均可运行。

## 安全边界与实现

原有路径是聊天 HTML/SVG → `HighlightCodeBlock` 默认预览 → 启用 JavaScript 的 WebView；HTML/SVG 作者代码可以到达页面。整条消息预览则存在 `markdown-it html:true`、宽松 Mermaid 和信任公式选项等独立入口。

本次在共享边界处理，而不是只匹配 `<script>`：

- `HtmlRenderPolicy.kt`：按 Markdown 语法区分源码和作者 HTML；HTML 源码保真；静态标签允许列表、属性/URL 过滤和 CSP。
- `HighlightCodeBlock.kt`、`Markdown.kt`、`MarkdownNew.kt`、`SimpleHtmlBlock.kt`：默认源码和手动静态预览；专用 SVG/图表仍走既有组件。
- `static-preview.js`：只负责应用自带的 SVG 尺寸归一化/渲染状态；作者内联脚本无权限。全屏背景脚本迁到固定本地 `fullscreen-background.js`，不放宽 CSP。
- `mark.html`：禁用作者 HTML、Mermaid strict、KaTeX trust=false；Markdown 与数学/图形模板仍正常工作。
- `WebView.kt`：内容预览拒绝 JS alert/confirm/prompt，作为额外防护；普通 URL 浏览页的处理不变。
- `WebViewPage.kt`、`WebViewContentCache.kt`：静态 HTML/SVG 与 xterm 禁止网络加载，取消旧 HTML 依赖恢复入口，隔离旧可执行预览缓存。
- `InteractiveCodeBlock.kt`：地图弹出标签使用 textContent；ECharts 提示框在所有尺寸及嵌套配置强制使用 richText，不把标签当 HTML 插入。专用地图显式 tileUrl 等原有受控用途保留，不宣称所有渲染都禁止网络。
- `RendererCatalog.kt`、`RenderingTools.kt`、`GenerationHandler.kt`、`SessionCapabilitiesTool.kt`、`RenderingToolUI.kt`、`ToolUI.kt`：统一目录、参数校验、注册、能力发现与 UI 展示；交互图表和 xterm 别名复用同一目录。

## 验证

结果：`fixed`（源码、JVM 与桌面浏览器验证范围）。按照安全修复技能完成独立边界调查与一次只读候选复核；复核没有报告新的具体绕过或回归。评审子任务的本机 Gradle 启动被缓存权限阻止，主任务使用既有 JDK/Gradle 缓存完成了下列验证，没有把评审受阻当作测试通过。

最终应用 Kotlin 编译及 110 项 JVM 测试通过；`git diff --check` 与新增/调整脚本的 `node --check` 通过。7 组浏览器回归通过：HTML 安全策略 6 场景、旧 HTML 依赖隔离 9 场景、SVG 4 场景、整条消息加载 5 场景、地图 32 场景、全屏交互 1 组、xterm 6 场景。

中间轮次的失败已逐项处理：旧的 HTML 自动执行断言更新为源码/静态策略；代码块定位排除新增的 HTML 源码块；全屏测试路由加载新的本地背景脚本；新增的行内代码保真回归修复了重复转义。宽屏 ECharts 提示框曾在恶意样例中请求外部图片，修复后该相同触发不再产生请求或脚本执行。最终恶意样例无弹窗、无外部请求、无攻击标记；正常 SVG/地图/Markdown/全屏/终端对照仍通过。

核心 JVM 用例：`HtmlRenderPolicyTest`、`RenderingToolsTest`、`SessionCapabilitiesToolTest`，并回归 SVG、图表、Markdown、xterm、WebView 资产和缓存等测试。浏览器脚本均使用生产 HTML 和打包的本地资源：

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests '*HtmlRenderPolicyTest' --tests '*RenderingToolsTest' --tests '*SessionCapabilitiesToolTest' --tests '*RawSvgTest' --tests '*TerminalPreviewTest' --tests '*InteractiveCodeBlockTest' --tests '*HtmlPreviewDependenciesTest' --tests '*HtmlLoadingRegressionTest' --tests '*WebRendererRegressionTest' --tests '*AdditionalRendererTest' --tests '*DiagramSourceTest' --tests '*HtmlImageContentTest' --tests '*GitHubRepositoryLinksTest' --tests '*GfmModernExtensionsTest' --tests '*WebViewPageTest' --tests '*WebViewContentCacheTest' --tests '*WebViewAssetPolicyTest'
node scripts/verify-html-policy.cjs
node scripts/verify-html-dependencies.cjs
node scripts/verify-svg-renderer.cjs
node scripts/verify-html-loading.cjs
node scripts/verify-geographic-renderer.cjs
node scripts/verify-fullscreen-pan.cjs
node scripts/verify-terminal-preview.cjs
```

恶意样例覆盖脚本、编码后的 javascript 链接、事件属性、iframe/srcdoc、表单、meta refresh、SVG foreignObject/链接变更动画、地图标签和图表提示框。浏览器检查记录弹窗、未捕获错误和外部请求；还主动插入脚本/事件验证静态页面 CSP。正常对照包括 SVG 渐变/滤镜/动画、Markdown/公式/Mermaid、地图、全屏及 xterm。

报告与合成样例位于 `app/build/reports/html-policy/` 和各原有回归目录，不包含用户聊天数据。未调用付费模型，未构建/安装新 QA APK。Android WebView 资产拦截、原生弹窗回调、触摸/全屏返回、工具卡实际展示与系统版本兼容仍需后续真机验证。
