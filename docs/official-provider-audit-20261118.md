# 官方供应商协议核查与补齐（2026-11-18）

范围：Android 的 `ai` 请求编解码、模型能力识别、供应商模板，以及助手参数页。核对 DeepSeek、MiniMax、Kimi、MiMo、GLM、OpenAI、Gemini 和 Claude 的官方文档；使用 AnySearch、Tavily 交叉检索并读取原文。没有执行真实计费生成、设备安装或修改 desktop 项目。

## 核查结果与实际修改

| 供应商 | 请求与认证 | 参数、工具与 JSON | 多模态与解析 |
| --- | --- | --- | --- |
| DeepSeek | 维持官方 Chat / Responses / Messages 的独立适配与认证 | 官方 Chat 在自定义参数合并后再次规范思考模式、工具选择和 Top P；JSON Object 缺少指令时补充 JSON 输出要求 | 保留已有图像 detail、思考历史和专用参数映射；回归已有 DeepSeek 参数样本 |
| MiniMax | 保留官方 OpenAI Chat 与 Anthropic Messages 两条协议 | M3/M3.1 移除原生接口未声明的 OpenAI 参数；Messages 的工具仅 auto，none 通过移除工具实现；M3.1 保持 adaptive 和 effort，不发送 disabled；输出上限 524288 | Chat 使用 video_url；默认模板采用的 Messages 现在支持 type=video 的 URL/Base64；图像 URL 使用 source.url |
| Kimi | 保留 Chat / Responses / Messages；Responses 仅开放已声明的 K3 系列 | Responses 去掉 store、parallel_tool_calls、采样参数、reasoning.summary 和 OpenAI encrypted include；保留 low/high/max；上限 1048576；Responses 结构化输出仅 JSON Schema；Chat 的 JSON Schema 仅为 K3 开放 | 沿用已有多模态、工具和思考历史编解码；本轮没有加入 Kimi 文件上传或独立缓存 TTL 控件 |
| MiMo | 官方端点使用单一 api-key，清除冲突的 Authorization/x-api-key；空供应商密钥保留用户自定义认证；原本 Bearer 也是官方 SDK 支持的方式 | V2.6 支持开/关思考、JSON Object；思考开启时不传用户采样值；Chat 使用 max_completion_tokens，上限 131072；工具仅 auto，none 移除工具；Responses 不带 OpenAI 思考摘要/密文字段 | 补入 V2.6 能力识别和三款默认模型，沿用原供应商 UUID；Chat 支持 video_url、input_audio.data，强制保留官方要求的多轮思考历史 |
| GLM | 国内默认 Chat 地址切到 Responses 时使用 /api/v1/responses；国际端保留其配置地址 | Responses 按国内文档映射 effort：none/minimal 关闭、low/medium/high→high、xhigh/max→max；上限 131072；工具 auto/none；输出 text/json_object；不发送 OpenAI summary/encrypted include | 接受 Responses message.content 为对象或数组；usage 缺 total_tokens 时由输入+输出计算 |
| OpenAI | 区域 *.api.openai.com 识别为原生端点，保留区域地址和 Responses 自动路由；替换重复 Bearer 头 | 新增助手输出格式/JSON Schema；严格输出关闭对象额外键，可选字段允许 null；函数显式 strict:false，避免可选工具参数被自动严格化；Ultrafast 限默认/US 端点 | 非流式和流式拒答均作为可见文本显示；回归 Responses 工具、思考、缓存和流式解析 |
| Gemini | x-goog-api-key 单值覆盖，Vertex key 不重复追加；空密钥保留自定义认证 | GenerateContent 函数使用 parametersJsonSchema，保留数字枚举、const、nullable；输出 Schema 明确校验；TEXT 模式忽略历史遗留 Schema；沿用独立 Interactions API(Beta) | GenerateContent 远程图片后台下载后发送纯 Base64；请求内去重、合计 20 MiB 限制、关闭响应、不携带供应商密钥或助手自定义头；下载失败明确报错；Interactions 保留官方 URI 形式 |
| Claude | 保留可配置的 anthropic-version，认证头使用单值 | output_config.format 的对象关闭 additionalProperties，同时保留可选字段语义；无效 Schema 明确报错；原生思考 enabled/adaptive 时将强制 any/tool 改为 auto | HTTP 图片使用 source.type=url；本地图片读取失败不再静默发送空文本；保留 thinking signature、server tools、pause_turn 等现有行为 |

