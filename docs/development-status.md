# 开发状态与文档范围

更新：2026-11-19。当前正式版本为 [2.4.8-revised.10](release-notes-2.4.8-revised.10.md)，其发布验证见[验证记录](release-verification-2.4.8-revised.10.md)。带日期的审阅仍保留未完成事项，不能将其中的建议视为已实现功能。

## 当前维护入口

| 领域 | 现行文档 |
| --- | --- |
| 构建、缓存与设备验证 | [构建指南](building.md)、[脚本](../scripts/README.md)、[签名身份](RELEASE_SIGNING.md) |
| 聊天、上下文与建议 | [聊天入口](chat-entry-improvements.md)、[上下文记忆](context-memory-upgrade.md)、[建议](chat-suggestions-upgrade.md)、[聊天链路审阅](chat-stack-review-20261118.md) |
| 富文本与工具卡片 | [渲染组件](rendering-components.md)、[搜索引用修复](markdown-citation-repairs-20261119.md)、[HEX 与引号高亮](chat-inline-hex-and-quote-highlights.md)、[链接与图片工具](chat-links-and-image-tools.md) |
| 外观 | [文字配色](text-color-modes.md)、[液态玻璃](liquid-glass.md)、[背景与输入栏](background-composer-fixes.md) |
| 日志 | [日志设计](logging-reliability.md)、[重构](logging-refactor-and-response-identity-20261117.md)、[系统日志展示](system-log-presentation-20261117.md) |
| 模型与协议 | [协议适配](model-protocol-adaptation-20261118.md)、[官方供应商补足](official-provider-audit-20261118.md)、[模型名称匹配](model-name-and-dark-text.md) |

## 文档清理原则

本轮将早期动画、徽章、全屏与聊天建议审查，已实现的图片工具设计稿，阶段性 Web 验证，文字取色修复过程，以及本机 SDK/缓存迁移和安装记录移出公共文档目录。现行指南保留功能、限制、实现入口与可复现命令，原始记录归档在本机 `docs/local/`，不进入上传清单。

历史发行说明、署名、许可证与签名连续性记录继续保留；近期逐模块审阅仍有未解决事项，作为带日期的审阅快照保留。审阅中的测试数量和源码行号只描述当时状态，不代表当前完整验证结果。

用户提供的图片、聊天内容、日志和设备采集不直接作为公开测试夹具。壁纸颜色测试使用可重建的合成图案，运行时第三方资源保留来源、许可证与校验值。

## 后续集成

源码、测试、Room schema、离线资源与其许可证需按依赖成组审阅。Desktop 与 Three.js 原型列为独立实验组；不要仅遗漏文件而保留对它们的强制构建引用。

此前源码同步以远端 main 为基线，并整合远端组件分支的六项修复。冲突按缓存隔离、用户消息保护、抽屉与外观行为逐项处理。该次同步未发布安装包；随后以整合源码构建 `.10` 正式版，保留历史发行记录。

Daily Build 的 nightly 标签修改和 APK 发布仅在手动运行并显式选择 `publish_nightly` 后执行，默认关闭。普通推送、定时构建不能自行发布安装包。
