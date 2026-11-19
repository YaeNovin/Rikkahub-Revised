# 聊天链路审阅与问题清单（2026-11-18）

本轮先构建并覆盖安装 QA，再审阅 Android 聊天缓存、上下文统计与压缩、消息/富文本渲染、工具卡片，以及 ask_user 的共享协议、Android 卡片和 Web 入口。共记录 **14 项代码问题或功能缺口，另有 3 项需要专项验证的结构边界**。没有修改应用实现；新增内容为审阅文档和 `build/reports/chat-stack-review-20261118/` 下的诊断脚本/样本。

## QA 构建与覆盖安装

| 项目 | 结果 |
| --- | --- |
| 构建 | `:app:assembleQa`，本机缓存、离线构建成功 |
| 设备 | USB ADB `SERIAL`，vivo V2301A，Android API 36，ARM64 |
| 包名 | `me.rerere.rikkahub` |
| 覆盖前 | `2.4.8-revised.9-20261117-172419` |
| 覆盖后 | `2.4.8-revised.9-20261118-170825`，versionCode `185` |
| 安装 | `adb install -r` 返回 `Success`，启动到 `RouteActivity`，进程存活 |
| 数据保留证据 | 首次安装时间覆盖前后均为 `2026-10-17 22:49:10`；没有卸载、清空应用数据 |
| ARM64 APK | `app/build/outputs/apk/qa/app-arm64-v8a-qa.apk` |
| SHA-256 | `bf1cd4ca869169ceae4ad52b28d747afe141526ef80e896c2255e9b3844febd9` |

安装与启动证据见 installation.json（本地证据，未公开）、安装日志（本地证据，未公开） 和 启动截图（本地证据，未公开）。构建日志为 `build/reports/qa-build/20261118-170812/attempt-1.log`。

## 验证口径

- 143 项已有相关 JVM 回归通过：AI 31 项、应用 110 项、HTTP 取消 2 项，失败/错误/跳过均为 0。
- 3 项独立 Kotlin 诊断观察通过：缓存清空竞态、上下文压缩预算、Gemini 配置失效键；直接调用当前实现。
- 2 项 Node/TypeScript 诊断观察复现：可选数值清空后变为 0、未展示的确认题被上报为 displayed。运行的表达式取自实际组件，不是重新实现一份逻辑。
- 诊断观察特意断言当前问题行为。其“通过”表示复现成功，不表示问题已修复；这些观察类只由临时 Gradle init 脚本注入，没有加入正式测试源目录或 QA APK。

归档见 summary.json（本地证据，未公开）、JVM 日志（本地证据，未公开）、Web 观察（本地证据，未公开） 及该目录的 `jvm/` XML。

以下“代码确认”指条件与执行链在当前实现中成立；只有注明诊断观察的项目运行过相应边界样本。本轮没有向真实模型发请求、没有向 QA 注入随机对话，没有进行新的 16K 真机渲染压力测试。Web 项目为源码审阅和表达式验证，QA 构建复用了现有 Web 资产，没有重新发布网页端 bundle。

## 优先处理

