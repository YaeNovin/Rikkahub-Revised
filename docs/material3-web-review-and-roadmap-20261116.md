# material3 与 web 专项审阅及开发方向（2026-11-16）

优先处理 Web 文件访问与活动内容边界、图片请求契约和服务启停线程；material3 重点修复 CMF 工厂路径及调用方缓存失效，再完善透明图片取色、规范兼容和可读性指标。**本次没有发现“默认深色 Material 角色必然变黑”的证据，不建议整体替换 MCU 或直接增加固定饱和度来处理所有问题。**

本轮完成审阅、合成验证和改进路线，没有修改生产代码、安装 APK、读取手机日志/真实配置或调用供应商。既有未提交修改保留，排除 desktop/desktopApp。

## 1. 范围和证据

- material3：本地五个封装文件、完整 Kotlin MCU 接入、角色映射、采样/评分/缓存与测试；并沿调用链检查 app/theme 的原图分析、文字前景和缓存。
- web：`Entry.kt`、构建脚本和静态资源；实际业务实现位于 `app/web`，因此检查了启动服务、鉴权、路由、SSE、配置和文件访问。web-ui 仅检查相邻的图片请求、认证、SSE、静态打包契约，没有把本轮扩大为整个 React 界面的完整审阅。
- 源码摘要（本地证据，未公开）保存 96 个核心代码/构建文件的 SHA-256，前后未变化；它不是整个工作区的完整快照，也不代表每个 MCU 内部函数经过独立算法审计。
- 本轮验证：**128 项 JVM + 8 项 Node，全部通过，0 跳过**。其中审阅探针为 7 项 JVM、2 项 Node；其余为现有测试。JVM 结果（本地证据，未公开）、Node 结果（本地证据，未公开）。通过的探针确认当前缺口存在，不表示已经修复。
- 颜色角色矩阵（本地证据，未公开）有 648 个请求组合：4 种种子 × 9 个非 CMF variant × 3 个请求规范 × 深浅色 × 3 档对比度。包含规范回退造成的重复输出；这不是 648 张真机截图。
- Web 复现只监听 `127.0.0.1`，使用临时目录、合成 DAO/配置文件及本地模拟响应。没有运行攻击网页去访问真实手机，也没有取出真实 API key。
- 通过 AnySearch 读取 MCU、Ktor、Android、W3C、MDN 和 Gradle 文档，见来源快照（本地证据，未公开）。

## 2. material3：需要修改和完善的内容

### M1 · P2：CMF 公开选项无法通过通用工厂生成

**证据：JVM 合成复现 + 固定 MCU 源码。**

