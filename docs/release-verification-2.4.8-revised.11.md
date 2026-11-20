# 2.4.8-revised.11 验证记录 / Verification record

正式包版本为 `2.4.8-revised.11`，版本码 187，包名 `me.rerere.rikkahub.revised`。
面向用户的变化见[更新说明](release-notes-2.4.8-revised.11.md)。
发布源码从 `a5362095a5d06d656273c31a1b7accc0b43cabc1` 开始，仅递增版本并更新发布记录，使用独立的发布 checkout。
Desktop 与 Three.js 实验没有进入该源码树或正式构建。

## 安装包与源码

- APK：`app-arm64-v8a-release-2.4.8-revised.11.apk`，42,074,938 字节。
- SHA-256：`d8f1fccfe4f7882ee4c18ac58aadbe0597de7c47605148a1384f8d0592557206`。
- 签名核验通过，与设备原有 `.10` 及[正式证书](RELEASE_SIGNING.md)一致。
- 包名、版本和版本码正确，非 debuggable，仅包含 `arm64-v8a` 原生库。
- 通过 16 KiB ZIP 对齐检查；此检查不等同于全部原生库在所有 16 KiB 内存页设备上的运行验证。
- 31 份随包浏览器组件许可证与源码逐字节一致；未打包签名私钥或本机配置。
- 内置源码与许可证链接指向本 Revised 仓库。源码扫描未发现凭据指标、失效文档链接或实验路径，改动文本使用 LF。
- 本次只发布 ARM64 APK 和 `SHA256SUMS.txt`；此前版本、标签和资产保留。

## 构建和模块测试

本轮运行 `:app:assembleRelease`、`:app:lintVitalRelease` 和下列模块的
`testDebugUnitTest`，整体构建成功，共 564 个任务，62 个执行、502 个复用最新结果。
应用模块重新执行测试，其他模块的测试结果由 Gradle 判定为最新并复用；没有将全部测试描述为重新执行。

| 模块 | 用例数 | 失败/错误 | 跳过 |
| --- | ---: | ---: | ---: |
| app | 1,270 | 0 | 0 |
| ai | 549 | 0 | 0 |
| common | 23 | 0 | 0 |
| document | 4 | 0 | 0 |
| highlight | 62 | 0 | 0 |
| material3 | 21 | 0 | 0 |
| search | 37 | 0 | 0 |
| speech | 57 | 0 | 0 |
| web | 2 | 0 | 0 |
| workspace | 44 | 0 | 4 |
| 总计 | 2,069 | 0 | 4 |

版本比较测试包含 `.10 < .11`。

完整 `:app:lintRelease` **未通过**：1,724 个错误、472 个警告、8 个提示。
错误包括 1,719 个 `MissingTranslation`、3 个壁纸颜色 API 检查、
1 个忽略的本机 Windows 配置路径转义检查，以及 1 个诊断页资源读取检查。
相比 `.10` 记录，缺译错误增加 8 个，非翻译错误类别与数量一致。
壁纸代码在 API 27 以下将颜色值设为 null，随后通过安全调用读取；
Lint 未沿该路径推导 API 保护。这是源码检查结论，不是旧系统实测。
缺译与诊断页资源观察问题仍需后续处理；没有新增 baseline 或禁用检查。

## USB 真机覆盖与启动

- 在 vivo V2301A、Android 16 上，从已安装的正式版 `.10`（186）使用 `adb install -r --user 0` 覆盖至 `.11`（187），返回 `Success`。
- 应用 UID 与首次安装时间保持一致，未卸载应用、清除数据或替换 QA 包。
- 冷启动成功，首次 Activity 启动记录为 635 ms；这是单次观测，不是性能基准。
- 抽屉进入设置并返回聊天共验证四轮，界面层级符合预期，应用进程保持运行；观测区间未发现本应用的致命异常或 ANR，崩溃缓冲区无对应记录。
- 临时保持亮屏设置已恢复原值。

本轮未重跑全部功能、真实供应商请求、多 Android 版本或内存压力测试。
源码中既有功能的更早真机结果应按各自带日期的文档理解，不能等同于此次正式包的完整回归。
完整构建与 Lint 报告、设备日志、截图、旧安装包及混淆映射仅保留在忽略的本地目录。

## English

The signed ARM64 release is `2.4.8-revised.11` (187), application ID
`me.rerere.rikkahub.revised`. Assembly and vital release Lint passed. Module
reports contain 2,069 tests with no failures or errors and four skips; the app
tests were executed, while unchanged dependency-module results were reused by
Gradle. Certificate continuity, package metadata, non-debuggable status, ARM64
contents, 16 KiB ZIP alignment, and all 31 bundled browser license files passed
verification. ZIP alignment alone does not establish universal native 16 KiB-page support.

An in-place USB upgrade from `.10` to `.11` succeeded on a vivo V2301A running
Android 16. The application UID and first-install time were retained. Cold
launch and four drawer/settings/chat round trips passed without an observed
fatal exception or ANR. The single 635 ms Activity launch is not a general
performance benchmark. Temporary keep-awake settings were restored. Complete
feature, live-provider, multi-version Android, and memory-pressure suites were
not rerun. Full reports, device evidence, and obfuscation mappings remain local.

Full release Lint **failed** with 1,724 errors, 472 warnings, and eight hints:
1,719 missing translations, three wallpaper API checks, one ignored local
Windows-path escape, and one resource lookup in diagnostics. The missing
translation count increased by eight compared with the `.10` record; other
error categories and counts are unchanged. Source inspection confirms the
wallpaper colors are null below API 27 before safe reads, but this does not
replace old-device testing. Translation and resource-observation work remains.
No Lint baseline or disabled checks were added.
