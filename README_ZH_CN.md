<div align="center">
  <img src="docs/icon.png" alt="Rikkahub Revised 应用图标" width="88" />
  <h1>Rikkahub Revised</h1>
  <p>基于 <a href="https://github.com/rikkahub/rikkahub">RikkaHub</a> 独立维护的 Android AI 聊天应用。</p>
  <p><a href="README.md">English</a> · 简体中文</p>
  <p>
    <a href="https://github.com/YaeNovin/Rikkahub-Revised/releases/latest"><img alt="最新正式版" src="https://img.shields.io/github/v/release/YaeNovin/Rikkahub-Revised?label=Release" /></a>
    <a href="https://developer.android.com/about/versions/oreo"><img alt="Android 8.0 或更高版本" src="https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android&amp;logoColor=white" /></a>
    <a href="https://github.com/YaeNovin/Rikkahub-Revised/stargazers"><img alt="GitHub 星标数" src="https://img.shields.io/github/stars/YaeNovin/Rikkahub-Revised?style=flat&amp;logo=github" /></a>
    <a href="LICENSE"><img alt="AGPL-3.0 许可证" src="https://img.shields.io/badge/License-AGPL--3.0-blue" /></a>
  </p>
  <p>
    <a href="https://trendshift.io/repositories/178561?utm_source=trendshift-badge&amp;utm_medium=badge&amp;utm_campaign=badge-trendshift-178561" target="_blank" rel="noopener noreferrer"><img src="https://trendshift.io/api/badge/trendshift/repositories/178561/daily?language=Kotlin" alt="YaeNovin/Rikkahub-Revised | Trendshift" width="250" height="55" /></a>
  </p>
</div>

## 📲 下载安装

**[在 GitHub Releases 下载最新版 APK](https://github.com/YaeNovin/Rikkahub-Revised/releases/latest)**

当前正式版提供带版本号的 ARM64 APK 和 `SHA256SUMS.txt` 校验文件。旧版本可在[发行归档](https://github.com/YaeNovin/Rikkahub-Revised/releases)中找到。Revised 使用独立的应用 ID `me.rerere.rikkahub.revised`，可与上游 RikkaHub 同时安装，两者分别保存数据。覆盖更新需要匹配 [Revised 签名证书](docs/RELEASE_SIGNING.md)。

## ✨ 你可以用它做什么

- **用自己的供应商和模型聊天。** 接入 OpenAI、Gemini 或 Claude 兼容服务，使用流式回答、图片与文档输入、网页搜索和助手配置。
- **管理长对话的上下文。** 从任意消息创建分支，自动总结历史，为助手绑定本地知识库、记忆或世界书。
- **在对话中查看富文本和交互内容。** 浏览 Markdown 表格、搜索引用、公式、代码高亮、图表、关系图、曲谱和只读 ANSI 终端预览。
- **按自己的习惯使用界面。** 可从壁纸提取文字颜色，开启 HEX 色块和引号高亮，使用语音服务或内置 Web 界面。

功能覆盖随模型、协议和预览入口而异。当前源码的详细说明见[文档索引](docs/README.md)与[渲染支持清单](docs/rendering-roadmap-20261120.md)。

<details>
<summary>📚 版本记录与上游基线</summary>

- [2.4.8-revised.10 发行说明](docs/release-notes-2.4.8-revised.10.md)和[验证记录](docs/release-verification-2.4.8-revised.10.md)
- [完整发行记录](https://github.com/YaeNovin/Rikkahub-Revised/releases)和[修改声明](docs/MODIFICATIONS.md)
- 本发行系列的上游基线为 [RikkaHub 2.4.8 的 `8824e0e8`](https://github.com/rikkahub/rikkahub/commit/8824e0e841f2008b322ca8214a27a978e4b4abaa)。

</details>

<details>
<summary>🛠️ 从源码构建</summary>

安装 JetBrains JDK 21、Android SDK API 37，并按[构建指南](docs/building.md)准备 Web UI 依赖。

```bash
git clone --recurse-submodules https://github.com/YaeNovin/Rikkahub-Revised.git
cd Rikkahub-Revised
./gradlew :app:assembleDebug
```

在 Windows 上执行 `.\gradlew.bat :app:assembleDebug`。测试命令及模块入口见[开发脚本](scripts/README.md)和[项目结构](docs/PROJECT_STRUCTURE.md)。

</details>

## 💬 反馈与归属

Rikkahub Revised 独立维护。欢迎通过本仓库的 [Issues](https://github.com/YaeNovin/Rikkahub-Revised/issues) 或 [Pull Requests](https://github.com/YaeNovin/Rikkahub-Revised/pulls) 反馈。报告问题时请附应用版本和复现步骤，提交前去除凭据和私密对话。

项目采用 [GNU Affero General Public License v3.0](LICENSE)。上游归属、贡献和依赖许可见 [NOTICE](NOTICE)、[贡献者](docs/CONTRIBUTORS.md)和[第三方声明](docs/THIRD_PARTY_NOTICES.md)。
