# 对话流标识兼容修复与日志系统重构

## 本次故障

用户提供的 `rikkahub-1794893172004.log.txt` 记录了 `ResponseApiStreamDecoder.parseEvent()` 抛出的 `IllegalStateException: item_id not found`。这是 Responses API 输出条目标识缺失，日志没有显示附件或手机文件丢失。错误文案分类原先把所有 `not found` 都视为文件不存在，进一步造成了“找不到文件”的误导。

修复包括：

- 用 `output_index`、已声明的输出条目和实际 ID 的映射，维持流内稳定标识；供应商 ID 晚到时不会新建重复正文。
- 对只有一个候选的文本或推理输出兼容缺少 ID/index 的网关；同时支持 SSE event 名称存在但 JSON 缺少 type 的情况。
- 并行工具调用缺少可靠标识时明确报协议错误，避免把参数拼到另一工具调用。
- 推理的本地显示 ID 与供应商真实 ID 分离，后续请求继续使用真实推理 ID 和 encrypted_content。
- 收窄文件错误识别，流字段缺失归为响应格式错误；真实 FileNotFoundException/ENOENT 保留文件提示。
- ChatService 异常使用结构化错误入口，旧版 TextLog 异常堆栈也能识别为错误。

官方协议要求相关事件携带 item_id；兼容回退针对第三方网关的非标准响应。本次附件没有供应商原始 SSE，因此不能断言具体是哪一类事件省略字段，也没有进行付费供应商调用。

## 日志结构与状态

保留原有 LogEntry 序列化类型，新增可选的 schemaVersion=3 诊断元数据，兼容读取旧记录：来源、级别、结果、会话/请求关联、属性、截断与详情可用性。

来源区分 APPLICATION、HTTP、PROVIDER、JAVA_CRASH、PROCESS_EXIT、RENDERER、LOG_STORAGE。结果区分 IN_PROGRESS、COMPLETED、FAILED、CANCELED、INTERRUPTED、UNKNOWN。

用户停止不再记作模型故障。低内存/ROM 回收属于中断证据，不等同崩溃；原因不明确的系统信号保持不确定。日志存储告警、未确认完成和未知结果不再混入成功筛选。

请求日志继续按供应商/模型筛选，列表、请求详情和应用内正文预览沿用原布局与渲染组件，仅调整状态和详情读取方式。软件与系统页仍沿用展开技术日志的交互。

## 写入、内存与保留

普通记录进入单独的 diagnostic-io 串行队列，生产者不等待磁盘。队列上限 128 项、待写内容预算 8 MiB；丢弃和保存失败用计数汇总，并显示 LOG_STORAGE 警告。显式读取、flush 和清空是 IO 屏障，业务/UI 调用已迁至 IO dispatcher。

请求以独立 JSON 正文和 index 摘要原子保存；启动和列表读取摘要，选中后按需读取正文，避免一次读入所有请求/回复。缺少或过期详情返回不可用说明，不作为聊天文件异常抛出。

| 内容 | 上限 |
| --- | --- |
| 请求记录 | 500 条 / 32 MiB，预算含摘要索引 |
| 应用/系统事件 | 300 条 / 8 MiB / 7 天 |
| 最近条目内存 | 100 条摘要 |
| 正文缓存 | 最多 16 条且总估算不超过 1 MiB |
| AI 诊断历史 | 50 条 / 4 MiB / 14 天 |
| 进程退出附件 | 最多 3 个，每个最多 512 KiB |

旧错误 JSON 会在后台迁移。损坏记录互相隔离，损坏/陈旧索引可回退读取原记录；中断写入临时文件和孤立索引只按本系统拥有的文件名清理。崩溃首报保留同步最小保存路径，导入标记只在确认落盘后推进。

## 请求捕获与内容边界

RequestLoggingInterceptor 改为 application interceptor，涵盖 DNS/TLS/连接建立失败。逻辑请求 ID 关联 HTTP 记录和模型完成时序；模型完成耗时不会被稍后的 body close 覆盖。

统一 DiagnosticSanitizer 用于捕获、存储、导出与 AI 输入。处理 Authorization/Basic/Bearer、常见自定义凭据字段、嵌套 JSON/SSE；移除 URL 用户信息和 fragment，隐藏全部 query 值，保留参数名。数字 token 配额/计数不作为密钥删除。关闭正文捕获后仍保留基础供应商请求信息。

移除供应商、搜索和语音路径中旁路输出的原始请求、回复、SSE 和查询内容，以及默认 HEADERS 网络 logcat。保留有意义的结构化错误，避免绕过应用日志策略。

“完整”导出仍屏蔽凭据，但可以包含用户启用采集后的对话正文；凭据脱敏不等于匿名化。自动云备份/设备迁移排除日志、诊断历史、崩溃首报和进程退出水位文件。

