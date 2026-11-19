# 模型与官方协议适配（2026-11-18）

本轮通过 AnySearch 与 Tavily 交叉检索官方资料，修改 Android 的 AI 请求层和供应商/助手参数页面。已有第三方网关配置保留各自选择的协议；不改动 desktop、desktopApp。

## 新模型

| 模型 | 本轮适配 |
| --- | --- |
| GPT-6.2 Astra、GPT-6.2 Sol | 文本/图片输入、工具/推理能力识别，1,050,000 上下文；low/medium/high/xhigh/max 思考档位，默认跟随官方 medium；官方端自动使用 Responses；支持 reasoning context/mode、Ultrafast、30m/12h 前缀缓存选项。官方端输出上限 128,000，过滤不支持的采样及概率参数。 |
| Qwen4-Max 正式版 | 文本/图片/视频输入、工具与思考能力，1,000,000 上下文；思考开关及 low/high/xhigh 档位；JSON Schema、流式工具、保留历史思考；联网搜索、频率惩罚、图片 detail 与 max_pixels。Chat Completions 输出上限按模式限制为 262,144/131,072。 |

识别同时使用模型 ID 与用户填写的显示名称，包括“千问4 Max”，不会把显示名称替换为请求中的模型 ID。Qwen4-Max Preview 与正式版参数不同，不套用本轮正式版规则。供应商可用性、地域和套餐限制由供应商决定，例如 Ultrafast 仅支持美国数据驻留。

GPT-6.2 的稳定系统指令放在 Responses input 的首个 developer 消息，而非会被追加到输入末尾的 instructions，保留历史 reasoning 项顺序。旧 prompt_cache_retention 在 GPT-6.2 原生请求中迁移到 prompt_cache_options.ttl。缓存写入价格随有效期不同，界面提供计费提示。

Qwen 的 Chat Completions max_pixels 位于 image_url 同级，优先于 detail；上限为 16,777,216。reasoning_effort 和 thinking_budget 避免同时发送。百炼 Responses 暂不接受视频/音频，输入此类附件时明确提示切换 Chat Completions。

## Interactions API(Beta)

入口：Google 协议的供应商配置页。默认关闭，开启后聊天请求使用 `/v1beta/interactions`，携带 `Api-Revision: 2026-05-20` 与原有 API Key、用户请求头及请求诊断。Google 当前文档已将该接口称为 GA；应用按用户要求保留实验性标签。

实现独立的请求/响应/SSE 编解码，不把 GenerateContent 的 parts 与 thoughtSignature 直接改名。支持：

- 文本与原有媒体预处理/上传；system_instruction、generation_config、JSON Schema、安全设置。
- 函数调用与结果，包括并行调用、分段 arguments、同一 UI 消息内工具结果到后续正文的顺序。
- Google Search、URL Context、代码执行；搜索建议及引用显示沿用现有消息注解结构。
- thought 与服务端工具步骤的原始签名回放，即使隐藏思考摘要仍保留签名。
- 逐段文本、思考与图片输出，Token 用量、错误、提前 EOF、流式取消及长输出 continuation。

使用 `store=false`、`background=false` 和本地完整历史管理编辑、分支与重新生成。续写重发完整历史及已生成步骤，原样传递 continuation_token；防止重复 checkpoint 无限循环。用户编辑模型正文后，新的正文参与历史回放。

GenerateContent 仍为默认，图像生成与编辑继续走既有接口。Vertex AI 暂不开放此实验入口；切到 Vertex 时自动关闭开关，导入互相矛盾的配置也会在发送前得到明确错误。

Interactions 暂不提供 GenerateContent 的搜索时间范围、text/x.enum、topK、重复惩罚参数与远程 Agent/后台任务配置。参数页说明差异；搜索时间范围及 enum 等无法转换的设置会明确报错，不静默丢弃。此入口并非完整的远程 Agent、File Search、MCP 或音视频生成产品界面。

## 其他官方协议补充

| 供应商 | 检查与改动 |
| --- | --- |
| Google | Gemini 2.5 补发原先被跳过的 seed、stop、结构化输出、惩罚、安全阈值和 includeThoughts；移除旧 CIVIC_INTEGRITY 类别；补充代码执行、函数调用模式以及适用旧模型的 topK 控件。混合工具继续遵守模型代际限制。 |
| OpenAI | 参数页按模型实际自动路由显示 Responses 选项；新版前缀缓存有效期；官方新模型参数归一化在自定义请求体合并后执行。 |
| Anthropic | Messages 的 API 日期版本不再完全硬编码：官方端可配置 anthropic-version，旧配置仍为 2023-06-01；只发送一个版本头。第三方 Claude 兼容端维持原版本。 |
| MiniMax、Kimi | 官方 OpenAI 兼容端的新模型改用文档推荐的 max_completion_tokens；保留现有思考、工具、流式处理及协议差异。 |
| 智谱、DeepSeek | 对照当前官方文档核对已有 thinking、reasoning_effort、清理/保留思考、工具与 Token 限制。本轮沿用已有对应实现。 |

