# ai 模块专项审阅与开发路线（2026-11-15）

建议先完善**协议结果完整性、统一请求计划、流式与媒体资源预算**，再新增 Gemini Interactions、完整结构化输出和多模态输入。继续增加模型名称规则有价值，但不能替代协议、参数和执行能力的一致性。

本次是审阅、合成验证和方向建议，没有修改生产代码、安装 APK 或调用真实供应商。排除 desktop/desktopApp，保留工作区既有修改。

## 1. 范围与证据

- 检查 Provider/ProviderManager、模型名称与能力匹配、参数族、三个协议的请求构造与流式解码、工具回放、图像/视频、embedding、文件编码、Vertex 认证、重试、决策模型、诊断及对应测试。
- ai 当前有 80 个主 Kotlin 文件；源码摘要（本地证据，未公开）记录 150 个 Kotlin/构建文件的 SHA-256，检查期间未变化。统计不包含全部资源/Java 文件，不能理解为逐行穷尽验证全部源码。
- 本轮完整 `:ai:testDebugUnitTest`：**506 项，0 失败，0 跳过**；其中现有用例 493 项、隔离审阅探针 13 项。测试摘要（本地证据，未公开）；合成探针（本地证据，未公开）。
- 探针通过表示复现了当前行为，不表示缺口已经修复。HTTP 场景由本地应用拦截器直接返回合成响应；不执行远程 DNS、供应商调用、收费生成或真实文件上传。既有取消测试另使用本机 loopback socket。
- 通过 AnySearch 读取官方文档，来源见文档快照（本地证据，未公开）和 Anthropic 原始块补充资料（本地证据，未公开）。只读取官方 URL，没有使用模型训练记忆推定新接口参数。
- 参考上游固定提交 `64452581dc15ac7f017d6fc1ee67e1861af4bfdf` 的 Provider 接口，作为结构对照；本轮未检查上游当前最新提交，不能把该快照称为最新上游。

## 2. 值得保留的现有实现

| 领域 | 已具备的基础 | 后续承接方式 |
| --- | --- | --- |
| 协议抽象 | 无状态 Provider、统一 UIMessage/StreamChunk，Chat/Responses/Google/Claude 独立 decoder | 继续保持协议转换与应用任务生命周期分离，不整体换 SDK |
| 历史回放 | 服务端工具原始块、调用 ID、OpenAI encrypted_content、Google thoughtSignature、Claude signature | 补齐缺失块，避免只保留可读正文 |
| 模型匹配 | API ID 与显示名称分离；名称用于能力/参数识别，请求保留实际 modelId | 增加能力来源及显式覆盖状态，保留用户选择 |
| 参数适配 | MiniMax、GLM、Kimi、DeepSeek、Qwen、Grok 和 Gemini 家族规则；最终请求清理不兼容字段 | 集中验证最终请求计划，减少散落分支 |
| 流结束与取消 | 明确提前 EOF、HTTP 200 流内错误、Finish；主要非流式正文读取已有取消测试 | 覆盖列表、认证、文件上传及诊断全链路 |
| 图像输出 | OpenAI 大 JSON 图片流式解码到临时文件，有大小、深度、清理约束 | 扩展到输入文件及聊天多模态路径 |
| Gemini 工具 | 搜索/URL context、Google server tool 原始部件、混合工具上下文循环 | 保留官方字段和 signature，新增工具前先补 parser 与回放 |
| 重试 | Retry-After、抖动、配额/认证区分、流式重放边界 | 结合任务类型决定能否重试，不能让通用 IOException 决定付费任务重发 |
| 决策与交互 | decision-model-preview 独立结构化评分；ask_user 答案验证、取消/拒绝语义 | 不将评分模型包装成聊天文本模型，不把用户回答当作执行授权 |
| 测试 | 协议夹具、请求 JSON、消息合并、重试、图片解析和取消回归 | 在现有测试上补契约矩阵和真机资源测试 |

上游 Provider 仍提供相似的基础协议接口。本地增加了媒体约束、视频任务、决策、参数家族和诊断；这些特性已有本地契约，不能通过直接覆盖上游文件来保留。

