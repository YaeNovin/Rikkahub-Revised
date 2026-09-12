# Rikkahub Revised 2.4.8-revised.8

## 更新说明（中文）

- 在保留原始 `2.4.8-revised.7` 的基础上，发布版本升级为 `2.4.8-revised.8`，版本码为 184。
- 优化 OpenAI、Google、Anthropic、Claude、Gemini、Qwen、DeepSeek、Grok 及兼容接口的模型参数、思考深度和工具调用。
- 增加 DeepSeek V4.1-Flash 的 `deepseek-flash` 规范名称、1M 上下文、384K 最大输出、视觉输入和工具调用适配。
- 改进工具调用、`ask_user`、JSON Schema、后台生成、SAF/本地文件和 Android 版本兼容性。
- 增强记忆、RAG、情景记忆生命周期、向量索引、世界书、提示词变量、聊天建议和灵感卡片。
- 增加图片/视频生成、后台任务保活、请求统计、日志诊断、日志导出和响应正文格式化。
- 增加 Mermaid、SVG、GeoJSON、Graphviz、ECharts、Leaflet、铁路图、ABC/简谱、KaTeX 和 Markdown 扩展的离线渲染。
- 修复聊天加载、流式输出、对话切换、分支对话、Diff、LaTeX、HTML/WebView 和全屏预览问题。
- 优化渐变背景、液态玻璃、动态配色、背景强调色、文字可读性和页面性能控制。
- 补充多语言资源、Room 数据库迁移、回归测试和项目维护文档。

## 下载与校验（中文）

- `app-arm64-v8a-release.apk`：大多数 Android 手机和平板。
- `app-x86_64-release.apk`：x86_64 模拟器及兼容设备。
- `app-universal-release.apk`：通用备用 APK。
- `app-release.aab`：应用商店或分发平台使用。
- `SHA256SUMS.txt`：正式包 SHA-256 校验值。

## Updates (English)

- Upgraded the release from the original `2.4.8-revised.7` to `2.4.8-revised.8`, version code 184.
- Improved model parameter, reasoning-depth, and tool-call routing for OpenAI, Google, Anthropic, Claude, Gemini, Qwen, DeepSeek, Grok, and compatible endpoints.
- Added the canonical `deepseek-flash` name for DeepSeek V4.1-Flash, with a 1M context window, 384K maximum output, vision input, and tool-call support.
- Improved tool calls, `ask_user`, JSON Schema handling, background generation, SAF/local files, and Android-version compatibility.
- Expanded memory, RAG, episodic-memory lifecycle, vector indexing, world books, prompt variables, chat suggestions, and inspiration cards.
- Added image/video generation, background keepalive, request statistics, log diagnostics, log export, and formatted response bodies.
- Added offline rendering for Mermaid, SVG, GeoJSON, Graphviz, ECharts, Leaflet, railroad diagrams, ABC/Jianpu notation, KaTeX, and Markdown extensions.
- Fixed chat loading, streaming, conversation switching, branches, Diff, LaTeX, HTML/WebView, and full-screen preview issues.
- Improved gradient backgrounds, Liquid Glass, Dynamic Color, Background Accent, text readability, and page performance controls.
- Added localized resources, Room migrations, regression tests, and maintenance documentation.

## Downloads and verification (English)

- `app-arm64-v8a-release.apk`: most modern Android phones and tablets.
- `app-x86_64-release.apk`: x86_64 emulators and compatible devices.
- `app-universal-release.apk`: universal fallback APK.
- `app-release.aab`: app-store or distribution-platform bundle.
- `SHA256SUMS.txt`: SHA-256 checksums for the release files.

All APKs use the recorded Revised release certificate. The source and release
assets are published from the same version-184 source tree.
