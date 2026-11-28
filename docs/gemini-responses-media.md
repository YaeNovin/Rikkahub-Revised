# Gemini 音视频附件：Responses 第一阶段

本轮针对 Android 聊天中的 Gemini 模型，为 OpenAI Responses 兼容端点接入原始音频、视频附件。Google 原生协议和 Interactions API 沿用现有路径，Chat Completions 的媒体格式不在本轮变更范围。

## 官方能力与协议边界

Gemini 原生支持音视频理解。应用不必先将音频转写为文本，也不必自行从视频抽帧；容器格式、大小和端点支持满足要求时，可以发送原始文件字节。Base64 是传输编码，不是媒体转码。音视频采样与理解由服务端完成。

Google 的 OpenAI 兼容文档当前主要介绍 Chat Completions，其中音频使用 `input_audio`。该文档不能证明 Google 提供 Gemini Responses 端点，也不能保证任意第三方供应商接受音视频。

本轮实现 OpenRouter 已公布的 Responses 扩展：

```json
{
  "model": "google/gemini-4-hydrogen",
  "input": [{
    "role": "user",
    "content": [
      {"type": "input_text", "text": "描述这段音视频"},
      {"type": "input_audio", "input_audio": {"data": "<原文件Base64>", "format": "wav"}},
      {"type": "input_video", "video_url": "data:video/mp4;base64,<原文件Base64>"}
    ]
  }]
}
```

其他网关可能要求 `input_file` 等格式。例如 Vercel 的 Responses 视频文档使用 `input_file`。这些差异需要单独适配，不能通过同一个“兼容”开关通用处理。

## 使用入口

供应商配置 → OpenAI → 启用 Responses API → **Gemini 音视频输入（Responses）**：

- **自动**：对 `openrouter.ai` 的 Gemini 聊天模型开放附件入口。
- **关闭**：隐藏该供应商的 Gemini Responses 音视频入口，并在发送已有附件时明确报错。
- **兼容扩展**：仅在中转端文档确认支持上述 `input_audio`、`input_video` 格式时启用。

该配置随供应商保存、备份和恢复。模型的独立供应商覆盖配置同样生效。能力识别沿用模型名称优先、ID 回退的机制；非 Gemini、图像生成、嵌入、TTS、Live 等专用模型不会因此获得 Responses 音视频能力。Google、OpenAI 等不提供该 Gemini 端点的官方地址不受兼容开关影响。

## 基础处理与限制

- UI 和请求构造共用能力判断，切换模型、协议、供应商覆盖配置后重新计算。
- 文件导入和编码在 IO 调度器执行，复制和编码期间响应协程取消。原文件保存在应用管理目录，读取时使用其真实字节，不进行转写、转码、抽帧或音视频内容缓存。
- 第一阶段仅接收本地文件或 Base64 data URL，不下载远程音视频，不将 Google Files URI 当作其他网关的文件 ID。
- 本轮设置应用侧保守预算：一次请求的原始音视频合计最多 **14 MiB**；Base64、文本、图片、自定义请求体等序列化后的完整请求最多 **20 MiB**。这是本阶段的内联传输限制，不能视为 Gemini 模型的通用上限。
- 复制前检查已知大小，复制中继续计数以覆盖文档提供者不报告大小的情况。导入失败回滚当前批次，不影响已有草稿。MIDI 继续按曲谱文档处理。
- 发送前校验格式、空文件、缺失文件、Base64、完整批次大小。附件不会因正文采用字符串简写而被丢弃；供应商不支持时明确报错，不跨协议自动重试。
- **OpenRouter Responses 的音频仅允许 WAV、MP3**，即使底层 Gemini 支持 FLAC 等格式也不能绕过接口限制。其他确认支持扩展的中转端可使用 MP3、WAV、AAC、OGG、FLAC、AIFF、M4A、Opus、WebM。视频支持 MP4、MPEG、MOV、AVI、FLV、WebM、WMV、3GPP。实际可用格式还取决于网关与具体模型，不支持的文件需由用户提供其他原文件。
- 沿用现有历史媒体策略：当前用户轮保留原始附件，较早轮次的音视频在请求中替换为占位说明；存储的会话不变。追问需要重新读取原文件时，应重新附加文件。大文件复用和历史文件引用不在本轮实现范围。
- 请求日志现有脱敏规则会省略 `data`、`video_url` 等二进制或 URI 内容；本轮不新增原始媒体日志。

## 后续阶段

按实际供应商接入文件上传、处理状态轮询、取消、过期与删除机制，再扩大文件预算；为 `input_file` 等不同 wire format 添加独立适配器。之后扩展其他协议和模型。需要转码时应根据具体格式限制显式提供操作，避免默认抽帧丢失视频音轨、时间顺序或细节。

## 回归验证

协议层回归入口：

```text
python scripts/build_qa.py :ai:testDebugUnitTest --tests "*ResponsesMediaTest" --tests "*ResponseApiRequestMessageTest" --tests "*OfficialProviderMediaTest"
python scripts/build_qa.py :app:compileDebugKotlin
```

测试覆盖模型与端点识别、模型供应商覆盖、旧配置兼容、混合消息顺序、原始文件字节、MIME 别名、音频格式限制、缺失和空文件、批次预算、完整 UTF-8 请求预算、编码取消，以及流式/非流式请求和鉴权的一致性。网络使用本地拦截器模拟，不向供应商提交文件或产生模型调用费用；供应商实际接收与理解媒体、系统文件选择器流程和真机表现需要另行联调。

2026-11-26 本地结果：以上三个测试类共 33 项测试通过，`:app:compileDebugKotlin` 通过，中英文新增资源 XML 校验和 `git diff --check` 通过。本轮未进行真实供应商调用或真机安装测试。

## 参考文档

核对日期：2026-11-26。

- [Google：音频理解](https://ai.google.dev/gemini-api/docs/audio)
- [Google：视频理解](https://ai.google.dev/gemini-api/docs/video-understanding)
- [Google：OpenAI 兼容](https://ai.google.dev/gemini-api/docs/openai)
- [OpenRouter：Responses 音频输入](https://openrouter.ai/docs/guides/overview/multimodal/audio)
- [OpenRouter：Responses 视频输入](https://openrouter.ai/docs/guides/overview/multimodal/videos)
- [Vercel：视频输入的协议差异](https://vercel.com/docs/ai-gateway/inputs-and-tools/video-input)