| 编号 / 优先级 | 问题与触发条件 | 影响、依据与建议 |
| --- | --- | --- |
| CHAT-01 / P1 | **ask_user 草稿会重写整个会话并重建全文索引，写入任务不合并。** 较长会话中每次防抖保存、确认点击和离开页面的 flush 都进入同一链路。 | [saveAskUserDraft](../app/src/main/java/me/rerere/rikkahub/service/ChatService.kt#L2697) 排队等待上一任务，随后 [updateAndSaveConversationState](../app/src/main/java/me/rerere/rikkahub/service/ChatService.kt#L2737) 整会话持久化；[仓库更新](../app/src/main/java/me/rerere/rikkahub/data/repository/ConversationRepository.kt#L398) 删除并插入全部节点，[FTS](../app/src/main/java/me/rerere/rikkahub/data/db/fts/MessageFtsManager.kt#L34) 再删除并重建全部消息索引。提交还要 join 草稿队列。开销随整个历史和分支增长，容易放大“提交没反应”的等待。建议用目标消息元数据的窄更新，草稿与正文索引分离，合并被新草稿覆盖的待写任务；确认确认项的及时 acknowledgement 仍须保留。代码链路确认，实际延迟幅度待长会话测量。 |
| CHAT-02 / P1 | **仅通过 REST 读取上下文的会话没有配对的引用释放，空闲清理可能从未启动。** 条件是访问不在 Android 页面/SSE 订阅中的会话。 | [context 路由](../app/src/main/java/me/rerere/rikkahub/web/routes/ConversationInteractionRoutes.kt#L48) 调用 initialize/getFlow 后返回，没有 acquire/release；[getOrCreateSession](../app/src/main/java/me/rerere/rikkahub/service/ChatService.kt#L716) 将完整会话放进长生命周期 map，却不启动首次空闲检查；[release](../app/src/main/java/me/rerere/rikkahub/service/ConversationSession.kt#L85) 才会调度清理。反复查询不同历史可能持续保留消息树。SSE 路径已有 add/remove 引用，所以不能将此问题推广到所有网页使用。建议所有短请求使用统一 session lease，或让新建且未使用的 session 也有首次空闲回收。代码确认，尚未做真机堆归因。 |

## 常规问题与功能缺口

| 编号 / 优先级 | 问题与条件 | 证据及下一步 |
| --- | --- | --- |
| CHAT-03 / P2 | **草稿持久化失败没有可靠反馈给客户端。** Room/磁盘写入出错时尤其需要明确反馈。 | [草稿任务](../app/src/main/java/me/rerere/rikkahub/service/ChatService.kt#L2719) 捕获异常后只记日志；[HTTP 路由](../app/src/main/java/me/rerere/rikkahub/web/routes/ConversationInteractionRoutes.kt#L43) 仅 join 后返回成功 metadata。join 本身也不能替代结果返回。内存已更新时，客户端可能误以为草稿已落盘，重启后丢失。建议返回明确的保存结果/版本，失败时 HTTP 返回错误，原生卡片显示“未保存/重试”；注入数据库写失败验证。 |
| CHAT-04 / P2 | **底层 LruCache 清空后可被正在进行的磁盘读取重新填入旧值。** | [get](../common/src/main/java/me/rerere/common/cache/LruCache.kt#L51) 在锁外加载，再不校验代次地回填；[clear](../common/src/main/java/me/rerere/common/cache/LruCache.kt#L90) 只清当前 map/store。诊断中 disk 已空，memory 却再次返回 old。建议使用 generation/version 或统一串行操作。此项是基础组件合同缺陷；当前“清理缓存”页面不应被直接等同为已经复现此 clear 路径。 |
| CHAT-05 / P2 | **OCR 缓存键没有包含识别模型、提示词和文件版本。** | [performOcr](../app/src/main/java/me/rerere/rikkahub/data/ai/transformers/OcrTransformer.kt#L82) 在读取设置之前就按 part.url 返回缓存。更换 OCR 模型/提示词后，同一图片仍可命中三天旧结果；同 URI 的文件变化也无法区分。建议键包含供应商/模型/有效提示词 hash、文件指纹和解析版本。 |
| CHAT-06 / P2 | **OCR 的取消/超时与识别失败没有分离。** | [performOcr 的 runCatching/getOrElse](../app/src/main/java/me/rerere/rikkahub/data/ai/transformers/OcrTransformer.kt#L120) 会把 CancellationException 也转为可发送的错误文本。建议显式重抛取消，识别失败返回结构化状态，由发送链路决定继续还是中止。外层 withContext 通常仍会传播父任务取消，因此本轮没有据此认定“停止按钮失效”已复现。 |
| CHAT-07 / P2 | **OCR 原文不受请求日志开关约束，直接写入 logcat。** | [OcrTransformer.kt:109](../app/src/main/java/me/rerere/rikkahub/data/ai/transformers/OcrTransformer.kt#L109) 使用 Log.i 输出完整识别内容，缓存命中也输出文件 URI。应改为长度、耗时、命中状态和错误分类等元数据；正文进入已有显式开启的私有请求诊断边界。 |
| CHAT-08 / P2 | **上下文统计失效键漏掉影响实际请求的配置。** | [requestContextScopeKey](../app/src/main/java/me/rerere/rikkahub/data/ai/context/RequestContextAccounting.kt#L16) 包含部分助手配置，却没有 Gemini useInteractionsApi、geminiOptions 等。诊断确认切换 Interactions 或启用 codeExecution 后 key 相同，旧校准仍可能被 [calculateChatContextUsage](../app/src/main/java/me/rerere/rikkahub/ui/components/ai/ChatContextUsage.kt#L77) 视为有效。建议按实际协议、工具定义、转换器/OCR 配置的稳定签名失效，避免把密钥放进签名内容。 |
| CHAT-09 / P2 | **压缩阈值的“至少半个窗口”下限可突破输入+输出预算。** | [effectiveRollingContextThreshold](../app/src/main/java/me/rerere/rikkahub/data/ai/context/RollingContext.kt#L38) 使用 maxOf(context/2, context-reserves)。诊断：context=64000、maxOutput=48000 时阈值为 32000，仅两者相加就达 80000，尚未算协议预留。具体是否被供应商拒绝取决于该模型允许的输出上限和当时输入量。建议联合校验模型窗口、输出上限、系统/工具预留；不以半窗口覆盖真实可用输入预算。 |
| CHAT-10 / P2 | **切换同一节点的消息版本时，旧 Markdown/HTML 解析快照可暂留。** | [ChatList](../app/src/main/java/me/rerere/rikkahub/ui/pages/chat/ChatList.kt#L606) 按节点 id 保持身份，[ChatMessage](../app/src/main/java/me/rerere/rikkahub/ui/components/message/ChatMessage.kt#L207) 没有按 currentMessage.id 重建正文作用域；[Markdown](../app/src/main/java/me/rerere/rikkahub/ui/components/richtext/Markdown.kt#L459) 和 [HTML 路径](../app/src/main/java/me/rerere/rikkahub/ui/components/richtext/MarkdownNew.kt#L198) 的快照仅 remember，异步新解析完成前仍画旧快照。缓存冷或解析忙时可能出现版本内容短暂不符，操作按钮却已指向新版本。建议区分“同消息流式追加”和“切换消息身份”，后者清空/切换对应快照与卡片状态；需 Compose UI 冷缓存切换复测。 |
| CHAT-11 / P2 | **Android/Web 的 ask_user 草稿对另一客户端写入的 metadata 缺少同步策略。** | [Android 状态初始化](../app/src/main/java/me/rerere/rikkahub/ui/components/message/ChatMessageTools.kt#L389) 的 remember 键不包含 metadata，也没有远端草稿合并；[Web effect](../web-ui/app/components/message/parts/tool-part.tsx#L930) 同样只依赖问题/input 等。两端同时开同一 pending 卡片，另一端保存后，本端可继续用旧完整草稿覆盖它。建议服务端草稿版本+冲突检测，客户端按 dirty 字段与远端版本合并，倒计时和确认 acknowledgement 使用服务器状态。 |
| CHAT-12 / P2 | **Web 把条件上可见的所有问题都当作实际已展示。** | [persistDraft](../web-ui/app/components/message/parts/tool-part.tsx#L984) 的 displayed=visibleQuestions 全集，没有卡片展开、视口或页面可见性门控。表达式诊断在 expanded=false、document hidden 时仍上报 consent；后端 [AskUserInteraction.update](../ai/src/main/java/me/rerere/ai/ui/AskUserInteraction.kt#L68) 会据此启动倒计时。建议与原生的展示语义对齐，用 IntersectionObserver、展开状态和 visibilitychange 控制，不在后台/未读卡片上提前耗尽确认时间。未进行浏览器挂载实测。 |
| CHAT-13 / P2 | **Web 清空可选数值题后，提交会将空值变为 0。** | [allAnswered](../web-ui/app/components/message/parts/tool-part.tsx#L1068) 允许可选数值留空，但 [handleSubmit](../web-ui/app/components/message/parts/tool-part.tsx#L1103) 对空串执行 Number。表达式诊断得到 answers.level=0；若范围 1..5，后端拒绝；若 0 合法则错误地选择了 0。建议复用唯一的答案编码器，空的可选值不发送，提交和草稿保持同一规则。 |
| CHAT-14 / P2，功能补齐 | **Web 没有 render_content 的专用内容/卡片呈现。** | 原生 [RenderingToolContent](../app/src/main/java/me/rerere/rikkahub/ui/components/message/tools/RenderingToolUI.kt#L70) 会将 accepted 渲染工具输出放到正文；Web 没有该工具分支，走 [通用参数/结果 JsonBlock](../web-ui/app/components/message/parts/tool-part.tsx#L1627)，无法等价显示 Markdown、图表等产物。建议共享 renderer 能力表和 accepted/error 合同，再补网页组件、导出和降级显示；这是端间功能缺口，不是本轮 QA 原生渲染失败的证据。 |

## 需要专项验证的三个边界

| 编号 | 已确认的结构 / 本轮证据 | 需要补做的验证与改进 |
| --- | --- | --- |
| CHAT-15 / 内存与缓存 | Markdown/HTML、语法高亮、Base64 图片和文档分别持有进程级缓存；[文档缓存](../app/src/main/java/me/rerere/rikkahub/data/ai/transformers/DocumentAsPromptTransformer.kt#L25) 只有 16 项数量上限，单项读取与总字节量未限制。相关解析缓存没有统一的主动释放入口；[SimpleCache](../app/src/main/java/me/rerere/rikkahub/utils/CacheUtil.kt#L10) 也没有容量上限，未再访问的过期项不会自动清理。 | 统一按实际权重预算，提供可取消的后台解析、single-flight 和清理代次，增加前后台资源释放。API 36 不应依赖已废弃的老式内存回调等级，按当前 Android 文档处理 UI_HIDDEN/BACKGROUND。做后台保留其它应用的压力对照，并分析堆与图形资源。当前证据不足以把旧版低内存回收归因于某个缓存泄漏。 |
| CHAT-16 / 持久化单行大小 | [MessageNodeEntity.messages](../app/src/main/java/me/rerere/rikkahub/data/db/entity/MessageNodeEntity.kt#L29) 将所有备选消息存成单个 JSON 字段；[加载](../app/src/main/java/me/rerere/rikkahub/data/repository/ConversationRepository.kt#L590) 只按节点分页。反复长输出/重新生成会放大一行；[分页回退](../app/src/main/java/me/rerere/rikkahub/data/repository/ConversationRepository.kt#L114) 缩至 1 行后仍只能抛错。 | 用独立测试数据库构造单节点多版本长消息，在目标设备验证 CursorWindow 边界、进程重启后的恢复和导出。若达到设备单行限制，分页无法解决，需要拆分消息/备选版本或将超大内容外置；不能用删除用户历史作为修复。本轮没有破坏或压测用户真实数据库。 |
| CHAT-17 / 长消息布局 | 列表的虚拟化单位是整条消息；[Markdown 正文](../app/src/main/java/me/rerere/rikkahub/ui/components/richtext/Markdown.kt#L541) 使用 Column 渲染整棵顶层节点；[带行号自动换行代码](../app/src/main/java/me/rerere/rikkahub/ui/components/richtext/HighlightCodeBlock.kt#L508) 每行创建 Row 和两个 Text。HTML 已有分帧挂载，但最终仍保留全部子节点。 | 使用至少 16K 混合输出、多次复制、长表格、数千行代码和多卡片场景，测 gfxinfo/Perfetto、解析队列和峰值内存。按数据决定增量挂载、按块虚拟化或将超大代码移到专用查看器。本轮未复现新的滚动卡顿，不能仅凭 Column 推断具体丢帧率。 |

## 设备记录的解释

启动空对话、自定义背景与当前材质设置下，一次快照为 PSS **531379 KiB（约 519 MiB）**、RSS **624984 KiB（约 610 MiB）**，当时 0 个 WebView。见 meminfo（本地证据，未公开）。这只是可调试 QA 的一次基线，包含代码页、保留堆、图形资源等，不能当成存活 Java 对象大小或内存泄漏结论。

系统退出记录（本地证据，未公开） 包含 11 月 16/17 日旧版本的 LOW_MEMORY，以及本次安装产生的 PACKAGE UPDATED。它们不等同于新版异常闪退。本次采集的应用 PID 启动日志未见 FATAL EXCEPTION/ANR；这不覆盖后续长时间后台和压力场景。

## 已有机制与建议顺序

已有消息预提交/停止后的完成屏障、会话持久化锁、滚动摘要分支校验、工具参数/答案严格验证、原生确认题的展示门控、受限 WebView 资源预算与进程退出恢复、HEX/引号正文装饰、代码块主题适配等机制。本轮相关回归通过，未将它们整体列为失效。编辑消息会创建新的消息 ID，因此没有把“摘要只核对 ID”单独判为普通编辑场景的失效。

建议先处理 CHAT-01/02 的存储与会话生命周期，再解决 CHAT-03/08/09 和 ask_user 跨端一致性，随后统一缓存清理并完成 CHAT-15～17 的专项验证。渲染结构改造应以实际帧/内存数据为依据；数据库拆分涉及迁移和旧备份兼容，应单独实施。

## 参考

- [Android：管理应用内存](https://developer.android.com/topic/performance/memory/manage-app-memory)，特别是缓存、UI_HIDDEN/BACKGROUND 和 Android 14+ 回调变化。
- [Compose：可组合项生命周期与 key](https://developer.android.com/develop/ui/compose/lifecycle)，用于核对消息版本身份与 remember/副作用的关系。
- 本地源码与实际实现诊断为本清单的主要依据；所有问题均未在本轮实施功能修复。
