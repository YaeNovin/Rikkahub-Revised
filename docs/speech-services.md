# 语音服务：可靠性修正与模型参数

官方资料核对日期：2026-09-14。

## 本轮范围

本轮完成语音识别的收尾、排序、取消、草稿保护，朗读的对话隔离、预取内存控制、播放状态和网络取消；增加 MiniMax TTS、MiMo TTS/ASR 的共享能力约束、请求校验及设置界面。

锁屏媒体会话、完整的音频文件管理、多角色对白、离线识别、实时通话与打断，属于后续独立功能，不应与本轮可靠性修正混淆。

## 录音与播放

- 录音结果先显示为独立草稿，可编辑后插入当前光标位置，不覆盖录音期间手动编辑的输入框。
- 可取消连接或录音；MiMo/Step 失败的音频段保留在本次会话内，可手动重试。取消本次录音会释放这些暂存内容。
- OpenAI/Qwen 识别按 item_id 整理结果，重复完成事件不重复追加。Qwen 停止时发送 session.finish 并等待 session.finished；OpenAI 提交剩余缓冲并等待最终转写；火山识别等待最后响应标志。收尾均有超时提示。
- 网络积压不再静默丢帧；达到容量限制时停止录音并提示。已发送但尚未识别完成的网络语音不保证能恢复。
- 朗读只响应当前对话的生成完成事件。合成与播放器的暂停状态保持一致，停止时取消未完成请求。
- 每次最多预取当前段之后两段，已播放的音频及时释放。超长无标点文字会切段，避免整篇落入单次请求。
- MiMo、Qwen，以及选择 PCM 的 MiniMax 支持 PCM 分片边收边播；MP3 等压缩格式继续按文本段合成后播放。尚未生成完毕的 PCM 流不提供快进。
- MiniMax/MiMo 的 SSE 采用按需读取及小容量缓冲，暂停/慢速消费不会因回调队列满而丢弃事件；流提前结束会报告错误。
- 来电/其他应用夺取音频焦点、耳机拔出时暂停。完整后台 MediaSessionService 仍需单独实施。

## 外观适配

- 语音模型选择、参数表单与状态说明使用所在表面的可读文字色，输入框保持透明底色；普通主题的强调色仍由应用主题控制。
- 下拉菜单按可见视口绘制背景，选项列表在内部单独滚动；固有尺寸预测使用有限边界，避免长列表造成 Constraints 溢出或把背景图片放大到整张列表的高度。
- 语音供应商及播放速度卡片复用页面卡片的透明度、虚化与液态玻璃策略。选中状态通过描边、单选按钮及状态标签表达。
- 模型获取区域与转写草稿使用轻量半透明描边；转写草稿沿用聊天输入栏的背景与文字设置，不单独铺设背景图片。
- 语音播放详情复用现有外观弹窗，悬浮播放器复用弹出层表面样式、透明度与虚化选项。
- 悬浮播放器的独立 ComposeView 继承父页面的主题及设置；修改背景或文字配色后能收到更新。

## 能力约束

配置界面与实际请求共用 speech 模块的 SpeechCapabilities、normalized 和请求构造函数。模型切换及保存时校验旧值；隐藏的专属参数不继续发送。未识别型号不默认继承全部高级能力。

### 获取语音模型

在“设置 → 语音服务”编辑供应商，填写 API Key 和服务地址后，点击模型输入框下方的“获取模型”，再从下拉框选择。本次查询不会自动更改已选模型。

- OpenAI、Gemini、MiMo、ElevenLabs、Groq、Step，以及提供兼容模型目录的 Qwen/第三方服务接入实际模型查询。鉴权分别使用供应商要求的请求头。
- TTS 列表过滤聊天、生图、嵌入、ASR 和仅用于语音聊天的模型；ASR 列表过滤 TTS。实时识别还排除仅支持文件转写、说话人区分或不同事件协议的型号。
- 优先使用明确的用途/能力标记；没有此类字段时按模型标识匹配语音用途。不能仅因模型名包含 audio 就认定能用于当前接口。
- 成功获取后，下拉框只展示本次服务返回的适用模型，不混入内置预设。空列表、鉴权失败或接口不支持时保留当前模型设置。
- 查询支持分页、取消与重试。修改密钥或地址会使旧列表失效，旧请求结果不能回写新配置；密钥不会进入 URL 或界面错误详情。
- MiniMax 官方文档目前没有模型目录接口，因此官方地址保留预设与说明；自定义兼容服务可查询 /models。Fish Audio 的 Model API 查询的是音色，不作为合成引擎目录使用。
- 系统 TTS、当前 xAI TTS 和火山 ASR 使用音色或资源配置，不增加虚假的模型选择项。
- 带供应商前缀的 MiMo/MiniMax 模型 ID 保留原始路由标识，同时按实际模型识别参数约束。

