# 聊天内只读终端展示

开发工作区新增，尚未发布。仅用于 Android 原生聊天的终端风格输出展示，不提供交互式终端。

## 使用入口

在聊天中使用 `ansi`、`terminal`、`xterm` 或 `console-output` 代码块。例如直接发送下列 Markdown，或让模型按此格式输出：

````markdown
```ansi
\x1b[32mSUCCESS\x1b[0m 构建完成
\x1b[33mWARN\x1b[0m 这只是输出展示，不会执行命令
中文与 emoji：你好 🙂
```
````

支持真实 ESC 字符和文本形式的 `\x1b` / `\u001b`。颜色、粗体等 ANSI 样式，以及基本光标移动、清行、回车覆盖通过 xterm 渲染。例如真实的回车与清行序列可以把进度 `10%` 覆盖为 `100%`。普通文本也可放入这些代码块，获得等宽终端布局。

- 闭合且不超过 131072 个 UTF-16 代码单元的代码块默认显示预览；尚未闭合或过大的内容保留源码展示。不是边生成边运行的实时终端。
- 普通 `bash`、`sh`、`shell`、`console`、`plaintext` 保持原有代码块行为，不自动执行或转换。
- 现有 `workspace_shell` 工具结果详情中的 stdout/stderr 使用同一展示组件；命令部分仍按 `bash` 显示。只改变已有结果的显示，不新增或改变工具执行能力。
- 工具摘要保留轻量原生文本，清理控制序列，不为每条摘要创建 WebView。

## 复用的交互与边界

- 复用代码块的源码切换、复制、下载原始文本、全屏预览和 PNG 保存；复制和文本下载保留原始内容，不复制过滤后的展示文本。
- PNG 保存当前预览视口，不是全部滚动历史的长截图。阅读长输出可打开全屏；聊天内优先让上下滑动滚动对话。
- 复用现有可见区域延迟加载、WebView 数量预算、加载错误处理与释放机制。全屏关闭后返回聊天。
- 使用深浅主题固定高对比度背景。终端全屏不显示图形专用的背景切换和重置按钮，不注入图形平移脚本。
- 自动适配视口，范围为 2–500 列、2–80 行；长行保留最多 240 源列的布局，通过横向滑动浏览。全屏支持纵向触摸浏览；滚动历史最多 2000 行。更早内容可能被移出终端缓冲，完整原文仍在源码中。
- 不支持键盘输入、粘贴输入、自动打开链接、OSC 超链接/剪贴板/标题控制、终端图片协议、设备查询或私有模式。部分复杂 TUI 输出不会完全还原。
- 终端展示本身不执行模型工具；后续已接入[统一渲染目录和 render_content](rendering-tools-and-html-policy.md)。显示为终端的内容仍可能只是模型生成的示例，不能据此认定命令已经执行。

## 实现与依赖

`TerminalPreview.kt` 负责语言识别、线性控制序列过滤和固定 HTML 模板；`terminal-preview.js` 负责 xterm 初始化、自适应、只读输入限制、渲染状态及退出释放。

内容先过滤，再以 Base64 UTF-8 数据传入页面，不作为 HTML 或 JavaScript 执行。模板 CSP 只允许指定的本地脚本与样式，禁止网络连接、嵌入框架和对象；内联与全屏终端同时关闭 WebView 网络加载。未添加 JS/原生桥、WebSocket、shell 或 Android PTY。

随包离线依赖为 `@xterm/xterm 6.0.0` 和 `@xterm/addon-fit 0.11.0`，均为 MIT 许可证，固定来源和 SHA-256 见[第三方归属](THIRD_PARTY_NOTICES.md#read-only-terminal-preview-2026-09-23)。未将 xterm 加入任意 HTML 预览的依赖自动注入，也未引入 p5.js、Web UI 交互终端或修改已有原生终端。

## 验证与复测

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests '*TerminalPreviewTest' --tests '*InteractiveCodeBlockTest' --tests '*HtmlPreviewDependenciesTest' --tests '*HtmlLoadingRegressionTest' --tests '*WebViewPageTest' --tests '*WebViewAssetPolicyTest' --tests '*WebViewLoadTrackerTest' --tests '*WebViewGestureTest' --tests '*WebViewContentCacheTest'
node scripts/verify-terminal-preview.cjs
```

浏览器脚本需要 Node.js、可解析的 `playwright` 包以及 Microsoft Edge。先运行 JVM 测试生成生产 HTML 样例；浏览器检查覆盖深色、浅色、全屏、空内容、长内容和依赖缺失，另检查 ANSI 色彩、Unicode、回车清行、禁用输入、注入隔离、禁止外连、自适应及释放。报告和截图写入 `app/build/reports/terminal-preview/`。

初次实现结果：上述 9 个 JVM 测试类共 54 项通过（含应用 Kotlin 编译），6 个桌面浏览器场景通过；JavaScript 语法检查和 `git diff --check` 通过。

初次实现时未进行真机验证。2026-11-07 的手势、高亮和深色测试扩展见[后续适配与验证记录](model-parameters-and-mobile-rendering.md)；浏览器与真机结果分别列出。
