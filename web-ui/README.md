# RikkaHub WebUI

React Router 7 / React 19 / TypeScript / Vite SPA。浏览器通过 REST 和 SSE 连接 Android App 内嵌的 Ktor 服务，数据和模型调用由手机处理。

## 环境

- Node.js 22.12+，推荐与 CI 一致使用 Node 24。
- pnpm 12.4.2（package.json 已固定）。
- Android 构建使用项目 Gradle 9.5 wrapper、JetBrains JDK 21 和 SDK 37。Java 21 可启动 wrapper，Gradle daemon 根据 gradle-daemon-jvm.properties 选择/下载 JBR 21。
- 真机联调需要 adb 和已授权设备；只改前端可复用已运行的手机服务。

## 开发与真机联调

1. 手机 App → 设置 → Web 服务，配置端口并启动；建议启用访问密码。USB 调试可选择“仅本机访问”。
2. 创建转发：`adb -s <设备序列号> forward tcp:18080 tcp:8080`（右侧端口与手机配置一致）。
3. 在本目录复制 `.env.example` 为 `.env.local`，设置 `WEB_API_TARGET=http://127.0.0.1:18080`。
4. `pnpm install --frozen-lockfile`，然后 `pnpm run dev`，访问终端显示的 localhost 地址。

局域网联调时关闭手机服务的“仅本机访问”，将 WEB_API_TARGET 设为手机 origin，如 http://192.168.1.20:8080。它仅用于 Vite 服务端代理，不嵌入浏览器 bundle，也不应包含密码。默认目标是 http://127.0.0.1:8080。开发与预览默认只监听电脑回环地址。

结束 USB 测试后清理转发：`adb -s <设备序列号> forward --remove tcp:18080`。

## 构建与测试

```sh
pnpm run typecheck
pnpm run lint:check
pnpm run test
pnpm run build
pnpm run test:browser
pnpm run start
```

build 生成 build/client 并复制到 ../web/src/main/resources/static，Android Gradle 的 :web:buildWebUi 自动执行相同流程。start 使用 Vite 预览 SPA 产物并复用 API 代理，这是本地预览，不是生产 Node 后端。生产环境由 Android Ktor 提供静态资源，不存在独立的 :web:run 后端，也不需要 Docker。

浏览器测试在 Windows 默认使用已安装 Edge，其他系统使用 Playwright Chromium。详见 [测试说明](tests/README.md)。GitHub Actions 的 WebUI checks 对前端/API 相关变更执行类型、lint、单元、构建和浏览器检查。

## 登录行为

登录同时建立 Bearer 与 HttpOnly Cookie 会话，媒体使用 Cookie。退出登录先请求服务端撤销会话并清除 Cookie，成功后清理浏览器会话并刷新；网络失败时提示重试。改密码、会话到期、服务重启或超过 256 个并发登录会话后最旧会话失效，需要重新登录。已打开的受保护 SSE 在会话失效后约一秒内关闭。

HTTPS 终止在反向代理时，代理应保证 Cookie/HTTPS 策略；服务不盲目信任客户端传入的转发协议头。file/data 页面及不透明 sandbox 来源不能访问 API，请使用服务提供的页面。
