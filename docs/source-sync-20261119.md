# 源码同步与公共资源整理（2026-11-19）

本次同步整合 Android、AI、Search、Common、Material3、语音、Workspace 与 Web 的开发源码、测试及必要运行时资源。正式发行记录仍以 `2.4.8-revised.9` 为准，本次源码同步不发布正式版安装包。

## 基线与集成

同步前通过 GitHub API 核对，远端 `main` 为 `7302fc3db40d63d33c46ffeb8755b3d03e77d4a5`，与原开发工作区的提交基线相同。
同时整合远端 `codex/upstream-component-migration` 的六个修复，保留其提交历史，以 `fd6d555d5f3c57ad3f310328c7408f48e6cbe57f` 为集成基础。

重叠代码按行为合并：用户消息换行和粘贴源码保护、抽屉打开与输入法处理继续生效；本地的引用与表格增强、引号/HEX 显示、按模式隔离的缓存、内容预算和分帧挂载也保留。增加两项交互回归，核对两种模式下的引用、代码字面量、缓存隔离与快照复用。

## 换行与上传边界

`.gitattributes` 明确自有 Kotlin、XML、TOML、YAML、TypeScript、文档与 Python 脚本使用 LF；Windows 批处理保留 CRLF。第三方分发文件、许可证和二进制不转换，保持其原始哈希。

`scripts/normalize_source_endings.py --check` 检查本轮自有源码，转换不会删除 Markdown 硬换行的尾部空格，也不会改写字符串中的转义序列。

上传排除密钥、凭据配置、设备日志、数据库、堆转储、APK/AAB、缓存、安装依赖及本机维护记录。Room schema、Gradle wrapper、固定版本 AAR、离线渲染依赖及许可证属于必要输入，继续保留。Desktop 和 Three.js 原型单独保留在本机，本次公共版本移除其构建和指南中的孤立引用。

## 文档与图片清理

公共索引与文字配色指南更新为当前行为，新增可移植的构建说明。六份远端已有、已被现行指南取代的文件移出默认分支：

- `animation-audit.md`、`badge-rendering-audit.md`。
- `chat-image-tool-and-confirmation-design.md`、`chat-suggestions-audit.md`。
- `fullscreen-preview-audit.md`、`video-generation-progress.md`。

本地归档继续保留原始文档；发行说明、署名、许可证、签名连续性记录与仍有未完成事项的模块审阅保留。53 个仅在维护者电脑上有效的截图/构建报告链接改为明确的本地证据说明，不把私人测试资料复制进公共仓库。

基于当前源码、文档引用和内容核对，删除 12 张无引用的旧图片，共 8,941,152 字节（约 8.53 MiB）：

| 路径 | 原用途与删除原因 |
| --- | --- |
| `docs/img/{assistants,chat,desktop,models,providers}.png` | 上游旧界面截图，当前指南无引用 |
| `docs/sponsors/{aihubmix.png,suixiang.jpg,ztest.png}` | 无引用的历史宣传图片 |
| `docs/donate.png` | 无引用的捐赠图片 |
| `app/src/main/assets/banner/banner-{1,2,3}.png` | 无引用的旧装饰背景 |

保留当前应用图标、供应商图标和被运行时使用的资源。颜色测试图片采用可重建的合成图案，用户原始壁纸与截图不进入公共树。删除项仍可从既有 Git 历史恢复，不重写历史或删除旧发行资产。

## 上传与核验

配置、功能、测试兼容和文档清理分别提交。更新默认分支时使用正常的快进约束，不强推；上传前再次核对远端基线，上传后核对提交和完整内容树的 Git SHA。

Daily Build 的标签更新与 APK 发布只有在手动运行并显式开启 `publish_nightly` 时执行，默认关闭。普通源码同步不会自动发布安装包。

## 本地验证

Debug APK 已完成构建；Android 设备测试源码编译通过。本轮没有在设备上运行这些测试，也没有覆盖安装或发布 APK。

| 模块 | JVM 测试 | 失败 | 跳过 |
| --- | ---: | ---: | ---: |
| app | 1263 | 0 | 0 |
| ai | 549 | 0 | 0 |
| search | 37 | 0 | 0 |
| common | 23 | 0 | 0 |
| material3 | 21 | 0 | 0 |
| highlight | 62 | 0 | 0 |
| speech | 57 | 0 | 0 |
| web | 2 | 0 | 0 |
| workspace | 44 | 0 | 4 |
| document | 4 | 0 | 0 |

最终 JUnit XML 汇总为 2,058 项通过，4 项因平台条件跳过，0 失败。Workspace 的旧下载夹具补齐必需的 `bin/sh` 后重跑通过；安装器的入口校验保持生效。

另有 13 项 Python 工具测试、18 项 Web 逻辑测试和 5 项浏览器测试通过，Web 类型检查通过，Lint 无错误（有 7 项现有未使用声明警告）。测试使用合成内容和模拟后端，不代表真实供应商请求、全部设备或完整安全审计已验证。

最终提交执行空白检查、候选凭据格式检查、私密/构建产物排除和本地文档链接检查。原始报告保留在本机忽略目录，公共树只保留功能、来源、限制及可复现入口。
