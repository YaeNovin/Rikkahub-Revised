# 日志专项审阅与改进方向（2026-11-15）

本次结论：现有日志已经具备请求落盘、正文限量、错误保留、退出信息补采和人工触发 AI 分析等基础，但各通道的脱敏、存储预算、清理和证据语义尚未统一。下一阶段应优先处理**敏感内容旁路与 AI 发送范围、同步 I/O 与全量缓存、捕获缺口**，再增强诊断能力。

本次属于代码审阅和方向确认，没有修改生产逻辑、安装 APK、导出手机日志或调用真实 AI。排除 desktop/desktopApp。既有未提交改动保持原状。

## 1. 证据与范围

- 重新核对 common 日志存储、HTTP 捕获、请求统计、崩溃处理、进程退出补采、渲染诊断、日志页面、导出、AI 分析、Android 备份配置及协议层日志出口。
- 本轮 JVM：**59 项，0 失败，0 跳过**。其中现有测试 53 项，隔离审阅探针 6 项。探针断言的是当前行为，验证问题存在；通过不代表问题已经修复。
- 工件：测试摘要（本地证据，未公开）、合成探针（本地证据，未公开）、外部文档快照（本地证据，未公开）。
- 源码摘要（本地证据，未公开）保存 27 个核心生产文件的 SHA-256，检查期间未变化；它不是整个工作区的不可变快照，也不表示所有相关文件逐行审计完成。
- 日志测试使用当前本地 Gradle/JDK 离线执行。没有进行本轮真机故障注入、低内存实验、云备份传输测试或 Release APK 日志反编译验证。

## 2. 当前真实的数据通道

| 通道 | 来源与触发 | 存储与保留 | 实际边界 |
| --- | --- | --- | --- |
| 供应商请求摘要 | 带 `ProviderRequestDiagnostics` 标签的 HTTP 请求；详细正文关闭时仍记录 | `files/logs/requests/<id>.json`；与 HTTP 日志共享 500 条 / 32 MiB 上限 | 模型、协议通道、操作、参数摘要、HTTP 状态、耗时；不是纯粹关闭全部请求日志 |
| 可选 HTTP 详情 | `RequestLoggingInterceptor`，默认关闭详细记录 | 同一请求记录补写；单个正文捕获限 256 KiB | 包含提示词、回复、工具参数；屏蔽部分凭据不等于匿名化。普通无供应商标签请求也可能进入此处 |
| 软件错误 | 主动调用 `Logging.logError` / `logSoftwareError`，包括聊天、图片、后台任务等 | `files/logs/error-logs.json`；7 天 / 200 条 | 整个错误列表同步原子重写；通用入口没有统一脱敏与总字节上限 |
| 普通事件 | `Logging.log` / `recordEvent` | 进程内最近 100 条，与请求条目共用 recentLogs | 不是持久化环形 breadcrumbs；请求也会挤占该内存列表 |
| Java 未捕获异常 | `CrashHandler`，主应用初始化中安装 | `shared_prefs/crash_handler.xml` 中同步保存最多约 32,000 字符，下次启动导入错误列表 | 仅覆盖安装后的未捕获 Java/Kotlin 异常；系统杀进程、native crash、ANR 不走此回调 |
| 系统进程退出信息 | Android 11 / API 30 起，启动后从 `ApplicationExitInfo` 读取 | 最多查询 16 条，筛主进程，时间水位去重；摘要进入错误列表 | 不是实时监听，不是完整系统日志；目前仅接受部分 reason |
| 系统退出附件 | 部分 ANR/native 退出的 `traceInputStream` | `files/logs/process-exits/<timestamp>.txt`；单份最多 512 KiB，保存最新 3 份 | ANR 摘取前 48 Ki 字符进入错误详情；native 文件未关联到可导出的 LogEntry |
| 渲染异常 | `RenderDiagnostics.report` | 队列 32、30 秒重复抑制、去重表 128；最终进入错误列表 | 有限后台写入是良好模式；队列满时静默丢弃，没有 dropped 计数 |
| Android logcat | `Log.*`、`println`、堆栈打印、OkHttp HEADERS 日志等 | Android 管理的环形缓冲区 | 独立于应用内开关和数据库，不会自动镜像到“软件与系统”页 |
| AI 分析历史 | 用户选中失败日志、选择模型并点击分析 | `files/log-analyses/<id>.json`；保存发送快照、结果、模型、供应商和状态 | 没有条数、期限、字节上限，也没有删除接口；应用私有文件中的明文 JSON |
| 使用统计 | `RequestStatisticsRecorder` / Room `request_statistics` | 业务数据库独立保存 | 不受请求文件的 500 条 / 32 MiB 保留规则控制，也不随“清空日志”删除 |

