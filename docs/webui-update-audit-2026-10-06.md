# WebUI 与浏览器访问更新检查

检查日期：2026-10-06。范围：当前工作区的 `web-ui`、`web`、Android Web API/服务及本机开发环境。

后续进展：依赖恢复、高/中优先级更新、设置页及可编辑配置已陆续实施。最新状态见 [Web 设置配置落地与验证](webui-settings-configuration-2026-10-06.md)；下文为初始审计快照。

## 结论

基础工具基本齐全，可继续进行更新准备。当前首要阻塞是 WebUI 依赖文件不完整，类型检查和生产编译尚未通过。浏览器访问可靠性、预览隔离、设置数据最小化、开发文档和测试基础设施应优先更新。

本次未修改业务源码或锁文件。工作区原有大量未提交修改，Web 相关的 `WebApiModule.kt`、`WebServerManager.kt` 和 `web-ui/app/services/api.ts` 已有修改，应在其基础上继续，避免覆盖。

## 实际架构与联调方式

- `web-ui`：React 19 + React Router 7 + TypeScript + Vite，`ssr: false` 的 SPA。
- `web-ui/copy.ts`：将 `build/client` 复制到 `web/src/main/resources/static`。
- `web/build.gradle.kts`：Android library，`preBuild` 依赖 `buildWebUi`；该任务执行 `pnpm run build`。
- `web/Entry.kt`：启动 Ktor CIO、提供 SPA 静态资源。
- `app/.../web`：调用 Android 设置、数据库和 ChatService，提供 REST API 与 SSE。
- `WebServerService`：已有 Android 前台服务；`SettingWebPage` 已包含端口、仅本机、访问密码及局域网权限处理。

开发时需在手机 App 中打开 Web 服务，再将其接入桌面 Vite 代理。默认端口下可使用 `adb forward tcp:8080 tcp:8080`，随后在 `web-ui` 运行 `pnpm run dev`，通过 localhost:5173 联调。端口应与手机配置一致。当前未创建转发，未开启或修改手机服务。

`./gradlew :web:run` 不是该 Android library 模块的独立后端启动方式。Docker 和桌面 Node 服务器不是现有 Android 内嵌部署的必要环境。

## 更新优先级

| 优先级 | 项目 | 当前证据与建议 |
| --- | --- | --- |
| P0 | 恢复可复现的前端依赖 | 类型生成与编译均被 `@babel/code-frame/lib/index.js` 缺失阻塞。先从可信包源恢复依赖，再取得完整 typecheck/build 基线；不要仅凭 node_modules 存在认定环境可用。 |
| P0 | HTML/SVG 预览隔离 | `workbench-host.tsx:165` 对模型生成的 srcDoc 同时启用 `allow-scripts allow-same-origin`。应去掉同源能力或迁移至独立 origin，并回归预览脚本无法访问父页面存储/API。当前为静态代码结论，未执行浏览器攻击验证。 |
| P0 | 浏览器专用设置 DTO | `EventsRoutes.kt:46` 直接序列化完整 Settings；`PreferencesStore.kt:888` 的访问密码未标记 Transient。JWT 签名密钥已标记 Transient，不能将它误报为同样发送。应改为明确字段白名单，核对提供商、备份等配置中的敏感信息。 |
| P1 | 对话 SSE 断线恢复 | `conversations.tsx:353` 建立详情流，错误处理仅记录日志，没有 onClose 重连。共享 `events.ts` 已有固定一秒重连，两者策略不一致。增加断线提示、退避重连、恢复快照及序号处理，并覆盖切网、锁屏恢复和服务重启。 |
| P1 | 鉴权会话完整性 | 当前已有 JWT、sessionStorage Bearer 及 HttpOnly SameSite Cookie。补充退出登录及 Cookie 撤销/清理链路，测试密码更改、过期、媒体访问与重新登录。仅清理 sessionStorage 不等于退出 Cookie 会话。 |
| P1 | 联调与启动配置 | Vite API 地址固定为 localhost:8080；增加环境变量配置及真机/局域网说明。README 为通用 SSR/Docker 模板，`start` 指向 SSR 产物，与实际 SPA 部署不一致。 |
| P1 | 工具链与 CI 一致性 | 本机 Node 24/pnpm 12，CI Node 22/pnpm 11，项目未固定 packageManager/engines。CI 设置 Java 17，而 Gradle daemon 要求 JetBrains 21。统一或明确支持矩阵，避免隐式下载及不同依赖安装行为。 |
| P1 | 自动化测试 | WebUI 未配置 test 脚本或 Playwright/Vitest 测试。现有 Android WebView 渲染测试不能替代浏览器端验证。建议覆盖登录、深链接刷新、流式聊天、断线重连、附件和预览隔离；CI 增加 typecheck、只读 lint、WebUI build 和冒烟测试。 |
| P2 | Android/Web 类型同步 | Kotlin ConversationDto 已有 `workspaceFileOperationMode`，TypeScript ConversationDto/Conversation 缺失；应补齐传输契约与相关交互。 |
| P2 | 渲染能力与离线使用 | Web 工作台目前支持 HTML/SVG/Markdown/Mermaid；Mermaid 从 esm.sh 在线导入，版本仅固定至主版本。按产品需要补齐 Android 端新增图表能力，并将依赖打包本地、明确版本。 |
| P2 | 设置与管理功能范围 | 浏览器只有首页及 `/c/:id` 路由，已有聊天、文件夹、搜索及部分助手选项。若目标是接近完整 Android 客户端，还需规划提供商/助手编辑、知识库、工作区文件与终端等管理 UI/API；这些是待确定范围，不应一概视为现有缺陷。 |

