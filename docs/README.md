# 文档索引与维护范围

更新日期：2026-09-15。当前正式版为 `2.4.8-revised.9`；此前的开发源码更新已收入该版本。
以下指南仍区分已实现能力、兼容限制和待实现方案。

## 使用与开发指南

| 主题 | 文档 |
| --- | --- |
| 世界书 | [使用与扫描深度教程](worldbook-guide.md)、[SillyTavern 兼容边界](worldbook-compatibility-audit.md) |
| 语音服务 | [TTS/ASR、模型获取、能力约束与验证边界](speech-services.md) |
| 工具问答 | [ask_user 参数及交互](ask-user-upgrade.md) |
| 聊天 | [入口与分支](chat-entry-improvements.md)、[聊天建议](chat-suggestions-upgrade.md)、[上下文与记忆](context-memory-upgrade.md) |
| 记忆 | [仅提取模式](memory-extraction-only.md)、[身份与可读性](memory-identity-and-readability.md) |
| 图片与视频 | [GPT Image 2.5 适配](openai-image-2.5-adaptation.md)、[视频供应商配置](video-provider-setup.md) |
| 渲染 | [组件列表](rendering-components.md)、[WebView](webview-rendering.md)、[全屏背景与离线地图](fullscreen-background-offline-maps.md)、[GitHub 仓库卡片](github-repository-cards.md)、[LaTeX 与输入栏](latex-composer-fixes.md) |
| 外观 | [液态玻璃](liquid-glass.md)、[文字配色](text-color-modes.md)、[背景与输入栏](background-composer-fixes.md) |
| 日志 | [持久化、崩溃处理与长文本](logging-reliability.md) |
| 工程 | [项目结构](PROJECT_STRUCTURE.md)、[回归脚本](../scripts/README.md)、[生成链路（中文）](references/chat-generation-pipeline.zh-CN.md)、[Generation pipeline (English)](references/chat-generation-pipeline.en.md) |

## 版本与归属

- [2.4.8-revised.9 发行说明](release-notes-2.4.8-revised.9.md)
- [2.4.8-revised.9 验证结果与限制](release-verification-2.4.8-revised.9.md)
- [2026-09-15 源码更新及验证记录](changes-2026-09-15.md)
- [2026-09-12 源码更新](changes-2026-09-12.md)
- [2.4.8-revised.8 发行说明](release-notes-2.4.8-revised.8.md)
- [2.4.8-revised.7 历史发行说明](release-notes-2.4.8-revised.7.md)
- [修改声明](MODIFICATIONS.md)、[贡献者](CONTRIBUTORS.md)、[第三方归属](THIRD_PARTY_NOTICES.md)
- [签名连续性与公开证书记录](RELEASE_SIGNING.md)、[许可证](../LICENSE)、[NOTICE](../NOTICE)

发行说明按标签对应的实际安装包保存，不把开发分支新功能补写成旧安装包已有功能。

## 历史审查与讨论

历史审查保留原因、处理方案及当时的验证边界，不作为当前版本的功能承诺：

- [动画审查](animation-audit.md)
- [全屏返回位置审查](fullscreen-preview-audit.md)
- [徽章渲染审查](badge-rendering-audit.md)
- [聊天建议审查](chat-suggestions-audit.md)
- [视频阶段进度与待办](video-generation-progress.md)

待实现方案：[聊天模型调用图片模型与 ask_user 确认](chat-image-tool-and-confirmation-design.md)。
该文档公开用于讨论，尚未实施其中的工具编排方案；已有手动聊天生图不等于此方案已完成。

## 本次上传与清理清单

| 处理 | 范围 | 原因 |
| --- | --- | --- |
| .9 发行更新 | `.9` 发行说明与验证记录、双语 README、签名契约、源码记录与工程索引 | 以新版本发布；安装包作为 Release 附件，不进入源码树 |
| 新增上传 | 本索引、`changes-2026-09-15.md`、`speech-services.md`、`logging-reliability.md`、`worldbook-compatibility-audit.md` | 当前新增能力、测试及限制需要公开说明 |
| 新增上传并标记讨论稿 | `chat-image-tool-and-confirmation-design.md` | 保留后续设计依据，明确未实现 |
| 更新上传 | 两种语言 README、`MODIFICATIONS.md`、`PROJECT_STRUCTURE.md`、`worldbook-guide.md`、`ask-user-upgrade.md` | 区分发行版与开发源码，修正构建前提，补齐新实现入口 |
| 更新状态与隐私表述 | 动画、徽章、聊天建议、全屏预览审查记录 | 明确历史时点，去除用户截图的本机目录 |
| 保留在仓库 | 历史发行说明、已有有效指南、项目规范、项目专用技能、`skills-lock.json`、许可证与归属文件 | 维护可追溯性及开发入口 |
| 从当前仓库停止跟踪 | `.agents/skills/claude-api/`、`.agents/skills/gemini-api-dev/`、`.agents/skills/gemini-interactions-api/` | 外部安装的开发参考文档副本；保留本地文件及来源锁定记录，避免维护易过期的镜像 |
| 仅本地保留，不上传 | 用户日志、聊天/世界书样本、截图、ADB 采集、测试报告、QA APK、构建缓存、私有签名与 `local.properties` | 用户数据和生成产物不属于源码；需要分享时另行脱敏和确认 |

停止跟踪只移除当前版本中的文档副本，不重写 Git 历史、不删除本地副本。
外部技能由需要它们的开发者按 `skills-lock.json` 的来源自行安装，应用构建不依赖它们。
原有项目专用的图标查找、翻译和发布技能继续跟踪。

Room schema、离线渲染库、运行时模型资源和第三方许可证是构建、迁移或运行所需，
不能按临时文件清理。新的本地笔记可放在已忽略的 `docs/local/` 中；公开复现示例应使用合成数据。

## English overview

The published release is `2.4.8-revised.9`, which includes the preceding source
update. Consult the bilingual [release notes](release-notes-2.4.8-revised.9.md),
[source update](changes-2026-09-15.md), and [English README](../README.md).
Historical audits retain their original validation limits. The image-tool
confirmation document is a proposal, not an implemented feature.

This upload includes current guides, tests, and project maintenance documents.
Downloaded Claude/Gemini skill copies are removed from tracking while local
copies and their source lockfile remain. User evidence, generated reports,
packages, caches, and private signing data are excluded. Release notes,
licenses, database schemas, and bundled runtime resources are retained.