### MiniMax TTS

- 官方型号预设：speech-2.8-hd/turbo、speech-2.6-hd/turbo、speech-02-hd/turbo、speech-01-hd/turbo。
- 支持手动音色 ID，并可按当前账号获取系统、克隆及设计音色。账号中尚未激活的克隆音色可能不出现在查询结果中。
- 合成语速 0.5–2，音量大于 0 且不超过 10，音调 -12–12；合成语速与客户端播放倍速分开。
- 按型号限定语言与情绪；fluent/whisper 按官方最保守约束只对 speech-2.6 开放。旧 speech-01/02 不开放 Persian、Filipino、Tamil。
- 提供 MP3、PCM、FLAC、WAV、μ-law、Ogg/Opus；μ-law 固定 8 kHz。码率仅对 MP3 生效，固定码率仅对流式 MP3 生效。
- 发音词典每行使用“原文/读法”。LaTeX 朗读会启用中文，公式用双美元符号包裹。
- 提供句级、词级字幕及流式词级字幕；供应商返回的字幕地址或字幕内容保留到该段播放详情。
- 音色明亮度、柔和度、清脆度与声音效果只在官方支持的格式/流式组合中开放。
- 不要求响应携带 ced；检查 base_resp 业务错误和流式完成状态，不把 HTTP 200 当作合成成功。

### MiMo TTS

| 模型 | 音色输入 |
| --- | --- |
| mimo-v2.5-tts | 内置音色：mimo_default、冰糖、茉莉、苏打、白桦、Mia、Chloe、Milo、Dean |
| mimo-v2.5-tts-voicedesign | 必填文字音色描述；不发送 audio.voice |
| mimo-v2.5-tts-voiceclone | MP3/WAV 参考样本；不发送内置音色 ID |

- 普通风格指令放在 user 消息，实际朗读文本放在 assistant 消息。
- 音色样本通过 SAF 选择并保留读取授权，设置仅保存 URI；请求时读取并校验文件签名及编码大小（小于 10 MB）。
- 非流式可选 WAV、MP3、PCM16；流式固定 PCM16。PCM 是 24 kHz、16 位、单声道，不提供虚假的独立采样率选项。
- optimize_text_preview 仅为音色设计模型开放，默认关闭；开启后可能改变朗读内容，返回的最终文本在播放详情中显示。
- 音色设计/克隆的 SSE 可能在合成结束后才一次返回，不承诺实时首声。唱歌仅属于普通内置音色模型。

### MiMo ASR

- 目前公开型号为 mimo-v2.5-asr，支持单个 MP3/WAV 音频输入。
- 服务端可调参数为 asr_options.language（auto/zh/en）和 stream。
- 本地录音采样率、分段秒数属于客户端设置，不作为服务端模型参数发送。
- 不展示文档未提供的热词、时间戳、说话人或风格参数。

## 参考

- [MiniMax TTS OpenAPI（完整字段约束）](https://platform.minimax.io/docs/api-reference/speech-t2a-http.md)
- [MiMo 模型列表](https://mimo.mi.com/docs/en-US/api/model/list-models)
- [OpenAI 模型列表](https://developers.openai.com/api/reference/resources/models/methods/list/)
- [ElevenLabs 模型及 TTS 能力标记](https://elevenlabs.io/docs/api-reference/models/list)
- [MiniMax 音色查询](https://platform.minimax.io/docs/api-reference/voice-management-get)
- [MiMo TTS API](https://mimo.mi.com/docs/en-US/api/audio/tts)
- [MiMo TTS 使用说明](https://mimo.mi.com/docs/en-US/quick-start/usage-guide/audio/speech-synthesis-v2.5)
- [MiMo ASR API](https://mimo.mi.com/docs/en-US/api/audio/Speech-Recognition)
- [Qwen 实时识别流程](https://help.aliyun.com/zh/model-studio/qwen-asr-realtime-interaction-process)
- [OpenAI 实时转写](https://developers.openai.com/api/docs/guides/realtime-transcription)
- [Android 音频焦点](https://developer.android.com/media/optimize/audio-focus)

## 验证边界

单元测试覆盖模型切换、参数互斥、请求序列化、流式错误、转写排序和文本分段。实机仍需检查尾句收尾、弱网重试、录音中编辑、后台/耳机切换、长文本暂停恢复，以及各账号的音色权限。没有使用用户密钥发起付费语音测试。
