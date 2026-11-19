# 消息提交、停止生成与原生搜索

2026-11-12，Android QA 开发记录。

## 消息与停止

原先 `sendMessage` 将初始化、等待上一轮任务、输入正则处理、添加用户消息和生成回答放在同一个可取消 Job 内。在添加消息之前点击停止，会直接取消用户消息的提交。停止入口还会等待旧任务收尾，再额外保存一份整个会话；界面则直到 Job 完成才退出回答状态。

现在 `ConversationSession.launchAcceptedTurn` 为已受理的输入安排独立提交任务，并按受理顺序串行化。用户消息同步进入待提交列表，聊天界面将它与持久化状态合并，等待旧任务收尾或磁盘保存时也能显示；稳定消息 ID 避免提交前后出现重复行。提交在应用作用域中执行，先发布并保存原始用户消息，再由可取消的生成任务进行预处理和调用模型。停止回答不会取消输入提交。新一轮提交等待旧任务最终保存完成，避免流式收尾覆盖新消息。自动保存读取持久化状态，避免把尚未排到的待提交消息提前写入旧任务快照。

`cancelGeneration` 同步发布停止状态，同时保留旧任务完成屏障，后续发送仍等待真实收尾。ChatVM 直接发起取消，输入框优先处理停止操作；停止入口不再在等待结束后写入可能过时的全会话快照。生成准备工作移到后台，取消不进入普通错误报告路径。

待确认工具在取消任务自己的保存屏障内收尾；追加新用户消息之前也会清理上一条中断工具，避免把新用户消息误当成待处理的旧消息。自定义搜索保留原有 2 MiB 有界读取限制，并在该读取期间绑定取消。

非流式 OpenAI Chat Completions、Responses、Google 和原生文本响应在读取正文期间也绑定 OkHttp 取消。旧搜索请求统一在可取消作用域中读取完整 JSON；原先同步 `execute()` 的智谱、博查、Exa 也接入该路径。

## 豆包／火山引擎

通过 Tavily 检索并读取官方参考：

