# 2.4.8-revised.12 验证记录

版本为 `2.4.8-revised.12`，版本码 188，正式包名 `me.rerere.rikkahub.revised`。
更新内容见[发行说明](release-notes-2.4.8-revised.12.md)。

本轮使用独立发布 checkout，从远端 `main` 的 `81ba708b1` 整合本地 Android 改动。功能提交为 `6e44da399`。
Desktop、Three.js 原型、其测试/图片/文档、Gradle 插件和 Debug 清单引用均未进入发布源码。
原开发目录中的实验内容继续保留。

## 构建与安装包

- `:app:assembleRelease`、R8 和 `:app:lintVitalRelease` 通过。正式构建复用 380 个 Web UI 静态资源文件，已核对其输入与前端构建脚本一致。完整 `lintRelease` 未重跑。
- 发布 ARM64 APK `app-arm64-v8a-release-2.4.8-revised.12.apk`，大小 44,911,161 字节。SHA-256 为 `89a98a5e38cadd8a99d2d0134bd368643428c5bc0cb2fb107979e1108a5e2026`，同时提供 `SHA256SUMS.txt`。
- 正式签名证书与 `.11` 一致。已核对包名、版本码、不可调试状态、原生库 ABI 与 16 KiB ZIP 对齐。该对齐检查的范围为 APK 容器。
- APK 内的 35 份许可证或署名文件全部与源码副本一致。核对了 Verovio 模块、Doulos SIL 字体、渲染脚本和内置源码链接，包内未发现实验资源或签名材料。

## JVM 与浏览器回归

10 个 Android 模块的 JVM 报告共记录 2,119 个用例，其中 2,115 个通过、4 个跳过。Windows 检出导致语法高亮快照使用 CRLF，已对该目录固定 LF 并复测。浏览器回归覆盖 33 个语言学场景、18 个音乐学场景和 20 个既有渲染场景。另有 13 页渲染脚本通过语法与模拟接口检查。

## 真机覆盖安装

在 vivo V2301A、Android 16 上通过 USB ADB 从正式版 `.11` 覆盖安装到 `.12`。应用 UID 与首次安装时间一致。应用启动后保持在前台，单次 Activity 启动记录为 958 ms。

## 验证范围

此前专项验证按各自文档保留，不能等同于本次正式包的完整回归：

- [GitHub Token 与卡片](github-token-native-cards-20261120.md)、[OG 风格卡片](github-social-preview-20261120.md)
- [记忆同步与检索预算](memory-optimization-20261120.md)
- [语言学](linguistic-rendering.md)、[音乐学](music-rendering.md)、[MiniMax 入口](minimax-endpoint-update-20261121.md)

MEI 纽姆歌词当前未参与刻谱/PDF。整条消息网页预览与独立 Web UI 不承诺支持全部 Android 专用渲染格式。
完整日志、设备证据、签名材料与混淆映射保留在忽略的本地目录。
