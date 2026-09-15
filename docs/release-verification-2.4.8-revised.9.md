# 2.4.8-revised.9 验证记录 / Verification record

## 中文

本记录与面向用户的[更新说明](release-notes-2.4.8-revised.9.md)分开维护。
安装包来自 `2.4.8-revised.9` 标签对应的源码，版本码 185，包名
`me.rerere.rikkahub.revised`，最低 Android 8.0（API 26），目标 API 37。

- 正式 ARM64 APK：`app-arm64-v8a-release-2.4.8-revised.9.apk`，46,201,210 字节。
- SHA-256：`8674cbba55a66647674a7b80c12cf1c8a3eb10d4cda7db04b8566358acdb070a`。
- APK 签名验证通过，与[既有正式证书](RELEASE_SIGNING.md)一致；16 KiB 页面 ZIP 对齐检查通过，未设置 debuggable。
- `:app:assembleRelease :app:lintVitalRelease` 通过；完整 Lint 的不同结果单独记录在下方。
- APK 内保留 26 份浏览器组件许可证文件，未包含私有签名文件或 `local.properties`；本次未变更第三方依赖版本或离线库。
- 同一业务源码此前通过 614 项 JVM 测试及 QA Kotlin 编译；本次运行正式版压缩构建。测试范围及缓存复用说明见[源码记录](changes-2026-09-15.md)。
- 本轮没有连接的 ADB 设备或可用模拟器，因此没有重新执行正式 APK 的安装、启动和覆盖升级测试。此前菜单设备回归不等同于本轮正式包的全功能设备验证。

### Android Lint 的实际结果

全量 `:app:lintRelease` **未通过**：1,788 项错误、511 项警告、7 项提示。
错误分类为：缺失翻译 1,740 项、界面资源读取 36 项、语言变化观察 5 项、
系统版本检查 2 项、字符串格式 2 项，以及通知权限、Activity 转换、remember 返回值各 1 项。

逐项将报告对应源码片段与 `.8` 标签比对（翻译项目按资源名比对），均能在历史源码中找到。
这是来源核对，并非重新运行旧版 Lint，也不说明每项都无风险或已经修复。
通知调用已有通知启用检查及异常捕获；系统动态色路径已有系统版本能力选择，但 Lint 未识别这种间接保护。
其余项目仍需后续专项整理。本次不增加 Lint baseline、不关闭检查，不将构建成功表述为全量 Lint 通过。

完整机器报告、构建日志与混淆映射保存在本地发布归档中，不作为安装包附件上传。
旧 `.7`、`.8` 的版本标签和 Release 资产保持不变。

## English

This record is separate from the user-facing [release notes](release-notes-2.4.8-revised.9.md).
The package corresponds to the `2.4.8-revised.9` source tag, version code 185,
application ID `me.rerere.rikkahub.revised`, minimum API 26, and target API 37.

- ARM64 APK: `app-arm64-v8a-release-2.4.8-revised.9.apk`, 46,201,210 bytes.
- SHA-256: `8674cbba55a66647674a7b80c12cf1c8a3eb10d4cda7db04b8566358acdb070a`.
- Signature verification passed with the existing release certificate; 16 KiB
  ZIP alignment passed, and the APK is not debuggable.
- `:app:assembleRelease :app:lintVitalRelease` passed; the separate full Lint
  result is recorded below.
- The APK retains 26 browser-component license files and contains no private
  signing files or `local.properties`. Dependency versions and offline libraries
  are unchanged in this release.
- The same feature source previously passed 614 JVM tests and QA Kotlin
  compilation; this run builds the optimized release. See the source record for
  test scope and cached-result reuse.
- No ADB device or configured emulator was available for a fresh release APK
  install, launch, or in-place-upgrade test. Earlier menu-device tests do not
  establish complete device validation of this release binary.

Full `:app:lintRelease` **failed** with 1,788 errors, 511 warnings, and 7 hints:
1,740 missing translations, 36 context-based resource reads, 5 locale observation
issues, 2 API checks, 2 string-format issues, and one each for notification
permission, Activity casting, and a remember return value.

Every reported source snippet (or resource name for translations) was found in
the `.8` tag. This comparison is not a rerun of older Lint and does not establish
that the findings are harmless or fixed. Notification delivery already checks
notification availability and catches exceptions; dynamic colors are already
selected through an API capability check that Lint does not trace. The remaining
findings require follow-up. No baseline or disabled checks are introduced, and a
successful APK build must not be presented as a clean full Lint run.

Machine reports, build logs, and the obfuscation map remain in the local release
archive. The original `.7` and `.8` tags and release assets are unchanged.
