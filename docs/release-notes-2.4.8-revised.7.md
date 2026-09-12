# Rikkahub Revised 2.4.8-revised.7

## 更新说明（中文）

- 新增 OrcaRouter 供应商，并提供官网、文档和兼容接口说明。
- 优化 OpenAI、Google、Anthropic、Claude、Gemini、Qwen、DeepSeek、Grok 及兼容接口的模型参数和思考深度适配。
- 增加 DeepSeek V4.1-Flash 的 `deepseek-flash` 规范名称、1M 上下文、384K 最大输出、视觉输入和工具调用适配。
- 改进工具调用、`ask_user`、JSON Schema、后台生成和 Android 不同版本的兼容性。
- 增加工作区 SAF/本地文件访问、命令执行、路径校验和权限保护。
- 增强记忆、RAG、情景记忆生命周期、向量索引、世界书和提示词变量功能。
- 增加聊天建议、灵感启动卡片、图片生成、视频生成和后台任务保活。
- 增加请求统计、高精度耗时记录、错误诊断、日志导出和响应正文格式化。
- 增加 Mermaid、SVG、GeoJSON、Graphviz、ECharts、Leaflet、铁路图、ABC/简谱、KaTeX 和 Markdown 扩展的离线渲染支持。
- 修复聊天加载、流式输出闪烁、对话切换、分支对话、Diff、LaTeX、HTML/WebView 和全屏预览问题。
- 优化渐变背景、液态玻璃、动态配色、背景强调色、文字可读性和页面性能控制。
- 补充多语言资源、Room 数据库迁移、回归测试和项目维护文档。

## 下载与校验（中文）

- `app-arm64-v8a-release.apk`：大多数 Android 手机和平板。
- `app-x86_64-release.apk`：x86_64 模拟器及兼容设备。
- `app-universal-release.apk`：通用备用 APK。
- `app-release.aab`：用于应用商店或分发平台。
- `SHA256SUMS.txt`：上述文件的 SHA-256 校验值。

## Release Notes (English)

- Added the OrcaRouter provider with website, documentation, and compatibility details.
- Improved model parameter and reasoning-depth routing for OpenAI, Google, Anthropic, Claude, Gemini, Qwen, DeepSeek, Grok, and compatible endpoints.
- Added the canonical `deepseek-flash` name for DeepSeek V4.1-Flash, with a 1M context window, 384K maximum output, vision input, and tool-call support.
- Improved tool calls, `ask_user`, JSON Schema handling, background generation, and Android-version compatibility.
- Expanded workspace SAF/local-file access, command execution, path validation, and permission safeguards.
- Improved memory, RAG, episodic-memory lifecycle, vector indexing, world books, and prompt variables.
- Added chat suggestions, inspiration starter cards, image generation, video generation, and background task keepalive.
- Added request statistics, high-precision timing, error diagnostics, log export, and formatted response bodies.
- Added offline rendering for Mermaid, SVG, GeoJSON, Graphviz, ECharts, Leaflet, railroad diagrams, ABC/Jianpu notation, KaTeX, and Markdown extensions.
- Fixed chat loading, streaming flicker, conversation switching, branches, Diff, LaTeX, HTML/WebView, and full-screen preview issues.
- Improved gradient backgrounds, Liquid Glass, Dynamic Color, Background Accent, text readability, and page performance controls.
- Added localized resources, Room migrations, regression tests, and maintenance documentation.

## Downloads and verification (English)

- `app-arm64-v8a-release.apk`: most modern Android phones and tablets.
- `app-x86_64-release.apk`: x86_64 emulators and compatible devices.
- `app-universal-release.apk`: universal fallback APK.
- `app-release.aab`: app-store or distribution-platform bundle.
- `SHA256SUMS.txt`: SHA-256 checksums for the release files above.

This release uses version code 183 and application ID `me.rerere.rikkahub.revised`.
All APKs are signed with the recorded Revised release certificate.