供应商日志里的 `provider` 通常是协议适配器标签，例如 OpenAI；第三方网关主要依赖 origin 区分，不能保证等于用户给供应商配置填写的名称。同 origin 的多个配置缺少稳定 provider configuration ID。

## 3. 已确认的问题与风险

### L1 · P1：日志开关与脱敏存在旁路

证据：**当前源码 + 合成 JVM 复现**。

- [DataSourceModule.kt](../app/src/main/java/me/rerere/rikkahub/di/DataSourceModule.kt#L317)对共享 OkHttp 客户端无条件安装 `HttpLoggingInterceptor.Level.HEADERS`，仅 `redactHeader("Authorization")`。
- 合成请求确认：Authorization 被屏蔽，但 URL 的 `key=`、`x-api-key`、`x-goog-api-key`、Cookie、Set-Cookie 会进入该 logger。探针仅收集内存 logger 输出，没有使用真实密钥或读取设备 logcat。
- [ResponseAPI.kt](../ai/src/main/java/me/rerere/ai/provider/providers/openai/ResponseAPI.kt#L119)和 [ChatCompletionsAPI.kt](../ai/src/main/java/me/rerere/ai/provider/providers/openai/ChatCompletionsAPI.kt#L191)直接序列化完整请求到 `Log.i`，并输出完整响应/SSE 数据；部分搜索、语音错误路径仍打印原始响应。它们绕过应用内开关。
- [RequestLoggingInterceptor.kt](../app/src/main/java/me/rerere/rikkahub/data/ai/RequestLoggingInterceptor.kt#L230)的 URL 清理只识别固定敏感 query 名。合成用例确认 URL user-info 中的密码及 `X-Amz-Signature` 留存。自定义字段、厂商签名 URL 也不能依赖一份固定黑名单完全覆盖。

影响：至少 Debug/QA 中，关闭详细日志仍不能保证正文或部分认证信息不进入系统日志。没有证明真实密钥已被泄露，也没有把普通第三方应用描述为能任意读取整机 logcat。Release 的最终日志剥离效果尚未检查，不能假设优化自动消除所有出口。

建议：建立统一结构化日志入口；生产 logcat 只输出允许的类别、ID、长度和受控错误摘要。详细正文仅走人工开启、限时的私有采集通道。URL 删除 user-info，对未知 query 默认隐藏值；header 使用白名单。替换原始请求/逐 delta 打印，并验证开关关闭时连大型 JSON 拼接也不会执行。

### L2 · P1：存储同步执行且持有全局锁，磁盘预算没有限制内存峰值

证据：**静态调用链**，本轮未做真机耗时归因。

- [Logging.kt](../common/src/main/java/me/rerere/common/android/Logging.kt#L109)在 Application 主线程初始化时读取、解析并重写错误 JSON；调用发生在 Koin 和界面启动之前。
- `logError`、请求保存、正文补写、耗时补写及部分读取持有同一把 `lock`；[atomicWrite](../common/src/main/java/me/rerere/common/android/RequestLogStore.kt#L95)执行 `fd.sync()`。
- [ChatService.addError](../app/src/main/java/me/rerere/rikkahub/service/ChatService.kt#L688)直接调用同步 `Logging.logError`，入口没有强制后台调度；调用方在主线程时会同步等待落盘及锁。
- [RequestLogStore.load](../common/src/main/java/me/rerere/common/android/RequestLogStore.kt#L56)第一次读取或保存时，把保留请求的正文全部解析到常驻 `records`。32 MiB 是序列化文件预算，不是堆内存预算；UTF-16 字符串、JSON 解析及导出还会增加分配。
- 每个请求阶段更新都重新写整条记录并扫描/排序文件目录。正文更新后，紧接着的完成回调可能再次写相同大正文；网络读取完成也要等待日志回调。
- 日志页面每 2 秒取得所有日志，虽在 IO 线程执行，仍与生产者竞争同一锁。

建议：先做有边界的 IO 写入队列、阶段合并和索引/正文分离，列表只读元数据，详情按需加载；限定内存 LRU 的字节权重。可沿用现有单请求原子文件，暂不要求整体迁移 Room；索引查询复杂后再引入 Room 元数据表。正常事件可批量写，严重错误保留最小同步崩溃旁路和明确持久化屏障，不能把所有写入简单异步化后承诺永不丢最后一条。

验收需要真机 StrictMode/线程轨迹与写入延迟数据，不能由 JVM 通过推断当前卡顿已解决。

### L3 · P1：AI 诊断发送范围比默认脱敏导出更宽，凭据规则也不一致

证据：**合成 JVM 复现 + 调用链**。

- [logAnalysisPayload](../app/src/main/java/me/rerere/rikkahub/ui/pages/log/LogExport.kt#L84)仅执行本文件的 `redact`，没有调用正文裁剪/隐私字段投影。合成失败请求中的 prompt 和 answer 标记均保留；同一条记录的默认脱敏 CSV 不保留这些标记。
- 错误详情中的 `Authorization: Basic <合成值>` 在 AI payload 内仍存在；`detailedLogBody` 可以清除此值。说明两套规则确实存在差异，不只是理论黑名单风险。
- [LogVM.kt](../app/src/main/java/me/rerere/rikkahub/ui/pages/log/LogVM.kt#L128)把该 payload 直接作为 user message 发给所选供应商，并先保存诊断快照。输入上限约 24,000 字符，截断后不一定是合法 JSON，也不是模型 token 预算。
- 通用错误入口本身不脱敏。探针确认 `Logging.logError(details="api_key=<合成值>")` 保留原值；非请求日志不能假定已经经过 HTTP 脱敏。

已有正向边界：必须用户点击分析并选择 CHAT 模型；页面告知可能收费、个人信息残留并提供发送内容预览；system prompt 明确日志是不可信数据、区分事实和假设、不执行修复；客户端 tools 为空，120 秒超时、4096 输出 token 上限。上述约束应保留，不应称为“静默自动上传”。`tools=emptyList()` 也不等于已经验证每个供应商的所有内置工具均无法被序列化，后续要检查最终请求。

建议：默认使用“诊断最小集”：错误类别、状态码、经清理的原因/堆栈、必要参数和证据 ID，去除提示词、回复、工具输入输出、媒体及签名 URL。允许用户主动附加正文并查看最终发送内容。捕获、落盘、导出、复制和 AI 发送复用同一 sanitizer 与 schema；截断按字段完成，再形成合法结构。诊断请求标记独立 operation 和 run ID，避免请求采集再次复制整个诊断快照。

### L4 · P1：Android 12+ 系统备份没有排除诊断文件

证据：**Manifest/XML 配置 + Android 官方文档**；未触发实际云备份。

- [AndroidManifest.xml](../app/src/main/AndroidManifest.xml#L53)设置 `allowBackup=true`。
- [data_extraction_rules.xml](../app/src/main/res/xml/data_extraction_rules.xml#L8)的 cloud-backup 没有 include/exclude，device-transfer 规则整段处于注释中。按系统默认规则，filesDir 和 SharedPreferences 中的日志、AI 快照、退出附件、崩溃痕迹具备进入备份/设备迁移的条件，受系统 transport、用户设置等影响。
- Android 11 及以下的 [backup_rules.xml](../app/src/main/res/xml/backup_rules.xml#L9)只 include upload，边界不同；文件里关于 API 31 的样例注释容易误导，应该纠正。
- 应用自己的 S3/WebDAV 使用显式附件/数据库等选择路径，本轮未发现它们直接枚举打包 `files/logs` 或 `log-analyses`。它们与 OS Auto Backup 必须分别评估；数据库备份可能包含业务请求统计。

建议：同时配置 cloud-backup 和 device-transfer，显式排除诊断目录、崩溃私有首报及设备退出水位；或迁到 noBackupFilesDir，并处理旧路径的迁移/删除。仅设置 allowBackup=false 不足以作为所有第三方 ROM 的设备迁移保证。

### L5 · P2：清理、期限和容量没有覆盖整个诊断体系

证据：**静态路径**。

- [Logging.clear](../common/src/main/java/me/rerere/common/android/Logging.kt#L310)仅清 recentLogs、errorLogs、RequestLogStore，并重写错误文件。
- [LogAnalysisStore](../app/src/main/java/me/rerere/rikkahub/ui/pages/log/LogAnalysisStore.kt#L23)只有 save/list，没有删除、期限、条数或字节预算；list 每次完整读取历史快照及结果。每次分析开始和结束还都会重新加载全部历史。
- 退出附件、尚存的 CrashHandler 首报不在清空范围；进程退出附件只有数量限制，没有按错误 7 天期限联动清理。
- 请求文件只有数量/容量限制，没有 TTL，低频使用时敏感正文可以保留很久。
- [RequestLogStore.clear](../common/src/main/java/me/rerere/common/android/RequestLogStore.kt#L45)遇到删除失败后通过 onFailure 写 logcat，不向 UI 返回结果；“清空完成”不能被视为已验证删除全部文件。

建议：定义清理域：请求详情、应用事件、退出附件、分析历史、业务统计分别显示。增加“清除全部诊断数据”，统一返回成功/失败及剩余大小；统计作为独立业务数据，由用户明确选择是否删除。为异步队列设计清理屏障，避免清理前的排队事件重新落盘；清理后新发生的事件仍应正常记录。

可讨论的初始预算：保留当前请求文件 500 条 / 32 MiB，新增正文短 TTL；错误继续 7 天 / 200 条并增加单条和总字节上限；分析历史 7 天 / 30 条并增加总预算；附件有独立 TTL 和总体预算。具体容量需用真机数据校准，这些是建议，不是当前实现。

### L6 · P1：请求记录挂在 network interceptor，连接建立前的失败会漏采

证据：**合成 JVM 复现 + OkHttp 官方语义**。

[DataSourceModule.kt](../app/src/main/java/me/rerere/rikkahub/di/DataSourceModule.kt#L315)用 `addNetworkInterceptor` 注册请求捕获。合成 DNS 直接抛出 UnknownHostException 时，network interceptor 的调用次数为零。因而其中 `chain.proceed` 的 catch 捕获不到这次错误，尽管上层聊天错误可能另有记录。

同类捕获缺口包括该拦截器之前的连接/TLS 失败和提前短路的缓存响应。重定向会产生多次 network exchange，当前 request_id 没有独立 attempt/exchange ID；同一个逻辑请求的多条物理记录只靠 firstOrNull 等方式补全，无法严格表达整条请求链。

建议：使用 application 层记录逻辑请求的开始/结束/取消/失败，用 EventListener 补 DNS/TCP/TLS/连接复用阶段，用 network interceptor 记录每次 exchange 及有界正文。不要简单搬动拦截器后忽略 gzip、缓存、重定向和重试语义。逻辑 request ID、exchange ID、供应商返回 request ID 分别保存并关联。

### L7 · P2：系统退出分类和附件证据不完整

证据：**源码 + Android 官方文档**。

- [ProcessExitReporter.kt](../app/src/main/java/me/rerere/rikkahub/utils/ProcessExitReporter.kt#L18)只查询最近 16 条主进程记录；不识别的 reason 被忽略，同时水位仍前移。只用 timestamp 去重也不能表示同毫秒多次/多进程事件。
- [processExitCategory](../app/src/main/java/me/rerere/rikkahub/utils/ProcessExitReporter.kt#L61)没有保留 SIGNALED 等未知退出。官方指出，不支持低内存退出报告的设备可能用 SIGNALED + SIGKILL 表达内存回收；不能据此把所有 SIGKILL 都判定为低内存，但应保留原始信号及设备能力信息。
- trace 只针对 ANR/native reason 读取。官方允许“发生过 ANR、恢复、后来因其他原因退出”的记录携带 ANR trace，现有条件会遗漏这类信息。
- Android 12 起的 native tombstone 可能是 protobuf，当前统一命名 `.txt`，最多 512 KiB 无截断标记；截断可能使二进制不可解码。只有 ANR 内容被拼入 LogEntry，native 附件没有引用，当前 CSV/.log 导出和 AI 分析因此看不到它。
- trace 为 null 可能是系统没有提供、权限/读取失败或全局环形缓冲已覆盖；当前将读取异常也合并为“未提供”，无法区分原因。
- PSS/RSS 是系统最后采样的进程占用，不是可用内存或峰值，不能单凭这两个值判断泄漏。

建议：所有退出保留原始 reason/status、processName、PID、API、描述和 trace 状态，再单独分类；未知保持未知。附件包含 MIME/格式、原始/截取长度、哈希、可用性和截断状态，以 attachment ID 关联。默认展示安全摘要，原始 ANR/native 证据通过人工选择加入诊断包；二进制解析应在后台、有界地执行。

### L8 · P2：崩溃首报仍有初始化空窗和异常处理脆弱点

证据：**静态路径**，未模拟真实崩溃。

- [RikkaHubApp.kt](../app/src/main/java/me/rerere/rikkahub/RikkaHubApp.kt#L89)先执行日志初始化、Koin、通知渠道创建，之后才安装 CrashHandler；早期初始化异常不被此自定义首报覆盖。
- [CrashHandler.kt](../app/src/main/java/me/rerere/rikkahub/utils/CrashHandler.kt#L23)先 Log.e、构建完整 stackTraceToString，再截断并写 SharedPreferences，最后调用原 handler。保存路径自身抛异常时，没有 finally 保证调用原 handler；commit 返回值也未转化为采集状态。
- 有些业务失败仅 Log.e/printStackTrace，有些进入 Logging；“应用捕获日志”不是自动接管所有 catch 或 Android 系统日志。
- Java CrashHandler 首报与 ApplicationExitInfo 的 Java 崩溃可能同时出现，尚未建立同一 incident 的去重关联。

建议：尽早安装依赖最少的首报器；避免在异常线程做完整诊断初始化；用 try/finally 确保原 handler 执行，首报失败不再递归记录。把“已同步提交/提交失败/下次启动导入”状态做实，有限记录异常链。API 26–29 没有 ApplicationExitInfo，应明确系统杀进程证据不可得。不能承诺 OOM、SIGKILL、断电等情况下都能保存最后日志。

### L9 · P2：失败、取消、正常回收及证据丢弃缺少独立状态

证据：**调用链**。

- [GenerationHandler.kt](../app/src/main/java/me/rerere/rikkahub/data/ai/GenerationHandler.kt#L929)捕获 Throwable 后统一 completeAttempt；[RequestStatisticsRecorder.kt](../app/src/main/java/me/rerere/rikkahub/data/ai/RequestStatisticsRecorder.kt#L76)一律 `failed=true`，最后写成 `Model generation failed or canceled`。用户停止会进入错误统计/AI 可分析范围，难以区分真实失败。
- 低内存回收与 ANR 都以 ErrorLog 表示；分类文案虽不同，缺少统一的 severity/outcome/evidence source。正常系统管理行为不能直接等同为软件崩溃。
- [RenderDiagnostics.kt](../app/src/main/java/me/rerere/rikkahub/data/render/RenderDiagnostics.kt#L14)用 DiscardPolicy 静默丢弃；没有汇总 dropped/deduplicated 计数。磁盘保存失败主要写 logcat，应用内缺少存储健康状态。
- 对进程中断的 AI 分析，没有下一次启动将 `running` 改为 `interrupted` 的恢复路径。

建议：把 outcome 设为 completed/failed/canceled/interrupted/unknown，与 HTTP 状态、severity 分离；字段中保留 capture source、truncated、dropped 和 persistence failure。用户取消默认不计软件故障；保留按需查看。日志采集异常用小型健康计数器聚合，不应引发二次日志风暴。

## 4. 系统日志边界应怎样呈现

“软件与系统”页应说明：**这里展示本应用记录的事件和系统可提供的本应用退出信息，不包含完整设备 logcat。**

| 信息 | 普通应用内可得程度 | 后续呈现 |
| --- | --- | --- |
| 主动记录的业务错误/渲染异常 | 可得；需调用统一入口 | 持久化的结构化事件，标明来源 |
| 已安装 handler 后的 Java 未捕获异常 | 尽力保存，下次启动补录 | crash incident，与系统退出记录关联 |
| ANR | API 30+ 可补取退出信息；trace 不保证存在 | 原始 reason + trace availability；尚未退出的短卡顿另外记录为慢响应证据 |
| native crash | 系统退出信息及部分 tombstone | 二进制附件/安全解析摘要；缺失时不伪造堆栈 |
| 低内存/ROM 回收 | 依设备支持和原始 reason，可能只有信号 | 明确采集能力与不确定性，不自动标为内存泄漏 |
| 页面慢、手势延迟但未 ANR | 当前没有持续性能证据链 | 有界 breadcrumbs、慢主线程样本、内存压力事件；必要时人工触发 Perfetto/gfxinfo |
| ActivityManager/lmkd/DropBox/整机日志 | 完整范围通常需要 ADB/系统权限，普通应用不能保证 | 作为外部诊断附件，注明来源和时间，不与应用事件混为一类 |

不建议为了“系统日志完整”向普通版本添加 READ_LOGS、强制 root 或持续抓整机日志。Android 官方说明 logcat 是受权限约束的公共缓冲区，厂商特权应用仍可能读取，故也不能用“普通 App 读不到”代替脱敏。

## 5. 建议的目标结构

```text
业务 / 网络 / 渲染 / 生命周期 / 退出补采
            ↓
统一 DiagnosticEvent（source、severity、outcome、schemaVersion、IDs）
            ↓
统一字段策略 + SecretSanitizer + 大小/深度/速率预算
            ↓
有界 IO writer → 元数据索引 + 按需正文/附件
            ↓
列表 / 明确范围的清理 / 人工导出 / AI 诊断最小投影

异常线程 → 独立最小 crash 首报 → 下次启动导入同一 incident
logcat   → 默认只允许安全摘要，详细信息不走此通道
```

统一事件建议包含：schemaVersion、eventId、sessionId、incidentId、logicalRequestId、exchangeId、providerConfigId（不含凭据）、conversation/message 的可选关联 ID、UTC 时间、单调耗时、captureSource、severity、outcome、正文/附件状态、版本/API、线程/进程标识。需要分享时对关联 ID 做包内一致的匿名映射。

不要一次性把全部模块改成新的数据库架构。先统一接口和字段策略，在现有原子文件上拆元数据与正文、限制读取；保留旧记录解码与迁移能力。

AI 诊断输出建议分为：观察到的事实（引用 event/field）、可能原因和支持/反对证据、还缺什么证据、可逆验证步骤。模型结论始终标为建议；不要自动执行设置修改、删除数据、上传附件或启用外部工具。输入是日志而非指令的边界既要保留提示词，也要靠无自动执行能力的产品设计维持。

## 6. 推荐开发顺序与验收

| 阶段 | 范围 | 关键验收 |
| --- | --- | --- |
| 第一批：收紧边界 | L1/L3/L4；共享 sanitizer、移除原始 logcat、AI 默认最小集、备份排除 | 详细记录开/关、Debug/QA/Release；header/query/user-info/Basic/嵌套 JSON/SSE/厂商签名；最终 AI payload 不含合成正文与凭据；cloud/D2D 规则分别验证 |
| 第二批：存储与性能 | L2/L5/L9；IO writer、索引/正文分离、统一预算、完整清理、取消/中断状态 | 500 条接近 32 MiB 时冷启动和查看列表不全量载入正文；主线程无普通日志磁盘写入；导出/补写/清空并发、磁盘满、文件损坏、进程中断均有明确结果；恢复无陈旧 running |
| 第三批：捕获与证据 | L6/L7/L8；逻辑请求 + exchange、退出原始字段与附件、最小首报、性能 breadcrumbs | DNS/TCP/TLS 失败、取消、重定向/重试、HTTP 200 流内错误、ANR、native、SIGKILL、后台回收；无法采集时显示缺口；不把 killed/unknown 伪称为已确认 OOM/崩溃 |

建议先执行第一批，再进入第二批。**不建议先靠更复杂的 AI 分析弥补采集和脱敏问题**：输入证据缺失或越界，增加模型能力只会放大不确定性和发送范围。

## 7. 本轮测试与限制

| 测试组 | 数量 | 结果 |
| --- | ---: | --- |
| common：Logging / RequestLogStore | 12 | 通过 |
| app：HTTP 脱敏、正文捕获、统计、导出、展示、分组、筛选、退出分类、提示词诊断可见性 | 41 | 通过 |
| 隔离合成探针 | 6 | 通过，确认当前缺口 |

探针分别确认：AI payload 保留对话；AI Basic 脱敏差异；URL user-info/签名未清理；共享 HEADERS logger 保留非 Authorization 凭据；DNS 失败不进入 network interceptor；通用错误入口保留原始凭据。

常规测试与探针通过不能代替真实系统杀进程、ROM 兼容、存储权限/空间故障、JNI 崩溃、Release R8 输出和备份 transport 验证。没有将这些未执行项描述为已通过。

构建日志：`build/reports/qa-build/20261115-142738/attempt-1.log`（common）和 `build/reports/qa-build/20261115-143149/attempt-1.log`（app）。探针只由本轮专用 init script 注入，默认构建不会包含。

## 8. 官方资料

通过本轮 AnySearch extract 读取，原文及失败记录保存在 external-sources.json：

1. [Android：Log Info Disclosure](https://developer.android.com/privacy-and-security/risks/log-info-disclosure)：logcat 权限与敏感信息控制；非 Debug 日志脱敏和 R8 规则需要明确配置/验证。
2. [Android：ApplicationExitInfo](https://developer.android.com/reference/android/app/ApplicationExitInfo)：退出 reason、设备低内存报告差异、最后 PSS/RSS、ANR/native trace 与全局环形缓冲缺失。
3. [Android：Auto Backup](https://developer.android.com/guide/topics/data/autobackup)：默认备份 files/shared preferences、Android 12 cloud/device-transfer 规则及 noBackupFilesDir。
4. [OkHttp：Interceptors 原始官方文档](https://raw.githubusercontent.com/square/okhttp/master/docs/features/interceptors.md)：application/network 的调用范围、缓存、重定向和连接语义。网页版本提取失败，使用官方仓库原文复核；不是项目依赖版本的逐行源码快照。
