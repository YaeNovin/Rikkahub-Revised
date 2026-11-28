# RikkaHub Revised 长期演进审阅

审阅日期为 2026-11-22（北京时间）。开发检出为 `codex/p0-align-revised10-20261119`，HEAD `81ba708b1`，包含当时的未提交改动与本地桌面原型。外部调研覆盖聊天产品、知识检索、数据互通、桌面移植、KMP、设备推理与开放规范。AnySearch 完成 20 次检索，Tavily 完成 12 次检索，并回读关键项目仓库、官方文档和规范。邻近项目按选定入口审阅源码、设计与已有验证记录。

## 优先判断

1. **先稳定一次生成的身份与结果。** 把消息版本、模型请求尝试、工具结果、引用和产物关联起来。流式中断、Web 重连、分支比较、记忆来源以及桌面复用都依赖这条链路。
2. **把引用和专业渲染变成可回访的工作。** 用户从结论跳回原文，从独立产物回到生成它的那条消息，修改或比较后可带着版本、来源与附件一起导出。这是现有知识库、搜索、分支、工作区和渲染能力可以共同支撑的方向。
3. **用 Android 与 Windows JVM 验证 KMP 的最薄切片。** 先共享决策规则、事件和编解码。Compose Desktop、React Web UI、独立 WinUI 可通过同一组数据契约和样例获得一致行为，各自沿用已有平台入口。

## 一、当前基线