新增设置沿用应用的 MaterialTheme、CustomColors 和现有表单组件，避免硬编码文字与卡片颜色。配置字段带默认值，旧配置可直接读取。本轮没有自动升级所有厂商的全部 API 版本，也不声称覆盖各家异步任务、批处理、远程文件管理等全部产品接口。

## 官方资料

新模型在两套搜索服务中交叉检索；检索摘要与缓存存在版本差异时，以官方模型页和对应接口页的实际参数为准。

- [OpenAI GPT-6.2 使用指南](https://developers.openai.com/api/docs/guides/latest-model)
- [GPT-6.2 Astra](https://developers.openai.com/api/docs/models/gpt-6.2-astra.md)、[GPT-6.2 Sol](https://developers.openai.com/api/docs/models/gpt-6.2-sol.md)
- [OpenAI 前缀缓存](https://developers.openai.com/api/docs/guides/prompt-caching)
- [Qwen4-Max](https://help.aliyun.com/zh/model-studio/qwen4-max)
- [百炼 Chat Completions](https://help.aliyun.com/zh/model-studio/qwen-api-via-openai-chat-completions)、[深度思考](https://help.aliyun.com/zh/model-studio/deep-thinking)
- [百炼 Responses](https://help.aliyun.com/zh/model-studio/compatibility-with-openai-responses-api)
- [Gemini Interactions](https://ai.google.dev/gemini-api/docs/interactions.md.txt)、[OpenAPI 定义](https://ai.google.dev/static/api/interactions.openapi.json)
- [Interactions 流式](https://ai.google.dev/gemini-api/docs/interactions/streaming.md.txt)、[函数调用](https://ai.google.dev/gemini-api/docs/interactions/function-calling.md.txt)、[思考与签名](https://ai.google.dev/gemini-api/docs/interactions/thinking.md.txt)
- [Google Search](https://ai.google.dev/gemini-api/docs/interactions/google-search.md.txt)、[URL Context](https://ai.google.dev/gemini-api/docs/interactions/url-context.md.txt)、[长输出](https://ai.google.dev/gemini-api/docs/long-decode.md.txt)
- [MiniMax Chat Completions](https://platform.minimax.io/docs/api-reference/text-chat-openai)
- [GLM-5.3](https://docs.bigmodel.cn/cn/guide/models/text/glm-5.3)
- [Kimi K3](https://platform.kimi.ai/docs/guide/kimi-k3-quickstart)
- [Anthropic Messages](https://docs.anthropic.com/claude/reference/complete_post)
- [DeepSeek Chat Completions](https://api-docs.deepseek.com/api/create-chat-completion/)

## 验证

使用已有本地 Gradle/JDK 缓存离线编译，复用嵌入式 Web UI 产物。本地协议样本覆盖模型/名称识别、参数限制、历史与签名、工具顺序、图片回放、超过 16K 的分段文本、错误/提前 EOF、HTTP 路由、版本头、续写和取消连接。测试使用本机临时 HTTP/SSE 服务，没有发送真实供应商的计费请求。

最终测试数量、构建结果与安装包摘要记录在 `build/reports/model-protocol-adaptation-20261118/`，不把本地回放当作真实供应商或真机验证。

最终结果：AI 模块 227 项、参数页及配置相关 16 项，共 243 项测试通过，失败/错误/跳过均为 0。Debug 源码与 QA 安装包编译成功，改动文件的差异空白检查通过（按 Windows CRLF 行尾检查）。

- QA：`2.4.8-revised.9-20261118-144825`，versionCode `185`，包名 `me.rerere.rikkahub`。
- 安装包：`app/build/outputs/apk/qa/app-universal-qa.apk`，107,057,842 字节。
- SHA-256：`80276f39a47695bfadc95b48a8a46c14693b6f2565bdc517199fa84b493d9d0e`。
- 完整验证数据：`build/reports/model-protocol-adaptation-20261118/verification.json`；构建日志：同目录 `build.log`。