## 系统日志与 AI 诊断

应用内系统日志范围是本应用主动记录的事件，以及 API 30+ 系统可提供的本应用历史退出信息；不代表整机 logcat，也没有新增 READ_LOGS 权限。

尽早安装 Java 崩溃 handler，保存失败仍通过 finally 调用 Android 原 handler。下次启动后台导入 Java 首报与 ApplicationExitInfo；近邻 Java 崩溃事件可通过 related_event_id 关联。保留原始 reason/status/importance、PSS/RSS 及 trace 可用性；PSS/RSS 不是峰值或设备剩余内存。

ANR 文本与 native 二进制附件分开保存，不把 native trace 当成 Java 堆栈。系统不提供 trace 或进程尚未退出时，不能据此伪造诊断证据。

AI 分析输入改为字段白名单和有界错误详情，不自动加入请求/回复正文、headers 或任意参数；字段级缩减保持 JSON 完整，保留根因尾部。预览内容就是实际发送内容。分析模型关闭工具和自定义请求体，保留超时/取消处理。历史中的 running 记录在下一次启动恢复为 interrupted；清空会先取消并等待分析任务，再删除记录。

## 验证与产物

使用本地 Gradle 9.5/JDK 21 与离线缓存。构建期间复用已有 Web 静态资源，不修改 desktop/desktopApp。测试命令、日志、JUnit XML 汇总和 APK 校验值保存在 `build/reports/logging-refactor-20261117/`。

- JVM：common 全部测试；AI Responses 解码和跨协议流回放；应用日志、筛选、导出、AI 投影、请求统计、退出分类及错误文案回归。
- DNS：真实 OkHttp application interceptor 配合本地失败 DNS，不发出外部请求，验证连接建立前失败仍有一条请求日志。
- 真机：USB vivo V2301A，Android 16/API 36，独立包 `me.rerere.rikkahub.revised.loggingqa`，仅生成合成记录。
- 真机持久化检查已通过：受测记录入口主线程 StrictMode 磁盘写违规 0；列表无正文；按需详情/时序正确；重新初始化可恢复记录；凭据哨兵清除；分析中断恢复和清理正常。

JVM 最终结果为 **93 项通过，0 失败/错误/跳过**：common 20、ai 25、app 48。包括缺失标识、并行工具隔离、晚到推理 ID 回放、DNS 失败捕获、取消状态、存储队列、脱敏、AI 投影与错误提示分类。原始 JUnit XML 和汇总见 `jvm-results/`、`jvm-summary.json`。Git 差异空白检查在显式允许 CRLF 后通过。

最终独立 Debug 包版本为 `2.4.8-revised.9-20261117-145639-debug`，两项真机回归均通过：`device-storage-final.txt`、`device-layout-final.txt` 中各有 `OK (1 test)`。布局回归覆盖日期/模型/错误状态筛选、打开详情、滚动至合成请求正文、切换软件与系统、展开及滚动错误堆栈。已人工核对列表、正文和技术日志截图。测试夹具的端点和嵌套滚动定位曾作修正，最终测试结果以这两个文件为准。系统历史退出记录只显示测试结束触发的 USER REQUESTED/FORCE STOP，不能作为压力或崩溃捕获能力的完整验证。

- 真机持久化结果（本地证据，未公开）
- 请求列表截图（本地证据，未公开）
- 正文预览截图（本地证据，未公开）
- 技术日志滚动截图（本地证据，未公开）

QA ARM64 产物为 `app/build/outputs/apk/qa/app-arm64-v8a-qa.apk`，版本 **2.4.8-revised.9-20261117-145639**，SHA-256 为 `df636b6992f7fdf31ed061e3dab487ce186fe08552fcd83ebf4de1e262c5c7dc`。本轮只安装独立 Debug/测试组件；没有覆盖或清空主 QA 数据，也没有发起真实模型或 AI 诊断请求。

未覆盖的边界：所有 OEM 的系统回收/ANR/native 退出、断电或 OOM 时的最后日志、极低磁盘空间故障注入、所有供应商实际网关行为。异步队列在进程骤停时可能丢失尚未落盘的普通事件，不能承诺全量崩溃捕获。

## 参考资料

- [OpenAI Responses streaming events](https://developers.openai.com/api/reference/resources/responses/streaming-events)
- [Android ApplicationExitInfo](https://developer.android.com/reference/android/app/ApplicationExitInfo)
- [OkHttp 官方 Interceptors 文档](https://github.com/square/okhttp/blob/master/docs/features/interceptors.md)
- [前期日志审阅](logging-review-and-roadmap-20261115.md)
