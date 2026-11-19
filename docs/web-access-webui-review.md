# Web 访问与 WebUi 审查记录

> 审查日期：2026-09-15
>
> 本文记录当前 Web 服务、Web API、Android WebView 和 WebUi 的实现方式、已知问题及后续建议。运行时行为仍需通过真机、局域网和不同 Android System WebView 版本验证。

## 1. 当前实现概览

### Web 服务

- 使用 Ktor CIO 嵌入式服务器，默认端口为 8080。
- 支持仅本机监听（`127.0.0.1`）和局域网监听（`0.0.0.0`）。
- 局域网模式通过 NSD/mDNS 广播服务。
- `WebServerService` 使用前台服务和常驻通知维持服务运行。
- `WebServerManager` 通过 `StateFlow<WebServerState>` 向设置页面报告状态。

### Web API

- REST 接口负责会话、文件、文件夹、助手、模型和设置操作。
- SSE 接口负责全局设置/列表变更，以及单个会话的流式消息更新。
- 可选 JWT 鉴权，当前浏览器端使用 Ky 请求 API，自定义解析 SSE。
- 文件接口支持上传、按 ID 下载和按应用文件目录相对路径读取。

### WebUi

- React Router 7 的 SPA（关闭 SSR），配合 React、TypeScript、Tailwind、Zustand 和 React Query。
- 构建产物由 `web-ui/copy.ts` 复制到 Android Web 模块的静态资源目录，Android `preBuild` 自动触发 WebUi 构建。
- WebUi 通过 `/api` 同源访问 Android 内嵌服务，并使用 SSE 接收实时更新。

### Android WebView 与渲染

- 使用 Compose WebView 封装，采用 `https://rikkahub.local` 作为稳定虚拟 origin。
- `/assets/...` 由 `WebViewLocalAssets` 映射到 APK 内置资源，图形渲染器主要离线加载。
- 支持缩放、拖动、加载超时、渲染进程退出恢复、内联 WebView 预算和全屏预览。
- 已接入 Mermaid、ECharts、GeoJSON、铁路图、简谱、ABC、Vega、WaveDrom、SMILES、MusicXML 等渲染路径。

## 2. 已知问题

### P0：安全

1. JWT 路由保护结构在 Web 服务启动时读取一次，运行中切换开关可能需要重启服务才能完全生效。
2. 令牌曾允许通过 `?access_token=` 查询参数传递，可能泄露到历史记录、日志、Referer 或截图。
3. JWT 签名密钥直接取访问密码，密码强度不足时容易受到离线猜测影响。
4. 登录接口缺少明显的失败限速、退避和锁定机制。
5. CORS 使用 `anyHost()` 和 `anyMethod()`，局域网场景下范围过宽。
6. `aiIconRoutes` 位于 JWT 保护路由之外，需要持续确认只暴露固定图标资源。
7. 全局异常处理可能直接返回内部异常文本。
8. 尚未形成统一的 CSP、安全响应头和 HTML/SVG 内容隔离策略。

### P1：可靠性

1. WebServerManager 的启停操作异步执行，快速重复点击、停止后立即启动和重启时存在竞态风险。
2. 端口预检查与实际 bind 之间存在 TOCTOU 窗口，最终仍需依赖 bind 异常处理。
3. SSE 已有自动重连，但还可以补充退避抖动、事件续传和快照恢复。
4. WebUi 普通 API 默认 30 秒超时，大文件上传或长时间操作需要更细粒度策略。
5. 服务被系统回收后的恢复策略需要在 Android 9 至 Android 17 真机上确认。

### P1：文件和 WebView

1. 上传会先将单文件完整读入内存，多个并发大文件可能造成内存压力。
2. 文件路径使用字符串 `..` 检查和 canonical 字符串前缀检查，建议改为 `Path` 规范化后进行边界判断。
3. `/api/files/path/...` 需要确认不会读取数据库、日志或其他内部文件。
4. HTML、SVG、JavaScript 等内容直接返回时，需要下载策略、CSP 和清洗机制。
5. 通用 WebView 应明确关闭文件访问、混合内容和不必要的外部窗口能力。
6. Workbench 的 Mermaid 仍有通过 `esm.sh` 访问网络的路径，与离线资源策略不完全一致。
7. Mermaid 使用宽松安全模式并写入 `innerHTML`，模型输出不可信时需防止 SVG/脚本注入。

### P2：可维护性和体验

1. API 尚未有正式版本前缀、健康检查和能力查询接口。
2. SSE 缺少统一的事件版本、`Last-Event-ID` 续传契约和长任务状态模型。
3. 搜索、文件传输和会话修改可以增加分页、ETag、Range、缓存校验和冲突检测。
4. WebUi 的令牌、语言切换、错误提示、焦点管理和无障碍体验仍可加强。
5. 当前 Web API 的集成安全测试、上传压力测试和多 Android 版本测试覆盖不足。

## 3. 推荐实施顺序

1. 串行化 WebServerManager 启停和重启流程。
2. 使用随机持久化 JWT secret，令牌仅通过 Authorization 或受限 Cookie 传递，移除常规 URL 令牌。
3. 增加认证限速、错误脱敏、安全响应头、CORS 白名单和请求体限制。
4. 收紧文件路径、HTML/SVG 内容和 WebView 权限。
5. 完善 SSE 断线恢复、长任务状态和 API 版本化。
6. 最后扩展能力查询、OpenAPI、PWA、离线缓存和高级 WebUi 体验。

## 4. 验证清单

- 本机和局域网两种监听模式。
- JWT 开关、密码修改、服务重启和旧令牌失效。
- SSE 断网、后台、进程恢复和重复连接。
- 20 MiB 边界、并发上传、路径穿越和非用户文件访问。
- Android 9、12、13、15、17 与不同 System WebView 版本。
- Mermaid、SVG、GeoJSON、外链资源和渲染进程退出场景。
