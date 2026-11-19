# 2.4.8-revised.10 验证记录 / Verification record

## 中文

本记录与面向用户的[更新说明](release-notes-2.4.8-revised.10.md)分开维护。
正式包版本为 `2.4.8-revised.10`，版本码 186，包名
`me.rerere.rikkahub.revised`，最低 Android 8.0（API 26），目标 API 37。
完整业务源码对应同名版本标签，沿用[既有正式签名](RELEASE_SIGNING.md)。

- ARM64 APK：`app-arm64-v8a-release-2.4.8-revised.10.apk`，42,067,205 字节。
- SHA-256：`2c60ae2f8db5b4a963e8ad6f5c0c11bc67e5b3c973a3150888ea7ec5e53c8927`。
- 签名验证通过，证书 SHA-256 为 `6c69b3b5f8206cc72108d7814e760ad62d3d8d92e3020203b1250ce37650e6fe`，与此前正式版一致。
- 仅包含 `arm64-v8a` 原生库，未开启 debuggable；16 KiB 页面 ZIP 对齐检查通过。此项 ZIP 检查不能代替所有原生库在 16 KiB 页面设备上的运行验证。
- `:app:assembleRelease :app:lintVitalRelease` 通过。安装包保留全部 31 份浏览器组件许可证，逐文件与源码核对一致；未包含签名文件或 `local.properties`，源码与许可证链接指向 Revised 仓库。
- 整合源码此前通过 2,058 项 JVM 测试、4 项平台跳过及 Debug 构建；详见[源码同步记录](source-sync-20261119.md)。本轮仅递增发布版本并整理发布文档，不将此前整套测试表述为重新执行。
- 本轮另行运行现有版本比较测试，9 项全部通过，覆盖 `.9` 小于 `.10`、可选 `v` 前缀和开发版本的排序。
- 在 vivo V2301A 上全新安装正式包，实际读取版本为 186 / `2.4.8-revised.10`。首次冷启动返回成功，Activity 计时 801 ms；抽屉、进入设置和返回聊天的基础流程通过，观测期间应用进程未退出、未出现 `FATAL EXCEPTION`。该单次计时不是通用性能基准。
- 本轮未执行旧正式版覆盖升级、完整功能、真实模型请求或多系统版本验证；现有 QA 包和数据未修改。真机验证临时保持亮屏设置已恢复。

### 完整 Android Lint 的实际结果

`:app:lintRelease` **未通过**：1,716 项错误、473 项警告、8 项提示。

| 错误规则 | 数量 | 核对结果与边界 |
| --- | ---: | --- |
| MissingTranslation | 1711 | 多语言翻译缺失，仍待专项整理 |
| NewApi | 3 | 壁纸颜色的三个取值调用；取数路径已有 API 27 检查，旧系统返回空值，Lint 未沿空值传播识别该保护；仍需旧系统设备验证 |
| PropertyEscape | 1 | 本机临时 `local.properties` 的 Windows 路径转义提示；该配置不进入 Git 或 APK |
| LocalContextGetResourceValueCall | 1 | 诊断页面的资源读取不随配置变化自动更新，仍需后续调整 |

本轮不增加 Lint baseline，不关闭检查，也不把正式构建或发布必需检查通过表述为完整 Lint 通过。
构建日志、完整报告、设备截图及混淆映射保留在本机发布归档中，不作为 Release 附件。
历史版本、标签与资产保留，当前仅发布 ARM64 APK 和 `SHA256SUMS.txt`。

## English

The signed release uses version `2.4.8-revised.10`, version code 186,
application ID `me.rerere.rikkahub.revised`, minimum API 26, and target API 37.
The matching source is published under the version tag. User-facing changes are
listed separately in the [release notes](release-notes-2.4.8-revised.10.md).

- ARM64 APK: `app-arm64-v8a-release-2.4.8-revised.10.apk`, 42,067,205 bytes.
- SHA-256: `2c60ae2f8db5b4a963e8ad6f5c0c11bc67e5b3c973a3150888ea7ec5e53c8927`.
- Signature verification matches the existing release certificate. The APK is
  not debuggable, contains only ARM64 native libraries, and passes 16 KiB ZIP
  alignment. ZIP alignment does not establish runtime support for every native
  library on every 16 KiB-page device.
- Release assembly and vital release Lint passed. All 31 bundled browser license
  files match the source. No signing material or local properties are packaged;
  source and license links point to this Revised repository.
- The integrated feature source previously passed 2,058 JVM tests, with four
  platform skips, and a Debug build. Those full suites were not rerun merely for
  this version bump. Existing updater version tests were run separately for this
  release; all nine passed, including `.9` to `.10` ordering.
- A fresh installation on a vivo V2301A launched successfully. The first Activity
  launch reported 801 ms; opening the drawer, visiting settings, and returning
  to chat passed without an observed fatal exception or process exit. This
  single observation is not a general performance benchmark.
- No older-release upgrade, complete feature suite, live-provider requests, or
  multi-version Android checks were performed in this run. QA data was preserved
  and the temporary keep-awake setting was restored.

Full `:app:lintRelease` **failed** with 1,716 errors, 473 warnings, and eight hints:
1,711 missing translations, three wallpaper API checks, one local Windows-path
escape, and one configuration-unaware resource lookup in diagnostics.
Wallpaper retrieval already checks API 27 and supplies null on older systems;
Lint does not follow that nullable protection through the subsequent reads.
This is a source inspection, not old-device validation. The path finding refers
to ignored local configuration; translation and resource-observation work remains.
No baseline or disabled checks were added.

Full reports, device evidence, and the obfuscation mapping remain local. This
release publishes only the ARM64 APK and its checksum file and retains all older
release assets and tags.