共用修正：无参数工具发送 `{ "type": "object", "properties": {}, "required": [] }`，不再发送 JSON null。Schema 转换只遍历 Schema 节点，不误处理名为 `items`、`required` 的业务属性；nullable object 也会关闭额外键。助手设置增加官方供应商标签，并禁用当前接口不支持的服务等级、并行工具或强制工具选项。新增控件使用现有 MaterialTheme / CustomColors。

官方主机判断用于限定供应商特定行为，避免将这些差异强制套用到所有第三方中继。通用有效 Schema、认证去重等修正仍适用于共享协议。不会自动替换用户配置的退役模型 ID；MiMo V2.5 的退役信息只作为模板和参数页提示。

## 回归与构建

最终结果由 `build/reports/official-provider-audit-20261118/verification.json` 和对应的 `build-*.log` 记录；`build.log` 为最后一次验证命令。验证包括完整 AI JVM 套件、助手参数路由与供应商设置相关测试、HTTP 取消回归，以及 QA APK 编译。请求通过本地 OkHttp 拦截器回放，不访问模型端点。远程图片读取复用共用的 `awaitAndUse`，取消请求时也取消正在读取的 HTTP 响应。

新增重点样本：八家相应协议的认证与工具 Schema、MiMo 开关思考/音视频/历史思考、Kimi Responses 字段限制、GLM 国内路由与 effort/上限、MiniMax Messages 视频、Google 数字枚举与远程图片、严格 JSON Schema、Responses 拒答与缺失用量字段。

最终结果：AI 549 项、应用 18 项、HTTP 取消 2 项，共 569 项通过，失败/错误/跳过均为 0。`assembleQa` 成功；QA 版本 `2.4.8-revised.9-20261118-165259`，versionCode `185`。ARM64 包为 `app/build/outputs/apk/qa/app-arm64-v8a-qa.apk`，SHA-256 为 `99f9b9476bb867d753abe7333e7358513887ee4213979efa5450d96b8c6bf499`；另有通用与 x86_64 包，完整摘要见 verification.json。

这些结果验证本地请求/响应合同和编译，不代表真实账户权限、供应商执行、图片实际识别效果或真机外观已经验证。现有数据结构尚无独立 AUDIO 模态枚举，MiMo 音频能力通过 Chat 附件传参支持。MiMo Responses 的音视频以及其它供应商未在本轮新增的 Files/Batch 等 API 不在此次补齐范围。Google 的自动下载修正针对 GenerateContent 图片，不扩展为通用远程音视频下载服务。

## 官方依据

- [DeepSeek Chat](https://api-docs.deepseek.com/api/create-chat-completion/)
- [MiniMax Chat](https://platform.minimax.io/docs/api-reference/text-chat-openai.md) / [Anthropic 兼容](https://platform.minimax.io/docs/api-reference/text-anthropic-api.md)
- [Kimi K3 快速开始](https://platform.kimi.ai/docs/guide/kimi-k3-quickstart) / [Responses](https://platform.kimi.ai/docs/api/responses.md) / [Messages](https://platform.kimi.ai/docs/api/messages.md)
- [MiMo Chat](https://mimo.mi.com/docs/en-US/api/chat/openai-api-post) / [Responses](https://mimo.mi.com/docs/en-US/api/chat/responses-post) / [Anthropic](https://mimo.mi.com/docs/en-US/api/chat/anthropic-api-post)
- [MiMo 结构化输出](https://mimo.mi.com/static/docs/quick-start/usage-guide/text-generation/structured-output.md) / [视频](https://mimo.mi.com/static/docs/quick-start/usage-guide/multimodal-understanding/video-understanding.md) / [音频](https://mimo.mi.com/static/docs/quick-start/usage-guide/multimodal-understanding/audio-understanding.md)
- [GLM Responses 指南](https://docs.bigmodel.cn/cn/guide/develop/responses/introduction) / [创建 Response](https://docs.bigmodel.cn/api-reference/response/创建-response) / [JSON mode](https://docs.bigmodel.cn/cn/guide/capabilities/struct-output)
- [OpenAI Function calling](https://developers.openai.com/api/docs/guides/function-calling) / [Structured outputs](https://developers.openai.com/api/docs/guides/structured-outputs) / [区域端点](https://developers.openai.com/api/docs/guides/data-residency)
- [Gemini REST Schema](https://ai.google.dev/api/caching) / [GenerateContent 图片](https://ai.google.dev/gemini-api/docs/image-understanding.md.txt?generate-content=true)
- [Claude 结构化输出](https://platform.claude.com/docs/en/build-with-claude/structured-outputs) / [工具定义](https://platform.claude.com/docs/en/agents-and-tools/tool-use/define-tools)
