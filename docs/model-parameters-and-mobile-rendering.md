# 模型参数与移动端渲染适配（2026-11-07）

本次范围是 Android 原生应用及其只读 WebView 渲染，未修改 desktop/desktopApp。

## Gemini 4 Argon

- 准确模型 ID 为 `gemini-4-argon`，支持视觉、工具和推理；输入、思考、输出共享 1,048,576 token 窗口。
- 推理提供自动、low、medium、high，自动采用服务默认 medium。助手设置页标明温度和 Top-P 已停用，协议层也会过滤采样、旧思考预算、显式缓存和重复惩罚参数。
- 原生 REST 收到 `CONTINUATION` 后，以原始 contents 和原始总预算，附加顶层 `continuationToken` 继续；累加各段用量和内容。收到 `MAX_TOKENS` 停止，不自动提高付费预算；取消会中止流和后续请求。重复或缺失 checkpoint 会报错，不循环调用。
- 每段请求支持 20 分钟超时。网络异常不会触发自动付费重试；当前不提供断线 checkpoint 的持久化恢复。
- OpenAI 兼容聊天通道复用受限推理等级及采样过滤；自动续写协议仅用于 Google 原生通道。

官方依据：[模型与迁移](https://aistudio.google.com/docs/latest-model?generate-content=true)、[Argon](https://aistudio.google.com/docs/models/gemini-4-argon)、[长输出续写](https://aistudio.google.com/docs/long-decode?generate-content=true)。

## GPT Image 2.6

支持 `gpt-image-2.6-flare` 和 `gpt-image-2.6-sunburst`，使用 Images generations/edits 路径。

| 参数 | 适配行为 |
| --- | --- |
| quality | auto、low、medium、high、xhigh、max |
| complexity | low、medium、high；留空使用默认 high，与画质独立 |
| output_format | png、jpeg、webp、gif、svg；GIF/SVG 按实际 MIME 保存 |
| GIF | 帧数 2–16，默认 9；每帧最小 20 ms，默认 200；提供 auto/512×512；移除流式、partial_images 和压缩，不允许 mask |
| SVG | 单图，保留透明背景；移除流式、partial_images、压缩及 prompt_rewriter；账户开放时可指定 svg.model 和作者推理深度 |
| 编辑 | JSON `images: [{image_url: data URI}]`，最多 16 张；文件流式编码，支持自定义结构化 markup；markup/mask 互斥 |

GIF、SVG 及 SVG 选模的项目权限仍由供应商决定；客户端不以出现选项作为账户已开放的证明。所有组合在发送前检查，旧图像模型不会收到新模型专用字段。

官方依据：[Flare](https://developers.openai.com/api/docs/models/gpt-image-2.6-flare)、[Sunburst](https://developers.openai.com/api/docs/models/gpt-image-2.6-sunburst)、[2.6 参数与提示词](https://developers.openai.com/api/docs/guides/image-prompting)、[生成参数](https://developers.openai.com/api/reference/resources/images/methods/generate)、[编辑参数](https://developers.openai.com/api/reference/resources/images/methods/edit)。

## ANSI 与消息边界

- 长行保留等宽列布局和手机字号，左右滑动浏览。聊天内纵向手势交给对话列表，全屏纵向手势浏览终端历史；触摸取消不保留拖动状态。
- 深浅色独立 16 色 ANSI palette，最低对比度 4.5；支持粗体、256 色、RGB。源码视图对控制序列与日志等级着色，原始复制文本保持不变。
- 代码高亮统一在后台执行，缓存完整高亮结果及其 palette；历史消息不再固定等待流式节流时间。
- 表格片段使用独立缓存，避免一张表的单元格挤掉相邻消息的整文档缓存。缓存同时限制条数与字符量。
- 真机跟踪发现整条长消息预加载会占用主线程约 536 ms。列表前向预加载窗口从 0.5 屏调整为 0.25 屏，后向保留窗口从 1 屏调整为 2.5 屏，减少往返消息边界时重复组合和测量已读内容。

## Python QA

迁移并移除对应 PowerShell 文件：`build_qa.py`、`verify_custom_search_ui.py`、`verify_floating_chat_controls.py`。
新增 `capture_android_flow.py` 采集滚动/键盘帧统计；`verify_dark_appearance.py` 检查深色/AMOLED 下模式切换、对比度和截图实际文字像素（需要 Pillow）。
`capture_android_trace.py` 采集有界的 Perfetto 跟踪，`analyze_android_trace.py` 保存主线程长任务与预加载任务分析。
ADB 命令统一由 `android_qa.py` 使用 subprocess 参数数组执行，坐标来自新采集的 UI 树；设备亮屏设置在 finally 恢复。固定显示测试页仅存在于隔离 debug 包，不读写 QA 设置和对话。

详细命令见 [scripts/README.md](../scripts/README.md)。构建与测试证据位于 `build/reports/qa-build/`、`build/qa-parameters/` 和 `app/build/reports/terminal-preview/`。

## 验证记录

最终 QA 为 `2.4.8-revised.9-20261107-172000`，versionCode 185，包名 `me.rerere.rikkahub`。使用 `adb install -r` 覆盖安装成功，首次安装时间仍为 **2026-10-17 22:49:10**；复测后亮屏设置恢复为原值 0，当前 QA 进程未发现致命崩溃日志。

- ARM64 APK：`app/build/outputs/apk/qa/app-arm64-v8a-qa.apk`。
- APK SHA-256：`ED0AC1A49E2A09988CB2FC09F8B4A14B3D65DC650A36E6A0924EA86BFB45D888`。
- 签名 SHA-256：`31b770dc8e50adefe30a1a0252175ce59e27c142e8ed286bf693ebc80b79be21`，与原 QA 一致。
- 最后构建及应用测试：`build/reports/qa-build/20261107-171947/attempt-1.log`，BUILD SUCCESSFUL。

| 检查 | 结果 |
| --- | --- |
| JVM | ai 453、highlight 62、app 1,158，共 1,673 项通过，无失败或跳过 |
| Python | 5 项工具回归测试通过，9 个 Python 文件语法检查通过 |
| ANSI 浏览器 | 6 个生产 HTML 场景通过：深浅色、全屏、空内容、长内容、缺失依赖；包含真实 Chromium touch、16 色对比度、粗体/256 色/RGB、输入隔离与缩放后恢复 |
| ANSI 真机 | 隔离 debug 固定页上原生横滑使终端偏移 156 CSS px；纵滑使 Compose 父列表移动 620 设备 px，输入法保持关闭 |
| 深色/AMOLED 真机 | 固定种子和背景下 12 次四种配色模式切换通过，最低对比度 11.518；每张截图匹配到至少 10,713 个实际前景色像素，未出现黑字 |
| 静态检查 | 本轮相关脚本语法与排除 desktop/desktopApp 的 diff whitespace 检查通过 |

真机为 vivo V2301A / Android 16，QA 为可调试构建。每组滚动使用当前 UI 树中的列表边界执行三次上滑、三次下滑，每次 350 ms。最终三次采样：

| 采样 | 总帧数 | 卡顿帧 | P95 | P99 |
| --- | ---: | ---: | ---: | ---: |
| final-scroll-1 | 245 | 19（7.76%） | 25 ms | 93 ms |
| final-scroll-2 | 243 | 16（6.58%） | 21 ms | 89 ms |
| final-scroll-3 | 250 | 16（6.40%） | 21 ms | 77 ms |

缓存窗口调整前的三次采样为 7.49% / 6.15% / 9.20% 卡顿帧，P95 为 31 / 26 / 48 ms，P99 为 150 / 89 / 300 ms。更早的单次旧版基线为 231 帧、11 卡顿帧（4.76%）、P95 26 ms。对话内容相同，但重启、缓存热度和具体滚动位置并非严格固定，因此不能据此宣称整体卡顿率有确定比例的下降。

两次 25 秒 Perfetto 跟踪的主线程证据：

| 指标 | 缓存窗口调整前 | 最终版复测 |
| --- | ---: | ---: |
| `compose:lazy:prefetch:idle_frame` | 535.957 ms，含 256 次文本测量 | 本次跟踪未出现该任务 |
| 最长 `Choreographer#doFrame` | 123.126 ms | 71.735 ms |
| 超过 50 ms 的 doFrame 数 | 2 | 2 |
| 跟踪同组 gfxinfo P95 / P99 | 21 / 150 ms | 23 / 69 ms |

本次复测未复现此前的大块预加载阻塞，仍记录到重组与绘制长帧，不能等同于所有对话均无卡顿。跟踪及 CSV 分析分别位于 `build/qa-parameters/boundary-trace/` 与 `build/qa-parameters/final-boundary-trace/`；安装身份、深色截图和 ANSI 真机结果也保存在 `build/qa-parameters/`。

原生 ANSI 真机验证覆盖嵌入预览的横滑、父列表纵滑和输入法隔离；全屏终端纵滑由浏览器场景验证。模型协议使用本地响应回归，未执行付费线上请求，账户预览权限未实测。
