# WebView 与图形渲染维护说明

## 本轮优化

- HTML 加载记录跟随原生 WebView 实例，避免聊天滚出屏幕后重新进入时，保留的 Compose 状态跳过加载而出现空白。刷新请求统一由视图更新处理，避免重复加载。
- 网页重定向不再触发重复加载；渲染进程退出、主文档加载失败时显示重试入口。
- 聊天内最多同时保留 6 个参与预加载的 WebView，优先处理用户主动加载的图形、屏幕内图形，再处理半屏范围内的预加载。全屏预览和导出不受这个数量限制。
- 页面生命周期只暂停或恢复对应 WebView，不使用影响其他页面的全局计时器暂停。销毁时清理手势、客户端和 JavaScript 接口引用。
- 全屏预览的 HTML 缓存写入、读取及整理移到后台。缓存单项上限 8 MiB，总量上限 48 MiB、128 项，保留期 7 天；写入使用临时文件替换，读取校验内容摘要。
- 混合 Markdown/HTML 中的流式代码块按实际语法节点判断是否闭合，包括引用块内的代码块。未闭合的图形代码不提前创建渲染器。
- SVG 预览比例只读取根 SVG 的 viewBox 或宽高，忽略内部图形的尺寸，拒绝非有限数值。
- Leaflet 圆形标记通过 getLatLng 计算地图边界；图表和地图在不支持 ResizeObserver 的旧 WebView 中回退到窗口 resize 事件。
- Mermaid 在绘制成功前隐藏原始源码；导出画布限制为单边 4096 像素且总量不超过 400 万像素，Android 侧也检查位图尺寸并在后台解码。

## 验证

1. 运行 `:app:compileQaKotlin`。
2. 运行 `:app:testDebugUnitTest`，选择 `*WebView*Test`、`*WebRenderer*Test`、`*InteractiveCodeBlockTest`、`*MermaidTest`、`*RichText*Test`。
3. 单元测试生成 `app/build/reports/webview-renderers` 内的 7 类 HTML 后，运行 `node scripts/verify-web-renderers.cjs`。脚本检查内联 JavaScript 语法及部分模拟 DOM/API 调用。

这些检查不包含真实 WebView 的布局、GPU 绘制或触摸手势。真机回归应覆盖多图长对话往返滚动、切换对话、后台恢复、深浅主题、全屏缩放和平移、长图导出，以及旧版 Android System WebView。

2026-09-08 本轮按“先安装 QA，再优化渲染”的顺序执行。已安装的 `2.4.8-revised.7-20260908-214236` 不包含本文所述的后续 WebView 优化，需再次打包后才能真机验证。

## 地理图专项修复

- 地图容器不再使用通用图形的居中 flex 布局和内边距，跳过通用 SVG 尺寸与滚动位置改写，由 Leaflet 管理图层坐标。
- GeoJSON 的 Point 和 MultiPoint 使用本地矢量圆形标记，避免请求未打包的默认图标。保留 GeoJSON 的经度、纬度顺序；`markers`、`polylines.points` 和 `center` 使用纬度、经度顺序。
- 校验标记、路线、GeoJSON 几何及配置类型。混合数据中的异常项会显示简短提示并跳过，有效图层继续渲染；无效路线不会被强行连接。支持 `geoJson` 和 `geojson` 两种配置字段。
- 清理 JSON 中无法表示的 Leaflet 实例、回调和无效 pane 参数，避免加载成功后在缩放或平移时出错。
- 2026-09-10 更新：未配置 `tileUrl` 时默认读取内置世界地图，提供无街道数据的离线底图。`basemap: false` / `"none"` 或显式 `tileUrl: ""` / `null` / `false` 保留仅显示数据图层的模式。显式在线底图失败时显示提示并回退离线概览。详细范围见 [全屏背景与离线地图说明](fullscreen-background-offline-maps.md)。
- 初次布局只适配一次数据范围，尺寸变化后保留用户视角；尊重 `fitBounds: false` 和 `maxFitZoom: 0`。

执行上述 JVM 测试生成 HTML 后，可运行 `node scripts/verify-geographic-renderer.cjs`。脚本需要 Playwright 和 Edge（或通过 `MAP_TEST_BROWSER` 指定 Chromium），将所有请求拦截为本地资源或模拟错误。它使用项目内置 Leaflet 在桌面浏览器中检查可见图层、图层尺寸、全屏布局、缩放、resize 回退及错误数据，并将截图输出到 `app/build/reports/geographic-renderer`。这部分属于真实浏览器验证，仍不替代 Android WebView 的触摸、生命周期和 GPU 真机检查。

### 底图与预览返回补充修复