[DynamicColorSchemeFactory.kt](../material3/src/main/java/me/rerere/material3/DynamicColorSchemeFactory.kt#L117)把所有 variant 送入 `ColorSpec.getPrimaryPalette` 等通用方法。CMF 不走这些方法；本地 MCU 的专用 [SchemeCmf](../material3/material-color-utilities/kotlin/scheme/SchemeCmf.kt#L28)自行构造多源色调板，并要求 SPEC_2026。

探针确认：公开工厂选择 CMF、使用默认规范或显式 SPEC_2026 都抛 IllegalArgumentException；直接构造 MCU SchemeCmf 则能生成角色。

**建议修改：**CMF 走专用构造路径并明确输入 source colors 和规范；不支持的组合在选项校验阶段明确拒绝。保留其他 variant 的旧输出兼容性。当前手机设置只映射五种样式，没有向用户开放 CMF，因此这是公开模块 API 的缺陷，不能据此宣称当前默认启动会崩溃。

### M2 · P2：半透明图片取色有突变，未表达实际合成颜色

**证据：JVM 合成像素。**

[ImageColorAnalysis.kt](../material3/src/main/java/me/rerere/material3/ImageColorAnalysis.kt#L11)丢弃 alpha<128 的像素，把 alpha>=128 的像素强制视为完全不透明。合成纯红图仅改变一个 alpha 单位：

- alpha=127：无种子；
- alpha=128：种子为不透明纯红；
- 但后者叠在白色表面上实际接近浅红，叠在深色表面上又不同。

丢弃完全透明像素是正确的；问题在于半透明像素的权重/背景合成政策没有作为算法输入或结果信息表达。当前 rawSamples 的文字对比度路径会合成背景，但 seed 路径使用原始 alpha 阈值，两者语义不同。

**建议优化：**明确提供“原图色相”和“渲染后背景色”两种分析意图；需要匹配实际表面时先按给定 base/opacity 合成再量化，或采用明确的 alpha 权重。返回有效像素权重/置信度，不把 `opaquePixelCount` 当作“所有像素均完全不透明”的保证。保留现有完全透明、灰度图不会突然取得无关蓝色的回退规则。

### M3 · P2：调用方快速缓存忽略文件变化

**证据：app 边界合成测试。**

[BackgroundAccentExtractor.kt](../app/src/main/java/me/rerere/rikkahub/ui/theme/BackgroundAccentExtractor.kt#L38)的正式缓存 key 包含 mtime/size，但 `cachedBackgroundBitmapAnalysis` 只按 source+aspect 找最近结果。探针插入旧缓存，再改变同一路径的内容、大小和 mtime，快速查询仍返回同一旧对象。

[AdaptiveBackgroundReadability.kt](../app/src/main/java/me/rerere/rikkahub/ui/theme/AdaptiveBackgroundReadability.kt#L38)又把命中结果作为 initial；非空就跳过正式提取。因此，当文件在同一路径更新时，这条路径可能长期显示旧种子/采样。该用例验证快速查询，Compose 的跳过行为由调用链确认，未做本轮真机重绘复现。

**建议修改：**统一正式/快速查询的 freshness key 和失效事件；本地文件检查轻量元数据，content URI 使用可用版本信息或主动 invalidation，远程同 URL 背景定义 TTL/ETag/手动刷新。失败与成功缓存分开处理，避免过期结果阻止更新。

### M4 · 默认角色矩阵表现正常，低对比度档位需要明确承诺

本轮抽查四类前景/背景配对，矩阵中的最低值如下。数值只表示这些种子/角色组合，不是所有界面的 WCAG 认证。

| 请求 contrastLevel | onPrimary / primary | onPrimaryContainer / primaryContainer | onSurface / surface | onSurfaceVariant / surfaceVariant |
| --- | ---: | ---: | ---: | ---: |
| -1 | 4.513 | 3.011 | 6.044 | 2.437 |
| 0 | 6.006 | 4.528 | 12.094 | 4.922 |
| 1 | 11.052 | 7.773 | 18.460 | 9.289 |

默认和高对比度的这四类配对均高于 4.5:1；低对比度出现低于 4.5:1 的配对，与其降低对比度的设计有关，不能全部当成 MCU 算法错误。若应用需要普通小字号正文始终满足最低对比度，应在实际使用表面进行角色修正，而不是把低对比度方案直接视为合规。

W3C 说明普通文本的亮度对比度是关键，色相/饱和度不能替代它。保持用户已确定的无描边方案；约 10% 字重增加也不等于自动满足大字或粗体例外标准。

### M5 · 饱和度实验实际不是原始色度简单增加 11%

[TextColorPalette.kt](../app/src/main/java/me/rerere/rikkahub/ui/theme/TextColorPalette.kt#L113)实际公式是 `(sourceChroma * 1.35).coerceIn(40,56) * 1.11`。例如原始 HCT chroma=8 会得到目标 44.4。1.11 是在既有增强和下限之后的实验倍率，不能描述为“任何原图仅增加 11% 饱和度”。HCT chroma 也不是 HSV saturation 百分比。

**建议优化：**保留可回退的实验参数，将原图 HCT、目标 HCT、最终可实现 HCT、对比度及采样覆盖率记录到本地调试数据；用固定参考图做 A/B。低饱和图片是否保留低饱和应是可说明的策略，不能靠提高下限去推断“提色更准确”。

### M6 · 规范更新与性能边界

- 当前编译来源明确是 `material-color-utilities/kotlin`，其他语言目录为参考，避免重复编译是正确的。子模块处于 `6fd88eb3e95ba1d457842e2a2bf847d06b3a018a`（2026-04-16），本轮检查无本地修改；没有检索当前最新 MCU 提交，不声称该版本已过时或必须立即升级。
- wrapper 和 MCU 的 DynamicScheme 各维护一份 spec fallback 规则；SPEC_2026 对部分 variant 会回退 2025，其余回退 2021。建议公开“请求规范/实际规范”和支持矩阵，升级时以固定提交及金样比较，默认 SPEC_2021 保持兼容，避免一次升级改变所有用户配色。
- `createDynamicColorScheme` 缓存限 16 条，是合理的有界设计；同 key 并发 miss 仍可能重复生成。先测冷生成/重组时延，再决定单次计算合并；没有证据表明需要无界缓存。
- 当前 app 会先裁剪、缩到最长边 128 再量化，符合 MCU 小图分析建议。公开 analyzeImageColors 本身没有输入像素预算，且 filter/map 会产生装箱集合；可增加明确预算、避免大数组额外复制，保留纯算法接口与 Android Bitmap 读取分离。
- BackgroundAccentExtractor 用全局 Mutex 包住解码/网络读取/量化，网络来源为 bounds、解码、EXIF 最多打开多次连接。可改为按 source 合并任务、全局并发限额和可取消下载；图片裁剪/旋转也应统一在工作线程，并补异常时的 Bitmap 释放。不要因为函数是 suspend 就认为其中所有 Bitmap 操作已离开主线程。

建议补充：alpha 渐变、低饱和/灰度、双色冲突、红色跨 0° 色相边界、超宽/超高裁剪、EXIF 八种方向、P3→sRGB、不同 viewport、系统深浅色与动态壁纸切换。现有四张参考图和纯 Kotlin 测试继续保留，但不能代替 ROM/屏幕截图验证。

## 3. web：需要优先修改的内容

### W1 · P1：通用文件路由越过媒体边界

**证据：生产 filesRoutes + 合成 filesDir/DAO + 本机 HTTP。**

[FilesRoutes.kt](../app/src/main/java/me/rerere/rikkahub/web/routes/FilesRoutes.kt#L118)允许 filesDir 下任意存在文件，managed-file 查询只用于 MIME，查不到也继续返回文件。

探针创建合成 `datastore/settings.preferences_pb`，通过真实路由成功得到 200 和合成标记。没有使用真实 Android DataStore 或解析任何真实密钥。实际设置也使用 preferencesDataStore，日志和诊断文件同样位于 filesDir；因此这里的访问边界与配置 DTO 的 write-only/allowlist 边界不一致。

**建议修改：**优先使用 managed file ID/生成媒体 ID，严格约束可公开目录和实体映射；DataStore、日志、诊断、workspace 内部元数据等默认不可由该接口读取。使用 canonical 路径边界加目录分隔符检查，明确软链接政策，不能只依赖字符串 startsWith/contains("..")。旧媒体 URL 做受控迁移。

此路径并没有绕过已启用的 JWT：真实部署中它在 authenticate 内。风险在于 JWT 关闭时 auth 被显式跳过，或普通已登录媒体访问被扩展为任意私有文件访问；不能把它描述为“任何情况下均匿名窃取配置”。

### W2 · P1：上传 HTML 可作为同源活动页面返回

**证据：同一生产路由的合成 HTTP 测试。**

合成上传 HTML 得到 `Content-Type: text/html`，没有 Content-Disposition attachment 或 CSP sandbox。按文件 ID 返回时还会信任已存储 MIME。浏览器直接打开此 URL 时，它属于 WebUI 的 origin；当前 sandbox iframe 的限制不会自动作用于直接导航。

**建议修改：**普通不可信 HTML/SVG/XML 默认附件下载，增加 nosniff；需要预览时使用已有 opaque sandbox 或独立无认证 origin。响应级 CSP sandbox 可作为活动内容的额外边界。不要给不可信页面 allow-same-origin。媒体可 inline 的类型和 MIME 来源应使用明确白名单。

本轮验证响应头和内容，没有在真实浏览器执行脚本读取会话，也没有证明每种打开方式都拥有当前 tab 的 bearer；same-origin 页面仍可能访问同源 API/存储，必须独立于 iframe 修复。

### W3 · P1（匿名访问场景）：CORS 允许任意外站访问

**证据：真实 Entry.kt 的回环 HTTP 测试 + Ktor 官方文档。**

[Entry.kt](../web/src/main/java/me/rerere/rikkahub/web/Entry.kt#L38)配置 anyHost/anyMethod。带 `Origin: https://unrelated.invalid` 的合成请求得到 200、`Access-Control-Allow-Origin: *`，并能执行合成 POST mutation。已有 Origin:null 拒绝逻辑工作正常，但它只保护 opaque-origin 场景。

当前服务默认不启动；启用后的设置默认允许 LAN、JWT 默认关闭。结果说明服务器没有限制外站 origin，不代表完整浏览器必然完成访问：浏览器本地网络权限、混合内容、用户网络和认证设置还会影响结果。anyHost 本身也不会凭空提供受保护会话的 Cookie/bearer。

**建议修改：**默认同源，开发/预览 origin 显式配置；统一 Origin/Host 校验并为写操作定义来源要求。新安装可优先 localhost 或首次 LAN 启用时明确设置认证，保留已有显式匿名使用的可迁移选择，不能静默切断已配置客户端。登录失败限速、连接/请求并发预算随后一起补齐。

### W4 · P2：未知 API 和缺失脚本返回成功 HTML

**证据：真实静态路由的本机 HTTP。**

[Entry.kt](../web/src/main/java/me/rerere/rikkahub/web/Entry.kt#L48)将 SPA fallback 放在根路径。探针访问 `/api/AUDIT_MISSING` 和 `/assets/AUDIT_MISSING.js` 均得到 200 text/html。

这会把 API 404 变成 JSON 解析失败，也让缺失资源表现为模块 MIME 错误，掩盖前后端版本不匹配。

**建议修改：**`/api/*` 和已知静态资源扩展名必须返回真实错误；只有前端页面导航路由允许 SPA fallback。API 404 返回统一 JSON，深链接 `/c/:id` 等仍返回 index.html。添加 Accept、HEAD、路径编码和资源缺失的集成测试。

### W5 · P1：浏览器图片生成与后端契约不一致

**证据：两个 Node 合成请求探针 + Kotlin DTO 调用链。**

- [ImageRoutes.kt](../app/src/main/java/me/rerere/rikkahub/web/routes/ImageRoutes.kt#L76)直接序列化原生 CustomHeader，字段为 name/value；[image-generation.ts](../web-ui/app/services/image-generation.ts#L194)却调用 header.key.trim。后端形状的合成 payload 会在发送前抛 TypeError。
- 浏览器 connection DTO 没有 imageGenerationPath/imageEditPath，构建器固定使用 `/images/generations` 或 `/images/edits`。即使给合成连接增加 `/media/generate`，最终 URL 仍走旧标准路径。
- Android 已支持第三方 media transport 和 version；浏览器目前没有相应的创建/查询/结果解析流程。只补一个 path 字段并不能完成异步协议适配。

**建议修改：**定义显式、带 schemaVersion 的 ImageConnection DTO，使用生成类型或跨语言契约夹具。图片约束必须包含实际 transport、生成/编辑路径和版本能力；选择支持的浏览器 transport adapter。对未支持协议在模型选择/提交前说明，不能显示“可生成”后走错误接口。

保持当前浏览器明确直连、CORS 失败不自动转交手机的产品边界；若后续增加手机代办，作为独立明确选项，并与已有可恢复图片任务策略统一。

### W6 · P1：服务启停在 Main scope 执行同步操作

**证据：静态调用链，未做本轮真机停服计时。**

[WebServerManager](../app/src/main/java/me/rerere/rikkahub/web/WebServerManager.kt#L64)通过 AppScope.launch 执行 start/stop/restart，AppScope 默认 Dispatchers.Main。`stopLocked` 直接 `server.stop(1000,2000)`，端口预探测和 engine.start 也没有统一切到 IO。

已有 lifecycleMutex 防止并发启停，值得保留；它不负责切换线程。Android Service/前台通知也不会自动使这些操作进入后台线程。同步等待期间有阻塞界面的风险，但不能仅凭代码断言每次一定卡满三秒。

**建议修改：**完整 engine 启停和 socket 操作放到专用 IO scope，保留序列化状态机、取消与错误处理。状态变更可通过 StateFlow 发布；加入 start→stop→restart、端口占用、配置快速变更、服务回收的无设备测试，随后测真机主线程轨迹。

端口“先绑定检查再释放再启动”仍有竞态，也不完全反映目标 host 的绑定范围；应依赖实际 engine bind 的错误。NsdServiceRegistrar 在没有地址时直接返回，没有释放刚取得的 multicast lock，应在失败/取消/网络切换路径统一清理。

### W7 · P2：错误、上传和登录缺少统一资源/诊断边界

**证据：静态路径。**

- [WebApiModule.kt](../app/src/main/java/me/rerere/rikkahub/web/WebApiModule.kt#L76)把任意 Throwable.message 直接返回 HTTP 500。内部路径、供应商响应或技术信息可能进入浏览器；请求解析错误也可能被映射为通用 500。
- 文件上传每文件 20 MiB，但没有应用层总请求字节数、文件数、并发上传预算。readPartBytes 先 ByteArrayOutputStream 再 toByteArray，存在额外复制；前面部分成功落盘后，后续文件失败会留下部分结果。
- 登录端点没有失败速率限制；当前会话注册表已限制 256，随机签名 secret、注销撤销与重启失效已实现，这些不需要重做。

**建议修改：**错误响应只返回稳定错误码、可读摘要和关联 ID，完整异常进入经过脱敏的本地日志；取消单独传播。上传用临时文件流式写入、总大小/数量/并发限制和明确的全成功或逐文件结果语义；异常清理临时文件。登录加入有界限速/退避和可观察计数，不把密码或 bearer 写入普通日志。

## 4. web：后续更新与性能优化

### 4.1 静态资源构建和缓存

- [web/build.gradle.kts](../web/build.gradle.kts#L14)声明了源码/配置输入和静态输出，是已有基础；Node/pnpm 运行时版本、构建环境/公开 Vite 变量没有进入明确的可重复构建契约。当前 `.env.example` 主要用于开发代理，不能把它直接认定为生产资源失效漏洞，但新增 VITE_* 配置时要同步建模。
- 每个 preBuild 都依赖完整前端任务；本轮 JVM 测试显式复用已有静态产物，未重新构建或覆盖。建议将 frontend build 与 Gradle Sync 分离，输出到 build/generated 资源目录，固定 Node/pnpm 版本、使用锁文件和离线缓存策略。
- [copy.ts](../web-ui/copy.ts#L49)先递归清除目标，再复制，删除错误被忽略。复制中断可能留下不完整输出。建议让 Gradle 管理独占输出目录/Sync，或使用 staging 后替换；失败应阻断打包，不留“旧脚本+新 index”混合产物。
- index.html 与带哈希静态资源没有分层 Cache-Control；ConditionalHeaders 有依赖/导入但未安装。建议 index 重新验证，哈希资源长缓存 immutable，按需 ETag/Last-Modified 和预压缩，减少手机 CPU 与重复下载。不能假定只引入插件依赖就已经启用。

### 4.2 SSE 和大对话

已实现：共享 settings/events 通道、会话 snapshot/node_update、连接重建后新快照、seq、心跳和会话撤销轮询。当前 Ktor 默认跳过 SSE 压缩，官方文档也如此说明，**没有把全局 Compression 直接判为流式阻塞问题**。

后续优化：

- singleNodeDiff 每次对比整个对话，并发送整条变化节点。超长消息持续追加时仍会反复序列化累计正文；会话列表 invalidation 也会按客户端读取完整助手列表。
- 先测每秒序列化字节、CPU、活跃连接数和慢消费者等待；再引入可校验的 append/patch + conversation revision，节点快照作为恢复基准。前端 seq 只拒绝旧值，没有完整 gap/conflict 恢复协议，不能直接承诺断线增量重放。
- 区分“停止生成状态已保存”与“网络连接断开”，不要让短暂断线自动重发用户消息或付费请求。保持当前重连先 snapshot、REST 迟到结果不覆盖新快照的设计。
- 会话流 1 秒心跳、每客户端 1 秒会话校验与长期 wake lock 应有连接/空闲预算；降低空闲活动前先验证 vivo 等 ROM 的后台行为，不盲目移除已有前台服务/锁。

### 4.3 模块边界和契约

web module 名称目前只代表 engine/static，业务路由在 app。可以逐步拆出 Android 无关的 Application 配置、DTO、Origin/文件政策和请求预算，使 Ktor 集成测试不需要 Android Context/Koin。Android 层保留服务、设置和仓库适配器。

优先生成或验证 Kotlin↔TypeScript DTO，包括 header name、图片 transport、消息状态和 schemaVersion。web-ui 的 CLAUDE.md 仍称所有 endpoint 在 web module，部分技术版本和类型描述也已滞后，应与 README/实际代码一并更新，避免开发者沿错误边界维护。

## 5. 推荐开发顺序和验收

| 批次 | 修改/更新/优化 | 主要验收 |
| --- | --- | --- |
| 第一批 | W1/W2/W3/W5：文件与活动内容隔离、Origin 政策、图片 DTO/transport | 合成 DataStore/日志不能通过媒体路由访问；HTML 不能取得应用 origin 能力；不可信来源受限；原生形状自定义头可用，第三方路径选择正确或提前明确不支持 |
| 第二批 | W4/W6/W7：真实 API 错误、IO 生命周期、上传与错误预算 | API/缺失 JS 返回正确 404；深链接正常；启停不阻塞 Main；上传溢出/取消/部分失败不留下不明文件；错误响应不含合成秘密/内部路径 |
| 第三批 | M1/M2/M3/M5：CMF、透明取色、缓存失效、实验色度说明 | 支持的 variant/spec 全部可生成；透明渐变输出连续且政策明确；同路径更新及时生效；保留深色可见色相与实际对比度 |
| 第四批 | 构建产物、静态缓存、SSE 增量/连接预算、MCU 固定版本对照 | 构建中断不混入旧资源；缓存命中与 API 404正确；长消息/多客户端字节量和内存下降；手机后台/重连/恢复保持正确 |

material3 适合新增“分析结果诊断”：候选种子、色系人口、有效 alpha 权重、裁剪范围、分析版本、实际文字对比度和覆盖率。web 适合新增连接/会话管理、可恢复媒体任务中心、服务器健康和客户端版本协商。先完成上述边界和契约，再增加更多浏览器原生供应商能力。

## 6. 测试及限制

| 组别 | 用例数 | 结果 |
| --- | ---: | --- |
| material3 全量 JVM，含 3 个审阅探针 | 24 | 通过 |
| web 全量 JVM，含 2 个回环探针 | 4 | 通过 |
| app/web 与 theme 定向 JVM，含 2 个边界探针 | 100 | 通过 |
| Node 图片请求现有测试和 2 个契约探针 | 8 | 通过 |

构建记录：`build/reports/qa-build/20261116-002009/attempt-1.log` 与 `20261116-002603/attempt-1.log`。本轮首次 init script 在任务注册前查找 buildWebUi，已在审阅脚本内改为延迟匹配；首次 app 回环夹具因 Android SLF4J 绑定在 JVM 初始化失败，随后仅在测试进程指定 NOP provider 后通过。两者均为隔离测试环境修正，没有修改生产实现来迎合探针。

app 文件路由夹具仅替代 filesDir/DAO，并绕过 Android stub ContextWrapper 构造器；执行真实 filesRoutes 和 Ktor engine。它证明路径/MIME 响应行为，不代表完整 Android 权限、ROM 或浏览器执行环境已经验证。

当前未执行：真机服务启停/功耗、屏幕关闭与网络切换、真实跨站浏览器本地网络权限、系统壁纸/广色域 Bitmap、完整前端生产重建和全套浏览器测试。没有将这些项目描述为通过。普通构建不会注入本轮探针或禁用前端构建；这些只存在于显式传入的审阅 init script。

## 7. 参考资料

- [MCU 提色指南](https://raw.githubusercontent.com/material-foundation/material-color-utilities/main/dev_guide/extracting_colors.md)、[固定提交的 SchemeCmf](https://raw.githubusercontent.com/material-foundation/material-color-utilities/6fd88eb3e95ba1d457842e2a2bf847d06b3a018a/kotlin/scheme/SchemeCmf.kt)。前者是抓取当日 main 文档，不视为项目依赖升级结果。
- [WCAG 2.2 普通文本对比度说明](https://www.w3.org/WAI/WCAG22/Understanding/contrast-minimum.html)：普通文本 4.5:1、阈值不可四舍五入、色相/饱和度与粗细不能替代亮度对比度。
- [Ktor CORS](https://ktor.io/docs/server-cors.html)、[Ktor SSE](https://ktor.io/docs/server-server-sent-events.html)、[Ktor 静态内容](https://ktor.io/docs/server-static-content.html)：Origin 允许范围、SSE 心跳/压缩、fallback 与缓存。旧 SSE URL 提取失败，保留失败记录后使用正确页面。
- [Android Services](https://developer.android.com/develop/background-work/services)：Service 默认运行在主线程，需要主动移出阻塞工作。
- [MDN Content-Disposition](https://developer.mozilla.org/en-US/docs/Web/HTTP/Reference/Headers/Content-Disposition)、[CSP sandbox](https://developer.mozilla.org/en-US/docs/Web/HTTP/Reference/Headers/Content-Security-Policy/sandbox)。
- [Gradle 增量构建](https://docs.gradle.org/current/userguide/incremental_build.html)：输入/输出及陈旧产物规则。长文提取有 50,000 字符限制，不将未读取部分当作已验证结论。