| 领域 | 当前代码或记录 | 长期审阅含义 |
| --- | --- | --- |
| 对话与生成 | `Conversation` 保存各节点的多个消息版本，独立分叉有来源元数据。`ChatService` 已有停止、部分输出、审批续跑、检查点与迟到更新保护。`GenerationHandler` 同时承担请求组织、工具和流式流程。 | 先明确每次生成及其副作用的身份和终态，并复用现有续跑行为。保留旧会话可读性与分叉独立性。 [会话模型](../app/src/main/java/me/rerere/rikkahub/data/model/Conversation.kt#L19) · [ChatService](../app/src/main/java/me/rerere/rikkahub/service/ChatService.kt#L1454) · [生成链路](references/chat-generation-pipeline.zh-CN.md) |
| 模型与搜索 | 已有多协议 provider、有效能力查询、原生与外接搜索路由，以及可持久化的网页引用 ID。Tavily 和 AnySearch 都已有应用内 search/scrape 适配。 | 机会在于统一能力来源、请求实际值、跨来源比对和证据留存。 [AI 专项](ai-module-review-and-roadmap-20261115.md) · [搜索路由](search-routing-models-scroll-20261115.md) · [SearchTools](../app/src/main/java/me/rerere/rikkahub/data/ai/tools/SearchTools.kt#L26) |
| 文档、知识与记忆 | 有文档提取、可恢复导入、页级排序、向量与词法检索、引用持久化。记忆已有作用域、写入版本保护和查询预算。 | 将检索命中转成可审查的来源，将记忆写入、来源、生命周期和删除转成用户可检查的状态。 [知识库仓库](../app/src/main/java/me/rerere/rikkahub/data/repository/KnowledgeBaseRepository.kt#L121) · [记忆优化](memory-optimization-20261120.md) · [分支审阅](memory-branch-review-20261120.md) |
| 渲染与交付 | Android 聊天正文、整条消息网页预览和 React Web UI 使用不同渲染路径。工作树已包含语言学、曲谱附件、MEI 与分页 PDF 相关源码及 QA 记录。 | 先复用格式定义、限制、能力与失败语义，再从已有格式中选代表性产物验证三端。 [渲染路线](rendering-roadmap-20261120.md) · [语言学](linguistic-rendering.md) · [曲谱](music-rendering.md) |
| 数据与附属模块 | Room schema v45，S3/WebDAV 归档备份，有配置和会话导入。平台功能包括 Android WebView、SAF、WorkManager、JNI/PRoot、媒体与系统服务。 `trace-cli`、`locale-tui`、`baselineprofile` 各有已有专项。 | 跨端首先传逻辑数据和附件引用。数据库迁移、本机凭据和任务执行采用各平台的生命周期。 [数据库版本](../app/src/main/java/me/rerere/rikkahub/data/db/AppDatabase.kt#L79) · [其余模块审阅](remaining-modules-review-and-roadmap-20261116.md) · [语音与工作区](speech-workspace-review-and-roadmap-20261116.md) |
| Web 与桌面 | `web` 是内嵌 Ktor 服务，`web-ui` 是独立 React 前端。本地 `desktopApp` 是可选的 Kotlin/JVM 原型，目录、构建开关及其专用依赖未随本仓库发布。 | 干净检出可复现性是桌面进入协作与 CI 的第一道验收。现有 Web 路径可继续承担轻量伴随端。 [模块结构](PROJECT_STRUCTURE.md#5-web-端集成链路) |

## 二、D:\Codex 项目中可借鉴的做法

| 本地参考 | 可迁移到 RikkaHub 的内容 | 适用边界 |
| --- | --- | --- |
| **Desktops / YeaNovin Desktop** `29fab03` | 右侧工作台按文件、图形、执行和变更展开。文件检查点用哈希检测后续修改，便于验证撤销与重做。知识库可定位文件/行并显示原生引用浮层。统计区分物理请求尝试与逻辑回复。凭据分离、短期使用与数据库/WAL 脱敏测试可作为边界样例。 | 优先共用合成 SSE、导入包、引用定位和产物样例。Windows 交互、DPAPI、WebView2 与 WinUI 更新链各自维护。 工作台（本地参考，未随仓库发布） · 文件权限与撤销（本地参考，未随仓库发布） · 验证（本地参考，未随仓库发布） · 凭据存储（本地参考，未随仓库发布） |
| **LingheChat** `d7662fe` | 小规模链路便于验证流解析、取消后保留部分输出、重开恢复、切模型和消息版本。还有原文与渲染分离、附件引用计数、Keystore、白名单诊断和测试环境隔离。 | 用同一合成场景检查两端状态机，明确各自用到的协议与平台能力。推荐先移植测试样例，再依据差异调整实现。 架构（本地参考，未随仓库发布） · 消息版本（本地参考，未随仓库发布） · 附件生命周期（本地参考，未随仓库发布） · 双轨日志（本地参考，未随仓库发布） |
| **Yunzai** `99f0897` | 插件注册表描述兼容状态、契约版本和启用条件。任务超时后可回收所属宿主，渲染复用后会清理 DOM、映射和视口。运行、管理与测试身份分域，生命周期删除会拒绝迟到响应。适合借鉴到 MCP/Skills、渲染器和产物清理。 | `Miao-Yunzai`、`miao-plugin`、`guoba-plugin`、`Yunzai-Bot-plugins-index` 是固定版本的上游参考。分别提供旧框架兼容样本、模板保真样本、配置面板设计和生态候选索引。CI、静态兼容与真实渲染各有验收记录。 架构（本地参考，未随仓库发布） · 兼容矩阵（本地参考，未随仓库发布） · 插件索引盘点（本地参考，未随仓库发布） · 渲染（本地参考，未随仓库发布） · 数据生命周期（本地参考，未随仓库发布） |
| **rikkahub-release** `5179a8da2` 与同级资料 | 发布检出显示旧版的源码边界。二次开发和开源准备记录提供历史数据模型、上游差异、签名与第三方材料的追踪方式。可据此挑选旧归档金样和检查从干净检出构建的路径。 | 发布检出位于 Revised .5 时期。长远判断取当前工作树源码，历史目录用于迁移与发布溯源。 发布构建入口（本地参考，未随仓库发布） · 旧审阅（本地参考，未随仓库发布） · 开源准备（本地参考，未随仓库发布） · 构建资源（本地参考，未随仓库发布） |

已排除 `blander`、`projects`。顶层 `cache` 只有 Gradle 缓存。RikkaHub 外层工具链、签名和制品目录属于构建支持，审阅查看了资源说明与发布资料。

## 三、功能与架构候选

### 1. 对话、模型和运行记录

| 候选 | 建议做法与验收 |
| --- | --- |
| 运行时间线与续跑 | 在现有检查点上补会话、分支、消息、逻辑运行、物理尝试和工具调用的稳定关联。记录等待审批、生成、停止、中断、失败、完成，以及已生效的副作用。用断流、进程重启、切分支和双端重复审批验证同一运行不会重复提交副作用。 |
| 上下文检查器 | 接续既有滚动摘要和计量，在发送前或事后展示实际使用的模型协议、各上下文来源的估算、被裁剪项和注入原因。支持从记忆、摘要段、知识片段跳回批次或消息版本。估算与供应商实计分列。 Desktops 上下文发布说明（本地参考，未随仓库发布） · [RikkaHub 上下文现状](context-memory-upgrade.md) |
| 多模型对照与分支树 | 对同一输入冻结上下文，生成两条独立候选。并排查看答案、证据、耗时、已知成本和错误。保留按回复版本继续、选中作为当前路径和整段分叉。验收包含一支取消、一支成功及历史各自重开。 [Cherry Studio](https://github.com/cherryhq/cherry-studio) · Desktops 回复版本与搜索（本地参考，未随仓库发布） |
| 可解释的模型能力目录 | 区分公开目录、provider 模板、协议映射、端点探测与用户覆盖。缓存来源和更新时间，显示哪些参数会真正发送。对 fallback 提前限定费用、数据目的地与允许的模型，已有输出或工具副作用时进入明确的续跑/重试决策。 [models.dev](https://github.com/anomalyco/models.dev) · [AI 专项](ai-module-review-and-roadmap-20261115.md) |
| 回放、评测与性能预算 | 用现有 `trace-cli` 产出脱敏、版本化的回放样例，补 UTF-8 分片、流终止、代理错误、双端恢复与工具重复回调。应用层统计按逻辑运行归集物理尝试，首字节与首可见字符分别计量。主路径用确定性夹具测帧时和峰值内存，用固定设备场景测电量。模型对测记录引用命中、任务成功率、耗时与成本。 [trace-cli 专项](remaining-modules-review-and-roadmap-20261116.md#6-trace-cli) · [Promptfoo](https://www.promptfoo.dev/) · [OpenTelemetry GenAI](https://opentelemetry.io/blog/2026/genai-observability) |

### 2. 研究会话、知识与记忆

| 候选 | 建议做法与验收 |
| --- | --- |
| 证据库与研究会话 | 将搜索结果、网页摘录、本地 PDF/文档片段和手动笔记纳入同一来源列表。保存 URL/文档身份、版本或内容哈希、获取时间、原文摘录及页码/段落锚点。点击结论定位原文，文档变动后标记版本已失效或锚点待重新确认。 [NotebookLM 聊天与引用](https://support.google.com/notebooklm/answer/16179559?hl=en) · [Zotero PDF 批注](https://www.zotero.org/support/pdf_reader) · [W3C Web Annotation](https://www.w3.org/TR/annotation-model/) |
| Tavily + AnySearch 研究模式 | 默认沿用当前选择，深度研究可显式开启双源。各家保留排名、时效、提取状态和请求 ID，统一 URL 去重和来源预算。先在多样化题集测两源并用对来源多样性、引用有效率、延迟及使用量的影响。Tavily Extract 应记录每个 URL 的成功或失败。AnySearch REST 文档现已公开，先为当前 `data.results` 解码和公开结果字段建立版本化夹具，并隔离可能携带敏感信息的错误体，再决定迁移策略。 [Tavily Search](https://docs.tavily.com/documentation/api-reference/endpoint/search) · [Tavily Extract](https://docs.tavily.com/documentation/api-reference/endpoint/extract) · [AnySearch REST](https://www.anysearch.com/docs/api-endpoints/v1-search) · [本地 AnySearch 适配](../search/src/main/java/me/rerere/search/AnySearchService.kt#L145) |
| 知识库检索控制台 | 让用户按会话选择库、文件夹、标签和日期，展示“命中了哪里”“为何注入”。基于现有词法/向量流程对比混合排序、重排与投喂全文。以人工标注问题测试召回、定位和失败原因，量化额外耗时。向量身份绑定模型配置和内容版本，允许按库重建。 [Open WebUI Knowledge](https://docs.openwebui.com/features/workspace/knowledge) · [本地知识库实现](../app/src/main/java/me/rerere/rikkahub/data/repository/KnowledgeBaseRepository.kt#L132) |
| 可治理记忆与角色设定 | 以来源、作用域、状态和有效期组织事实记忆、分支情景记忆与手动固定内容。提供矛盾并列、主动确认、回滚与删除解释。世界书在命中时展示规则、注入位置和优先级，方便复现角色或叙事分支。验收关注跨分支误注入和旧任务回填。 [记忆分支审阅](memory-branch-review-20261120.md) · [世界书指南](worldbook-guide.md) |
| 文档和研究包 | 文档处理先输出页/节/表格位置和明确的局部失败，再做按需 OCR 或重排。研究包能保留报告、证据、产物源文件和 Markdown 引用索引，用于跨设备或编辑器回访。连接外部资料时记录来源权限及索引更新时间。 [文档专项](remaining-modules-review-and-roadmap-20261116.md#2-document) · [AnythingLLM](https://anythingllm.com/) |

### 3. 产物、工作区和专业渲染

| 候选 | 建议做法与验收 |
| --- | --- |
| 项目级上下文 | 从会话文件夹、助手和工作区之间建立可选的项目关联。项目保留说明、参考资料和产物集合，会话仍可单独选模型。首版验证移入/移出会话、项目设置的版本、共享资料的变更是否影响历史复现。 [LibreChat Projects](https://www.librechat.ai/docs/features/projects) · [LobeHub Projects](https://github.com/lobehub/lobehub) |
| 独立产物与版本 | 为图表、报告、谱面和数据定义独立 ID、schema 版本、来源消息/运行、原始内容、修订、预览状态和导出。旧消息围栏继续可读，用户可显式保存为产物。以同一段谱面或 Vega 图验证保存、改版、分支引用、源码下载和可访问降级。 [LibreChat Artifacts](https://www.librechat.ai/docs/features/artifacts) · [LobeHub Pages 与 Projects](https://github.com/lobehub/lobehub) · [Jupyter nbformat](https://nbformat.readthedocs.io/en/stable/format_description.html) |
| 渲染器能力与预算 | 从当前 catalog 导出带版本的格式、别名、输入约束、各端能力和回退。区分输入合法、已绘制、可导出与失败。同一合成样例运行 Android WebView、消息网页预览、浏览器或未来桌面宿主，比较主题、字体、资源、挂起恢复、内存及渲染宿主回收。网页内容与 MCP UI 各自有宿主策略。 [渲染目录](../app/src/main/java/me/rerere/rikkahub/data/render/RendererCatalog.kt#L5) · [JSON Schema](https://json-schema.org/specification) · Yunzai 渲染规范（本地参考，未随仓库发布） |
| 学科工作流 | 优先做完整链路：语言学支持对读、词注和依存树导出，音乐支持原谱、修改和分页 PDF，历史支持不确定日期、事件/地图联动和史料依据，数理支持表格数据、图形及计算来源。再按需求评估化学结构和 3D 模型。验收用典型作业，包含离线打开、文本替代、分享后重开、失败仍可读取原件。 [分学科路线](subject-rendering-roadmap-20261120.md) · [Vega-Lite](https://vega.github.io/vega-lite/) |
| 文件提案与会话工作台 | 大屏采用文件、产物、运行、变更面板，手机用会话内抽屉/全屏。先显示只读差异、来源与影响范围；仅对能被完整捕获的应用内变更提供校验及撤销。外部工具结果显示执行状态、耗时和人工确认记录。 Desktops 工作台（本地参考，未随仓库发布） · 工作区操作（本地参考，未随仓库发布） |
| MCP/Skills 兼容与交互 | 先完善工具分页、结构化结果、取消、来源归属和配置版本；用小型兼容矩阵标明各会话可用范围。再用只读图表实验 MCP Apps，独立验证消息桥、加载资源、断开清理和显式发起动作。会话授权、设备授权和 UI 交互权限各有自己的生命周期。 [McpSessionRegistry](../app/src/main/java/me/rerere/rikkahub/data/ai/mcp/McpSessionRegistry.kt#L309) · [MCP 工具规范](https://modelcontextprotocol.io/specification/2025-06-18/server/tools) · [MCP Apps](https://blog.modelcontextprotocol.io/posts/2026-01-26-mcp-apps/) |
| 创建可复用模板 | 从研究、曲谱导读、论文阅读、模型对测和文档整理中提取可版本化的助手/项目模板，包含输入、可选来源、工具范围和预期产物。先做数据导入、模板预览与字段缺失说明，再评估第三方模板分发。 |

### 4. 数据、可靠性和离线

| 候选 | 建议做法与验收 |
| --- | --- |
| 可验证的备份恢复 | 为 S3/WebDAV 共用一致快照与版本清单，明确附件清单、哈希、兼容范围、失败回滚和凭据另配。迁移验证测试旧归档、恢复中断、并发写、引用完整性与首次重建索引。 [现有备份审阅](remaining-modules-review-and-roadmap-20261116.md#9-app此前未专项展开的数据与工具管理) |
| 跨端可移植包 | 首版切片建议只传文本消息、当前选择、版本关系和有限附件，下一版逐步加引用及产物。导入前预览增量与丢失字段，重复导入幂等，每端用合成文件验证校验失败和回滚。社区 Tauri 桌面移植公开声明可导入 Android 备份，可作为边界试验参照。 [本地 Chatbox 导入器](../app/src/main/java/me/rerere/rikkahub/data/sync/importer/ChatboxImporter.kt) · [社区桌面移植](https://github.com/yuh-G/rikkahub-desktop) |
| 本地优先增量同步 | 先做单用户、追加式消息和多设备游标。显示同步进度，附件按哈希续传，删除使用带期限的墓碑。草稿、标题、分支选择、记忆和文档各有不同冲突语义。只有当多人或多端同时编辑同一文本成为真实需求，再对比 CRDT、服务端同步与简单版本冲突。 [Ink & Switch](https://www.inkandswitch.com/essay/local-first/) · [Automerge](https://automerge.org/) · [PowerSync Kotlin SDK](https://docs.powersync.com/client-sdks/reference/kotlin) |
| 端侧模型与桌面推理 | 先用现有兼容协议连接用户选择的本机/局域网推理服务，明确请求目的地。手机端以小模型做标题、分类或有限摘要的可退回实验。比较首字延迟、峰值内存、热量、电量、模型下载和磁盘占用，再决定端侧能力覆盖范围。 [LiteRT-LM](https://github.com/google-ai-edge/LiteRT-LM) · [llama.cpp](https://github.com/ggml-org/llama.cpp) |
| 移动设备体验与后台 | 长会话、流式大段 Markdown、切版本、键盘、折叠屏、浏览器后台和低内存退出用真实场景计量。逐步引入按块渲染/挂载和焦点恢复。Web 重连以快照和序列号对账，Android 后台按系统约束持久化可恢复状态。 [Android 后台恢复](runtime-resume-memory-20261115.md) · [Web 后台验证](web-background-access-fix-2026-10-06.md) |
| 工程与发布 | 默认 CI 从干净检出验证 Android 与 Web，共享契约同时在 Android JVM、Windows JVM、TypeScript 回放。逐轮增加真实 Android/WebView 和 Windows IME/安装升级验收。素材清单保存引擎、字体、运行时、来源、哈希与许可，覆盖资产更新与移除。 [构建维护](building.md) · [第三方声明](THIRD_PARTY_NOTICES.md) · Yunzai CI（本地参考，未随仓库发布） |

## 四、KMP 与桌面路线

### 当前实现和迁移边界

- 本地 `desktopApp` 原型使用 Kotlin JVM 插件和 Compose Multiplatform 的桌面依赖，JDK 工具链为 21，字节码目标为 JVM 17。本地扫描未发现已接入的 KMP `commonMain`、多目标共享库、iOS 入口或共享业务依赖。桌面现有 UI 只验证窗口、主题和草稿输入底座；原型与验证说明留在本地，未随本仓库发布。
- `:ai` 与 `:search` 当前都按 Android Compose library 配置，部分 provider 设置与 Compose 耦合。`Conversation` 暴露 Android `Uri`，带 JVM `Instant` 和 app 内其他类型。直接使用这些模块会将 Android 依赖引入首个桌面业务切片。 [AI Gradle](../ai/build.gradle.kts) · [Search Gradle](../search/build.gradle.kts) · [ProviderSetting](../ai/src/main/java/me/rerere/ai/provider/ProviderSetting.kt#L3) · [Conversation](../app/src/main/java/me/rerere/rikkahub/data/model/Conversation.kt#L19)
- AGP 9 下新增共享库应评估 Android-KMP 库插件。它使用单 variant，Android 构建变体继续由平台应用与 Android 专用库持有。Room KMP 可作为后续候选，现有 `SupportSQLiteDatabase` 迁移、Paging/FTS、附件路径及备份测试应有独立迁移计划。 [Android KMP 插件](https://developer.android.com/kotlin/multiplatform/plugin) · [AGP 9 迁移](https://kotlinlang.org/docs/multiplatform/multiplatform-project-agp-9-migration.html) · [Room KMP](https://developer.android.com/kotlin/multiplatform/room)

| 模块 | 可优先提取 | 随平台维护的实现 |
| --- | --- | --- |
| `:ai`、`:search`、`:common` | 消息事件、参数和能力规则、协议 JSON 解析、引用规范化、请求预算。 | HTTP 引擎、端点密钥、设备 URI、provider Compose 编辑器、Android QuickJS。 |
| `:app` 的数据与运行层 | 分支选择、上下文预算、任务状态转换、授权结果与备份交换格式。 | Room 迁移、Worker、服务进程、系统文件选择、密钥库和本机作业存储。 |
| `:document`、`:speech`、`:workspace` | 页面与段落契约、语音请求和结果、文件句柄、工作区操作及状态。 | 文档库、OCR 与 JNI，音频采集/播放，SAF、PRoot 与 Android 终端。 |
| `:highlight`、`:material3`、富文本 | 标记格式、主题色角色和可移植判定规则。 | Compose 与 React 组件、字体测量、WebView/浏览器宿主、资源包及设备分色。 |
| `:web`、`web-ui` 与其他客户端 | API/事件 schema、兼容策略、测试样例和源数据。 | Ktor 服务、React 客户端、独立 WinUI 的 UI 与运行时。 |

### 纵切验证顺序

| 步骤 | 共享内容 | 平台侧与通过条件 |
| --- | --- | --- |
| K0 · 本地底座可复现 | 定义位置、依赖锁定和首批平台目标。现阶段可继续显式开启。 | 进入团队 CI 前，从干净检出按说明编译 Android 默认配置与 Windows 桌面。离屏测试之外用实际窗口查中文 IME、剪贴板、快捷键、焦点和 100%/150% DPI。 |
| K1 · 纯 Kotlin 行为 | 首批共享消息事件与结束状态、版本选择、能力规则、引用和附件句柄等最小数据，使用 `commonTest` 验证状态转换和序列化。 | Android 从现有消息/数据库适配，桌面从合成样例适配。保留旧数据读取、未知字段和已有消息 ID；同一输入产生相同的逻辑事件序列。 |
| K2 · 文本聊天闭环 | 一条现有兼容文本协议，验证发现模型、发送、流式解析、取消、断流后保留和错误映射。先锁定契约，复用现有 provider 行为并用合成流检查等价性。 | Android 与 Windows JVM 可先用现有 JVM 网络设施。若之后确需 Kotlin/Native，再用一条实际协议比较 Ktor 引擎的 SSE、POST、代理和取消表现。`Flow` 可共享，连接和凭据交由宿主提供。 [Ktor Client SSE](https://ktor.io/docs/client-server-sent-events.html) |
| K3 · 数据与少量 UI | 对话读写、归档编解码和渲染能力共享。按使用价值试做纯消息/引用组件。 | 保持 Android Room 迁移链。桌面以自身仓储实施关闭重开与导入；本地文件、密钥、WebView、通知和窗口由宿主适配。Windows 桌面本地记录决定独立 WiX 流程，进入分发时再验证运行时、升级和签名。相关桌面原型说明未随本仓库发布。 |
| K4 · 扩平台判断 | 根据 Android 和 Windows 已复用能力决定 iOS 或 Linux/macOS 的下一条纵切。 | iOS 从文字闭环起步，单独验证 macOS/Xcode 构建、凭据、后台恢复、文件选择、键盘和发布。React Web 优先消费协议和样例。 [JetBrains Jetcaster 迁移](https://kotlinlang.org/docs/multiplatform/migrate-from-android.html) · [Compose Multiplatform](https://github.com/jetbrains/compose-multiplatform) |

### Windows 与 Web 三条路径

| 路径 | 适合的验证方向 | 决策依据 |
| --- | --- | --- |
| RikkaHub `desktopApp` | Android/桌面共用 Kotlin 核心，验证自主桌面客户端。 | 最能检验 KMP 能否减轻多端维护。当前业务量小，输入法和包体是早期验证项。可对照 Kotlin + Compose Desktop + Ktor 的社区实现。 [Lyrecoul 桌面移植](https://github.com/Lyrecoul/rikkahub-for-desktop) |
| YeaNovin Desktop / WinUI | 独立产品的 Windows 深度工作流、原生工作台和系统集成。 | 保持独立，通过运行、引用、产物和归档夹具检验互通。参考 Windows UX、DPAPI、日志和安装验证。 Desktops README（本地参考，未随仓库发布） |
| 现有 React Web UI / 轻桌面壳 | 浏览器访问手机侧会话，或验证单独运行 Web 应用的交付需求。 | 当前内嵌服务已有实用价值。社区 React + Tauri + Bun sidecar 提供替代架构证据，选择独立宿主意味着维护 sidecar 生命周期、升级与数据迁移。 [社区 Tauri 移植](https://github.com/yuh-G/rikkahub-desktop) · [本地 Web 对齐文档](webui-feature-parity-plan-2026-10-06.md) |

## 五、实施批次与完成条件

| 批次 | 交付 | 完成条件 |
| --- | --- | --- |
| 首批 | 生成身份/终态、跨端回放夹具、KMP 纯 Kotlin 纵切，并行确定归档包最小字段。 | 原生与 Web 可回放同一断流/恢复场景。Android 原有路径和干净构建可用。桌面可从合成数据验证文本输入、取消和重开。 |
| 第二批 | 一个研究会话和一个独立产物，从双源检索或本地 PDF 走到原文定位、改版、导出。 | 有失败 URL 与过期锚点反馈。Android 与 Web 至少共用来源和产物契约。导出包在隔离桌面库中重开并保留分支、原文和修订。 |
| 第三批 | Windows 首条真实业务纵切、选择性备份恢复和工作台变更审查。 | 离屏、实际窗口、干净安装与升级均有各自验证；中止恢复或导入中断后数据库与附件引用一致。 |
| 条件性探索 | 增量同步、iOS、端侧模型、MCP Apps、专业学科编辑和多人共享。 | 每项需有代表性用户任务、使用频率与维护成本证据。只有离线、多设备或跨端输入的实际缺口能证明其优先级。 |

下一轮可用三个小型验收样本推进。**可恢复对话**覆盖断流、切分支和重复审批。**带证据的研究报告**从 PDF 或网页结论跳回原文。**可移植谱面或 Vega 图**在另一端恢复源码、版本和附件。三者都可使用无凭据的合成夹具。

## 审阅验证范围

本次查阅工作树、邻近仓库、历史构建/设备记录与公开资料。联网查询使用 Codex 会话中的 Tavily 和 AnySearch，应用内两个 REST 适配器的生产联调仍需另行验证。Windows 桌面历史测试 XML 记录 2026-10-16 的 2 项离屏 UI 测试通过。本轮尝试重跑，Gradle 无法写入指向工作区外共享缓存的 `native-platform.dll.lock`，在初始化阶段终止。各项历史 QA 保留原验证口径，本次审阅未重新运行整套 Android 或 Windows 真机流程。