- 底图选项清理不存在的 pane、无效瓦片尺寸和非数值参数，避免标记正常而瓦片无法创建。当前默认底图为离线矢量概览；显式网络瓦片恢复并成功加载后清除底图失败提示。
- 普通 HTML 的响应式媒体 CSS 排除 Leaflet 瓦片、标记图标和图层 SVG，保留 Leaflet 定位、尺寸和边距，避免缩放及居中样式破坏拼接。HTML 全屏沿用相同规则。
- 进入图形或整条消息全屏预览前，记录聊天当前消息标识、索引和像素偏移，保存在当前导航页的可保存状态中。返回时优先按标识恢复；富内容暂为占位时先保持目标消息可见，等待该消息排版完成再恢复深层偏移，等待上限为 10 秒。用户拖动立即取消恢复，期间禁用流式自动跟随和键盘补偿。
- 代码块的源码/预览选择和展开状态随导航保存。键盘补偿以当前高度为基线，避免页面重建重复计入整个键盘高度，并正确更新键盘关闭后的基线。
- 验证：27 个地图浏览器场景包含默认、自定义、禁用底图、错误参数、网络失败及 HTML 内嵌/全屏；瓦片使用本地合成图，未验证某个真实网络对地图服务的可达性。`ChatPreviewReturnTest` 验证消息重排/删除的定位策略；`ChatPreviewReturnRegressionTest` 提供 Android 页面重建及占位高度变化回归，编译该用例不等于完成真机运行。

## 2026-09-09 图形识别、预览与切页修复

- Mermaid 延迟加载容器明确占满可用宽度，修复未创建原生视图时宽度为零、进而始终无法进入可见区域加载的问题。
- Mermaid 使用主题原始表面色及匹配的文字色，避免继承亮色壁纸的黑字并绘制在深色节点上；覆盖思维导图的 cScaleLabel 配色。图形区域提供主题底色，外部页面继续显示已有背景。渲染失败、缺库或超时显示错误，避免一直空白。
- 完整 SVG 可从 XML 代码块中识别，GeoJSON 和常见图表配置可从 JSON 中识别。保留原始标签、源码、复制与导出功能；未闭合代码块和普通代码不提前执行图形渲染。
- 支持 EBNF 标签、常见 W3C 字符范围与多条规则，修复分词器丢弃赋值符号的问题。支持 `Diagram(Terminal(...), ...)` 铁路图构造语法，保留选择、循环、可选分支，修复原先根节点被展开而丢失规则结构的问题。
- ECharts JavaScript 示例仅解析字面量、局部数据声明、option 和 registerMap，不执行任意 JavaScript。`china` / `world` 及中文别名按需使用内置 ECharts 地图数据；`registerMap` 中未定义的 `chinaGeoJson` / `chinaJson` / `worldGeoJson` / `worldJson` 可引用对应内置地图。已提供的地图数据优先，未知地图继续明确提示缺失。
- 用户提供的崩溃日志来自 `2.4.8-revised.7-dev-20260908-225055`，异常是 Compose `LayoutNode should be attached to an owner`。WebView 改用稳定的 FrameLayout 容器，将原生视图创建、切换与释放推迟至布局之外；待执行更新在容器销毁时取消。该修复针对图形页切换中的节点生命周期风险，不能单凭通用堆栈确认已排除所有相同异常来源。

`DiagramSourceTest` 生成截图回归 HTML，`node scripts/verify-diagram-regressions.cjs` 使用项目内置库验证时序图、思维导图、流程图、铁路图、EBNF、全屏和错误提示。`WebViewNavigationRegressionTest` 是 Android 原生视图反复切页的测试，需在设备或模拟器上执行；只编译该用例不代表已验证真机崩溃修复。

## HTML 加载停滞修复

- 整条消息预览移除在线 ESM 依赖，Markdown、KaTeX/mhchem、任务列表、高亮、Mermaid 和公式字体全部本地加载。公式插件编译到 Chrome 74 语法，并补充其使用的 Array.at 兼容处理。
- 页面预先保留经过 HTML 转义的消息原文，初始化失败或依赖缺失时仍可阅读；20 秒排版超时会显示重试说明。单个 Mermaid 语法错误不会阻止其他内容显示。
- 原生 WebView 收到 100% 进度时结束导航加载；未结束的加载最多等待 30 秒，超时停止剩余资源并保留现有页面，提供重试。旧视图/旧导航的计时器不会影响新页面。
- 连续视图更新只合并最新操作，不反复取消并把创建任务放回消息队列尾部。Markdown/HTML 原生解析异常显示可重试提示，不一直保留占位状态。
- 验证：`HtmlLoadingRegressionTest` 和 `scripts/verify-html-loading.cjs`，覆盖离线、缺库、旧数组 API、无效 Mermaid、原文保留及超时归属。浏览器测试不等同于 Android 原生超时和触摸测试。

## 普通 HTML 中的图形依赖

控制台中的 `vegaEmbed is not defined`、`opensheetmusicdisplay is not defined`、`SmilesDrawer is not defined` 暴露出普通 HTML 预览与专用图形代码块之间的加载缺口。渲染库已在安装包内，之前普通 HTML 仅添加手机布局样式，未接入这些依赖。

