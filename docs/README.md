# 文档索引

更新：2026-11-21。当前正式版为 `2.4.8-revised.12`，见[发行说明](release-notes-2.4.8-revised.12.md)和[验证记录](release-verification-2.4.8-revised.12.md)。后续审阅和未完成事项见 [开发状态与文档范围](development-status.md)。

本次源码整合、换行规则及旧文档/图片删除范围见[同步与清理记录](source-sync-20261119.md)。

## 使用与开发

| 领域 | 文档 |
| --- | --- |
| 构建与验证 | [环境和缓存](building.md)、[脚本](../scripts/README.md)、[项目结构](PROJECT_STRUCTURE.md)、[签名连续性](RELEASE_SIGNING.md) |
| 聊天与上下文 | [聊天入口](chat-entry-improvements.md)、[上下文记忆](context-memory-upgrade.md)、[建议](chat-suggestions-upgrade.md)、[记忆提取](memory-extraction-only.md)、[身份与可读性](memory-identity-and-readability.md) |
| 富文本 | [渲染组件](rendering-components.md)、[语言学与外语](linguistic-rendering.md)、[音乐学与谱面](music-rendering.md)、[原生表格](markdown-tables.md)、[搜索引用与 Markdown 修复](markdown-citation-repairs-20261119.md)、[HEX 和引号高亮](chat-inline-hex-and-quote-highlights.md)、[链接和图片工具](chat-links-and-image-tools.md)、[图片导出](chat-image-export.md)、[P0 基线对齐与验证](development-baseline-alignment-20261119.md) |
| 图形与 WebView | [WebView](webview-rendering.md)、[HTML 策略](rendering-tools-and-html-policy.md)、[数学与法阵](math-plot-and-sigil.md)、[程序化纹样](procedural-patterns.md)、[特效与性能](decorative-effects-and-performance.md)、[几何与手势](geometry-background-and-gestures.md) |
| 外观 | [文字配色](text-color-modes.md)、[液态玻璃](liquid-glass.md)、[背景与输入栏](background-composer-fixes.md)、[云母与聊天控件](mica-background-and-floating-chat-controls.md)、[高亮配色](highlight-original-palette-and-comments.md) |
| 模型与搜索 | [模型名称识别](model-name-and-dark-text.md)、[模型协议](model-protocol-adaptation-20261118.md)、[供应商参数](official-provider-audit-20261118.md)、[自定义搜索](custom-search-service.md)、[Gemini 工具与豆包密钥](gemini-assistant-tools-and-doubao-keys.md) |
| 图片与视频 | [图片参数](lingke-image26-shared-parameters.md)、[图片 API 和决策模型](image-api-decision-model-and-prompt-fixes.md)、[视频配置](video-provider-setup.md) |
| 语音、工具与世界书 | [语音](speech-services.md)、[ask_user](ask-user-upgrade.md)、[终端](chat-terminal-preview.md)、[世界书](worldbook-guide.md)、[兼容边界](worldbook-compatibility-audit.md) |
| 日志 | [日志设计](logging-reliability.md)、[日志重构](logging-refactor-and-response-identity-20261117.md)、[系统日志展示](system-log-presentation-20261117.md) |
| Web 前端 | [功能边界](webui-remaining-sync-audit-2026-10-06.md)、[独立设置与图片](webui-independent-settings-images-2026-10-09.md)、[供应商向导](webui-provider-wizard-custom-js-2026-10-09.md) |

## 带日期的审阅与后续事项

以下文件保留当时的代码结论和待办，需结合后续实现核对：

- [记忆与对话分支审阅](memory-branch-review-20261120.md)
- [记忆清除、提取与检索首轮优化](memory-optimization-20261120.md)
- [AI 模块](ai-module-review-and-roadmap-20261115.md)
- [日志链路](logging-review-and-roadmap-20261115.md)
- [Material3 与 Web](material3-web-review-and-roadmap-20261116.md)
- [Common 与 Search](common-search-review-and-roadmap-20261116.md)
- [Speech 与 Workspace](speech-workspace-review-and-roadmap-20261116.md)
- [其他模块](remaining-modules-review-and-roadmap-20261116.md)
- [聊天链路](chat-stack-review-20261118.md)
- [Markdown 与上游同步](markdown-upstream-sync-review-20261119.md)
- [Markdown 与 GFM 渲染覆盖](markdown-gfm-rendering-audit-20261120.md)
- [聊天渲染现状与下一步计划](rendering-roadmap-20261120.md)
- [分学科渲染扩展调研与路线](subject-rendering-roadmap-20261120.md)
- [MiniMax 国内 API 域名更新](minimax-endpoint-update-20261121.md)
- [GitHub 卡片、Token 方案与链接交互](github-cards-and-link-interactions-20261120.md)
- [GitHub Token 与原生仓库卡片实现](github-token-native-cards-20261120.md)
- [GitHub OG 风格卡片与导出](github-social-preview-20261120.md)
- [总体审阅](module-review-and-roadmap-20261115.md)

## 发行、归属与历史

- [2.4.8-revised.12 发行说明](release-notes-2.4.8-revised.12.md)及[验证边界](release-verification-2.4.8-revised.12.md)
- [2.4.8-revised.11 发行说明](release-notes-2.4.8-revised.11.md)及[验证边界](release-verification-2.4.8-revised.11.md)
- [2.4.8-revised.10 发行说明](release-notes-2.4.8-revised.10.md)及[验证边界](release-verification-2.4.8-revised.10.md)
- [2.4.8-revised.9 发行说明](release-notes-2.4.8-revised.9.md)及[验证边界](release-verification-2.4.8-revised.9.md)
- [2.4.8-revised.8](release-notes-2.4.8-revised.8.md)、[2.4.8-revised.7](release-notes-2.4.8-revised.7.md)
- [修改声明](MODIFICATIONS.md)、[贡献者](CONTRIBUTORS.md)、[第三方归属](THIRD_PARTY_NOTICES.md)、[许可证](../LICENSE)、[NOTICE](../NOTICE)
- [2026-09-15 源码更新](changes-2026-09-15.md)、[2026-09-12 源码更新](changes-2026-09-12.md)

发行记录按实际标签和安装包保存，不把后续功能补写成旧发行版已有能力。已被替代的过程文档、本机路径和设备证据移至忽略的本地归档。源码上传范围见[开发状态](development-status.md)，通过 `scripts/prepare_github.py` 生成最新路径清单。

远端组件修复的来源与验证边界见[组件集成记录](upstream-component-migration.md)。
