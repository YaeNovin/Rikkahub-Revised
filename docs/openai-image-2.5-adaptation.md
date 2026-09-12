# GPT Image 2.5 参数适配

本次参数以 OpenAI Docs 的以下官方模型页为准：

- [GPT-Image-2.5 Flare](https://developers.openai.com/api/docs/models/gpt-image-2.5-flare.md)
- [GPT-Image-2.5 Sunburst](https://developers.openai.com/api/docs/models/gpt-image-2.5-sunburst.md)
- [Create image](https://developers.openai.com/api/reference/resources/images/methods/generate)

官方模型页确认 Flare 与 Sunburst 使用 Image API `v1/images/generations`，支持文本/图片输入、图片编辑，以及 `low`、`medium`、`high`、`xhigh`、`max`、`auto` 六档质量。项目现在为两个 ID 及其日期快照识别这六档质量，并复用官方指南列出的 1K、2K、4K 标准尺寸预设。

生成请求沿用官方 Images API 的通用字段：`model`、`prompt`、`n`、`size`、`quality`、`background`、`output_format`、`output_compression` 和 `moderation`；不添加官方页面没有确认的自定义步数、种子、guidance 或采样器。官方指南同时确认 GPT Image 2.5 自定义尺寸要求宽高为 16 的倍数、长宽比在 1:3 到 3:1、单边不超过 3840 像素且总像素不超过 8,294,400，项目按此开放自定义尺寸。输入图片和编辑入口保留。

`background` 的 API 文档值为 `auto`、`transparent`、`opaque`。透明背景与 JPEG 的组合仍由请求转换层按格式过滤。`output_compression` 仅在接口和输出格式允许时发送，范围使用 0–100。标准预设覆盖 1:1、4:3、3:4、3:2、2:3、16:10、10:16、16:9、9:16、7:4、4:7、5:4、4:5、2:1、1:2、21:9、9:21、3:1、1:3 等比例及 1K–4K 分辨率；自定义尺寸遵守 16 倍数、1:3–3:1、3840 单边和 8,294,400 总像素限制。

官方模型页标明 Chat Completions、Responses、Videos 等端点不支持，只有 Image generation 与 Image edit 支持；因此模型不会被错误注册为聊天或视频模型。

检索日期：2026-09-09。官方文档正文可由 `.md` 页面获取；第三方搜索摘要未用于推断专属参数。