- HTML 预览现在根据可执行脚本中的库引用按需加载 Vega、Vega-Lite、Vega Embed、SmilesDrawer 和 OpenSheetMusicDisplay。Vega Embed 自动补齐前两个依赖；纯 HTML、普通文字、代码示例和 JSON 数据块不加载这些库。
- 已知 jsDelivr、unpkg、cdnjs 的经典浏览器脚本地址以及本地资源别名改用安装包内的版本。移除原 CDN 标签上的 `async`、`defer` 和不再适用的 SRI，按依赖顺序在 HTML 初始化脚本之前执行，避免重复加载和竞态。原始消息及源码复制内容不变。
- 保留字符编码、base URL 和 CSP 元数据的顺序；未知域名、插件、ES 模块及 template 中的脚本保持原样，不将自定义脚本替换为其他实现。此修复不提供任意 npm 包或任意版本 API 的兼容保证。
- 聊天内预览和全屏使用同一规则；带旧版普通 HTML 预览标记的缓存页面在打开全屏时也会补齐依赖。依赖确实缺失时显示具体库名和重试说明，保留页面其他内容。
- MusicXML 的 HTML 示例如使用 `osmd.load(string)`，需提供带 `<?xml ...?>` 声明的完整 XML，或传入已经解析的 XML Document；当前 OSMD 会把较短且没有 XML 声明的字符串视为 URL。专用 MusicXML 代码块原本就使用 Document，不受此问题影响。

验证入口：`HtmlPreviewDependenciesTest` 生成原始错误和修复后的真实 HTML，`node scripts/verify-html-dependencies.cjs` 使用项目内置库和 Playwright/Edge 检查三种原始 ReferenceError、离线绘制、混合渲染、CDN 乱序、全屏及缺库提示。结果与截图位于 `app/build/reports/html-dependencies`。Android WebView 的 GPU、生命周期及触摸行为仍需真机回归。

## JSON 图形识别与流式消息稳定性

- `json`、`jsonc`、`json5`、`application/json`、`application/geo+json` 以及缺少语言标签的完整对象现在会经过同一套安全数据解析。GeoJSON 的 Feature、GeometryCollection、标准几何对象、对象数组、`markers` 和 `polylines` 均可路由到 Leaflet；普通 JSON 仍显示源码。支持注释、尾逗号和单引号对象字面量，但拒绝函数调用、变量、`NaN` 及额外脚本。
- GeoJSON 数组会规范化为 `geoJson` 配置后再渲染，避免识别阶段认为它是图形而执行阶段只收到裸数组。流式输出期间不会提前创建图形；代码围栏结束或消息完成后才开始解析和创建 WebView。
- 流式输出自动跟随在延迟校正前后都会重新确认用户仍位于底部。只要用户开始上下滑动，旧校正立即失效；回到底部后才重新接管。回答完成时保留同一条消息的内容树和代码块状态，不再因启用文本选择或 `animateContentSize` 让消息闪现并把视口拉到开头。
- 这些变更不会替换用户明确声明的非图形 JSON 语言，也不会把任意 JavaScript 当作地图或图表执行。`StreamingSelectionRegressionTest` 和 `ChatPreviewReturnRegressionTest` 是 Android 设备回归用例；当前仅完成编译，未替代真机滑动测试。

## Graphviz HTML 兼容

普通 HTML 直接使用旧版 `hpccWasm.graphviz.layout()` 或 `.dot()` 时，页面会因当前模块版 Graphviz 只导出 `Graphviz` 而出现 `hpccWasm is not defined`。HTML 依赖扫描现在识别该调用并注入本地 `graphviz-compat.js`，提供同名兼容外观，首次调用时再动态加载现有 `graphviz.min.js` WASM 模块。模块脚本、未知版本和任意外部 Graphviz 包仍保持原样；兼容层只接受字符串 DOT 和有限的布局参数，不执行页面任意代码。

## ECharts 地理边界与导航返回补充

- 区域着色图、经纬度散点和飞线需要 ECharts 注册地图，与 Leaflet 的在线瓦片不同。China/World 边界来自固定版本 ECharts 4.9.0 发行包，离线按需读取；不把内置数据注入任意变量，也不自动执行或下载用户脚本。来源、版本和校验值见 `THIRD_PARTY_NOTICES.md`。需要最新行政区划时仍应显式提供 GeoJSON。
- 预览返回改由实际导航栈顶部是否为原聊天页驱动。离开时不启动恢复；回到原页才等待目标消息的 Markdown/HTML 排版完成，再恢复消息标识及偏移。解析超过原来的 2.5 秒不会直接放弃定位，最长保护等待 10 秒，拖动仍立即取消。此过程不等待固定高度 WebView 的网络资源。
- 回归增加真实 Navigation 3 页面出栈、连续两次往返和 3.5 秒延迟排版；地图回归使用真实内置边界检查区域绘制数量及内嵌/全屏效果，未知地图和缺失本地资源仍有明确错误路径。
- 本轮 QA Kotlin、Debug 和 Android 测试包编译通过，19 项图形浏览器场景通过，已核对中国区域着色及世界飞线截图。设备在安装独立 Debug 回归包前断开，且本地无已配置 AVD；Android 导航往返用例尚未在设备运行，现有 QA 安装未替换。
