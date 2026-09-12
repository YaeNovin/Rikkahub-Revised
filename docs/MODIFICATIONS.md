# Rikkahub Revised Modification Notice

Notice date: 2026-09-12

This repository is a modified distribution of the upstream
[RikkaHub project](https://github.com/rikkahub/rikkahub). It is based on the
upstream `2.4.8` tag at commit
`8824e0e841f2008b322ca8214a27a978e4b4abaa`. The first revised release line is
`2.4.8-revised.1`; the current published release is `2.4.8-revised.8`.

This file describes the modified work as required for an auditable AGPL-3.0
release. It does not claim authorship of unchanged upstream code. Release checks
are maintained separately from user-facing release descriptions.

## 2026-09-12 Grouped Upload Scope / 分组上传范围

The source changes are organized as grouped commits so provider,
application, rendering, shared-platform, and documentation changes can be
reviewed independently. Generated APKs, local Gradle caches, IDE metadata,
signing files, local properties, and logs are not part of the upload scope.
Reusable regression scripts and the KaTeX build adapter are included so the
documented checks and bundled asset build can be reproduced.

本次待上传源码按 Provider/AI、应用核心、聊天与渲染 UI、公共模块与 Web UI、文档
分组提交。APK、Gradle 缓存、IDE 文件、签名材料、local.properties 和日志不上传。
通用回归脚本与 KaTeX 构建适配器随源码保留，便于复现文档中的检查。

逐条双语更新记录见 [2026-09-12 源码更新](changes-2026-09-12.md)，完整文档入口见
[文档索引](../README.md)。本次源码已递增版本并发布 `2.4.8-revised.8` 的 APK/AAB 资产；
逐条中文后英文的发布说明见 [release-notes-2.4.8-revised.8.md](release-notes-2.4.8-revised.8.md)。

### Latest provider and interaction additions / 最新 Provider 与交互新增

- DeepSeek V4.1-Flash uses the documented `deepseek-flash` model name and keeps
  legacy aliases compatible. The implementation covers the 1M context window,
  384K maximum output, vision input, reasoning effort, JSON output, tool choice,
  and OpenAI/Responses/Anthropic request differences.
  中文：DeepSeek V4.1-Flash 使用官方 `deepseek-flash` 名称并兼容旧别名，补齐
  1M 上下文、384K 最大输出、视觉输入、思考强度、JSON 输出、工具选择，以及
  OpenAI/Responses/Anthropic 协议差异。
- `ask_user` supports richer choices, numeric controls, date/time input,
  confirmation countdowns, conditional fields, and protocol-safe schemas.
  中文：`ask_user` 支持富选项、数值控件、日期/时间、带倒计时确认、条件字段联动
  和协议安全的工具 Schema。
- Media/video generation keeps tasks alive in the background and records output
  safely before writing it back to conversations.
  中文：图片/视频生成支持后台保活，并在写回对话前安全保存生成结果。

### Latest UI and rendering additions / 最新 UI 与渲染新增

- Added offline renderer assets and licenses for diagrams, maps, music notation,
  chemistry, Markdown extensions, SVG, and KaTeX.
- Added full-screen preview positioning, source/render switching, GitHub repository
  cards, request-log formatting, memory diagnostics, inspiration cards, and
  adaptive appearance/text-color controls.
- 增加图表、地图、乐谱、化学式、Markdown 扩展、SVG 与 KaTeX 的离线渲染资源及
  许可证；完善全屏预览定位、源码/渲染切换、GitHub 仓库卡片、请求日志格式化、
  记忆诊断、灵感卡片和自适应外观/文字配色。

## 2.4.8-revised.7 Historical Release / .7 历史版本

- English: Added OrcaRouter as an OpenAI-compatible provider, including its
  endpoint, website, documentation links, and localized provider descriptions.
  中文：新增 OrcaRouter OpenAI 兼容供应商，包含接口地址、官网、文档链接及本地化供应商说明。
- English: Added model- and protocol-aware parameter routing for OpenAI,
  Google, Anthropic, Claude, Gemini, Qwen, DeepSeek, and compatible endpoints,
  with safer tool-argument JSON handling and diagnostics.
  中文：增加面向 OpenAI、Google、Anthropic、Claude、Gemini、Qwen、DeepSeek 及兼容接口的模型与协议参数路由，并改进工具参数 JSON 处理和诊断安全性。
- English: Improved prompt templates, placeholder variables, entertainment
  extensions, memory and world-book diagnostics, request statistics, and
  workspace SAF/local-file operations.
  中文：优化提示词模板、占位符变量、娱乐模式扩展、记忆与世界书诊断、请求统计，以及工作区 SAF/本地文件操作。
- English: Hardened chat loading and streaming recovery, rich-content rendering,
  WebView previews, image-generation settings, and Android compatibility paths;
  focused regression tests were added alongside the fixes.
  中文：修复聊天加载与流式恢复、富内容渲染、WebView 预览、生图设置及 Android 兼容性路径问题，并同步增加针对性回归测试。
- English: The original signed `2.4.8-revised.7` Release is restored with its
  ARM64 APK and original bilingual notes.
  中文：原始签名正式版 `2.4.8-revised.7` 已恢复为原 ARM64 安装包和原双语说明。

## 2.4.8-revised.8 Release / .8 正式版本

中文更新说明与英文更新说明按语言分段记录于
[release-notes-2.4.8-revised.8.md](release-notes-2.4.8-revised.8.md)。该版本版本码为
184，使用最新分组源码构建，并提供 ARM64、x86_64、Universal APK、AAB 与 SHA256 校验文件。

## 2.4.8-revised.7 Release Notes / 发布说明

更新内容:

- 新增 OrcaRouter 供应商，并提供官网、文档和兼容接口说明。
- 优化不同模型与协议的参数、思考深度及工具调用适配。
- 改进工作区本地文件访问、SAF 授权和命令执行安全性。
- 增强提示词模板、占位符、世界书、记忆和娱乐模式扩展。
- 增加请求统计、耗时记录、诊断信息和回归测试。
- 修复聊天加载、流式恢复、分支对话及复杂内容渲染问题。
- 改进图片生成设置、WebView 预览、Diff、LaTeX 和 Mermaid 显示。
- 补充多语言资源与 Android 版本兼容性处理。

Updates:

- Added the OrcaRouter provider with official website, documentation, and compatibility details.
- Improved model and protocol parameter, reasoning-depth, and tool-call handling.
- Hardened workspace local-file access, SAF grants, and command execution.
- Expanded prompt templates, placeholders, world books, memories, and entertainment extensions.
- Added request statistics, timing diagnostics, and focused regression tests.
- Fixed chat loading, stream recovery, conversation branches, and complex-content rendering.
- Improved image-generation settings, WebView previews, Diff, LaTeX, and Mermaid rendering.
- Added localized resources and Android-version compatibility handling.

## 2.4.8-revised.6 Maintenance Changes / 维护变更

- English: Added advanced appearance controls for global and assistant
  backgrounds, page and overlay surfaces, navigation, chat controls, bubbles,
  typography, rich content, and automatic accent colors.
  中文：增加全局与助手背景、页面与浮层、导航、聊天控件、气泡、排版、富内容及
  自动强调色等高级外观设置。
- English: Added safe global-background override behavior that preserves
  assistant backgrounds, defaults new background opacity to 100%, and improves
  contrast and readability across supported surfaces.
  中文：增加安全的全局背景覆盖机制，保留原有助手背景，将新背景默认透明度调整为
  100%，并改善受支持界面的对比度与可读性。
- English: Added Android-version capability detection with reduced effects on
  Android 12 and disabled unsupported blur or glass controls on older systems,
  together with compatibility and performance notices.
  中文：增加 Android 版本能力检测，在 Android 12 上降低效果，并在更旧系统上停用
  不受支持的模糊或玻璃控件，同时补充兼容性与性能提示。
- English: Improved chat Dock, input, sidebar, drawer, dialog, menu, and sheet
  rendering, including stable fallbacks for long or complex message content.
  中文：改善聊天 Dock、输入框、侧边栏、抽屉、对话框、菜单和弹出页渲染，并为长
  消息或复杂内容提供稳定的回退效果。
- English: Expanded diagnostics with concrete provider error reasons,
  localized parameter labels and values, troubleshooting guidance, and reduced
  duplicate request entries.
  中文：扩展诊断日志，显示具体供应商错误原因、本地化参数名称和值、排查建议，并
  减少重复请求记录。
- English: Improved Gemini image and Anthropic Messages diagnostics while
  preventing full prompts, response streams, image Base64 data, credentials,
  and source image URLs from being written to logs.
  中文：改善 Gemini 图片与 Anthropic Messages 诊断，并防止完整提示词、响应流、图片
  Base64、凭据及源图片地址写入日志。
- English: Completed Simplified Chinese resources and expanded Traditional
  Chinese coverage for appearance, diagnostics, Gemini, Claude, and image
  generation, plus several previously hard-coded interface labels.
  中文：补齐简体中文资源，并扩展高级外观、诊断、Gemini、Claude 与图片生成的繁体
  中文覆盖，同时汉化多处原先硬编码的界面文字。
- English: Fixed the search-provider picker so its provider list scrolls while
  Confirm and Cancel remain visible, and improved reproducible QA and offline
  builds with a verified bundled icon dependency.
  中文：修复搜索供应商选择列表遮挡“确定”和“取消”的问题，并通过校验后的内置图标
  依赖改善 QA 与离线构建的可复现性。

## 2.4.8-revised.5 Maintenance Changes / 维护变更

- English: Added model-aware request parameters for OpenAI, Claude, Gemini,
  Grok, Qwen, and DeepSeek, with sanitized request diagnostics that omit
  credentials, prompts, schemas, and binary content.
  中文：为 OpenAI、Claude、Gemini、Grok、Qwen 和 DeepSeek 增加按模型能力适配的
  请求参数，并提供不记录凭据、提示词、Schema 与二进制内容的脱敏请求诊断。
- English: Improved stream reliability with premature-termination detection,
  corrected retry time budgets, and event-stream connection keepalive settings.
  中文：通过流提前终止检测、正确的重试时间预算及事件流连接保活设置，提高流式
  生成可靠性。
- English: Added Gemini image-generation controls and unified source, inline,
  and full-screen previews for supported rich code blocks; crop output now
  remains available until persistence completes.
  中文：增加 Gemini 生图参数，并统一受支持富代码块的源码、内嵌与全屏预览；裁剪
  输出会在持久化完成后再清理。
- English: Fixed stale settings writes that could block conversation switching,
  made navigation replacement atomic, and restored the provider model action bar
  to the bottom of the model list.
  中文：修复可能导致无法切换对话的旧设置快照写入，使导航替换成为原子操作，并将
  供应商模型操作栏恢复到模型列表底部。

## 2.4.8-revised.4 Maintenance Changes / 维护变更

- English: Improved model capability, context-window, and reasoning-parameter
  detection for Qwen, DeepSeek, Doubao, and compatible providers.
  中文：优化千问、DeepSeek、豆包及兼容供应商的模型能力、上下文窗口与推理参数识别。
- English: Standardized GPT Image 2 size, aspect-ratio, and quality parameters,
  and improved Gemini, Grok, and Seedream image-generation compatibility.
  中文：规范 GPT Image 2 的图片尺寸、比例和质量参数，并完善 Gemini、Grok 与
  Seedream 生图兼容性。
- English: Upgraded the image gallery with previews, details, folders, image
  import, metadata backup, and batch add, move, and delete operations.
  中文：升级图库预览、详情、文件夹、图片导入、元数据备份，以及批量添加、移动和删除。
- English: Improved automatic reconnection for network changes, rate limits,
  interrupted connections, chat continuation, and image generation or editing.
  中文：改进网络切换、限流、连接中断、聊天续答，以及生图和图片编辑过程中的自动重连。
- English: Improved full-conversation and file token accounting, context-window
  detection, and automatic context compression reliability.
  中文：提升完整对话和文件的 Token 统计、上下文窗口识别与自动压缩可靠性。
- English: Fixed DOC, DOCX, and XLSX parsing and improved large multimedia
  attachments and complex MIDI processing.
  中文：修复 DOC、DOCX、XLSX 解析，并优化大型多媒体附件和复杂 MIDI 文件处理。
- English: Localized software error messages and added a seven-day,
  entry-based diagnostic log viewer.
  中文：汉化软件错误提示，增加按条目保存七天的诊断日志及查看入口。
- English: Improved MCP OAuth authorization, connection status, and reconnect
  feedback.
  中文：完善 MCP OAuth 授权、连接状态和重连反馈。

## 2.4.8-revised.3 Maintenance Changes / 维护变更

- English: Fixed stale update downloads and repeated installation prompts by
  using versioned download files and strictly newer Android version codes.
  中文：通过版本化下载文件和严格递增的 Android 版本码，修复旧安装包重复提示及
  更新安装后不生效的问题。
- English: Added device ABI matching for update packages and text/image preview
  support for knowledge-base files.
  中文：更新时按设备 ABI 匹配安装包，并为知识库文件增加文本与图片预览。
- English: Added a maximum reasoning level with protocol- and vendor-aware
  request mapping for OpenAI-, Google-, Anthropic-, and compatible model APIs.
  中文：增加“最高”推理深度，并针对 OpenAI、Google、Anthropic 及兼容模型接口按
  协议和厂商映射请求参数。
- English: Added day-based cleanup for chat attachments and generated images
  while preserving conversation text.
  中文：支持按天清理聊天附件与生成图片，同时保留聊天文本。

## 2.4.8-revised.2 Maintenance Changes / 维护变更

- English: Fixed automatic context-window discovery for OpenAI-, Google-, and
  Anthropic-compatible model lists. Explicit provider metadata takes priority,
  known model limits provide a fallback, and saved manual values are preserved.
  中文：修复 OpenAI、Google 与 Anthropic 兼容协议的上下文窗口自动获取；优先采用
  提供商返回值，在缺失时使用已知模型限制回退，并保留用户手动设置。
- English: Removed the upstream official-website entry from the About screen
  and made the displayed application name follow the Revised app resource.
  中文：从“关于”界面移除上游官网入口，并让界面显示名称跟随 Revised 应用资源。
- English: Added a bilingual automatic context-compression status with a
  continuous progress animation during rolling-summary refreshes.
  中文：滚动摘要自动刷新时显示中英文自动压缩状态，并提供持续进度动画。

## Major Modifications

- Replaced fixed message-count truncation with token-aware rolling context
  summaries, context-window discovery/configuration, context usage UI, and
  throttled streaming updates.
- Added local knowledge bases, document ingestion and chunking, vector and
  lexical retrieval, assistant bindings, RAG, source citations, and read-only
  knowledge tools.
- Extended memories with types, timestamps, source conversations, embeddings,
  semantic retrieval, lexical fallback, and scoped management. The current
  pipeline uses background extraction instead of foreground memory tools.
- Added provider capability metadata, embedding support, connection and stream
  diagnostics, sanitized protocol traces, and secret-safe custom-provider
  import/export behavior.
- Tightened local-tool privacy boundaries for workspace/rootfs access,
  clipboard operations, calendar deletion, and screen-time results, with
  approval-policy tests.
- Split backup scopes for databases, settings/credentials, attachments, and
  workspaces; excluded rootfs; and added archive path validation for S3 and
  WebDAV restore flows.
- Added interactive ECharts, ABC staff notation, offline Jianpu numbered
  notation, Leaflet, and railroad-diagram renderers; improved Mermaid, LaTeX,
  Markdown, and animated-background behavior; and hardened local WebView
  settings.
- Added database migrations and Room schemas through version 43, knowledge-base
  navigation, provider configuration UI, multilingual resources, focused tests,
  and architecture documentation.
- Added timestamped Debug/QA versions and an optional QA keystore override.
- Restored the in-app update card and reminder controls, replacing the upstream
  service with this fork's public GitHub Releases feed and device-compatible APK
  selection.
- Renamed the distribution to `Rikkahub Revised`, changed the release
  application ID to `me.rerere.rikkahub.revised`, isolated custom URI schemes,
  app-internal actions, Provider authorities and the Debug package ID, and made
  Firebase integration opt-in for a future fork-owned configuration.
  QA retains the legacy test package ID; see `docs/RELEASE_SIGNING.md`.

Implementation details are preserved in the source and Git history.

## Upstream Version Context

The newer upstream snapshot used during the 2026-08-17 audit is commit
`3b4b80a4173ea626422c7ec037af383b828f8623`, approximately the upstream
`2.4.10` line and 15 commits ahead of `2.4.8`. It was released after this fork's
development baseline and is only a compatibility/security review reference.
Those commits have not been merged into this working tree.

An upstream upgrade should be performed as a separate, reviewable change after
the current modifications are committed and tested. It is not a prerequisite
for accurately publishing the existing `2.4.8`-based source.

## Source Links in Release Builds

The About screen now defaults to the public revised repository and its
AGPL-3.0 license on the `main` branch:

```text
https://github.com/YaeNovin/Rikkahub-Revised
https://github.com/YaeNovin/Rikkahub-Revised/blob/main/LICENSE
```

The Gradle properties `rikkahub.sourceRepositoryUrl` and
`rikkahub.sourceLicenseUrl` remain available for mirrors and reproducibility
tests. Public binaries must point to the complete corresponding source, build
scripts, license, this modification notice, and applicable third-party notices.

## Build and Signing Notes

- `release` uses `2.4.8-revised.8` and signing values
  from ignored `local.properties` when supplied.
- The release application ID is `me.rerere.rikkahub.revised`; Debug and QA add
  `.debug` and `.qa` respectively so test builds do not replace a signed release.
- Debug and QA builds append an Asia/Shanghai build timestamp. The QA signing
  configuration inherits Android debug signing and only uses
  `../android-user-home/debug.keystore` when that optional file exists.
- Google Services and Firebase Crashlytics Gradle plugins are disabled by
  default because the ignored upstream `google-services.json` does not belong
  to the revised application ID. A maintainer-owned matching configuration can
  opt in with `--project-prop=rikkahub.enableFirebase=true`.
- Keystores, passwords, `google-services.json`, `local.properties`, IDE state,
  and build logs must not be committed.

The release certificate fingerprint and key-continuity rules are documented in
[RELEASE_SIGNING.md](RELEASE_SIGNING.md). The private key and recovery passwords
must remain outside the repository and be backed up offline.

## Update Distribution

The restored in-app updater checks only:

```text
https://api.github.com/repos/YaeNovin/Rikkahub-Revised/releases/latest
```

GitHub `404` is treated as no published update. Release tags accept an optional
leading `v`; historical Revised releases use the `v2.4.8-revised.N` form, while
  the current `2.4.8-revised.8` release omits the prefix. Each distributed update
must increment both `N` and Android `versionCode`. The release must be a
non-draft, non-prerelease GitHub Release so it is returned by `/releases/latest`.

The updater accepts APK assets only from this repository. It offers the first
matching ABI from `Build.SUPPORTED_ABIS` plus the Universal fallback and filters
out incompatible architectures. Asset names may include a version suffix; the
updater identifies the ABI from the filename. The current ARM64 asset is:

- `app-arm64-v8a-release.apk`

Android still enforces signing continuity during installation. Every public APK
must use the certificate recorded in `RELEASE_SIGNING.md`. Release descriptions
remain limited to user-facing updates and fixes; asset integrity, signing, and
build-environment details are verified separately during publication.

## License

The modified work is distributed under GNU AGPL-3.0, consistent with the
upstream project. No `-or-later` grant is inferred from the repository's current
license declaration. See [LICENSE](../LICENSE), [NOTICE](../NOTICE), and
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
