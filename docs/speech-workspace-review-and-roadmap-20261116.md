# speech 与 workspace 专项审阅及开发方向（2026-11-16）

优先修复 workspace 的路径/权限一致性与 Rootfs 补丁写入边界，同时处理语音的真实接口兼容：Step REST 字段、Gemini 3.8 音频容器、新 OpenAI 转写参数和临近的官方 TTS 停用。其后统一语音响应预算、录音状态机、进程生命周期和 Rootfs 安装事务。

本轮只做审阅、隔离验证和路线整理，没有修改生产逻辑、安装 APK、操作手机或调用真实语音供应商。现有未提交修改保留，排除 desktop/desktopApp。

## 1. 范围、证据与测试结果

- speech：35 个生产 Kotlin 文件，包括 11 类 TTS、5 类 ASR、模型目录/能力/配置、分段合成、播放器、SSE 和录音状态；沿 app 的 ASR/TTS hook、工具和配置接入检查。
- workspace：7 个生产 Kotlin 文件、JNI/PTY/CMake，以及 app 的仓库、AI 工具、文档提供器和交互终端接入。SAF 文件系统仅检查相邻调用边界，没有将其完整实现单独当作本轮已审计模块。
- 源码摘要（本地证据，未公开）覆盖 74 个生产/既有测试/资源/构建及相邻调用文件，前后 SHA-256 一致；并非整个工作区的完整快照。
- 本轮 **112 项 JVM 用例：107 通过、1 失败、4 跳过**。speech 为 59/59 通过；workspace 为 48 通过、1 失败、4 跳过。包含 11 个隔离探针，全部通过；探针断言现状，不能理解为缺陷已修复。测试摘要（本地证据，未公开）、speech 探针（本地证据，未公开）、workspace 探针（本地证据，未公开）。
- 唯一既有失败为 `ExampleUnitTest.rootfsInstallerDownloadsAndExtractsTarGz`：夹具没有 `bin/sh`，安装器明确要求该入口。四项跳过是已有 Windows 环境条件下的 shell/进程测试。本轮新符号链接探针实际执行并通过，没有跳过。
- 文件/链接探针均限于 JUnit 临时目录，外部目标也是同一测试目录中的合成文件。stdin 探针只启动读取管道的本地 Python 子进程；安装既有测试使用本机 HTTP。没有读取应用私有文件、修改真实工作区或运行任意 Rootfs 命令。
- 通过 AnySearch 获取官方文档；部分连接/提取失败后使用 Tavily 读取同一官方页面。Gemini 按仓库 gemini-api-dev 指引先读官方索引，再读当前语音页。成功/失败及完整提取结果存于来源快照（本地证据，未公开）。

## 2. speech：需要优先修复和修正的内容

### S1 · P1：Step TTS 把 SDK 属性名当成 REST 字段发送

**证据：当前请求源码 + Step 官方 REST 文档。未向真实服务发送请求。**