- [Custom API](https://www.volcengine.com/docs/87772/2272953?lang=zh)
- [Global API](https://www.volcengine.com/docs/87772/2548026)
- [功能发布记录](https://www.volcengine.com/docs/87772/2272950)

搜索供应商列表新增“豆包 / 火山引擎”，使用搜索控制台 API Key 的 Bearer 鉴权。这里接入独立搜索服务：

| 模式 | POST 路径 | 能力 |
| --- | --- | --- |
| Custom | `https://open.feedcoopapi.com/search_api/web_search` | 网页和文搜图；站点、屏蔽站点、正文、URL、权威度、ICP备案、时效、行业、词改写、正文格式及图片筛选 |
| Global | `https://open.feedcoopapi.com/search_api/global_search` | 网页、文搜图、图搜图；摘要长度、图片数量、队列，以及短边／高宽比筛选 |

按版本发送 `Count` 或 `DocCount`，按搜索类型限制数量；Global 最大 20，Custom 网页最大 50、图片最大 5。参数保存在服务配置中，图搜图的工具参数包含图片 URL 和可选归一化检索区域，设置页测试入口也能填写图片 URL。底层同时支持纯 Base64 图片，但不会把 Base64 字符串放入模型的常规工具参数定义。

响应分别解析 Custom 的 `WebResults` / `ImageResults`、Global 的 `Documents` / `Snippet`。保留正文、摘要和图片；没有 URL 的火山如意文字结果仍可返回。HTTP 200 中的 `ResponseMetadata.Error` 与 `Result.ErrorCode` 会报告失败及 RequestId，避免将业务错误显示为空结果。

## Gemini

通过 Tavily 检索并读取：

- [generateContent API 参考](https://ai.google.dev/api/generate-content?hl=en)
- [Grounding with Google Search](https://ai.google.dev/gemini-api/docs/google-search)
- [URL Context](https://ai.google.dev/gemini-api/docs/url-context)
- [图像生成中的 Google Image Search](https://ai.google.dev/gemini-api/docs/image-generation)

助手的 Gemini 参数页新增原生搜索区域：

- Google 搜索和 URL 上下文分别支持跟随模型、开启、关闭。
- 网页搜索、图片搜索类型，以及成对 RFC 3339 搜索时间范围。
- 使用 `googleSearch.searchTypes.webSearch/imageSearch`、`timeRangeFilter.startTime/endTime` 和空对象 `urlContext`，与当前 Kotlin HTTP 使用的 generateContent 协议一致。
- Gemini 3 及以上保留原有内置工具与函数调用组合协议。Gemini 2 不支持这种混用；助手明确开启内置工具时优先使用内置工具，未明确选择时沿用原有函数优先策略。

官方将图片搜索增强描述为检索网页图片，作为图像生成的视觉参考，并列出 Gemini 3.1 Flash Image。当前按这一已确认范围开放图片搜索。用户仍可提交图片和文字，但这里没有虚构 Google Lens 或专用反向图片检索 API。豆包 Global 的 `SearchType=visual` 则是官方明确提供的图搜图接口。

URL Context 工具本身没有额外请求字段；完整网址随原有消息内容传入。补齐网页／图片来源、图片地址、搜索建议，以及每个 URL 的成功、读取失败、付费墙、安全拦截状态。流式和非流式共用解析，包含没有 content 的流式元数据事件。Google 返回的搜索入口 HTML 使用无脚本、无本地文件权限的 WebView 展示。

## 验证

- JVM：`AcceptedTurnTest` 覆盖慢初始化、慢保存、停止时的旧任务收尾与连续 30 次已受理发送；`NonStreamingCancellationTest` 用本地 TCP 慢响应验证响应头之后的取消；`GoogleSearchToolsTest` 和 `VolcengineSearchServiceTest` 覆盖协议、序列化、元数据及业务错误。
- USB 真机：隔离 Debug 的 `ChatTurnRegressionActivity` 调用正式 ChatService、Room 与 HTTP 提供商，连接本地延迟接口。Python 入口为 `scripts/qa_chat_turns.py`。
- 独立接口的请求与响应夹具、真机本地接口验证，与真实供应商账号的在线调用分别记录；本轮不依赖付费调用验证。

最终验证：搜索模块 34 项、AI 模块 478 项、应用模块 1183 项，共 1695 项 JVM 测试通过，失败、错误和跳过均为 0。最终 Debug / QA 构建均成功，`git diff --check` 通过。

USB 真机 vivo V2301A / Android 16 完成 14 次发送与停止：8 次同一调用栈内立即停止，3 次等待收到非流式响应头并开始读取正文后停止，3 次等待流式内容开始后停止。每次仅点击一次停止，14 条用户消息均由 Room 重新读取核验，未丢失或重复。消息进入显示状态 0–2 ms，停止受理 0–2 ms，包含数据库核验的收尾为 20–70 ms。后 6 次确认连接本地接口；真实供应商账号没有进行在线调用验证。

搜索控件通过真机操作，并检查 Gemini 深色／浅色设置、Google 搜索入口 HTML，以及豆包 Custom / Global / visual 配置截图。结果：`build/reports/chat-turns/final-device-verified/results.json`，相同目录保留界面层级与截图。测试脚本先等待应用启动计数写回结束，再配置隔离应用的本地接口，避免启动期间的旧设置快照覆盖夹具配置。

已通过 USB 执行 `adb install -r`，覆盖安装 `me.rerere.rikkahub`，版本 `2.4.8-revised.9-20261112-021837`。设备首次安装时间仍为 `2026-10-17 22:49:10`，未卸载、未清除数据；QA 启动成功。测试后已关闭隔离 Debug 应用，并将手机自动锁屏时间从临时的 1800000 ms 恢复为原值 600000 ms。
