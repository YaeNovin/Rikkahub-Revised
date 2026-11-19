# common 与 search 专项审阅及开发方向（2026-11-16）

建议先统一搜索响应预算、取消及错误契约，并补齐已确认的请求/结果处理缺口；common 优先修复缓存并发一致性与余额表达式解析，再整理公共依赖和日志边界。不要先增加大量搜索参数而继续复用无大小限制的完整响应读取。

本轮为审阅和方向确认，没有修改生产逻辑、安装 APK、操作手机或使用应用内真实供应商密钥。既有未提交修改保持原状，排除 desktop/desktopApp。

## 1. 范围与验证

- common：16 个生产 Kotlin 文件，覆盖 HTTP/取消/SSE、JSON 表达式、缓存、语言头、Context 工具、QuickJS fetch、日志与请求文件存储。
- search：24 个生产 Kotlin 文件，包含 22 个搜索适配器、配置和统一接口；同时追踪 app 的 SearchTools、搜索设置、共享客户端及 OCR 缓存调用。
- 源码摘要（本地证据，未公开）覆盖 66 个生产/既有测试/资源/构建文件及相邻调用方，审阅前后 SHA-256 一致；它不是整个工作区的完整快照。
- **65 项 JVM 测试通过，0 失败、0 跳过：common 21 项、search 44 项。**其中现有测试 52 项，隔离探针 13 项。探针断言当前行为，测试通过不代表已经修复。结果（本地证据，未公开）、common 探针（本地证据，未公开）、search 探针（本地证据，未公开）。
- 搜索探针用 OkHttp interceptor 生成合成响应，不进行 DNS、真实 HTTP 或收费供应商调用；缓存探针通过同步门控制并发顺序，SSE 使用内存中的合成 event-stream。
- 通过 AnySearch 查询并读取官方文档；部分页面提取失败后用 Tavily 获取同一官方页面。Tavily 旧文档地址实际为 404，已改读新 `endpoint/search`、`endpoint/extract` 页面。失败、404、最终正文均保存在来源快照（本地证据，未公开）。未取得可确认的 AnySearch REST 新规范，不把 MCP 工具参数直接当作 REST 迁移依据。

## 2. common：需要修复的内容

### C1 · P2：缓存的内存与磁盘操作没有统一并发顺序

**证据：两个确定顺序的 JVM 并发探针。**