[StepTTSProvider.kt](../speech/src/main/java/me/rerere/tts/provider/providers/StepTTSProvider.kt#L57)下发 `responseFormat`、`sampleRate`，注释也宣称官方使用 camelCase；但本轮读取的官方 `/v1/audio/speech` 文档明确是 **`response_format`、`sample_rate`**。

客户端随后又根据用户填写的 responseFormat 将返回字节标记为 PCM/WAV/Opus 等。如果服务忽略未知字段并按默认 MP3 返回，客户端可能把 MP3 当 PCM 再封装，或用错误采样率播放。不能把客户端的 ignoreUnknownKeys 理解为服务端会按预期处理未知请求字段。

**建议修改：**按实际 REST schema 构建请求，补非默认 format/rate 的 wire 契约测试；保留 Kotlin 配置字段名和旧设置迁移，不要求为了改 wire 字段而重命名所有存储属性。依据响应 MIME/容器验证实际格式。

当前设置注释允许 flac，但 provider 映射没有 FLAC 分支，会落到 MP3；应一并修正。格式标记错误不等于所有默认 MP3 请求都无法播放。

### S2 · P1：Gemini 3.8 非流式 WAV 被固定当成裸 PCM

**证据：官方模型迁移说明 + 请求/播放调用链。**

[GeminiTTSProvider.kt](../speech/src/main/java/me/rerere/tts/provider/providers/GeminiTTSProvider.kt#L112)仅取第一候选的第一 part，忽略 inlineData.mimeType，固定输出 PCM/24 kHz/单声道。[AudioPlayer.play](../speech/src/main/java/me/rerere/tts/controller/AudioPlayer.kt#L184)遇到 PCM 会再次加 WAV 头。

官方当前文档明确：**Gemini 3.8 TTS 的 unary 默认返回完整 WAV；streaming 默认返回裸 PCM**。因此通过手填或模型目录选入新模型后，当前路径可能形成双重 WAV 封装，产生异常声音或播放失败。旧模型默认裸 PCM 的场景不能一概当作错误。

**建议修改：**根据 MIME、编码和容器识别 WAV/PCM/μ-law 等，确认 sample rate/channels；非音频 part、被阻止候选和 finish reason 单独处理。按模型能力决定 unary/streaming，保留 2.5/3.1 兼容。3.8 的持续风格/角色应使用 `speech_metadata`，不要把全部表演指令继续混在需逐字朗读的 text 中。

### S3 · P1：OpenAI 语音迁移有明确期限，且新 ASR 不能沿用固定 server_vad

**证据：本轮重新读取官方弃用页、TTS 和 Realtime transcription 文档。**

- `tts-1`、`tts-1-hd`、`gpt-4o-mini-tts` 官方停用日期是 **2027-01-06**；推荐目标为 Realtime API 的 `gpt-realtime-2.1-mini`。官方明确它**不能直接用于 `/v1/audio/speech`**。当前 [OpenAITTSProvider](../speech/src/main/java/me/rerere/tts/provider/providers/OpenAITTSProvider.kt#L44)、默认配置和目录预设仍走旧 Speech API。
- 当前目录已接受 `gpt-live-transcribe`，但 [OpenAIRealtimeASRController](../speech/src/main/java/me/rerere/asr/providers/OpenAIRealtimeASRController.kt#L345)固定发送 `turn_detection.type=server_vad`，以及旧单语言字段。官方对新转写模型要求 turn_detection 省略或为 null，使用客户端 VAD/commit；语言提示为 languages，并新增 keywords/delay 等选项。
- 旧转写模型也列有 **2027-02-26** 停用日期。不能只更新模型下拉列表而让请求继续使用旧参数。

**建议更新：**增加明确的传输/模型能力矩阵与 Realtime TTS adapter，分别实现音频增量、完成/错误、取消和恢复政策。新 ASR 使用正确参数和客户端分段；官方域和第三方兼容域分别迁移，保留合法的第三方 Speech API。不要把官方停用日期机械套到所有代理。

### S4 · P1/P2：Step ASR 的截断、失败和重试语义需要修正

**证据：静态完整解析/重试路径 + 官方 SSE 终止契约。**

[StepASRController.parseSseTranscript](../speech/src/main/java/me/rerere/asr/providers/StepASRController.kt#L338)在 EOF 后直接返回已收集文本，没有要求 `transcript.text.done`。服务正常结束和连接提前断开可能都被当成整段成功；MiMo/MiniMax 相关解码器已经有完成标志检查，应保留并统一。

[executeWithRetry](../speech/src/main/java/me/rerere/asr/providers/StepASRController.kt#L314)将所有非成功 HTTP 状态和 SSE error 都转成 IOException，再最多尝试三次；401/403 等固定错误也会重复。发生过付费处理后的网络/解析失败是否能安全重试，需要按供应商契约判断，不能默认重复 POST。

**建议修改：**解码结果显式带 partial/final/error、request ID 和 usage；未收到 done 时保留文字与音频重试状态，而不是冒充完整成功。按状态码分类，尊重 Retry-After，只有明确允许的暂态失败才有界重试；对已经产生部分结果的请求给出重复消费政策。

一个尝试执行该 Android JSON 私有解析器的 JVM 探针被 `JSONObject.opt not mocked` 阻断，已移出最终探针集；没有添加假 JSON 实现、开启 returnDefaultValues 或改动生产代码。因此 S4 仍是源码/官方契约结论，不声称已经完成该路径的运行时复现。

### S5 · P2：长数学片段突破分块上限，缓冲合成未检查采样率变化

**证据：两个纯 JVM 探针。**

- [TextChunker](../speech/src/main/java/me/rerere/tts/controller/TextChunker.kt#L8)为保护 `$$...$$` 会扩大块边界。含 12,000 个 x 的数学块被作为 12,004 字符单块返回，突破控制器的 160 字符设置，并在 MiniMax 请求前校验中失败。
- [TtsSynthesizer](../speech/src/main/java/me/rerere/tts/controller/TtsSynthesizer.kt#L36)检查 format 一致，却只记住第一个非空 sampleRate；16 kHz 与 24 kHz 的 PCM 被拼接为一个 16 kHz 响应。流式 AudioPlayer 已检查采样率变化，不能说所有播放路径都缺此检查。

**建议修改：**给受保护内容设置硬预算，按供应商处理超长公式/标签，明确降级或拒绝原因。音频块验证 encoding/rate/channels/frame alignment，只有一致数据可拼接；容器流与裸 PCM 的拼接方式分开。

## 3. speech：资源、性能和供应商更新方向

### 3.1 统一内存预算与网络接入

多个 provider 私建 OkHttpClient 并完整 body.bytes/string 后再解码 Base64/hex。[TtsSynthesizer](../speech/src/main/java/me/rerere/tts/controller/TtsSynthesizer.kt#L38)虽有单段 32 MiB 上限，但校验发生在 provider 已分配完整音频之后；两个预取加当前块、拼接副本及 WAV 包装会进一步增大峰值。不能把该上限当作应用整体内存限制。

建议注入受控网络客户端和语音执行上下文，统一代理、总超时、取消、脱敏摘要及单次/并发内存预算。大音频落受控临时文件或连续播放，按字节预算预取，不只按块数。PCM→WAV 的大数组复制目前发生在播放调用线程，需移到工作线程或用无整段复制的数据源；本轮没有测真机主线程耗时或堆峰值。

SpeechSse 已是 pull-based、buffer(2)、awaitAndUse，现有慢消费者测试验证 200 条全部收到，不能套用 common 未使用的 callback SSE 丢事件结论。但 readUtf8Line 先构造整行，之后才检查 data 长度；还需单行/单事件实际字节预算、解析深度、空闲超时，以及 Qwen/Step 旧解析器的统一。

### 3.2 录音/播放状态和生命周期

- 现有 microphone 权限检查、AudioRecord 初始化错误、慢网络停止、终结等待、会话 generation 和取消释放是已有保护，应保留。
- WebSocket controller 应等待 session.updated/服务确认后再发送录音，验证 send 返回值，增加连接/收尾截止时间和短录音测试。OpenAI 的 input_audio_transcription.failed 当前未被识别，可能落入忽略分支，应保留已识别内容并结束相应 item。
- MiMo/Step 采集、flush、retry 分散在多个 Job，failedSegment 只在内存；建议显式定义“录音中、上传中、可重试、停止收尾、取消”状态和资源所有者。hook 已在每次 start 创建新 controller，不能把旧模块内部状态风险直接说成当前 UI 必然串稿。
- 一些 WebSocket onFailure/onClosed 直接从回调线程 stop/release AudioRecord；MiMo/Step 的 releaseRecorder 未采用 expected-recorder 身份校验。建议统一由音频工作线程串行释放，避免停止后新会话的资源被旧 finally 触及。
- AudioPlayer 对 ExoPlayer 使用自动焦点，PCM 路径手动管理，已有 noisy receiver。需要验证两种路径切换、焦点丢失/恢复、耳机拔出与重播时的状态一致性；不能泛称完全没有音频焦点处理。
- 目标 SDK 37，Android 15+ 请求焦点受前台条件约束。当前没有独立 mediaPlayback/microphone 服务；若产品需要离页/锁屏持续播放或录音，再设计对应生命周期和服务；仅前台朗读不应为了假设需求增加常驻服务。
- SystemTTS 每段重新初始化引擎、文件合成并完整读取，缺少总完成超时；部分初始化/启动失败路径没有统一清理文件/实例。应抽离可测的引擎生命周期，处理迟到初始化/回调与取消竞态。

### 3.3 供应商更新清单

| 服务 | 修改/更新方向 |
| --- | --- |
| OpenAI TTS/ASR | S3：先做协议迁移；支持新的转写提示、commit 和失败事件，保留兼容服务旧端点。 |
| Gemini TTS | S2：3.8 Flash/Flash-Lite、格式识别、streamGenerateContent、style/speaker metadata；多说话人、设计/克隆音色作为后续独立能力。不要只增加模型名。 |
| Step TTS/ASR | S1/S4；补 stepaudio-3-tts、stepaudio-3-asr-max 预设，48000 Hz、模型对应 instruction 长度、text_normalization、pronunciation_map。SSE 音频/字幕、Step Plan 路径需要独立约束和解析。官方仍兼容旧 vivid 名称，不应称其已立即不可用。 |
| MiniMax | 2.8/2.6 等模型、字幕、音效及音色列表已接入。新文档有 quality、2.8 专属插入标签等差异，应按模型逐项核对并加入能力矩阵，保留完成检查和 exclude_aggregated_audio。 |
| MiMo | 保留内置音色/设计/样本三种输入和样本校验，改进样本加载/编码缓存、流式结束检查与录音分段预算。不能把“能获取模型列表”当作所有发现的模型都可走同一语音协议。 |
| Qwen/DashScope | 保留 ASR 的 item 对齐和 session.finish 收尾；Qwen TTS 旧 SSE parser 需处理 EOF、终结/错误、过大数据与取消，按实际模型核对地区端点、音色和格式。 |
| Volcengine ASR | 保留二进制帧和最终包等待；补协议版本/header length/flags、长度溢出和 GZIP 解压后大小校验，以及日志 ID。当前 gzipDecompress.readBytes 无总量约束。 |
| Fish Audio | raw PCM 缺明确 sampleRate/声道元数据；应按该服务实际响应固定或读取准确参数，再开放格式/流式能力。音色与合成引擎目录继续区分。 |
| ElevenLabs | 根据模型能力控制 stability 等参数，补音色目录/格式/延迟与流式支持；错误正文不直接输出系统日志。 |
| Groq | 保留 WAV 容器路径，核对 Orpheus 模型/音色及语言对应；增加请求前参数和响应预算。 |
| xAI | 当前 `/tts` 返回 MP3 路径可保留；后续核对语言/音色/输出格式与流式传输，不直接套用 OpenAI 的模型参数。 |
| System TTS | 引擎/语言/音色可用性、完成超时、复用与取消清理；外部引擎回调视为可能迟到或重复。 |

SpeechModelCatalog 已有限页、单页 5 MiB、同 origin 分页、关闭重定向和凭据专用 header。建议增加总条数/总字节/整次查询截止时间与“发现可用名称/当前传输可执行”区分，不要把已有保护列成缺失。

## 4. workspace：需要修复的边界和数据正确性

### W1 · P1/P2：根目录名称与“不能删除根目录”不变式不完整

**证据：两个临时目录 JVM 探针 + app 创建/调用链。**

[WorkspaceManager](../workspace/src/main/java/me/rerere/workspace/WorkspaceManager.kt#L652)的根名称正则接受 `.`、`..`。workspaceDir("..") 的 canonical 路径是 baseDir 的父目录，workspaceDir(".") 是 baseDir 本身；其他基于该方法的操作也会继承错误根。正常新建使用 UUID，因此不能声称普通创建界面直接生成了这些值，但恢复数据、文档 ID 和模块调用仍应在入口拒绝。

[WorkspaceFileSystem.delete](../workspace/src/main/java/me/rerere/workspace/WorkspaceFileSystem.kt#L145)只排除原始空串/点号。`nested/..` 解析后等于 root，recursive=true 时会删除整个根。探针只删除 JUnit 自己的子目录，旁边合成文件保留。

**建议修改：**根标识禁止点目录并验证 canonical 路径严格位于 base 内；破坏性操作在解析后再次拒绝 target==root，覆盖斜杠、反斜杠、点路径和链接。当前 AI delete/move/copy 的 requiredWorkspacePath 已拒绝点路径，因此这是底层 API 缺陷，不等于本轮证实所有 AI 删除入口均可直接绕过。

### W2 · P1：写路径策略与实际写入采用不同的规范化规则

**证据：策略判断 + 真实 manager 写入的合成探针 + 实际 AI 写工具调用链。**

[WorkspaceAccessPolicy.allowsWrite](../workspace/src/main/java/me/rerere/workspace/Workspace.kt#L101)只做字符串前缀比较：允许 `/workspace/output` 时，会接受 `/workspace/output/../private.txt`。随后 manager 的文件解析规范化 `..`，实际写入 `/workspace/private.txt`，超出授权子目录。

与删除入口不同，AI write/edit 使用的 [absolutePath](../app/src/main/java/me/rerere/rikkahub/data/ai/tools/WorkspaceTools.kt#L1031)没有拒绝点路径，仓库 requireAiWriteAllowed 直接调用此策略，存在实际可达链。**没有绕过工具本身的审批流程；缺口是审批/权限约束中的写路径范围可以失真。**

**建议修改：**先解析到绑定区/实际目标，再判断策略，校验与执行共用同一个已解析对象。至少拒绝 `.`/`..` 并验证 canonical 目标仍在允许根下；对 symlink、bind mount 和路径变更竞态定义政策，不能只在 UI 上增加字符串提示。

### W3 · P1：已存在但损坏的权限文件会恢复完整权限

**证据：合成 policy.properties 探针。**

[readAccessPolicy](../workspace/src/main/java/me/rerere/workspace/WorkspaceManager.kt#L400)将无效 readOnly 回退 false、无效 shellEnabled 回退 true、无效/空路径列表回退 `/`；解析失败整体也回退默认策略。探针写入三个无效字段后得到 shell 可用、任意路径可写。

新工作区从未配置过策略与已有策略文件损坏必须区分。**建议修改：**对已存在的非法策略禁止写和 shell，报告可修复状态；加入 schemaVersion、明确迁移和原子持久化校验。普通“未设置策略”的现有默认行为可保留为明确产品选择。

### W4 · P1/P2：遍历和 Rootfs 修补没有统一遵守链接边界

**证据：两个实际符号链接 JVM 探针，均只访问合成文件。**

- [WorkspaceFileSystem.grep](../workspace/src/main/java/me/rerere/workspace/WorkspaceFileSystem.kt#L345)使用 Files.walk 不跟随目录链接，但 Files.isRegularFile 默认跟随文件链接，随后直接读取。探针中直接 readText 拒绝外部链接，grep 却读到链接目标里的合成标记。当前未发现 app 调用该 grep API，因此是模块公共能力缺口，不能描述为已验证 AI grep 窃取真实配置。
- [RootfsPatcher](../workspace/src/main/java/me/rerere/workspace/RootfsPatcher.kt#L7)直接用 rootfs/etc。将 etc 链到同一测试树内的外部目录后，patch 在该目录创建了 resolv.conf、hosts 等。安装器允许 guest 绝对链接，而 patcher 没有对 etc 和各写目标再校验；这条补丁路径会在安装和启动 shell/终端时使用。

**建议修改：**遍历每个实际文件时校验根边界或明确采用 NOFOLLOW_LINKS。Rootfs 的 guest 绝对链接需要专用解析器，不能直接让宿主 File IO 跟随；补丁目标逐级检查，拒绝或安全物化不合规目录/文件链接。保留合法的 guest 链接语义，不是删除所有 Linux symlink。

这些访问仍受 Android 应用 UID 的权限限制，不代表获取系统 root。本轮没有测试活动 shell 并发换链时的 TOCTOU，后续还需原子打开/句柄级边界设计。

### W5 · P2：PAX 长度按字符处理，不能正确还原 Unicode 归档

**证据：Python 标准 tarfile 生成的合法 PAX 夹具 + 真实 extractTar。**

[RootfsInstaller.parsePax](../workspace/src/main/java/me/rerere/workspace/RootfsInstaller.kt#L310)把元数据先解成 String，再按 PAX length 做字符下标。POSIX 明确 length 是整个记录的 UTF-8 **字节数**。包含 `目录/资料.txt` 和后续元数据的标准归档无法正确还原预期文件。

**建议修改：**先按字节长度切记录，再解码 key/value，严格验证正长度、边界、换行及支持的 hdrcharset；补多语言名称、多记录、长路径、PAX linkpath 和截断夹具。可评估成熟 TAR 解析器，继续保留路径/解压预算和取消检查，不能用换库覆盖这些保护。

### W6 · P2：无 stdin 时子进程不会收到 EOF

**证据：真实本地 Python 管道探针。**

[Process.readResult](../workspace/src/main/java/me/rerere/workspace/WorkspaceShellRunner.kt#L64)仅在 stdin 非 null 时创建会关闭输出流的 writer；没有输入时，父进程保持 stdin 管道开放。只等待输入结束的合成子进程因此超时；显式 ByteArray(0) 则正确收到 EOF 并退出。

**建议修改：**非交互执行中，未提供输入也应立即关闭 stdin；互动 PTY 保留独立语义。为总截止时间、writer/drainer 完成与失败、流关闭建立 finally。当前 stdout/stderr 继续排空并限保留量的做法正确，不应到上限就停止读取导致管道死锁。

### W7 · P2：只读副本挂载名存在碰撞，准备失败也缺统一清理

**证据：prepareWorkspaceBindMounts 合成目录探针。**

[ProotShellRunner.kt](../workspace/src/main/java/me/rerere/workspace/ProotShellRunner.kt#L55)将 `/a_b` 和 `/a/b` 都转换成镜像目录 a_b。两个挂载实际共享 source，第二个副本覆盖第一个内容。当前默认挂载是否触发该组合未证实，属于公开配置能力缺陷。

**建议修改：**使用唯一 ID/摘要或独立序号，并校验 target 重复/重叠；整组副本准备作为事务，任何复制失败都清理已创建内容。每挂载 256 MiB/20k entries 之外还应有整次任务预算。SHELL_READ_ONLY_COPY 实际允许修改副本，只是不写回原始源，应在行为说明中保持准确。

## 5. workspace：安装、进程、JNI 与更新方向

### 5.1 Rootfs 安装事务与供应链

- staging/backup、SHA-256、压缩包/解压/条目数量限制、tar checksum 与中断检查已经实现。现有测试失败应给夹具补真实 shell 入口；同时统一 installer 只接受 bin/sh 与 manager/runner 接受 usr/bin/sh/bash 的规则，继续要求有效 shell，不能简单删除检查。
- install 固定复用 archive/staging/backup 名称，仓库/VM 未看到与 repair/delete/terminal 共用的安装互斥。建议按 workspace 建统一生命周期锁，避免安装、恢复和删除交错；shell 当前已有独立 Mutex，不能说完全没有串行化。
- 恢复失败会记录错误，但 cleanupAllTempDirs 随后仍删除整个 tempDir，可能抹掉恢复尚未成功的 backup；新 install 开始也先删除 backup。需要持久化事务阶段和恢复结果，在确认新/旧可用版本之一已安全落位前禁止清理唯一有效备份。此项为静态失败路径发现，未注入真实进程崩溃。
- HTTP 下载检查线程中断是在阻塞 read 之间，取消不一定能立即打断连接；已有读超时 60 秒。建议绑定可关闭的连接/Call、整体截止时间。下载最大 1 GiB、解压最大 4 GiB 并不保证设备有足够剩余空间。
- XZInputStream 没有字典内存限制；解压后字节预算不能代替解压器分配预算。先限制 decoder memory、CPU/时间与磁盘余量，再读取不可信 header。
- 官方预设应固定来源、版本和 SHA-256，记录已安装 ABI、压缩包哈希、补丁版本及实际 shell。自定义 URL/本机 HTTP 保留明确高级用途，不假装一个可选 checksum 已建立完整签名信任链。
- RootfsPatcher 默认 DNS 不一定符合当前网络/VPN/私有 DNS；终端接入有 activeDnsServers，非交互 runner 应共用同一网络策略和变更处理。

### 5.2 文件操作与应用集成

- listPage 先枚举、排序整个目录再截页，大目录内存与时延不随页长受限；统计每次扫描、配额检查和 copy 双遍历也有成本。可增加索引/按需统计和总任务取消预算。
- grep 的 regex 在最长 512 KiB 行上可能有高 CPU 模式，遍历上限不能限制单次正则计算；先定义安全语法/可控引擎/可中断执行，而非仅套协程 timeout。
- read/export 的检查长度后读取存在文件增长竞态，应在实际流上限量；编辑冲突检查到替换之间也需串行版本检查，当前 revision 设计应保留。
- app DocumentsProvider 存在独立复制实现和更大预算，SAF 同步也有独立路径；建议统一文件策略核心，保留不同用户入口的合理预算。普通系统文件选择器操作与 AI 受限写策略的授权主体需要明确，不能误把用户手动编辑都视为 AI 操作。
- 仓库删除先删数据库记录再删目录且不检查删除结果，失败可能留下难以发现的文件；建议软删除/事务状态与重试清理。审计轮转/写入失败也不应覆盖已成功完成操作的结果。
- executeCommand 的权限由 AI 工具先检查，manager 本身只验证参数。应使“用户手动终端”与“AI 工具执行”使用明确执行主体和策略接口，避免新调用方误以为底层自动完成所有权限检查。

### 5.3 进程、PTY 与真实隔离承诺

- PRoot 已用 --kill-on-exit，host/PRoot 的主进程 waitFor/取消处理和输出截断已实现；但杀直接进程并不等于证明后代/后台进程全部结束。四个 Unix shell 测试在 Windows 跳过，后续需 Linux/Android 覆盖管道、孙进程持管道、超时、取消和并发终端。
- PRoot 通过 ptrace 提供文件视图和 root 身份仿真；它不创建独立 Android UID/SELinux/网络隔离。当前 /dev、/proc、/sys 与显式挂载要纳入授权范围，保留工具审批和访问限制，不扩大“沙箱”的产品承诺。
- [termux_pty.cpp](../workspace/src/main/cpp/termux_pty.cpp#L54)已在 fork 前复制 Java 字符串并设置 CLOEXEC；仍需检查 null/分配异常、空 pid 数组、rows/columns、chdir/ioctl/dup2/execve 失败，避免一律静默变为 127。waitpid 应处理 EINTR，父子错误管道可让 UI 得到准确启动原因。
- 进程继承 FD、进程组/会话结束与终端 callback 兼容需要独立 native/device 验证；本轮没有把 JNI 静态检查当作已经跑过 Android PTY。

### 5.4 原生库更新与构建

本轮直接解析四个预编译 PRoot ELF（arm64-v8a/x86_64 的 exec 与 loader），**PT_LOAD 均为 16 KiB 对齐，地址/文件偏移同余检查通过**，见ELF 记录（本地证据，未公开）。不能把它们误报为仍只有 4 KiB 对齐。

CMake 已对 termux 明确设置 16 KiB 链接选项。上述结果不代替 APK ZIP 对齐、动态依赖及 16 KiB Android 真机启动验证。建议记录 PRoot/Termux 上游提交、补丁、编译器、许可和可复现构建；当前占位 workspace.cpp 形成的库可在确认无加载方后移除。更新原生二进制时继续验 ABI、page size 和终端 JNI 契约，不只看 assemble 成功。

## 6. 推荐实施顺序

| 批次 | 内容 | 验收 |
| --- | --- | --- |
| 第一批 | W1–W4：根路径、实际写目标、策略损坏、patcher/遍历链接 | 点目录不可成为 workspace root；解析后的根不能删除；受限目录不能经 .. 或链接越界；已有损坏策略禁止写/shell；补丁只写 Rootfs 内合法目标 |
| 同步推进 | S1–S3：Step REST、Gemini 容器、OpenAI 迁移 | 非默认音频格式/采样率请求正确；WAV 不再二次封装；新 ASR 不带不支持 VAD；2027-01-06 前完成官方 Realtime TTS 与设备回归 |
| 第二批 | S4/S5、统一响应/录音预算、W6/W7 | 截断不能伪装完整转写；重试按类型；长公式有硬边界；采样参数一致；无输入立即 EOF；镜像不碰撞/失败无残留 |
| 第三批 | W5、安装事务、进程/JNI | 合法 Unicode/PAX 可解包；恢复失败保留唯一备份；并发安装/删除互斥；管道/后代停止、PTY 错误可观察 |
| 后续 | 新模型/音色/字幕、SDK/UI 分离、可恢复任务与性能 | 能力与 wire/结果契约一致；低内存、弱网、前后台、耳机/蓝牙/来电、16 KiB 设备有实际数据 |

可新增但不应先于正确性改造的能力：逐词字幕与高亮、按任务的音频下载/重试、模型/音色能力诊断、workspace 安装清单与完整性检查、进程会话中心，以及按路径/任务的资源用量。语音双向实时对话和更强 shell 隔离需作为独立设计，不能靠新增模型名或改 UI 文案完成。

## 7. 验证记录及限制

使用已有 Gradle 9.5.0 / JDK 21 缓存离线执行 `:speech:testDebugUnitTest :workspace:testDebugUnitTest`，隔离 init script 注入探针；没有修改生产构建配置。记录：`build/reports/qa-build/20261116-011931/attempt-1.log`（基线）、`20261116-013143/attempt-1.log`（Android JSON 探针环境失败）、`20261116-013523/attempt-1.log`（最终 112 项）。最终 Gradle 退出失败是已保留的既有 rootfs 测试失败，**不报告全套通过**。

两个语音探针绕过构造器，只执行不触碰 Android 音频设备的分块/收集逻辑；不证明播放器、JNI 或录音设备正常。Python PAX 夹具生成时采用显式 Unicode code point，避免 Windows 命令行编码影响；标准 tarfile 校验其目标名称后再用于测试。

没有执行：完整 APK/NDK 构建、真机录音与播放、焦点/蓝牙/后台权限、原生崩溃注入、Rootfs 真实发行版安装、供应商联通/计费测试或手机文件权限读取。本轮也没有检查各依赖最新发行版，更新建议以实际契约和构建来源为依据。

## 8. 官方参考

- [OpenAI TTS](https://developers.openai.com/api/docs/guides/text-to-speech.md)、[弃用计划](https://developers.openai.com/api/docs/deprecations)、[Realtime transcription](https://developers.openai.com/api/docs/guides/realtime-transcription)。弃用页提取有 50,000 字符上限，所引用语音条目位于已读取部分。
- [Gemini 官方索引](https://ai.google.dev/gemini-api/docs/llms.txt)、[语音生成 generateContent](https://aistudio.google.com/docs/speech-generation.md?generate-content=true)、[Gemini 3.8 Flash TTS](https://aistudio.google.com/docs/models/gemini-3.8-flash-tts.md)。
- [Step REST 语音合成](https://platform.stepfun.com/docs/zh/api-reference/audio/create-audio.md)、[StepAudio 3 TTS](https://platform.stepfun.com/docs/zh/guides/models/stepaudio-3-tts.md)、[Step ASR SSE](https://platform.stepfun.com/docs/zh/api-reference/audio/asr-sse)。
- [MiniMax 同步 TTS](https://platform.minimax.io/docs/api-reference/speech-t2a-http)：模型、quality、字幕、音效等按具体字段约束处理。
- [Android 音频焦点](https://developer.android.com/media/optimize/audio-focus)、[Media3 故障排查](https://developer.android.com/media/media3/exoplayer/troubleshooting)。
- [POSIX pax](https://pubs.opengroup.org/onlinepubs/9799919799/utilities/pax.html)：extended header record 的 UTF-8 字节长度。
- [PRoot](https://proot-me.github.io/)：ptrace、guest rootfs 与 bind 机制。
