# 构建与本地验证

## 环境

Android 工程使用 Gradle wrapper、JDK 21 和 Android SDK；具体插件及依赖版本以 `gradle/libs.versions.toml` 为准，SDK 版本以 `app/build.gradle.kts` 为准。首次克隆需要初始化 Material Color Utilities 子模块：

```text
git submodule update --init --recursive
```

在本机 `local.properties` 中设置 `sdk.dir`，或使用 `ANDROID_HOME`。该文件、签名密钥与密码不进入 Git。保留 `gradle/wrapper/`、Room schema、离线渲染依赖、第三方许可证和 `third_party/hugeicons-compose/` 的校验后 AAR，它们属于构建输入。

Web 模块的前端资源来自 `web-ui/`；先依据该目录的 `package.json` 安装相应 Node/pnpm 版本并运行 `pnpm install --frozen-lockfile`。根目录的 npm 开发工具与 Web 子项目的 pnpm 依赖分别管理，不上传任何 `node_modules`。

## 日常命令

```text
python scripts/build_qa.py
python scripts/build_qa.py :app:assembleDebug
python scripts/build_qa.py :app:testDebugUnitTest --tests '*MarkdownCitationTest'
```

Python 入口使用标准库，可在没有本机资源库时直接调用 Gradle wrapper。需要仅使用已有依赖时加 `--offline --max-attempts 1`；缺少依赖会直接失败，此时应恢复依赖后再构建，而不是删除全部缓存。

QA 包 ID 为 `me.rerere.rikkahub`，沿用旧测试安装身份；Debug 和 Revised 正式版使用独立包 ID。覆盖安装前必须核对包 ID 和签名。详见 [签名连续性](RELEASE_SIGNING.md)。

## 可选的本机资源库

`scripts/local_build_resources.py` 从环境变量 `RIKKAHUB_BUILD_RESOURCES` 或仓库上一级的 `build-resources/resources.json` 读取可选配置。不存在该文件时使用普通 wrapper/SDK 配置，其他开发者不需要复刻维护者的磁盘布局。

配置格式为 `schemaVersion: 1`，包含 `gradle`、`javaHome`、`gradleUserHome`、`androidSdk` 和 `mavenRepository` 的本机路径。`agpVersion`、`aapt2` 可用于匹配本机 AAPT2。配置与资源库留在仓库外，Python 入口仅为构建子进程设置环境变量，不改系统环境。

本机历史迁移脚本属于维护工具；不要在另一台电脑上直接运行带预设清理目标的操作。C/D 盘迁移、空间统计和设备原始报告仅保留在忽略的 `docs/local/`。

## 推送前清单

```text
python scripts/prepare_github.py
python -m unittest discover -s scripts -p test_prepare_github.py
```

脚本只读取 Git 路径与候选文件，生成源码、单独审阅、待处理、排除四类清单；不会暂存、提交、推送或访问远端。输出在 `docs/local/github-preparation-current/`，匹配的凭据值不进入报告。它检查常见凭据形式和本地链接，不能替代内容、图片来源与 Git 历史审阅。