另需将局域网 HTTP、HTTPS 反向代理、CORS 允许来源、媒体 Cookie、缓存及深链接刷新列入浏览器回归矩阵。当前 CORS 使用 anyHost/anyMethod；是否收紧应与实际访问方案一起设计。

## 本机环境

| 项目 | 已核实状态 | 是否需要补齐 |
| --- | --- | --- |
| Node.js | 24.21.0，`<local-path>` | 已有；满足 Vite 7 的 Node 版本要求 |
| pnpm | 12.4.2，Corepack 入口可运行 | 已有；需与 CI 固定版本对齐 |
| WebUI 依赖 | node_modules 存在，但 Babel 入口文件缺失 | 当前必须修复 |
| Java | Oracle JDK 21.0.12.1，JAVA_HOME 为 `<local-path>` | 已有 |
| JetBrains JDK | `gradle-home-test/jdks/jetbrains_s_r_o_-21-amd64-windows.2` 中 JBR 21.0.10 可执行 | 已有；需让实际 Gradle 使用正确缓存/工具链 |
| Gradle | 本地 9.5.0 的 `--version` 成功，项目 wrapper 也指定 9.5.0 | 无须全局安装 Gradle |
| Android Studio | `<local-path> Studio`；自带 JBR 25.0.2 | 已有；其 JBR 25 不应直接当作项目要求的 JBR 21 |
| Android SDK | local.properties 指向用户目录 SDK；含 android-37.0、build-tools 37.0.0 | 已有；另有 D 盘测试 SDK |
| SDK Command-line Tools | 配置的 SDK 未发现 cmdline-tools，sdkmanager 不在 PATH | 建议安装，方便命令行安装/更新 SDK |
| adb | 37.0.1，可列出已授权的 vivo V2301A 真机 | 已有，可走真机联调 |
| 模拟器 | emulator 程序存在，AVD 列表为空，未发现 system-images | 需要模拟器回归时补系统镜像与 AVD；真机开发不必等待 |
| 浏览器 | 已找到 Microsoft Edge 可执行文件 | 手工调试可用；未进行浏览器页面验证 |
| 浏览器自动化 | 项目未配置 Playwright 测试，默认 ms-playwright 缓存未发现 | 建议补 Playwright；可使用已安装 Edge 或独立测试浏览器 |

ANDROID_HOME/ANDROID_SDK_ROOT 当前未设置，adb 之外的 Android 工具未加入 PATH；Gradle 可以通过 local.properties 找到 SDK，因此不能据此判断 SDK 未安装。

## 本次验证结果与限制

- Node、pnpm、JDK、JBR、Gradle、adb 版本均已实际查询；真机已授权连接。
- pnpm 在执行检查时发生自动依赖同步，并行命令发生依赖目录冲突。随后已改为顺序执行，并进行了 frozen-lockfile 安装及 offline/force 缓存恢复；锁文件和业务源码无新增修改，但 Babel 入口文件仍缺失，尚未恢复到可编译状态。不能将所有缺包都归因于检查前的环境。
- 只读 `oxlint app` 完成：109 个文件，7 个未使用变量/导入警告，0 错误。未使用项目中带 `--fix` 的 lint 脚本。
- `pnpm run typecheck` 与 `react-router build` 未通过，均阻塞于依赖加载，尚未形成源码类型或生产构建结论。
- 部分 Corepack/SDK/Gradle 沙箱访问失败，已在获准的沙箱外复核；不将这些沙箱限制算作本机工具缺失。
- 未运行 Android 完整构建、未安装 APK、未进行手机至浏览器端到端验收。设备监听快照中没有默认 8080；自定义端口下的 Web 服务是否运行未确认。
- 未查询线上依赖的全部最新版本或完成漏洞审计。升级依赖应在恢复基线后按兼容性和维护需求执行。

建议顺序：依赖恢复与工具链固定 → 设置 DTO 和预览隔离 → SSE/会话可靠性 → 功能对齐和视觉更新 → 真机浏览器回归。