## 3. 优先修复的具体问题

### A1 · P1：OpenAI 拒绝回复没有完整表示

**证据：两个合成探针。**

- [ResponseAPI.parseResponseOutput](../ai/src/main/java/me/rerere/ai/provider/providers/openai/ResponseAPI.kt#L653)遇到合法 `content.type=refusal` 抛出 `unknown part type refusal`。
- [ResponseApiStreamDecoder](../ai/src/main/java/me/rerere/ai/provider/providers/openai/ResponseApiStreamDecoder.kt#L24)没有处理 `response.refusal.delta/done`。合成流收到 refusal 再 completed 后，消息已完成但没有可见文本。
- Chat Completions 的 message/delta 解析也只读取 content/reasoning，没有独立 refusal 字段映射；这一点是静态确认，本轮探针重点验证 Responses。

官方 Structured Outputs 文档明确要求处理 refusal、incomplete 和 API errors，拒绝并不保证符合输出 schema。

**改进：**增加可持久化的拒绝/受限结果表示，UI 显示供应商允许返回的说明；与网络错误、正常空输出、截断分离。同步覆盖流式/非流式、结构化输出及历史回放，不将拒绝触发为自动重发。

### A2 · P1：Claude redacted_thinking 原始内容丢失

**证据：合成探针 + 官方 SDK 类型定义。**

- [ClaudeProvider.kt](../ai/src/main/java/me/rerere/ai/provider/providers/claude/ClaudeProvider.kt#L935)在非流式响应中仅打印“收到 redacted thinking”，丢弃块。
- [ClaudeStreamDecoder.kt](../ai/src/main/java/me/rerere/ai/provider/providers/claude/ClaudeStreamDecoder.kt#L72)将此类块处理成普通空 Reasoning，未保存原始 `data`。
- 合成“redacted thinking + 可见文本”响应中，密文在两条路径均未保留；流式结果重新构造 Claude messages 后，变成 `type=thinking` 且没有 signature，原来的 `redacted_thinking` 不存在。
- 官方 `RedactedThinkingBlock` 类型定义明确说明 data 为不可读加密数据，多轮续接必须原样回传。

**改进：**单独保存协议块类型和 opaque data，不能转换为普通 thinking。UI 显示受控占位，正文不可见不代表协议内容可以删除。覆盖工具调用续轮、切换分支、备份恢复、流式和非流式回放。普通 signature 和该密文块分别处理。

### A3 · P2：Responses 搜索引用丢失

**证据：合成探针 + 官方 web search 文档。**

[ResponseAPI.kt](../ai/src/main/java/me/rerere/ai/provider/providers/openai/ResponseAPI.kt#L725)读取 output_text 的 text，没有投影其 `annotations`。合成含合法 url_citation 的响应得到正确正文，但 `UIMessage.annotations` 为空。流式 decoder 同样没有 annotation 事件分支。

官方要求搜索结果引用以显著可点击的链接展示。记录 web_search_call 本身不能替代最终答案的引用。

**改进：**统一流式/非流式 citation 解析，保留 URL、标题、文本范围和内容 part 身份，终态去重并与 UI 对齐；未知 annotation 类型应留诊断信息或安全保留，不能因供应商新增注解让整条回答失败。已有 Google groundingSegments 可复用概念，但不同协议的索引语义需要分别验证。

### A4 · P1：Gemini 续写累计计费用量被当作单次上下文输入量

**证据：两段合成请求 + 应用边界调用链。**

- [GoogleProvider](../ai/src/main/java/me/rerere/ai/provider/providers/google/GoogleProvider.kt#L588)和 [addGoogleHopUsage](../ai/src/main/java/me/rerere/ai/provider/providers/google/GeminiArgon.kt#L56)会累加续写各段 promptTokens；作为计费用量，这个累加符合官方 REST 说明。
- 探针中首段 prompt=100，第二段 prompt=500，最终返回 prompt=600、total=612。结果没有独立保存初始 prompt=100 的字段。
- [GenerationHandler.kt](../app/src/main/java/me/rerere/rikkahub/data/ai/GenerationHandler.kt#L881)把累计 promptTokens 写入 `measuredPromptTokens`；非流式路径也如此。
- [ChatContextUsage.kt](../app/src/main/java/me/rerere/rikkahub/ui/components/ai/ChatContextUsage.kt#L85)据此校准上下文分类，因而可能高估上下文占用。没有在本轮证明某次用户会话已经因此提前摘要或超限。

官方明确区分“各 hop 用量总和”和“首段 promptTokenCount”，前者不能直接用于上下文压缩/裁剪。

**改进：**分离 initialInputTokens、billedInputTokens、cachedInputTokens、visibleOutputTokens、thinkingTokens、totalBilledTokens、hopCount；让上下文估算与统计分别取正确字段。保留旧 TokenUsage 的兼容解码，不改变旧记录语义。续写检查点还应进入可恢复任务状态，不能只存在函数局部变量。

### A5 · P2：Google/Claude 模型发现只读第一页

**证据：两个合成探针 + 官方分页文档。**

- [GoogleProvider.listModels](../ai/src/main/java/me/rerere/ai/provider/providers/google/GoogleProvider.kt#L206)固定 pageSize=1000，没有消费 nextPageToken。
- [ClaudeProvider.listModels](../ai/src/main/java/me/rerere/ai/provider/providers/claude/ClaudeProvider.kt#L261)固定 limit=1000，没有消费 has_more/last_id/after_id。
- 带“还有下一页”标记的合成响应均只发出一次请求。设置大 page size 不等于供应商承诺全部返回；第三方兼容网关也可能设置更低上限。
- Google 对非成功 HTTP 返回 emptyList，诊断的 discovery 因此可能显示成功但模型数为 0；Claude 则抛错。两个协议的错误语义不一致。

**改进：**分页、ID 去重、重复游标检测、总页数/条数预算、取消；区分空列表、无权限、接口不支持和失败。模型发现优先提取官方能力/输入输出上限，静态注册表作为有来源的后备。

### A6 · P2：ProviderDiagnostics 吞取消异常，后续阶段仍可继续

**证据：合成 Provider 探针。**

[ProviderDiagnostics.kt](../ai/src/main/java/me/rerere/ai/provider/ProviderDiagnostics.kt#L408)的 timed 捕获 Throwable，streaming 又用 runCatching；没有重抛 CancellationException。探针让 listModels 抛取消，之后 health、stream、tool 三个阶段仍被调用。

这是模块取消契约问题；本轮没有调用真实收费模型，不能据此声称已发生额外扣费。实际已取消的 Job 也可能在下一次挂起处中止，但不能依赖它修补捕获取消的错误。

**改进：**所有阶段首先传播取消；每个阶段执行前检查 Job；设单阶段及总超时、输出预算。用户点击“关闭弹窗”与“取消测试”明确区分并绑定 Job。探测保留人工触发，并拆为模型列表/连通性/流式/工具等可选择等级；不支持工具不应自动变成整个供应商不可用。

### A7 · P2：MiniMax 视频输入尚未到达请求序列化层

**证据：官方 M3/M3.1 文档 + 合成请求。**

官方 Chat Completions 支持 image_url/video_url；MiniMax M3/M3.1 的视频还可设置 fps，并有请求/文件大小限制。当前 [ModelRegistry](../ai/src/main/java/me/rerere/ai/registry/ModelRegistry.kt#L558)为其声明 vision，但没有 video；[ChatCompletionsAPI.addNonAssistantMessage](../ai/src/main/java/me/rerere/ai/provider/providers/openai/ChatCompletionsAPI.kt#L904)只处理 Text/Image。

即使给模型手动添加 VIDEO，合成含文本和 Video 的请求仍只发送文本，video_url 和视频 URL 均消失。**这是“理解视频输入”的缺口，与已经实现的 Hailuo“生成视频”是两种能力。**

**改进：**补充官方 MiniMax 视频输入路径、fps/detail/图像长边等类型化参数、URL/base64/mm_file 引用及大小校验。不支持的协议应在发送前明确拒绝或提示，不能静默丢附件。随后按文档扩展音频与其他多模态；Modality 当前没有 AUDIO，需要先定义能力表示和迁移。

### A8 · P2：OpenAI-compatible embedding 缺少统一维度校验

**证据：合成响应。**

[OpenAIProvider.generateEmbedding](../ai/src/main/java/me/rerere/ai/provider/providers/openai/OpenAIProvider.kt#L336)检查数量、非空与有限数值，但不检查各向量维度一致。两个输入对应长度 2 和 1 的响应仍被接受；Google 路径已经检查一致维度。

**改进：**在 ai 边界统一检查数量、索引唯一性/完整性、统一维度、请求 dimensions 与结果关系，以及 NaN/Infinity；返回类型化的供应商协议错误。具体知识库/记忆调用方可能另有校验，本轮不把这个结果扩大为“坏向量已写入真实数据库”。

### A9 · P2：自定义认证头与内置认证头可能重复

**证据：合成请求。**

自定义 header 内部的“后者覆盖前者”已实现，但 [ResponseAPI.kt](../ai/src/main/java/me/rerere/ai/provider/providers/openai/ResponseAPI.kt#L109)在 `.headers(custom)` 后仍使用 `.addHeader("Authorization", ...)`。探针最终看到两个 Authorization 值；Chat/Claude/Google 对相关认证头也存在同类叠加写法。

**改进：**明确保留认证头的策略：默认由 credential resolver 唯一生成；若产品允许显式覆盖，则校验并按确定优先级 `.header` 替换。不能把它交给各家网关猜测选择。测试覆盖大小写不同、辅助网关认证头及模型/助手覆盖顺序。

### A10 · P2：运行时协议“回放通过”仅比较事件计数

**证据：合成 trace。**

[ProviderProtocolTraceReplayer](../ai/src/main/java/me/rerere/ai/provider/ProviderDiagnostics.kt#L359)对比 finish/text/reasoning/tool 事件数量及 finishedAt。将 TextDelta 从 EXPECTED 改成 WRONG，回放仍通过。

它目前是“标准化事件可合并”的健康检查，并不能证明原始 SSE decoder、工具参数、正文、签名和引用正确。已有 `StreamTraceReplayTest` 的部分离线夹具有更丰富的语义断言，应保留，不能说整个项目没有语义回归。

**改进：**界面明确检查范围；新增原始 SSE→decoder→消息→下一轮 wire 请求的端到端夹具。脱敏后的测试应校验文本哈希/长度、工具 ID/参数、签名存在性、引用、结束状态和使用量，不记录真实秘密；截断过的 trace 标明 incomplete，不能用于完整协议一致性认证。

## 4. 继续承接的已知问题与结构风险

这些项目已在前轮发现，本轮核对仍存在；不重复计为新发现。

| 项目 | 当前状态/证据 | 处理方式 |
| --- | --- | --- |
| 原生搜索路由与能力判断不一致 | `shouldUseResponsesApi` 可为官方 GPT-6 自动走 Responses，`supportsNativeWebSearch` 仍只看 useResponseApi | P1：先生成唯一请求计划，UI/提示词/工具声明和 wire 一起消费，第三方保留显式协议 |
| 媒体图片任务重试可能重复创建 | LingkeMediaImageAPI 的 taskId 仍只保存在 generate 函数中；本轮未重复 HTTP 复现实验，前轮已有复现工件 | P1：创建/查询/下载分离，持久化任务 ID，未知创建结果不能自动重发 |
| 正文绕过日志开关 | Responses/Chat 仍直接 Log/println 请求、响应或 SSE | P1：按[日志专项路线](logging-review-and-roadmap-20261115.md)统一处理 |
| 回调流使用 Channel.UNLIMITED | 四条文字协议路径均存在；修复了满队列 trySend 丢 delta，却缺总字节预算 | P1：从受控读取端实现背压，不得简单改小缓冲后继续 trySend 丢数据；区分可合并文本与不可丢协议事件 |
| 每个小 delta 复制累计内容 | StreamChunkHandler 做 text.text + delta，并复制 parts 和消息列表 | 先量化分配和吞吐，再使用请求内增量构建/批量发布；不可改变工具与签名顺序 |
| Provider 类职责过宽 | Google 1969 行、OpenAI 1698 行、Chat 1105 行、Claude 1061 行 | 随修复拆分 request builder/transport/decoder/media；避免只按文件大小机械搬迁 |

流式压力验收要覆盖慢消费者、16K/64K/长 decode、图片大快照、并行工具、断流与取消。协议计数完整、内存稳定、停止及时应同时满足；本轮没有重新测真机帧率或 OOM。

## 5. 需要完善的设计边界

### 5.1 模型能力应保存来源和显式覆盖

[ModelIdentity](../ai/src/main/java/me/rerere/ai/provider/ModelIdentity.kt)通过“当前值是否等于旧默认值”猜测是否手动修改。模型名可以帮助识别 family，但不能证明第三方网关支持相同的工具、参数和上下文上限；目前 supportsFunctionCalling/supportsReasoningCapability 还会重新依据注册表推断。

建议将 API modelId、用于匹配的名称、官方 metadata、静态默认、用户 override、探测结果与协议能力分开保存；为字段记录来源、确认时间和支持状态。明确关闭的能力不应因重命名又恢复；未知能力保留用户手动配置。上下文 capacity、最大输出、统一窗口、thinking 占用、不同协议限制分别表示，不用一个整数承担所有预算。

### 5.2 文件与凭据缓存应隔离身份并有生命周期

- Google uploadedFileCache 的 key 只有 baseUrl 和本地文件属性，没有 credential/project/provider scope；切换同 host 的不同项目/凭据可能复用不属于当前身份的 URI。当前 24 小时 TTL 只在命中时检查，Map 没有全局数量/容量淘汰，也没有远端删除/手动删除后的重新验证。官方文件会在 48 小时自动删除，但不能代替本地身份与失效管理。
- [ServiceAccountTokenProvider](../ai/src/main/java/me/rerere/ai/provider/providers/google/vertex/ServiceAccountTokenProvider.kt#L40)缓存按 email+scopes。探针确认同 email 更换成无效 PEM 后仍返回缓存 token。这不等于认证绕过：同账号 token 缓存本来可能仍有效；但“验证新凭据”应提供强制刷新，不能复用旧 token 作为新 key 有效的证据。还应防止并发请求同时刷新。
- Claude 模型列表和 Vertex token exchange 仍用阻塞 execute；Dispatchers.IO 不会自动在 Job 取消时 cancel 底层 Call，应统一复用可取消 HTTP helper。
- KeyRoulette 将 API key 本身作为 cacheDir JSON 的 map key；这是应用私有缓存，不是已确认的外部泄露，但轮询时间戳不需要复制原始凭据。可保存不可逆凭据标识，配套失效/冷却与配置删除清理。

上述缓存问题主要是静态风险和合成认证行为，本轮没有跨真实 Google 项目上传文件验证。

### 5.3 多模态输入预算与表示需要统一

FileEncoder 已有图片降采样、EXIF 修正和加权缓存，但音视频 `encodeToBase64Streaming` 最终仍返回整个 String；“流式读取文件”不等于“全程常量内存”。Lingke 编辑参考图也构造完整 base64 JSON，虽然已有文件上限，仍有较高瞬时分配。

此外，Image 的 data URI 分支无视 withPrefix=false，HTTP URL 也作为 EncodedImage 返回；Claude 等需要纯 base64 的路径不可直接复用该结果。这是静态确认，Android 编码分支本轮未做新的设备/Bitmap 测试。建议以 LocalFile/RemoteUrl/DataUri/UploadedFileRef 区分输入类型，让各协议明确选择内联/上传/URL，统一检查 MIME、大小、有效负载及取消。不能静默把编码失败替换为空文本或一段“编码错误”后继续生成。

### 5.4 工具 schema 和自定义参数要给出实际生效结果

当前 `InputSchema.Obj` 只有 properties/required，Google schema 转换会丢弃部分约束；部分 responseJsonSchema 解析失败后静默忽略。建议以协议支持表执行字段校验，输出“有效/被转换/不支持/无效”结果。自定义参数可以保留高级用途，但要在最终请求预览中说明与类型化设置的优先级，避免保存成功却没发送。

不要统一强制所有工具 strict=true。OpenAI 的 strict schema 有 required/additionalProperties 等约束，ask_user 又有条件可选字段；应分别选择严格模式、降级和本地业务校验，并保留当前 ask_user 的明确例外。

## 6. 适合新增的功能

| 优先级 | 功能 | 具体范围与前置条件 |
| --- | --- | --- |
| 高 | 统一 ResolvedRequestPlan / 请求预检 | 集中解析实际协议、端点、身份、能力、有效参数、工具、输入/输出预算与重试许可；支持无正文/凭据的有效配置预览，是后续 UI 与协议一致性的基础 |
| 高 | 完整结构化输出 | OpenAI Chat 的 response_format、Responses 的 text.format、Google 的 schema/MIME、Claude 的 output_config 统一为类型化选项；显式呈现拒绝/截断/校验失败，并说明协议差异。当前 OpenAI 通用 options 没有完整 schema 页，不能要求用户都手写 customBody |
| 高 | MiniMax M3 多模态输入 | 先完成 A7，再加入文档中的视频 fps、图像 detail/max_long_side_pixel 和 Files API 大视频引用；保持与 Hailuo 生成分开 |
| 高 | 模型发现增强 | 分页、能力 metadata、输出 token 上限、上下文窗口来源、最后刷新时间与手动 override；可按 endpoint/protocol 缓存，不把临时网络失败当成“模型被删除” |
| 中 | Gemini Interactions 独立适配器 | 官方现推荐此 API，支持 stateful/stateless、background、取消与长任务恢复；保留现有 generateContent 和兼容网关，不直接切换所有用户。需要 interaction ID/检查点、store 策略、远端取消/删除、分支与供应商切换规则 |
| 中 | 显式缓存与 token 预算工具 | 在模型/协议确实支持时加入 countTokens 和缓存管理；明确隐式/显式缓存、失效和计费，部分 Gemini 4 不接受旧显式缓存参数。上下文显示使用初始输入，费用统计使用累计用量 |
| 中 | 可恢复的统一媒体任务 | 图片/视频/长 decode 共用可恢复任务状态与关联 ID，但分别定义创建、轮询、取消、下载及幂等语义；将未知创建结果作为明确状态 |
| 中 | 精确错误模型与请求阶段 | 区分 auth/quota/rate limit/overload/unsupported parameter/refusal/safety/canceled/interrupted；保存 provider error code、request ID、retry-after。减少仅从错误字符串猜测重试 |
| 中 | 高质量协议回放与参数矩阵 | 官方/第三方 × 名称别名 × 协议 × 工具 × 媒体 × stream/nonstream；对照最终 wire 与结果回放，回归兼容供应商 |
| 后续 | 更多 Gemini 内置工具或实时会话 | code execution、file search、Maps、Live 等先评估用户价值；必须同时实现结果类型、权限/成本提示、回放和资源预算，不能只往 tools JSON 添加声明 |

Gemini 官方文档说明 Interactions 默认会保存交互，`store=false` 可选，但与 background 不兼容。新增时要让用户理解数据留存与长任务恢复的选择，不能暗中改变当前本地会话的数据边界。长 decode 在现有 generateContent 已有实现，Interactions 是补充传输与任务能力，不是“首次增加 Gemini 4 支持”。

decision-model-preview 建议继续作为独立评分器扩展：配置可解释的评分维度、并列分数策略、结果缓存键、超时后回退与合成评价集；不要因为其名为“决策”就让它自动执行工具或替代用户批准。

## 7. 推荐实施顺序

1. **先修协议结果和边界**：A1/A2/A3/A4/A6/A9，加上已知日志旁路。验收覆盖拒绝、引用、opaque 块、取消、首段/累计用量、唯一认证头。
2. **统一请求计划并补发现/输入契约**：搜索路由一致性、A5/A7/A8、能力来源、最终参数预检。附件不被静默丢弃；未知供应商仍可显式配置。
3. **资源与恢复**：有背压的流式读取、输入文件预算、缓存身份隔离、图像任务 ID 持久化与继续查询。再用 release-like 真机压力数据验证延迟/内存。
4. **增加完整 schema 和 Interactions**：有明确请求计划、结果类型和任务状态后再增加，分别提供保留旧协议的开关和回归矩阵。

建议下一批先完成第 1 步。它范围清晰，已有可转为正式回归用例的合成证据，也能改善“请求成功但内容不可见/回放失败/上下文显示异常”的实际体验。

## 8. 验证结果和限制

最终构建日志：`build/reports/qa-build/20261115-232118/attempt-1.log`。506 项通过，其中 13 个审阅探针分别验证：非流式拒绝解析失败、流式拒绝丢内容、引用丢失、redacted thinking 丢失与错误回放、Google 分页遗漏、Claude 分页遗漏、诊断吞取消、回放未校验正文、Gemini 续写累计输入、Vertex 旧 token 复用、embedding 维度不一致、MiniMax 视频遗漏、重复认证头。

第一轮审阅夹具只有 redacted block、没有可上传的可见内容，被现有 `isValidToUpload` 过滤，导致额外的“unsigned thinking 回放”断言未成立。补充真实可见文本事件后重跑全部 ai 测试通过；原始密文丢失的观察未改变。失败与修正都只涉及隔离探针，没有修改生产代码来迎合断言。

本轮没有进行新的真机性能测试、真实第三方网关计费验证、Google 项目间 Files 访问、云端 token 吊销或 Release 日志剥离验证。通过 JVM 不能替代这些环境结果。专用探针只在显式传入本轮 init script 时编译，普通构建不会加入它们。

## 9. 参考文档

- [OpenAI：Streaming](https://developers.openai.com/api/docs/guides/streaming-responses)、[Structured Outputs](https://developers.openai.com/api/docs/guides/structured-outputs)、[Function calling](https://developers.openai.com/api/docs/guides/function-calling)、[Web search](https://developers.openai.com/api/docs/guides/tools-web-search)。
- [Google：generateContent 长 decode](https://aistudio.google.com/docs/long-decode.md?generate-content=true)、[Interactions](https://aistudio.google.com/docs/interactions-overview.md)、[Thought signatures](https://aistudio.google.com/docs/thought-signatures.md?generate-content=true)、[混合工具](https://aistudio.google.com/docs/tool-combination.md?generate-content=true)、[模型分页](https://aistudio.google.com/api-reference/models/list-models.md?api-version=v1)、[Files](https://aistudio.google.com/docs/files.md?generate-content=true)。
- [Anthropic：Streaming](https://platform.claude.com/docs/en/build-with-claude/streaming)、[模型分页与能力字段](https://platform.claude.com/docs/en/api/models/list)、[官方 SDK 的 RedactedThinkingBlock](https://raw.githubusercontent.com/anthropics/anthropic-sdk-python/main/src/anthropic/types/redacted_thinking_block.py)。SDK main 是抓取当日材料，未固定到提交，不视为项目依赖版本。
- [MiniMax：OpenAI-compatible Chat](https://platform.minimax.io/docs/api-reference/text-openai-api)。
- [上游固定提交的 Provider 接口](https://raw.githubusercontent.com/rikkahub/rikkahub/64452581dc15ac7f017d6fc1ee67e1861af4bfdf/ai/src/main/java/me/rerere/ai/provider/Provider.kt)。

部分长文提取有 50,000 字符上限，文档快照保留实际获取范围。本报告只引用已读取的具体契约；未对所有模型的全部官方参数作穷尽认证。
