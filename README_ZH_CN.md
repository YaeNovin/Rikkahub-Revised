<div align="center">
  <img src="docs/icon.png" alt="Rikkahub Revised 应用图标" width="100" />
  <h1>Rikkahub Revised</h1>

  <p>基于 RikkaHub、由社区独立维护的 Android AI 聊天客户端。</p>

  [English](README.md) | 简体中文

  [![最新版本](https://img.shields.io/github/v/release/YaeNovin/Rikkahub-Revised?display_name=tag&sort=semver)](https://github.com/YaeNovin/Rikkahub-Revised/releases/latest)
  [![许可证：AGPL-3.0](https://img.shields.io/badge/license-AGPL--3.0-blue.svg)](LICENSE)
  [![Android 8.0+](https://img.shields.io/badge/Android-8.0%2B-3DDC84.svg)](https://developer.android.com/about/versions/oreo)
</div>

> [!IMPORTANT]
> Rikkahub Revised 是 [RikkaHub](https://github.com/rikkahub/rikkahub)
> 的独立修改发行版，不是 RikkaHub 官方版本，也不由上游维护者认可或提供支持。
> 与 Revised 有关的问题应提交到本仓库，不应提交给上游项目。

## 项目身份

本仓库提供 Rikkahub Revised 的完整源码。当前发行分支基于上游 RikkaHub
`2.4.8`，对应提交
[`8824e0e8`](https://github.com/rikkahub/rikkahub/commit/8824e0e841f2008b322ca8214a27a978e4b4abaa)。
后续上游版本不会自动包含在本项目中。

| 项目 | 内容 |
| --- | --- |
| 发行名称 | `Rikkahub Revised` |
| Android 应用 ID | `me.rerere.rikkahub.revised` |
| 当前版本 | [`v2.4.8-revised.6`](https://github.com/YaeNovin/Rikkahub-Revised/releases/tag/v2.4.8-revised.6) |
| 最低 Android 版本 | Android 8.0（API 26） |
| 源码仓库 | `YaeNovin/Rikkahub-Revised` |
| 开源协议 | GNU Affero General Public License v3.0 |

独立的应用 ID 允许 Rikkahub Revised 与上游 RikkaHub 在同一台设备上并存。
两个应用的数据相互隔离，本项目不会自动导入上游应用的数据。

## 下载

Rikkahub Revised 的正式 APK 仅通过本仓库的
[GitHub Releases](https://github.com/YaeNovin/Rikkahub-Revised/releases/latest)
发布。

| APK | 适用设备 |
| --- | --- |
| `app-arm64-v8a-release.apk` | 大多数现代 Android 手机和平板电脑 |
| `app-x86_64-release.apk` | x86_64 模拟器及兼容设备 |
| `app-universal-release.apk` | 同时包含 ARM64 与 x86_64 原生库的通用备用包 |

应用内更新功能读取本仓库公开的最新 Release，并根据设备 ABI 提供兼容的
APK。Release 说明仅包含面向用户的更新与修复；安装包完整性和签名连续性在发布
流程中单独核验。

后续 Revised 正式版本必须继续使用同一签名证书。使用其他密钥签名的构建无法覆盖
安装现有的 Rikkahub Revised。公开证书信息与签名连续性要求见
[RELEASE_SIGNING.md](docs/RELEASE_SIGNING.md)。私有签名材料不会存放在本仓库中。

## Revised 的主要改动

Rikkahub Revised 以上游 Android 客户端为基础，主要增加或调整了以下内容：

### 当前正式版：v2.4.8-revised.6

- 为 OpenAI、Claude、Gemini、Grok、Qwen 和 DeepSeek 增加按模型能力适配的请求
  参数，并新增不记录凭据、提示词、Schema 与二进制内容的脱敏诊断。
- 增加流提前终止检测，修正重试时间预算，并完善事件流连接保活。
- 增加 Gemini 生图参数，统一受支持富代码块的源码、内嵌与全屏预览。
- 修复裁剪结果在保存完成前被清理、频繁调整设置后可能无法切换对话、导航替换
  非原子化，以及供应商模型操作栏位置错误的问题。

- 基于 Token 的滚动上下文摘要、上下文窗口自动识别、压缩动画提示和使用量展示。
- 本地知识库、文档导入与分块、混合检索、助手绑定、RAG、来源引用和文件预览。
- 更完整的记忆元数据、基于嵌入的检索、词法回退和限定范围的记忆工具。
- Provider 能力元数据、嵌入支持、连接诊断、脱敏协议追踪、按协议和厂商映射的
  最高推理深度，以及更安全的自定义 Provider 配置处理。
- 更严格的工作区、剪贴板、日历和屏幕使用时间工具审批与隐私边界。
- 分离的备份范围，以及更安全的 S3/WebDAV 归档恢复路径校验。
- 按天清理聊天附件和生成图片，同时保留聊天文本。
- ECharts、ABC 乐谱、Leaflet 和铁路图的交互式渲染，以及 Mermaid、LaTeX、
  Markdown 和 WebView 改进。
- 由 Revised 仓库维护、能够按 ABI 选择 APK 的更新源。
- 独立应用 ID、隔离的 URI Scheme 与 Provider Authority，以及默认关闭的
  Firebase 集成。

完整的修改记录、上游对比和发行要求见
[MODIFICATIONS.md](docs/MODIFICATIONS.md)。

## 继承自上游的基础能力

上游项目为本应用提供了 Android 聊天客户端基础，包括：

- OpenAI、Google、Anthropic 兼容 Provider 和自定义端点。
- 流式对话、多模态输入、文档解析和消息分支。
- MCP、网络搜索 Provider、提示词变量、工具和助手配置。
- Markdown、代码高亮、LaTeX、表格和 Mermaid 渲染。
- 本地记忆、Provider 导入导出、内嵌 Web 界面和可选 Linux 工作区环境。

除非 [MODIFICATIONS.md](docs/MODIFICATIONS.md) 明确记录了修改，否则这些基础能力的
原始实现与作者归属于上游项目。

## 从源码构建

构建环境要求：

- JDK 17
- Android SDK API 37 与 Build Tools 37
- 支持 Submodule 的 Git

克隆完整源码并初始化子模块：

```bash
git clone --recurse-submodules https://github.com/YaeNovin/Rikkahub-Revised.git
cd Rikkahub-Revised
```

在 Windows 上构建开发版 APK：

```powershell
.\gradlew.bat :app:assembleDebug
```

在 Linux 或 macOS 上构建：

```bash
./gradlew :app:assembleDebug
```

使用 `./gradlew test` 或 `gradlew.bat test` 运行 JVM 测试。Android 主应用位于
`app` 模块，其他模块和常见修改入口见
[docs/PROJECT_STRUCTURE.md](docs/PROJECT_STRUCTURE.md)。

聊天请求的运行链路分别提供
[英文文档](docs/references/chat-generation-pipeline.en.md)和
[简体中文文档](docs/references/chat-generation-pipeline.zh-CN.md)。

Release 构建从被忽略的本地 `local.properties` 读取签名值。请勿提交 Keystore、
密码、`local.properties` 或 Firebase 配置。Firebase 与 Crashlytics 默认关闭；
只有主动启用时，才需要提供由维护者控制且与 Revised 应用 ID 匹配的配置。

## 贡献与支持

欢迎通过本仓库的
[Issues](https://github.com/YaeNovin/Rikkahub-Revised/issues)和 Pull Request
报告或改进 Rikkahub Revised。报告问题时请说明该问题是否也会在上游 RikkaHub
出现，并且不要附带 Provider 密钥、账号数据、签名文件或私人聊天内容。

上游文档与社区渠道属于上游项目，不应作为 Revised 专属行为的支持渠道。

## 许可证与归属

Rikkahub Revised 与其上游基础一致，采用
[GNU Affero General Public License v3.0](LICENSE) 发布。分发修改版本或通过网络
提供软件服务时，必须遵守 AGPL-3.0 关于对应源码可获取性的要求。

- [NOTICE](NOTICE) 说明上游项目与 Revised 发行版的关系。
- [MODIFICATIONS.md](docs/MODIFICATIONS.md) 记录主要修改内容。
- [CONTRIBUTORS.md](docs/CONTRIBUTORS.md) 列出直接构建与维护本独立仓库的人员。
- [THIRD_PARTY_NOTICES.md](docs/THIRD_PARTY_NOTICES.md) 列出内置第三方组件及其许可证。
- [RELEASE_SIGNING.md](docs/RELEASE_SIGNING.md) 记录公开签名身份和发行密钥连续性要求。

未修改上游代码的版权与作者身份仍归上游贡献者所有，本仓库不主张这些代码的作者权。