[LruCache.kt](../common/src/main/java/me/rerere/common/cache/LruCache.kt#L51)只在访问内存 map 时持锁，磁盘读取/保存/清理在锁外执行。store 自己的锁不能协调两层之间的顺序。

已复现：

1. get 开始读取旧磁盘值，clear 清掉内存及磁盘，先前的 get 随后把旧值重新写入内存；之后新的 get 仍能取到已清理的旧值。
2. 两次 put 交错，内存保持 new，磁盘最终却保存 old；重建缓存后可能回到旧值。

当前明确调用方为 [OcrTransformer](../app/src/main/java/me/rerere/rikkahub/data/ai/transformers/OcrTransformer.kt#L28)。本轮证明通用缓存缺陷，没有在手机上复现 OCR 清空/恢复异常。

**建议修改：**引入每 key 的操作顺序和缓存 generation；clear/remove 让旧的在途加载失效。持久化更新要与版本比较或串行写队列一致。不能只把整个磁盘操作放进一把全局 UI 可竞争锁。补清理屏障、同 key 更新、删除与重载交错测试。

### C2 · P2：JSON 表达式受 Locale 影响并提前损失精度

**证据：JVM 合成输入 + 实际余额调用链。**

[JsonExpression.kt](../common/src/main/java/me/rerere/common/http/JsonExpression.kt#L356)把 JSON 数字先用 `"%.2f".format` 转为本地化字符串，再用与 Locale 无关的 toDouble 解析。

- 美国 Locale 下，1.23456 在参与表达式之前就变成 1.23；后续乘除会使用已经四舍五入的值。
- 德国 Locale 下输出 1,23，随后抛 NumberFormatException。

此解析器目前用于 [OpenAIProvider.getBalance](../ai/src/main/java/me/rerere/ai/provider/providers/openai/OpenAIProvider.kt#L164)，不是自定义搜索的点分路径解析器。影响应描述为余额查询/表达式计算出错，不能泛化为所有搜索响应解析失败。

**建议修改：**解析/运算保持与 Locale 无关的原始精度，显示阶段再格式化；金额表达式可考虑 BigDecimal，明确舍入规则。补逗号小数系统、极小数、大数、单位换算和非有限值测试。

### C3 · P2：表达式词法与语法校验有缺口

**证据：JVM 探针。**

[Lexer](../common/src/main/java/me/rerere/common/http/JsonExpression.kt#L98)在识别标识符前把 x/X 无条件解释为乘号，因此 `xrate`、`data.xrate` 都解析失败。乘法别名 `2 x 3` 可以使用，但合法字段名被误伤。

另外，单独一个双引号会被当作合法字符串；`items[1.9]` 被接受并将索引截成 1。递归解析和求值没有表达式长度、节点或嵌套预算，深输入的栈风险为静态发现，本轮没有在应用进程中注入栈溢出。

**建议修改：**区分独立运算符与标识符上下文，显式记录字符串是否闭合，数组下标只接受范围内整数。对错误返回位置和可读摘要，增加有界深度/节点数；不要将非法输入静默转换成其他字段或索引。

### C4 · P2：SSE 在慢消费者情况下静默丢事件，目前属于未接入的公共 API 缺陷

**证据：512 条合成 SSE + 慢速消费探针。**

[SSE.kt](../common/src/main/java/me/rerere/common/http/SSE.kt#L66)调用 trySend 却忽略结果；callbackFlow 默认缓冲填满后发送失败。本轮 512 条输入只收到 **64 条**，没有把溢出报告为错误，关闭事件也可能未入队。

当前在 app/ai/search/common 的源码检索只找到该扩展的定义，没有业务调用。**不能将它认定为此前聊天丢字或发送后消息丢失的原因。**

**建议修改：**若继续公开，定义有界且不丢增量的背压政策，溢出时明确终止并报告，或采用按读取进度生产的 Flow；不要简单改成无限队列或 conflated。错误对象避免跨生命周期暴露已关闭的 Response。保留 awaitClose 中的连接取消，并验证消费者提前退出。

## 3. common：需要改造和更新的内容

### 3.1 缓存存储的可靠性和预算

- [SingleFileCacheStore](../common/src/main/java/me/rerere/common/cache/SingleFileCacheStore.kt#L24)每次保存读取、解析并重写整个文件；预加载也先加载全部，内存容量并不等于磁盘/临时解析预算。OCR 虽在 IO 线程使用，仍需按字节控制文本量。
- [FileIO.atomicWrite](../common/src/main/java/me/rerere/common/cache/FileIO.kt#L15)在 rename 失败时可能先删除旧文件再重试；第二次失败会丢旧缓存。与 common/android 下带同步、原子替换的日志写入实现不是同一个函数，不能混淆。缓存未必需要每写必 fsync，但替换/失败政策必须明确。
- 写入/删除异常经常被吞掉，get 的存储异常则可能向外抛出，错误语义不一致。建议区分命中、缺失、损坏、持久化失败；默认缓存失败不阻断聊天，但应有无敏感内容的计数与诊断。
- PerKeyFileCacheStore/Base64JsonKeyCodec 当前没有发现业务调用。其 key 直接编码成文件名，长 key 可能超过文件名限制；新增调用前改用固定长度摘要和版本化元数据，限定目录/单项/总字节预算。
- TTL 使用墙上时钟，时钟跳变、零/负 TTL、溢出和无效 capacity 需要明确约束。保留可重启的绝对过期时间，同时考虑进程内单调时间和时钟注入测试。

### 3.2 HTTP 与 JavaScript 边界

[Request.kt](../common/src/main/java/me/rerere/common/http/Request.kt#L37)已保持取消直到响应消费完成，现有两个取消测试通过；这是需要保留的基础，不应重新改成只等待响应头。

[QuickJSFetch.kt](../common/src/main/java/me/rerere/common/js/QuickJSFetch.kt#L53)则同步 execute/read，并没有把 Call 关联到协程 Job；读取完整正文也没有大小上限。header 查询 Content-Type 大小写敏感，读取异常时 response.close 不在 finally/use 中。传入小写 content-type 时默认 JSON RequestBody 还可能改写实际声明。

建议让 bridge 接收受控执行上下文：总截止时间、在途 Call 注册与取消、响应字节预算和输出预算；HTTP header 查找大小写无关，使用 use 确保异常关闭。原生脚本停止能力见 S3，不能只套一层 withTimeout。

### 3.3 日志问题继续按已有专项路线处理

本轮重新核对 [Logging.kt](../common/src/main/java/me/rerere/common/android/Logging.kt#L109)、RequestLogStore，确认同步初始化/全局锁内落盘及请求正文常驻问题仍存在；本轮日志现有测试 12 项通过，不表示这些性能/隐私缺口已消除。

具体改造沿用[日志专项审阅](logging-review-and-roadmap-20261115.md)：有界 IO 队列、索引与正文分离、统一脱敏、失败可观察和清理屏障。不要同时为 cache、日志、AI 分析再各写一套异步文件框架。此次没有重新验证 AI 发送页面或系统备份传输。

### 3.4 依赖与可测试性

- common 通过 api 导出 OkHttp、QuickJS、FloatingX/Compose、Commons Text 等依赖；可逐步拆 common-core、common-android、script bridge，收紧仅内部使用的依赖暴露，先盘点调用再调整可见性。
- search 声明 minSdk 23，但其 common/ai 使用的通用 library 约定最低 26，主 app 也为 26。当前主应用没有因此支持倒退，但独立搜索库不能据此宣称兼容 API 23；应统一声明或拆出真正可用的轻量核心。
- AcceptLanguageBuilder 的 q 格式化已经使用 Locale.ROOT，不能照搬 C2 指认其相同问题；可补参数范围、脚本语言和空 Locale 测试。Context 缓存目录工具目前没有找到调用，不列为已发生故障。
- 当前版本目录包含 OkHttp 5.4.0、coroutines 1.11.0、QuickJS wrapper 3.2.3、jsoup 1.23.1；本轮没有检索各库最新发行版，不建议无依据整体升级。QuickJS 的中断/资源接口应作为升级评估重点。

## 4. search：需要优先修复的内容

### S1 · P1：统一响应读取缺少总大小和解析预算

**证据：合成响应探针 + 调用链；未进行真机堆分析。**

[SearchService.kt](../search/src/main/java/me/rerere/search/SearchService.kt#L439)为保持取消而完整读取 body.bytes，随后重建内存 Response；多数适配器再转 String 并解析 JSON。2 MiB+1 字节的合成响应被完整接收，没有大小拒绝。

这不意味着 2 MiB 本身必然导致 OOM；缺口是没有统一上限，且完整字节、文本、JSON 和最终工具消息会叠加分配。callTimeout 60 秒、readTimeout 45 秒只限制时间，不限制快速大响应。Volcengine 虽直接 awaitAndUse，仍使用无限制 body.string；QuickJS fetch 也独立读取全部。

**建议修改：**统一有界读取器，先检查声明长度，再在流上限制实际解压后字节；搜索和抓取可有不同预算。限定 JSON 深度、单条正文、结果/图片数及最终工具结果总量，返回 truncated/partial 状态。继续保持读取期间取消，不要为省内存而退回失去取消的实现。自定义 HTTP 已有 2 MiB 限制，可复用其设计，但预算应结合移动端场景标定。

### S2 · P2：Tavily 请求未声明 JSON 类型，抓取失败会表现为空成功

**证据：两个请求/响应探针 + Tavily 官方契约。**

[TavilySearchService.kt](../search/src/main/java/me/rerere/search/TavilySearchService.kt#L102)的 search/extract 用无 MediaType 的 toRequestBody，也没有 Content-Type header。共享客户端只规范已有 JSON header，不会补上缺失值。探针确认 request header 与 body.contentType 均为空；官方声明 application/json。严格网关可能拒绝，**本轮没有实际调用 Tavily 证明其所有线上请求必然失败**。

[ScrapeResponse](../search/src/main/java/me/rerere/search/TavilySearchService.kt#L187)只定义 results，忽略 failed_results；官方明确 HTTP 200 可表示所有 URL 均抓取失败。合成全失败响应被转换成 Result.success(urls=[])，调用方失去原因，模型可能认为没有内容而再次尝试。

**建议修改：**显式 JSON MediaType，解析 request_id 和逐 URL 失败；全部失败返回有类型错误，部分失败保留成功项及失败明细。Tinyfish 的 errors 也应接入同一部分成功契约，不丢弃已付费得到的成功结果。

### S3 · P1（静态风险）：自定义 JS/Bing 阻塞工作无法及时响应取消

[CustomJsSearchService](../search/src/main/java/me/rerere/search/CustomJsSearchService.kt#L89)在 IO 线程执行原生 JS，再通过同步 fetch 做 HTTP；未设置脚本指令/时间/堆预算，也没有 job→Call.cancel 关联。脚本持续计算时，协程取消无法强制中断 native evaluate；多次同步 fetch 的总执行时间也没有统一限制。

[BingSearchService](../search/src/main/java/me/rerere/search/BingSearchService.kt#L49)直接 Jsoup.connect(...).get()，绕过共享 OkHttp 的代理/拦截/取消机制。5 秒 timeout 不能等同于用户点击停止即刻取消；其具体时延还取决于 jsoup 实现和阶段。本轮没有在手机运行无限脚本或测停止延迟。

**建议改造：**先核实 QuickJS wrapper 是否提供同线程安全的中断/内存限制；若没有，评估绑定扩展或专用可回收进程，并限制并发。不能从其他线程随意 destroy 正在运行的 native context。原生计算与网络分别设限，整体任务有截止时间。Bing 可用可取消 OkHttp 获取 HTML，再在工作线程解析，保留反爬/验证码识别。

多数旧适配器使用 runCatching，建议统一重抛 CancellationException；但 withContext 本身也有取消检查，不能据此断言所有已修复的 OkHttp 搜索都无法停止。Custom HTTP、DeepSeek、Volcengine 的显式取消路径应保留。

### S4 · P2：结果数量和文本回退不一致

**证据：三个合成适配器探针。**

- [Ollama](../search/src/main/java/me/rerere/search/OllamaSearchService.kt#L68)将 resultSize 强制夹到 5–10，设置 1 时实际发送 5。官方默认 5、最多 10，示例也使用 3；默认值不等于最低值。
- [Bocha](../search/src/main/java/me/rerere/search/BochaSearchService.kt#L95)使用 summary ?: snippet；summary 为空字符串时即使 snippet 有内容，也返回空文本，应使用非空白回退。
- [Jina](../search/src/main/java/me/rerere/search/JinaSearchService.kt#L96)只采用 description；description 空而 content 有正文时仍返回空文本。应定义摘要与正文的优先级和截断预算。

全局 SearchCommonOptions 没有范围校验，设置页也没有显式按供应商范围适配。Bing/Tinyfish 忽略公共 resultSize，一部分供应商直接发送，另一部分夹取或返回后 take，行为不一致。

**建议修改：**建立供应商 capability/limits，并区分请求的条数、实际发送条数、最终返回条数；对非法值在请求前报错或明确归一化，不应已经发出付费请求后才因 take(负数) 失败。不要统一用一个最大值覆盖所有供应商。

### S5 · P1：搜索错误与系统日志仍可包含原始查询/响应

[Exa](../search/src/main/java/me/rerere/search/ExaSearchService.kt#L108)、Metaso、Zhipu、Bocha 的部分解码失败打印完整响应并拼入异常；Grok、LinkUp、Perplexity、RikkaHub、SearXNG 存在直接 Log.i 查询/URL。共享 HEADERS logger 也继续用于搜索。

这延续了日志专项已确认的边界问题，并非本轮证明新发生泄露。应把供应商、配置 ID、operation、HTTP/API 错误码、request ID、耗时和长度作为默认诊断；原始查询/页面只进入用户主动开启的受限详情。所有 adapter 共用 sanitizer 和错误投影，不能把云端 error.message 无条件当作可展示/可发送 AI 的安全摘要。

## 5. search：供应商更新与功能扩展方向

下面区分代码缺口和文档更新，不代表已逐家联网验证。

| 适配器 | 需要修改、改造或更新的内容 |
| --- | --- |
| Bing | 保留无 Key 搜索选择；统一网络配置/取消，识别验证码、DOM 变化与真正零结果，遵守结果条数。不要因临时解析失败静默切换到收费服务。 |
| Tavily | S2；补 fast/ultra-fast、域名/语言/时间过滤、内容块、extract 深度及超时。include_answer=advanced 和 include_images 目前固定打开，应给用户可见开关与响应预算；不能把答案选项本身直接宣称为额外固定收费。 |
| Exa | 当前工具枚举 fast/auto/deep；本轮官方页列出 lite、deep-lite、deep、auto 及 legacy instant，应建立明确支持/迁移表，核实 fast 的兼容状态后再改。outputSchema/结构化输出需独立解析，不能继续假设 content 总为 String。 |
| Zhipu | 当前固定 search_std；后续核对官方可用引擎和领域/时间选项，配置与 schema 同步。解码失败必须移除原文打印，增加最小合法结果夹具。 |
| SearXNG | 保留私有/LAN 实例和 Basic Auth；改善 base URL/路径构造、JSON 未启用的提示、结果缺省字段和引擎失败诊断。 |
| LinkUp | 文档允许 includeImages 为 boolean 或 string，当前字符串 false 不能报成类型缺陷。可开放域名、时间、输出格式和引用选项；聚合 sourcedAnswer 的费用/时延应独立展示。 |
| Brave | 增加明确的供应商条数/查询限制与容错夹具，再按官方契约补地区、语言、安全/时间筛选。 |
| Metaso | 去除原响应日志，验证缺少摘要/日期等正常缺省形态；后续补搜索范围和内容长度控制。 |
| Ollama | 修正 1–4 条请求；保留 cloud web_search/web_fetch 与本地模型推理端点的区别，不暗示本地 Ollama 自动提供联网搜索。 |
| Perplexity | 保留现有 max_tokens/max_tokens_per_page；补筛选及请求 ID，避免把不完整/错误响应默认为无结果。 |
| Firecrawl | 当前已经使用 v2/search、v2/scrape，不能列成仍需从 v1 迁移。官方有 images、更多来源/分类、域名/时间/地区、安全与 scrapeOptions；只应开放可完整解析的来源，当前 parser 仅消费 web/news。 |
| Jina | S4；统一可配置 search/reader URL 校验、结果 code/status 处理及正文预算，按需要开放 reader 选项。 |
| Bocha | S4；区分 API code 错误、无结果与内容缺省，并补 request/log ID 关联。 |
| RikkaHub | 与 LinkUp 的相似请求不等于永久兼容承诺，应固定独立契约；不支持 scrape 时统一 Result.failure 语义。 |
| Grok | 已用 Responses 的 web_search/x_search，保留正确入口；补多个 assistant output/text、终止状态与错误、citation title/位置及工具调用用量，按配置选择 web 或 X。 |
| Tinyfish | 解析 errors 和 final_url、控制返回条数，核对 search/fetch 专用服务的当前文档；不要用浏览器自动化 API 的文档直接替换现有搜索端点。 |
| Serper | 保留 answerBox/knowledgeGraph 回退；补计数约束、语言/地区和合法空结果/可选字段夹具。 |
| AnySearch | 已有错误码/request_id 处理和空内容回退；下一步获取可版本化的 REST 契约，再评估新的域路由、图片、用量和反馈能力。当前 MCP 功能不能证明 v1 REST 需要相同参数。 |
| DeepSeek | 本轮官方文档确认 v1/tools/search、fetch、Unicode 查询长度、count 和 need_content 与当前核心实现一致。可新增原始问题、地区定位和元数据，但定位只能用户明确配置；优先补预算与状态码分类。 |
| Volcengine | 保留 Custom/Global 独立配置字段和旧 Key 迁移，现有测试覆盖主要计数、视觉区域及结果解析。Custom 官方接口/过滤参数与本轮代码基本对应；后续补用量、LogId、卡片/图像结果的类型化表示，不能只当普通 URL 列表。 |
| Custom HTTP | 已有请求前校验、模型专用字段迁移、可选参数、2 MiB/深度限制、关闭重定向/自动网络重试、取消传播，18 项现有测试通过。后续可按需求增加受控认证/方法/版本化映射，保留旧固定 JSON POST。 |
| Custom JS | S3；本地配置检查与脚本执行分离，展示同步 fetch 的语义，补结果 schema/数量/字节验证。 |

Tavily 的 advanced 深度目前是默认配置，官方计价为每次 2 credits，basic/fast/ultra-fast 为 1 credit；建议提供用户可选的速度/相关性档位，不应未经迁移说明直接改变既有用户的默认深度。具体计价以供应商实时页面为准，本轮不进行费用测算。

## 6. 建议的结构改造与新增能力

1. **统一 SearchExecutionContext。**包含配置 ID、operation ID、截止时间、取消、网络/解析/输出预算和诊断接口。保留本地/LAN 搜索用途，按来源区分网络权限，不能用一刀切的公网限制破坏 SearXNG/自定义服务。
2. **能力声明与结果契约。**把 web、X、image、visual、scrape、batch、异步、筛选和用量声明成可检查能力；schema、配置页、测试页和返回解析从同一信息构建。结果支持 success/empty/partial、逐 URL failure、provider request ID、来源日期/排名、图片元数据及 truncated，而不是只有 answer/items/images。
3. **费用感知调度。**按服务/账号设置并发、QPS 和有界退避；仅在文档允许且可判定安全时重试。HTTP 200 的解析失败不应自动重发付费 POST；备选供应商必须显式配置，保留无结果和失败的区别。
4. **短期缓存与同请求合并。**缓存 key 包含 provider/config revision、查询、过滤、语言和深度；支持 bypass。时间敏感查询使用短 TTL，错误与空结果单独处理，取消一个消费者不应无条件终止其他消费者共享的请求。避免直接复用尚未修复 C1 的缓存。
5. **搜索内容与模型上下文预算。**SearchTools 当前把完整标准化结果作为一条工具文本返回；新增去重、按来源截断、总字符/token 预算和可继续抓取的标记。外部页面保持不可信数据身份，避免直接提升为 system 指令。引用 ID 保持稳定可关联，不随正文裁剪失去来源。
6. **SDK/UI 分离与依赖注入。**SearchService 当前同时包含 Compose Description、序列化配置、全局可变客户端和 ai InputSchema/KeyRoulette 依赖。先拆纯执行/DTO 与 Android 设置展示，再逐步改为实例级 client/clock/key selector。可提升普通 JVM 契约测试覆盖，不要求一次重写 22 个服务。
7. **有用的新测试/界面。**增加不联网的配置检查，以及用户主动触发的单次联通测试；测试结果显示协议、实际返回数、耗时、部分失败和截断，提供取消。每家至少覆盖最小成功、无结果、缺省、HTTP/API 失败、取消、超限响应；删除示例加法测试时用真实契约覆盖替代。

## 7. 推荐实施批次

| 批次 | 工作 | 验收重点 |
| --- | --- | --- |
| 第一批 | S1/S2/S4/S5：有界响应、Tavily JSON/失败、文本回退/条数、诊断边界 | 未知长度/压缩大响应及时拒绝；取消关闭 Call；全失败不会显示空成功；查询和合成秘密不进入默认日志；不自动重发已成功的付费请求 |
| 第二批 | C1/C2/C3：缓存一致性、Locale/精度、词法和语法 | clear 后在途结果不能复活；内存/磁盘版本一致；德语系统余额可计算；x 字段可访问、非法引号/下标被拒绝 |
| 第三批 | S3/C4：JS/Bing 停止、公共 SSE 背压 | 脚本/网络/整体执行有界，native 中断先验证安全性；慢消费者无静默丢增量，且不能改成无限内存缓存 |
| 第四批 | capability/结果契约、供应商参数和可观察性 | 配置/schema/执行一致；partial/empty/error 可区分；旧配置可迁移，图片/视觉结果不会被当作空网页列表 |
| 第五批 | 模块拆分、版本固定、缓存/调度和设备回归 | JVM 适配器独立可测；多并发/低内存/后台停止有真机数据；升级依赖有契约对照，而非只看编译成功 |

## 8. 测试记录与实际限制

运行本地 Gradle 9.5.0 / JDK 21 / 既有缓存，任务为 `:common:testDebugUnitTest :search:testDebugUnitTest`，离线且仅通过审阅 init script 注入探针。初次沙箱内无法加载 native-platform.dll，在获准的正常本地环境重跑成功；没有为测试改动生产代码或环境配置。

成功记录：`build/reports/qa-build/20261116-005102/attempt-1.log`。采集脚本为 collect_evidence.py（本地证据，未公开）。报告中 65 项包含两个现有示例加法测试，不能把数量等同于 65 个供应商场景；未对每个适配器建立完整请求/响应矩阵。

本轮未执行：真机性能/堆/电量测试、JNI 脚本中断、真实搜索准确率/召回率、供应商当前账户权限/计费、重定向网络链路及 Release 日志剥离验证。上述方向需要后续实现后专项验收。来源中的截断文档/404 不作为已验证正文。

## 9. 官方资料

- [Kotlin callbackFlow](https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines.flow/callback-flow.html)：默认缓冲、背压和 awaitClose。
- [Kotlin Cancellation](https://kotlinlang.org/docs/cancellation-and-timeouts.html)：协作取消、阻塞操作、重抛 CancellationException。
- [Tavily Search](https://docs.tavily.com/documentation/api-reference/endpoint/search)、[Extract](https://docs.tavily.com/documentation/api-reference/endpoint/extract)：JSON、深度/额度、全部失败可返回 HTTP 200、逐 URL 错误。
- [Exa Search](https://exa.ai/docs/reference/search)：搜索类型、过滤、输出结构；Bearer 认证也被官方接受，不把它误报为只能 x-api-key。
- [Ollama Web Search](https://docs.ollama.com/capabilities/web-search)：默认/max_results、搜索与抓取。
- [Firecrawl Search](https://docs.firecrawl.dev/features/search)：v2、图片及筛选/抓取选项；页面同时有 SDK 示例，升级时应固定目标 REST schema。
- [LinkUp Search](https://docs.linkup.so/pages/documentation/endpoints/search/reference)：参数类型与 outputType。
- [DeepSeek Search & Fetch](https://api-docs.deepseek.com/guides/search/)：实际路径、查询/计数、可重试状态与使用边界。
- [豆包搜索 Custom](https://docs.volcengine.com/docs/87772/2272953)：接口、过滤、队列模式、计数、如意结果和 QPS。此页不能单独证明 Global 的全部最新约束。
